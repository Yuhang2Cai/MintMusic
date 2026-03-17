package com.example.timedmusicplayer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.example.timedmusicplayer.databinding.ActivityPlayerBinding
import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.playback.PlaybackMode
import com.example.timedmusicplayer.playback.PlaybackService
import com.example.timedmusicplayer.playback.PlaybackSnapshot
import com.example.timedmusicplayer.playback.PlaybackUiState
import java.util.Locale
import kotlin.math.max

/**
 * 全屏播放器页面：展示播放状态、进度与控制按钮。
 */
class PlayerActivity : AppCompatActivity(), PlaybackService.PlaybackListener {
    // 属性： binding
    // 说明：当前界面的 ViewBinding 引用，用于类型安全地访问布局控件。
    private lateinit var binding: ActivityPlayerBinding

    // 属性： playbackService
    // 说明：播放服务引用，用于调用播放控制与读取快照。
    private var playbackService: PlaybackService? = null
    // 属性： isBound
    // 说明：绑定状态标记，避免重复绑定或重复解绑。
    private var isBound = false

    // 属性： pendingQueue
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
    private var pendingQueue: ArrayList<Track>? = null
    // 属性： pendingIndex
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
    private var pendingIndex: Int = 0

    // 属性： isUserSeeking
    // 说明：布尔标记位，用于控制分支逻辑与 UI 可用性。
    private var isUserSeeking = false
    // 属性： coverRotationAnimator
    // 说明：运行期状态变量，承载 coverRotationAnimator 相关上下文信息。
    private var coverRotationAnimator: ObjectAnimator? = null

    // 属性： serviceConnection
    // 说明：服务连接回调对象，负责绑定成功/断开后的状态处理。
    private val serviceConnection = object : ServiceConnection {
        // 函数： onServiceConnected
        // 说明：服务连接成功回调，获取服务实例并注册监听。
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
    // 属性： binder
    // 说明：运行期状态变量，承载 binder 相关上下文信息。
            val binder = service as? PlaybackService.LocalBinder ?: return
            playbackService = binder.getService()
            isBound = true
            playbackService?.registerListener(this@PlayerActivity)
            consumePendingQueue()
        }

        // 函数： onServiceDisconnected
        // 说明：服务连接断开回调，清理引用并重置页面状态。
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService?.unregisterListener(this@PlayerActivity)
            playbackService = null
            isBound = false
        }
    }

    // 函数： onCreate
    // 说明：生命周期初始化入口，完成依赖注入、组件初始化与初始状态设置。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        initCoverRotation()
        applyCoverArt()
        setupControls()

        readIntent(intent)

        PlaybackService.startService(this)
    }

    // 函数： onNewIntent
    // 说明：单实例页面收到新 Intent 时触发，用于更新入参。
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        consumePendingQueue()
    }

    // 函数： onStart
    // 说明：组件进入可见态时触发，通常用于建立绑定或开始监听。
    override fun onStart() {
        super.onStart()
    // 属性： serviceIntent
    // 说明：页面或服务通信载体，承载跳转与控制参数。
        val serviceIntent = Intent(this, PlaybackService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // 函数： onStop
    // 说明：组件进入不可见态时触发，用于释放绑定和监听。
    override fun onStop() {
        super.onStop()
        if (isBound) {
            playbackService?.unregisterListener(this)
            unbindService(serviceConnection)
            isBound = false
        }
        pauseCoverRotation()
    }

    // 函数： onDestroy
    // 说明：组件销毁前触发，用于回收资源并清理任务。
    override fun onDestroy() {
        coverRotationAnimator?.cancel()
        super.onDestroy()
    }

    // 函数： onSupportNavigateUp
    // 说明：处理顶部返回按钮事件，统一页面回退行为。
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // 函数： onSnapshotChanged
    // 说明：接收播放快照变化并驱动页面 UI 增量更新。
    override fun onSnapshotChanged(snapshot: PlaybackSnapshot) {
        runOnUiThread {
            renderSnapshot(snapshot)
        }
    }

// 函数： setupControls
// 说明：设置内部状态并触发必要的联动更新。
private fun setupControls() {
        binding.btnPrevious.setOnClickListener {
            playbackService?.playPrevious()
        }

        binding.btnNext.setOnClickListener {
            playbackService?.playNext()
        }

        binding.btnPlayPause.setOnClickListener {
            playbackService?.togglePlayPause()
        }

        binding.btnPlaybackMode.setOnClickListener {
            playbackService?.cyclePlaybackMode()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            // 函数： onProgressChanged
            // 说明：响应拖动条进度变化，处理用户交互中的实时显示。
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = formatTime(progress.toLong())
                }
            }

            // 函数： onStartTrackingTouch
            // 说明：用户开始拖动进度条时触发，进入手动拖动状态。
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            // 函数： onStopTrackingTouch
            // 说明：用户结束拖动时触发，提交 seek 目标位置。
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
    // 属性： target
    // 说明：运行期状态变量，承载 target 相关上下文信息。
                val target = seekBar?.progress?.toLong() ?: 0L
                playbackService?.seekTo(target)
                isUserSeeking = false
            }
        })
    }

    // 函数： readIntent
    // 说明：封装 readIntent 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun readIntent(intent: Intent?) {
        if (intent == null) return

    // 属性： queue
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
        val queue = intent.getParcelableArrayListExtra<Track>(EXTRA_QUEUE)
        if (!queue.isNullOrEmpty()) {
            pendingQueue = queue
            pendingIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
        }
    }

