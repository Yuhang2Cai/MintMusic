package com.example.timedmusicplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.timedmusicplayer.adapter.TrackLibraryAdapter
import com.example.timedmusicplayer.data.MusicRepository
import com.example.timedmusicplayer.databinding.ActivityMainBinding
import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.model.TrackFilter
import com.example.timedmusicplayer.playback.PlaybackService
import com.example.timedmusicplayer.playback.PlaybackSnapshot
import com.example.timedmusicplayer.playback.PlaybackUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

/**
 * 首页：统一音乐库与迷你播放器控制区。
 */
class MainActivity : AppCompatActivity(), PlaybackService.PlaybackListener {
    // 属性： binding
    // 说明：当前界面的 ViewBinding 引用，用于类型安全地访问布局控件。
    private lateinit var binding: ActivityMainBinding
    // 属性： repository
    // 说明：数据仓库入口，统一提供本地与云端数据访问能力。
    private val repository by lazy { MusicRepository.getInstance(this) }

    // 属性： adapter
    // 说明：列表适配器实例，负责数据到条目视图的绑定。
    private lateinit var adapter: TrackLibraryAdapter
    // 属性： currentTracks
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
    private var currentTracks: List<Track> = emptyList()
    // 属性： activeFilter
    // 说明：当前筛选条件，决定音乐库展示数据范围。
    private var activeFilter: TrackFilter = TrackFilter.ALL

    // 属性： playbackService
    // 说明：播放服务引用，用于调用播放控制与读取快照。
    private var playbackService: PlaybackService? = null
    // 属性： isServiceBound
    // 说明：绑定状态标记，避免重复绑定或重复解绑。
    private var isServiceBound = false

    // 属性： libraryLoadJob
    // 说明：协程任务句柄，用于取消旧任务防止并发回写。
    private var libraryLoadJob: Job? = null
    // 属性： lastMiniStateToken
    // 说明：状态令牌，用于节流高频更新并减少无效重绘。
    private var lastMiniStateToken: MiniStateToken? = null