// 函数： consumePendingQueue
// 说明：封装 consumePendingQueue 相关业务流程，负责参数校验、状态流转与异常兜底。
private fun consumePendingQueue() {
    // 属性： queue
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
        val queue = pendingQueue ?: return
        if (queue.isEmpty()) {
            pendingQueue = null
            return
        }

        playbackService?.playQueue(
            tracks = queue,
            startIndex = pendingIndex,
            forcePlay = true
        )
        pendingQueue = null
    }

// 函数： renderSnapshot
// 说明：根据当前状态刷新界面元素与可见性。
private fun renderSnapshot(snapshot: PlaybackSnapshot) {
    // 属性： current
    // 说明：运行期状态变量，承载 current 相关上下文信息。
        val current = snapshot.currentTrack

        binding.tvTrackTitle.text = current?.title ?: getString(R.string.no_resume_item)
        binding.toolbar.subtitle = when {
            current == null -> null
            current.isStream -> getString(R.string.source_cloud)
            else -> getString(R.string.source_local)
        }

        binding.tvStatus.text = when (snapshot.state) {
            PlaybackUiState.LOADING -> getString(R.string.status_loading)
            PlaybackUiState.BUFFERING -> getString(R.string.status_buffering)
            PlaybackUiState.PLAYING -> getString(R.string.status_playing)
            PlaybackUiState.PAUSED -> getString(R.string.status_paused)
            PlaybackUiState.ERROR -> snapshot.errorMessage ?: getString(R.string.status_error)
            PlaybackUiState.IDLE -> getString(R.string.ready_to_play)
        }

        binding.btnPlaybackMode.text = getString(
            R.string.playback_mode_with_value,
            modeLabel(snapshot.playbackMode)
        )

    // 属性： showLoading
    // 说明：运行期状态变量，承载 showLoading 相关上下文信息。
        val showLoading = snapshot.state == PlaybackUiState.LOADING ||
            snapshot.state == PlaybackUiState.BUFFERING
        binding.loadingContainer.visibility = if (showLoading) View.VISIBLE else View.GONE

    // 属性： duration
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val duration = snapshot.durationMs.coerceAtLeast(0L)
    // 属性： progress
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val progress = snapshot.positionMs.coerceAtLeast(0L)
    // 属性： buffered
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val buffered = snapshot.bufferedPositionMs.coerceAtLeast(progress)

    // 属性： seekMax
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val seekMax = when {
            duration > 0L -> duration
            current?.isStream == true -> max(progress + STREAM_PROGRESS_WINDOW_MS, MIN_STREAM_PROGRESS_MAX_MS)
            else -> 0L
        }

        if (!isUserSeeking) {
            binding.seekBar.max = seekMax.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    // 属性： safeProgress
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
            val safeProgress = progress.coerceAtMost(seekMax).toInt()
    // 属性： safeBuffered
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
            val safeBuffered = buffered.coerceAtMost(seekMax).toInt()
            binding.seekBar.progress = safeProgress
            binding.seekBar.secondaryProgress = safeBuffered.coerceAtLeast(safeProgress)

            binding.tvCurrentTime.text = formatTime(progress)
            binding.tvTotalTime.text = if (duration > 0L) {
                formatTime(duration)
            } else {
                "--:--"
            }
        }

        if (current?.isStream == true) {
            binding.tvBufferedInfo.visibility = View.VISIBLE
    // 属性： percent
    // 说明：运行期状态变量，承载 percent 相关上下文信息。
            val percent = if (seekMax > 0L) {
                ((buffered * 100L) / seekMax).coerceIn(0L, 100L).toInt()
            } else {
                0
            }
            binding.tvBufferedInfo.text = getString(R.string.buffered_percent_with_value, percent)
        } else {
            binding.tvBufferedInfo.visibility = View.GONE
        }

        binding.btnPlayPause.setImageResource(
            if (snapshot.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )

        binding.btnPrevious.isEnabled = snapshot.queue.size > 1
        binding.btnNext.isEnabled = snapshot.queue.size > 1

        if (snapshot.isPlaying) {
            startCoverRotation()
        } else {
            pauseCoverRotation()
        }
    }

    // 函数： modeLabel
    // 说明：封装 modeLabel 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun modeLabel(mode: PlaybackMode): String {
        return when (mode) {
            PlaybackMode.ORDER -> getString(R.string.mode_order)
            PlaybackMode.REPEAT_ONE -> getString(R.string.mode_repeat_one)
            PlaybackMode.REPEAT_ALL -> getString(R.string.mode_repeat_all)
            PlaybackMode.SHUFFLE -> getString(R.string.mode_shuffle)
        }
    }

    // 函数： initCoverRotation
    // 说明：初始化组件、默认参数与运行时依赖。
    private fun initCoverRotation() {
        if (coverRotationAnimator != null) return
        coverRotationAnimator = ObjectAnimator.ofFloat(binding.ivCover, View.ROTATION, 0f, 360f).apply {
            duration = 12_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    // 函数： startCoverRotation
    // 说明：封装 startCoverRotation 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun startCoverRotation() {
    // 属性： animator
    // 说明：运行期状态变量，承载 animator 相关上下文信息。
        val animator = coverRotationAnimator ?: return
        if (animator.isPaused) {
            animator.resume()
        } else if (!animator.isStarted) {
            animator.start()
        }
    }

    // 函数： pauseCoverRotation
    // 说明：暂停当前播放或动画并保持状态可恢复。
    private fun pauseCoverRotation() {
    // 属性： animator
    // 说明：运行期状态变量，承载 animator 相关上下文信息。
        val animator = coverRotationAnimator ?: return
        if (animator.isRunning) {
            animator.pause()
        }
    }

    // 函数： applyCoverArt
    // 说明：将配置应用到目标组件并立即生效。
    private fun applyCoverArt() {
    // 属性： customCoverResId
    // 说明：运行期状态变量，承载 customCoverResId 相关上下文信息。
        val customCoverResId = resources.getIdentifier(CUSTOM_COVER_RES_NAME, "drawable", packageName)
        if (customCoverResId != 0) {
            binding.ivCover.setImageResource(customCoverResId)
        } else {
            binding.ivCover.setImageResource(R.drawable.cover_placeholder)
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

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        const val EXTRA_QUEUE = "extra_queue"
        const val EXTRA_START_INDEX = "extra_start_index"

        private const val CUSTOM_COVER_RES_NAME = "jay_cover"
        private const val MIN_STREAM_PROGRESS_MAX_MS = 10 * 60 * 1000L
        private const val STREAM_PROGRESS_WINDOW_MS = 3 * 60 * 1000L
    }
}