    // 属性： folderPickerLauncher
    // 说明：系统能力启动器，用于接收异步返回结果。
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { saveLocalFolder(it) }
    }

    // 属性： playbackConnection
    // 说明：服务连接回调对象，负责绑定成功/断开后的状态处理。
    private val playbackConnection = object : ServiceConnection {
        // 函数： onServiceConnected
        // 说明：服务连接成功回调，获取服务实例并注册监听。
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
    // 属性： binder
    // 说明：运行期状态变量，承载 binder 相关上下文信息。
            val binder = service as? PlaybackService.LocalBinder ?: return
            playbackService = binder.getService()
            isServiceBound = true
            playbackService?.registerListener(this@MainActivity)
        }

        // 函数： onServiceDisconnected
        // 说明：服务连接断开回调，清理引用并重置页面状态。
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService?.unregisterListener(this@MainActivity)
            playbackService = null
            isServiceBound = false
            lastMiniStateToken = null
            renderMiniPlayer(null)
        }
    }

    // 函数： onCreate
    // 说明：生命周期初始化入口，完成依赖注入、组件初始化与初始状态设置。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 绑定布局并设置为页面内容视图。
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 顶部工具栏作为页面主操作栏。
        setSupportActionBar(binding.toolbar)

        // 列表项点击后，携带“当前列表+索引”进入播放器。
        adapter = TrackLibraryAdapter(
            onItemClick = { index -> openPlayer(currentTracks, index) }
        )

    // 属性： layoutManager
    // 说明：列表布局管理器，控制条目排布与复用策略。
        // 线性布局管理器，负责纵向列表排布。
        val layoutManager = LinearLayoutManager(this).apply {
            // 提前预取即将出现的 item，降低快速滑动时卡顿。
            initialPrefetchItemCount = 14
            isItemPrefetchEnabled = true
        }
        // RecyclerView 性能优化：固定尺寸、关闭变更动画、提高缓存命中。
        binding.rvTracks.layoutManager = layoutManager
        binding.rvTracks.adapter = adapter
        binding.rvTracks.setHasFixedSize(true)
        binding.rvTracks.itemAnimator = null
        binding.rvTracks.setItemViewCacheSize(24)
        binding.rvTracks.recycledViewPool.setMaxRecycledViews(0, 60)

        binding.btnSelectFolder.setOnClickListener {
            // 打开系统目录选择器；传入上次目录作为初始位置。
            folderPickerLauncher.launch(repository.getLocalFolderUri())
        }

        binding.btnManageCloud.setOnClickListener {
            // 进入在线音源管理页。
            startActivity(Intent(this, CloudSourceActivity::class.java))
        }

        binding.btnResumeLast.setOnClickListener {
            // 按上次播放记录恢复播放。
            resumeLastPlayback()
        }

        binding.miniPlayerContainer.setOnClickListener {
            // 点击迷你播放器进入全屏播放器页。
            openPlayerScreen()
        }

        binding.btnMiniPrevious.setOnClickListener {
            // 迷你播放器：上一首。
            playbackService?.playPrevious()
        }

        binding.btnMiniPlayPause.setOnClickListener {
            // 迷你播放器：播放/暂停切换。
            playbackService?.togglePlayPause()
        }

        binding.btnMiniNext.setOnClickListener {
            // 迷你播放器：下一首。
            playbackService?.playNext()
        }

        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            // 根据用户选择切换筛选条件（全部/本地/云端）。
            activeFilter = when (checkedIds.firstOrNull()) {
                R.id.chipLocal -> TrackFilter.LOCAL
                R.id.chipCloud -> TrackFilter.CLOUD
                else -> TrackFilter.ALL
            }
            // 切换筛选后重新加载列表（优先走缓存）。
            loadLibrary(forceRefresh = false)
        }

        // 默认筛选为“全部”，并触发首屏加载。
        binding.chipAll.isChecked = true
        // 首次进入页面时立即加载音乐库。
        loadLibrary(forceRefresh = false)
    }

    // 函数： onStart
    // 说明：组件进入可见态时触发，通常用于建立绑定或开始监听。
    override fun onStart() {
        super.onStart()
        // 页面进入前台时绑定播放服务，接收播放状态快照。
        bindPlaybackService()
    }

    // 函数： onStop
    // 说明：组件进入不可见态时触发，用于释放绑定和监听。
    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            // 页面离开时解除监听与绑定，避免内存泄漏和无效 UI 更新。
            playbackService?.unregisterListener(this)
            unbindService(playbackConnection)
            isServiceBound = false
            playbackService = null
        }
    }

    // 函数： onResume
    // 说明：组件恢复交互时触发，用于刷新界面与最新状态。
    override fun onResume() {
        super.onResume()
        loadLibrary(forceRefresh = false)
    }

    // 函数： onDestroy
    // 说明：组件销毁前触发，用于回收资源并清理任务。
    override fun onDestroy() {
        // 取消未完成的加载任务，防止销毁后回调。
        // 页面销毁后不应再刷新 UI。
        libraryLoadJob?.cancel()
        super.onDestroy()
    }

    // 函数： onSnapshotChanged
    // 说明：接收播放快照变化并驱动页面 UI 增量更新。
    override fun onSnapshotChanged(snapshot: PlaybackSnapshot) {
    // 属性： position
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val position = snapshot.positionMs.coerceAtLeast(0L)
    // 属性： duration
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val duration = snapshot.durationMs.coerceAtLeast(0L)
    // 属性： buffered
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val buffered = snapshot.bufferedPositionMs.coerceAtLeast(position)
    // 属性： progressMax
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val progressMax = resolveMiniProgressMax(duration, position, snapshot.currentTrack?.isStream == true)

    // 属性： token
    // 说明：状态令牌，用于节流高频更新并减少无效重绘。
        // 使用粗粒度令牌节流迷你播放器的高频刷新。
        val token = MiniStateToken(
            trackId = snapshot.currentTrack?.id,
            queueSize = snapshot.queue.size,
            state = snapshot.state,
            isPlaying = snapshot.isPlaying,
            errorMessage = snapshot.errorMessage,
            positionSecond = (position / 1000L).toInt(),
            durationSecond = (duration / 1000L).toInt(),
            bufferedSecond = (buffered / 1000L).toInt(),
            progressMaxSecond = (progressMax / 1000L).toInt()
        )

        // 状态令牌一致说明页面可见信息无变化，跳过 UI 重绘。
        if (token == lastMiniStateToken) {
            return
        }
        lastMiniStateToken = token

        // 服务回调线程不保证为主线程，UI 更新统一切回主线程。
        runOnUiThread {
            renderMiniPlayer(snapshot)
        }
    }

    // 函数： bindPlaybackService
    // 说明：建立组件连接关系以便接收回调与控制。
    private fun bindPlaybackService() {
    // 属性： serviceIntent
    // 说明：页面或服务通信载体，承载跳转与控制参数。
        val serviceIntent = Intent(this, PlaybackService::class.java)
    // 属性： bound
    // 说明：运行期状态变量，承载 bound 相关上下文信息。
        val bound = bindService(serviceIntent, playbackConnection, Context.BIND_AUTO_CREATE)
        // 绑定失败时清理迷你播放器状态，避免显示旧数据。
        if (!bound) {
            lastMiniStateToken = null
            renderMiniPlayer(null)
        }
    }

    // 函数： saveLocalFolder
    // 说明：保存关键状态到持久层，保证下次启动可恢复。
    private fun saveLocalFolder(uri: Uri) {
        try {
            // 持久化目录读取权限，避免进程重启后权限丢失。
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // 权限可能已被持久化。
        }

        // 保存目录后强制刷新列表，触发重新扫描/索引。
        repository.saveLocalFolder(uri)
        loadLibrary(forceRefresh = true)
    }

    // 函数： loadLibrary
    // 说明：加载并整理数据，必要时命中缓存或触发刷新。
    private fun loadLibrary(forceRefresh: Boolean) {
        libraryLoadJob?.cancel()

    // 属性： filter
    // 说明：当前筛选条件，决定音乐库展示数据范围。
        val filter = activeFilter
        // 首次加载或强制刷新时先显示加载提示。
        if (currentTracks.isEmpty() || forceRefresh) {
            binding.tvLibraryCount.text = getString(R.string.library_loading)
        }

        // 在子线程加载曲目数据，完成后回到主线程更新 UI。
        libraryLoadJob = lifecycleScope.launch {
    // 属性： loadedTracks
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
            val loadedTracks = withContext(Dispatchers.IO) {
                // 在 IO 线程读取仓库数据（可能访问磁盘/SAF）。
                repository.getTracks(filter, forceRefresh)
            }

            // 协程已取消则直接返回，避免无效 UI 更新。
            if (!isActive) {
                return@launch
            }

            // 用新结果替换页面缓存，并提交给列表适配器。
            currentTracks = loadedTracks
            adapter.submitTracks(loadedTracks)

            // 同步更新数量文案与空态显示。
            binding.tvLibraryCount.text = getString(R.string.library_count, loadedTracks.size)
            binding.tvEmpty.visibility = if (loadedTracks.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // 函数： resumeLastPlayback
    // 说明：封装 resumeLastPlayback 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun resumeLastPlayback() {
    // 属性： lastPlayback
    // 说明：运行期状态变量，承载 lastPlayback 相关上下文信息。
        val lastPlayback = repository.getLastPlayback()
        // 没有历史播放记录时提示用户。
        if (lastPlayback == null) {
            Toast.makeText(this, getString(R.string.no_resume_item), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
    // 属性： allTracks
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
            val allTracks = withContext(Dispatchers.IO) {
                // 使用 ALL 拉取完整曲库，确保可按 trackId 精确定位。
                repository.getTracks(TrackFilter.ALL, forceRefresh = false)
            }

            if (!isActive) {
                return@launch
            }

            if (allTracks.isEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.library_empty_tip), Toast.LENGTH_SHORT).show()
                return@launch
            }

    // 属性： index
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
            val index = allTracks.indexOfFirst { it.id == lastPlayback.trackId }
            // 历史曲目已不存在（删除/目录变更），给出提示。
            if (index == -1) {
                Toast.makeText(this@MainActivity, getString(R.string.resume_item_missing), Toast.LENGTH_SHORT).show()
                return@launch
            }

            // 命中历史曲目后直接打开播放器。
            openPlayer(allTracks, index)
        }
    }

    // 函数： openPlayer
    // 说明：执行页面跳转或打开目标能力入口。
    private fun openPlayer(queue: List<Track>, startIndex: Int) {
        // 基本参数校验：空队列或越界索引直接返回。
        if (queue.isEmpty() || startIndex !in queue.indices) {
            return
        }

        // 通过 Intent 传递播放队列和起播索引到播放器页面。
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putParcelableArrayListExtra(PlayerActivity.EXTRA_QUEUE, ArrayList(queue))
                putExtra(PlayerActivity.EXTRA_START_INDEX, startIndex)
            }
        )
    }

    // 函数： openPlayerScreen
    // 说明：执行页面跳转或打开目标能力入口。
    private fun openPlayerScreen() {
        // 仅打开播放器页，不覆盖当前服务队列。
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // 函数： renderMiniPlayer
    // 说明：根据当前状态刷新界面元素与可见性。
    private fun renderMiniPlayer(snapshot: PlaybackSnapshot?) {
        // 没有可播放上下文时隐藏迷你播放器。
        if (snapshot == null || snapshot.queue.isEmpty()) {
            binding.miniPlayerContainer.visibility = View.GONE
            return
        }

        // 有播放上下文时展示迷你播放器并刷新内容。
        binding.miniPlayerContainer.visibility = View.VISIBLE
        binding.tvMiniTitle.text = snapshot.currentTrack?.title ?: getString(R.string.no_resume_item)

        binding.tvMiniStatus.text = when (snapshot.state) {
            PlaybackUiState.LOADING -> getString(R.string.status_loading)
            PlaybackUiState.BUFFERING -> getString(R.string.status_buffering)
            PlaybackUiState.PLAYING -> getString(R.string.status_playing)
            PlaybackUiState.PAUSED -> getString(R.string.status_paused)
            PlaybackUiState.ERROR -> snapshot.errorMessage ?: getString(R.string.status_error)
            PlaybackUiState.IDLE -> getString(R.string.ready_to_play)
        }

        // 根据当前是否在播放切换按钮图标。
        binding.btnMiniPlayPause.setImageResource(
            if (snapshot.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )

    // 属性： hasMultipleItems
    // 说明：布尔标记位，用于控制分支逻辑与 UI 可用性。
        val hasMultipleItems = snapshot.queue.size > 1
        // 单曲队列时禁用上一首/下一首。
        binding.btnMiniPrevious.isEnabled = hasMultipleItems
        binding.btnMiniNext.isEnabled = hasMultipleItems

    // 属性： position
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val position = snapshot.positionMs.coerceAtLeast(0L)
    // 属性： duration
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val duration = snapshot.durationMs.coerceAtLeast(0L)
    // 属性： buffered
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val buffered = snapshot.bufferedPositionMs.coerceAtLeast(position)
    // 属性： progressMax
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val progressMax = resolveMiniProgressMax(duration, position, snapshot.currentTrack?.isStream == true)

    // 属性： safeMax
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        // 将数值限制在 Int 范围内，因为 ProgressBar 仅接受 Int。
        val safeMax = progressMax.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    // 属性： safeProgress
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val safeProgress = position.coerceAtMost(safeMax.toLong()).toInt()
    // 属性： safeBuffered
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val safeBuffered = buffered.coerceIn(safeProgress.toLong(), safeMax.toLong()).toInt()

        // 主进度=已播放，次进度=已缓冲。
        binding.miniProgressBar.max = safeMax
        binding.miniProgressBar.progress = safeProgress
        binding.miniProgressBar.secondaryProgress = safeBuffered

        // 时间显示：当前时间 / 总时长（流媒体未知时长显示 --:--）。
        binding.tvMiniCurrentTime.text = formatTime(position)
        binding.tvMiniTotalTime.text = if (duration > 0L) formatTime(duration) else "--:--"
    }

    // 函数： resolveMiniProgressMax
    // 说明：基于输入条件推导最终可用结果。
    private fun resolveMiniProgressMax(durationMs: Long, positionMs: Long, isStream: Boolean): Long {
        return when {
            // 本地文件已知总时长时，直接使用真实时长。
            durationMs > 0L -> durationMs
            // 在线流通常无总时长：用当前位置+窗口构造动态上限。
            isStream -> max(positionMs + STREAM_PROGRESS_WINDOW_MS, MIN_STREAM_PROGRESS_MAX_MS)
            // 其他场景默认 0。
            else -> 0L
        }
    }

    // 函数： formatTime
    // 说明：将原始数据转换为便于展示的文本格式。
    private fun formatTime(millis: Long): String {
    // 属性： safeMillis
    // 说明：运行期状态变量，承载 safeMillis 相关上下文信息。
        val safeMillis = millis.coerceAtLeast(0L)
    // 属性： totalSeconds
    // 说明：运行期状态变量，承载 totalSeconds 相关上下文信息。
        val totalSeconds = (safeMillis / 1000L).toInt()
    // 属性： hours
    // 说明：运行期状态变量，承载 hours 相关上下文信息。
        val hours = totalSeconds / 3600
    // 属性： minutes
    // 说明：运行期状态变量，承载 minutes 相关上下文信息。
        val minutes = (totalSeconds % 3600) / 60
    // 属性： seconds
    // 说明：运行期状态变量，承载 seconds 相关上下文信息。
        val seconds = totalSeconds % 60

        // 超过 1 小时显示 h:mm:ss，否则显示 mm:ss。
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private data class MiniStateToken(
        val trackId: String?,
        val queueSize: Int,
        val state: PlaybackUiState,
        val isPlaying: Boolean,
        val errorMessage: String?,
        val positionSecond: Int,
        val durationSecond: Int,
        val bufferedSecond: Int,
        val progressMaxSecond: Int
    )

    companion object {
        private const val MIN_STREAM_PROGRESS_MAX_MS = 10 * 60 * 1000L
        private const val STREAM_PROGRESS_WINDOW_MS = 3 * 60 * 1000L
    }
}