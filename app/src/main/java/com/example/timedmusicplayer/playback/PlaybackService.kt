package com.example.timedmusicplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.timedmusicplayer.PlayerActivity
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.analytics.EventLogger
import com.example.timedmusicplayer.data.MusicRepository
import com.example.timedmusicplayer.model.Track

/**
 * 播放核心服务：集成 ExoPlayer、MediaSession、通知与保活。
 */
class PlaybackService : Service() {

    interface PlaybackListener {
        // 函数： onSnapshotChanged
        // 说明：接收播放快照变化并驱动页面 UI 增量更新。
        fun onSnapshotChanged(snapshot: PlaybackSnapshot)
    }

    inner class LocalBinder : Binder() {
        // 函数： getService
        // 说明：读取并返回当前数据或状态快照。
        fun getService(): PlaybackService = this@PlaybackService
    }

    // 属性： binder
    // 说明：运行期状态变量，承载 binder 相关上下文信息。
    private val binder = LocalBinder()
    // 属性： listeners
    // 说明：运行期状态变量，承载 listeners 相关上下文信息。
    private val listeners = mutableSetOf<PlaybackListener>()

    // 属性： player
    // 说明：ExoPlayer 播放器实例，负责媒体加载与播放。
    private lateinit var player: ExoPlayer
    // 属性： mediaSession
    // 说明：MediaSession 会话对象，承接系统媒体控制通道。
    private lateinit var mediaSession: MediaSessionCompat
    // 属性： notificationManager
    // 说明：通知管理器，用于发布与更新播放通知。
    private lateinit var notificationManager: NotificationManager
    // 属性： repository
    // 说明：数据仓库入口，统一提供本地与云端数据访问能力。
    private lateinit var repository: MusicRepository
    // 属性： logger
    // 说明：日志记录器，写入事件、错误与崩溃信息。
    private lateinit var logger: EventLogger

    // 属性： wifiLock
    // 说明：Wi-Fi 锁对象，在线播放时维持网络性能与稳定性。
    private var wifiLock: WifiManager.WifiLock? = null

    // 属性： queue
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
    private var queue: List<Track> = emptyList()
    // 属性： currentIndex
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
    private var currentIndex: Int = 0
    // 属性： state
    // 说明：运行状态变量，表示当前 UI 或播放状态。
    private var state: PlaybackUiState = PlaybackUiState.IDLE
    // 属性： playbackMode
    // 说明：运行期状态变量，承载 playbackMode 相关上下文信息。
    private var playbackMode: PlaybackMode = PlaybackMode.ORDER

    // 属性： lastErrorMessage
    // 说明：错误信息缓存，用于 UI 展示与日志上报。
    private var lastErrorMessage: String? = null
    // 属性： retryCount
    // 说明：运行期状态变量，承载 retryCount 相关上下文信息。
    private var retryCount = 0
    // 属性： isForegroundStarted
    // 说明：布尔标记位，用于控制分支逻辑与 UI 可用性。
    private var isForegroundStarted = false

    // 属性： mainHandler
    // 说明：定时调度组件，用于周期性同步状态。
    private val mainHandler = Handler(Looper.getMainLooper())
    // 属性： ticker
    // 说明：定时调度组件，用于周期性同步状态。
    private val ticker = object : Runnable {
        // 函数： run
        // 说明：封装 run 相关业务流程，负责参数校验、状态流转与异常兜底。
        override fun run() {
            //把最新播放快照推给页面（主页面迷你播放器/播放器页）
            dispatchSnapshot()
            //把当前曲目和进度写入持久化，用于断点续播。
            persistProgress()
            //更新 MediaSession 的播放状态，锁屏和耳机键状态会同步。
            updateSessionState()
            //刷新通知栏标题、按钮、播放态，并决定是否前台服务。
            updateNotification()
            //按当前是否在线流媒体决定持有/释放 Wi-Fi 锁。
            updateWifiLock()
            //再次把自己延时投递，形成循环（直到 onDestroy 里 removeCallbacks 停止）。
            mainHandler.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    // 函数： onCreate
    // 说明：生命周期初始化入口，完成依赖注入、组件初始化与初始状态设置。
    override fun onCreate() {
        super.onCreate()

        repository = MusicRepository.getInstance(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        logger = EventLogger.getInstance(this)

        createNotificationChannel()
        initPlayer()
        initMediaSession()

    // 属性： savedMode
    // 说明：运行期状态变量，承载 savedMode 相关上下文信息。
        val savedMode = PlaybackMode.fromRaw(
            repository.getPlaybackMode(PlaybackMode.ORDER.name)
        )
        applyPlaybackMode(savedMode, persist = false)

        mainHandler.post(ticker)
        logger.logEvent("playback_service_create")
    }

    // 函数： onBind
    // 说明：服务绑定入口，返回 Binder 供外部调用服务能力。
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    // 函数： onStartCommand
    // 说明：服务启动命令入口，解析 action 并分发控制逻辑。
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_PREVIOUS -> playPrevious()
            ACTION_STOP -> stopPlaybackAndService()
        }

        intent?.action?.let { action ->
            logger.logEvent("playback_service_action", mapOf("action" to action))
        }

        return START_STICKY
    }

    // 函数： onTaskRemoved
    // 说明：任务被系统移除时触发，用于决定是否停止服务。
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    // 函数： onDestroy
    // 说明：组件销毁前触发，用于回收资源并清理任务。
    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)

        if (isForegroundStarted) {
            stopForegroundCompat(removeNotification = true)
        } else {
            notificationManager.cancel(NOTIFICATION_ID)
        }

        releaseWifiLock()

        runCatching { mediaSession.release() }
        runCatching { player.release() }

        listeners.clear()
        logger.logEvent("playback_service_destroy")
        super.onDestroy()
    }

    // 函数： registerListener
    // 说明：注册监听器以接收后续状态变化通知。
    fun registerListener(listener: PlaybackListener) {
        listeners.add(listener)
        listener.onSnapshotChanged(buildSnapshot())
    }

    // 函数： unregisterListener
    // 说明：注销监听器，避免重复回调和内存泄漏。
    fun unregisterListener(listener: PlaybackListener) {
        listeners.remove(listener)
    }

    // 函数： playQueue
    // 说明：执行播放控制动作并维护队列状态一致性。
    fun playQueue(tracks: List<Track>, startIndex: Int, forcePlay: Boolean) {
        if (tracks.isEmpty()) {
            return
        }

        queue = tracks.toList()
        retryCount = 0
        lastErrorMessage = null

    // 属性： safeIndex
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val safeIndex = startIndex.coerceIn(0, queue.lastIndex)
        currentIndex = safeIndex

    // 属性： mediaItems
    // 说明：运行期状态变量，承载 mediaItems 相关上下文信息。
        // 将统一 Track 模型转换为 ExoPlayer 的 MediaItem。
        val mediaItems = queue.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(Uri.parse(track.uri))
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .build()
                )
                .build()
        }

    // 属性： startPosition
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val startPosition = resolveResumePosition(queue[safeIndex])

        // 在 prepare/play 前先切换到加载状态。
        state = PlaybackUiState.LOADING
        player.setMediaItems(mediaItems, safeIndex, startPosition)
        player.prepare()
        player.playWhenReady = forcePlay

        updateNowPlayingMetadata(queue[safeIndex])
        updateSessionState()
        updateNotification()
        dispatchSnapshot()

        logger.logEvent(
            "queue_start",
            mapOf(
                "size" to tracks.size.toString(),
                "start_index" to safeIndex.toString(),
                "mode" to playbackMode.name
            )
        )
    }

    // 函数： togglePlayPause
    // 说明：在两种状态之间切换并保持行为幂等。
    fun togglePlayPause() {
        if (queue.isEmpty()) {
            return
        }

        if (player.isPlaying) {
            player.pause()
            state = PlaybackUiState.PAUSED
            logger.logEvent("playback_pause")
        } else {
            player.playWhenReady = true
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            state = if (player.playbackState == Player.STATE_BUFFERING) {
                PlaybackUiState.BUFFERING
            } else {
                PlaybackUiState.PLAYING
            }
            logger.logEvent("playback_resume")
        }

        updateSessionState()
        updateNotification()
        dispatchSnapshot()
    }

    // 函数： playNext
    // 说明：执行播放控制动作并维护队列状态一致性。
    fun playNext() {
        if (queue.isEmpty()) {
            return
        }

        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else {
            player.seekToDefaultPosition(0)
        }
        player.playWhenReady = true
        logger.logEvent("playback_next")
    }

    // 函数： playPrevious
    // 说明：执行播放控制动作并维护队列状态一致性。
    fun playPrevious() {
        if (queue.isEmpty()) {
            return
        }

        if (player.currentPosition > RESTART_THRESHOLD_MS) {
            player.seekTo(0)
            logger.logEvent("playback_restart_current")
            return
        }

        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekToDefaultPosition(queue.lastIndex)
        }
        player.playWhenReady = true
        logger.logEvent("playback_previous")
    }

    // 函数： seekTo
    // 说明：将播放位置定位到目标时间点并更新日志。
    fun seekTo(positionMs: Long) {
        if (queue.isEmpty()) {
            return
        }

    // 属性： maxDuration
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val maxDuration = if (player.duration > 0) player.duration else Long.MAX_VALUE
    // 属性： target
    // 说明：运行期状态变量，承载 target 相关上下文信息。
        val target = positionMs.coerceIn(0L, maxDuration)
        player.seekTo(target)
        logger.logEvent("playback_seek", mapOf("position_ms" to target.toString()))
    }

    // 函数： cyclePlaybackMode
    // 说明：循环切换枚举配置并返回最新值。
    fun cyclePlaybackMode(): PlaybackMode {
    // 属性： nextMode
    // 说明：运行期状态变量，承载 nextMode 相关上下文信息。
        val nextMode = playbackMode.next()
        applyPlaybackMode(nextMode, persist = true)
        dispatchSnapshot()
        updateSessionState()
        updateNotification()

        logger.logEvent("playback_mode_changed", mapOf("mode" to nextMode.name))
        return nextMode
    }

    // 函数： getPlaybackMode
    // 说明：读取并返回当前数据或状态快照。
    fun getPlaybackMode(): PlaybackMode {
        return playbackMode
    }

    // 函数： getSnapshot
    // 说明：读取并返回当前数据或状态快照。
    fun getSnapshot(): PlaybackSnapshot {
        return buildSnapshot()
    }

    // 函数： initPlayer
    // 说明：初始化组件、默认参数与运行时依赖。
    private fun initPlayer() {
    // 属性： loadControl
    // 说明：运行期状态变量，承载 loadControl 相关上下文信息。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 120_000, 1_000, 2_500)
            .build()

    // 属性： httpFactory
    // 说明：运行期状态变量，承载 httpFactory 相关上下文信息。
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

    // 属性： mediaSourceFactory
    // 说明：运行期状态变量，承载 mediaSourceFactory 相关上下文信息。
        val mediaSourceFactory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(this, httpFactory)
        )

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                setHandleAudioBecomingNoisy(true)
                addListener(createPlayerListener())
            }
    }

    // 函数： applyPlaybackMode
    // 说明：将配置应用到目标组件并立即生效。
    private fun applyPlaybackMode(mode: PlaybackMode, persist: Boolean) {
        playbackMode = mode

        when (mode) {
            PlaybackMode.ORDER -> {
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.shuffleModeEnabled = false
            }

            PlaybackMode.REPEAT_ONE -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.shuffleModeEnabled = false
            }

            PlaybackMode.REPEAT_ALL -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = false
            }

            PlaybackMode.SHUFFLE -> {
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.shuffleModeEnabled = true
            }
        }

        if (persist) {
            repository.savePlaybackMode(mode.name)
        }
    }

    // 函数： createPlayerListener
    // 说明：创建并返回后续流程所需对象或实例。
    private fun createPlayerListener(): Player.Listener {
        return object : Player.Listener {
            // 函数： onMediaItemTransition
            // 说明：媒体项切换回调，更新当前索引与统计信息。
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    // 属性： current
    // 说明：运行期状态变量，承载 current 相关上下文信息。
                val current = player.currentMediaItemIndex
                if (current != C.INDEX_UNSET) {
                    currentIndex = current.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
                    currentTrack()?.let { track ->
                        repository.markPlayed(track.id)
                        updateNowPlayingMetadata(track)
                        logger.logEvent(
                            "track_transition",
                            mapOf(
                                "track_id" to track.id,
                                "reason" to reason.toString()
                            )
                        )
                    }
                }
                retryCount = 0
                lastErrorMessage = null
            }

            // 函数： onPlaybackStateChanged
            // 说明：播放器状态变化回调，将内核状态映射到 UI 状态。
            override fun onPlaybackStateChanged(playbackState: Int) {
                // 将 ExoPlayer 状态映射为页面可消费的 UI 状态。
                state = when (playbackState) {
                    Player.STATE_IDLE -> PlaybackUiState.IDLE
                    Player.STATE_BUFFERING -> {
                        if (player.playWhenReady) PlaybackUiState.BUFFERING else PlaybackUiState.LOADING
                    }

                    Player.STATE_READY -> {
                        if (player.isPlaying) PlaybackUiState.PLAYING else PlaybackUiState.PAUSED
                    }

                    Player.STATE_ENDED -> PlaybackUiState.PAUSED
                    else -> PlaybackUiState.IDLE
                }

                if (playbackState == Player.STATE_READY) {
                    currentTrack()?.let { track ->
                        repository.markPlayed(track.id)
                        updateNowPlayingMetadata(track)
                    }
                }

                updateSessionState()
                updateNotification()
                dispatchSnapshot()
            }

            // 函数： onIsPlayingChanged
            // 说明：播放开关变化回调，更新播放态与展示态一致性。
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state = when {
                    isPlaying -> PlaybackUiState.PLAYING
                    player.playbackState == Player.STATE_BUFFERING -> PlaybackUiState.BUFFERING
                    player.playbackState == Player.STATE_READY -> PlaybackUiState.PAUSED
                    else -> state
                }
                updateSessionState()
                updateNotification()
                dispatchSnapshot()
            }

            // 函数： onPlayerError
            // 说明：播放器错误回调入口，转入统一错误处理流程。
            override fun onPlayerError(error: PlaybackException) {
                handlePlaybackError(error)
            }
        }
    }

    // 函数： handlePlaybackError
    // 说明：处理异常或分支场景并执行兜底策略。
    private fun handlePlaybackError(error: PlaybackException) {
    // 属性： track
    // 说明：运行期状态变量，承载 track 相关上下文信息。
        val track = currentTrack()
    // 属性： isStream
    // 说明：布尔标记位，用于控制分支逻辑与 UI 可用性。
        val isStream = track?.isStream == true

        // 对瞬时流媒体错误先重试，再向 UI 暴露错误。
        if (isStream && retryCount < MAX_AUTO_RETRY) {
            retryCount += 1
            state = PlaybackUiState.BUFFERING
            lastErrorMessage = getString(R.string.status_retrying, retryCount, MAX_AUTO_RETRY)
            dispatchSnapshot()
            updateNotification()

            logger.logError(
                name = "stream_retry",
                throwable = error,
                params = mapOf(
                    "retry" to retryCount.toString(),
                    "track_id" to (track?.id ?: "")
                )
            )

    // 属性： retryDelay
    // 说明：运行期状态变量，承载 retryDelay 相关上下文信息。
            val retryDelay = 1500L * retryCount
            mainHandler.postDelayed({
                if (queue.isNotEmpty()) {
                    player.seekToDefaultPosition(currentIndex)
                    player.prepare()
                    player.playWhenReady = true
                }
            }, retryDelay)
            return
        }

        state = PlaybackUiState.ERROR
        lastErrorMessage = error.errorCodeName
        updateSessionState()
        updateNotification()
        dispatchSnapshot()

        logger.logError(
            name = "playback_error",
            throwable = error,
            params = mapOf("track_id" to (track?.id ?: ""))
        )
    }

    // 函数： initMediaSession
    // 说明：初始化组件、默认参数与运行时依赖。
    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, SESSION_TAG).apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                // 函数： onPlay
                // 说明：媒体会话播放指令回调，触发播放状态切换。
                override fun onPlay() = togglePlayPauseIfNeeded(play = true)
                // 函数： onPause
                // 说明：组件即将失去焦点时触发，用于暂停高频任务。
                override fun onPause() = togglePlayPauseIfNeeded(play = false)
                // 函数： onSkipToNext
                // 说明：媒体会话下一首指令回调，跳转到后一个媒体项。
                override fun onSkipToNext() = playNext()
                // 函数： onSkipToPrevious
                // 说明：媒体会话上一首指令回调，跳转到前一个媒体项。
                override fun onSkipToPrevious() = playPrevious()
                // 函数： onSeekTo
                // 说明：媒体会话定位指令回调，执行时间轴跳转。
                override fun onSeekTo(pos: Long) = seekTo(pos)
                // 函数： onStop
                // 说明：组件进入不可见态时触发，用于释放绑定和监听。
                override fun onStop() = stopPlaybackAndService()
            })
            isActive = true
        }
        updateSessionState()
    }

    // 函数： togglePlayPauseIfNeeded
    // 说明：在两种状态之间切换并保持行为幂等。
    private fun togglePlayPauseIfNeeded(play: Boolean) {
        if (queue.isEmpty()) {
            return
        }
        if (play) {
            if (!player.isPlaying) {
                togglePlayPause()
            }
        } else {
            if (player.isPlaying) {
                togglePlayPause()
            }
        }
    }

    // 函数： updateSessionState
    // 说明：更新状态并同步到相关依赖组件或持久层。
    private fun updateSessionState() {
    // 属性： position
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val position = player.currentPosition.coerceAtLeast(0L)
    // 属性： buffered
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val buffered = player.bufferedPosition.coerceAtLeast(position)
    // 属性： actions
    // 说明：运行期状态变量，承载 actions 相关上下文信息。
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP

    // 属性： stateCompat
    // 说明：运行状态变量，表示当前 UI 或播放状态。
        val stateCompat = when (state) {
            PlaybackUiState.LOADING,
            PlaybackUiState.BUFFERING -> PlaybackStateCompat.STATE_BUFFERING

            PlaybackUiState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            PlaybackUiState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            PlaybackUiState.ERROR -> PlaybackStateCompat.STATE_ERROR
            PlaybackUiState.IDLE -> PlaybackStateCompat.STATE_STOPPED
        }

    // 属性： builder
    // 说明：运行期状态变量，承载 builder 相关上下文信息。
        val builder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(stateCompat, position, if (player.isPlaying) 1f else 0f)
            .setBufferedPosition(buffered)

        if (!lastErrorMessage.isNullOrBlank()) {
            builder.setErrorMessage(lastErrorMessage)
        }

        mediaSession.setPlaybackState(builder.build())
    }

    // 函数： updateNowPlayingMetadata
    // 说明：更新状态并同步到相关依赖组件或持久层。
    private fun updateNowPlayingMetadata(track: Track) {
    // 属性： duration
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val duration = if (player.duration > 0) player.duration else track.durationMs
    // 属性： metadata
    // 说明：运行期状态变量，承载 metadata 相关上下文信息。
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .build()
        mediaSession.setMetadata(metadata)
    }

    // 函数： dispatchSnapshot
    // 说明：封装 dispatchSnapshot 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun dispatchSnapshot() {
    // 属性： snapshot
    // 说明：运行期状态变量，承载 snapshot 相关上下文信息。
        val snapshot = buildSnapshot()
        listeners.forEach { listener ->
            listener.onSnapshotChanged(snapshot)
        }
    }

    // 函数： buildSnapshot
    // 说明：封装 buildSnapshot 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun buildSnapshot(): PlaybackSnapshot {
    // 属性： safeDuration
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val safeDuration = player.duration.takeIf { it > 0 } ?: 0L
    // 属性： safeIndex
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val safeIndex = currentIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        return PlaybackSnapshot(
            queue = queue,
            currentIndex = safeIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
            durationMs = safeDuration,
            isPlaying = player.isPlaying,
            state = state,
            playbackMode = playbackMode,
            errorMessage = lastErrorMessage
        )
    }

    // 函数： persistProgress
    // 说明：封装 persistProgress 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun persistProgress() {
    // 属性： track
    // 说明：运行期状态变量，承载 track 相关上下文信息。
        val track = currentTrack() ?: return
    // 属性： position
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val position = player.currentPosition.coerceAtLeast(0L)
        repository.saveLastPlayback(track.id, position)
    }

    // 函数： resolveResumePosition
    // 说明：基于输入条件推导最终可用结果。
    private fun resolveResumePosition(track: Track): Long {
    // 属性： last
    // 说明：运行期状态变量，承载 last 相关上下文信息。
        val last = repository.getLastPlayback() ?: return 0L
        if (last.trackId != track.id) {
            return 0L
        }
        return last.positionMs.coerceAtLeast(0L)
    }

    // 函数： currentTrack
    // 说明：封装 currentTrack 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun currentTrack(): Track? {
        return queue.getOrNull(currentIndex)
    }

    // 函数： updateNotification
    // 说明：更新状态并同步到相关依赖组件或持久层。
    private fun updateNotification() {
        if (queue.isEmpty()) {
            if (isForegroundStarted) {
                stopForegroundCompat(removeNotification = true)
                isForegroundStarted = false
            }
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }

    // 属性： notification
    // 说明：运行期状态变量，承载 notification 相关上下文信息。
        val notification = buildNotification()
    // 属性： keepForeground
    // 说明：运行期状态变量，承载 keepForeground 相关上下文信息。
        // 仅在播放中或加载中时保持前台服务。
        val keepForeground = shouldStayForeground()

        if (keepForeground) {
            if (!isForegroundStarted) {
                startForeground(NOTIFICATION_ID, notification)
                isForegroundStarted = true
            } else {
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            if (isForegroundStarted) {
                stopForegroundCompat(removeNotification = false)
                isForegroundStarted = false
            }
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    // 函数： shouldStayForeground
    // 说明：根据当前上下文判断是否满足执行条件。
    private fun shouldStayForeground(): Boolean {
        return when (state) {
            PlaybackUiState.PLAYING,
            PlaybackUiState.BUFFERING,
            PlaybackUiState.LOADING -> true

            else -> false
        }
    }

    // 函数： buildNotification
    // 说明：封装 buildNotification 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun buildNotification(): Notification {
    // 属性： track
    // 说明：运行期状态变量，承载 track 相关上下文信息。
        val track = currentTrack()
    // 属性： openIntent
    // 说明：页面或服务通信载体，承载跳转与控制参数。
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // 属性： playPauseAction
    // 说明：运行期状态变量，承载 playPauseAction 相关上下文信息。
        val playPauseAction = if (player.isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                getString(R.string.pause),
                createServicePendingIntent(ACTION_TOGGLE_PLAY, 100)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                getString(R.string.play),
                createServicePendingIntent(ACTION_TOGGLE_PLAY, 100)
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track?.title ?: getString(R.string.app_name))
            .setContentText(track?.artist ?: getString(R.string.ready_to_play))
            .setContentIntent(openIntent)
            .setDeleteIntent(createServicePendingIntent(ACTION_STOP, 101))
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_previous,
                    getString(R.string.previous),
                    createServicePendingIntent(ACTION_PREVIOUS, 102)
                )
            )
            .addAction(playPauseAction)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_next,
                    getString(R.string.next),
                    createServicePendingIntent(ACTION_NEXT, 103)
                )
            )
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(player.isPlaying)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    // 函数： createServicePendingIntent
    // 说明：创建并返回后续流程所需对象或实例。
    private fun createServicePendingIntent(action: String, requestCode: Int): PendingIntent {
    // 属性： intent
    // 说明：页面或服务通信载体，承载跳转与控制参数。
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // 函数： updateWifiLock
    // 说明：更新状态并同步到相关依赖组件或持久层。
    private fun updateWifiLock() {
    // 属性： shouldHold
    // 说明：布尔标记位，用于控制分支逻辑与 UI 可用性。
        // 仅在在线流媒体活跃播放时持有 Wi-Fi 锁。
        val shouldHold = currentTrack()?.isStream == true && shouldStayForeground()
        if (shouldHold) {
            acquireWifiLock()
        } else {
            releaseWifiLock()
        }
    }

    // 函数： acquireWifiLock
    // 说明：封装 acquireWifiLock 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun acquireWifiLock() {
        if (wifiLock == null) {
    // 属性： manager
    // 说明：运行期状态变量，承载 manager 相关上下文信息。
            val manager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG).apply {
                setReferenceCounted(false)
            }
        }

        if (wifiLock?.isHeld == false) {
            wifiLock?.acquire()
        }
    }

    // 函数： releaseWifiLock
    // 说明：封装 releaseWifiLock 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun releaseWifiLock() {
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
    }

    // 函数： stopPlaybackAndService
    // 说明：封装 stopPlaybackAndService 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun stopPlaybackAndService() {
        queue = emptyList()
        currentIndex = 0
        state = PlaybackUiState.IDLE
        lastErrorMessage = null
        retryCount = 0

        player.stop()
        player.clearMediaItems()

        updateSessionState()
        dispatchSnapshot()

        stopForegroundCompat(removeNotification = true)
        isForegroundStarted = false
        releaseWifiLock()

        logger.logEvent("playback_stop")
        stopSelf()
    }

    // 函数： stopForegroundCompat
    // 说明：封装 stopForegroundCompat 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (removeNotification) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(STOP_FOREGROUND_DETACH)
            }
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    // 函数： createNotificationChannel
    // 说明：创建并返回后续流程所需对象或实例。
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

    // 属性： channel
    // 说明：运行期状态变量，承载 channel 相关上下文信息。
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.playback_channel_desc)
            setShowBadge(false)
        }

        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val SESSION_TAG = "MintWavePlaybackSession"
        private const val CHANNEL_ID = "playback_channel"
        private const val NOTIFICATION_ID = 2001
        private const val WIFI_LOCK_TAG = "MintWaveWifiLock"

        private const val MAX_AUTO_RETRY = 2
        private const val RESTART_THRESHOLD_MS = 3_000L
        private const val PROGRESS_TICK_MS = 500L

        const val ACTION_TOGGLE_PLAY = "com.example.timedmusicplayer.action.TOGGLE_PLAY"
        const val ACTION_NEXT = "com.example.timedmusicplayer.action.NEXT"
        const val ACTION_PREVIOUS = "com.example.timedmusicplayer.action.PREVIOUS"
        const val ACTION_STOP = "com.example.timedmusicplayer.action.STOP"

        // 函数： startService
        // 说明：封装 startService 相关业务流程，负责参数校验、状态流转与异常兜底。
        fun startService(context: android.content.Context) {
    // 属性： intent
    // 说明：页面或服务通信载体，承载跳转与控制参数。
            val intent = Intent(context, PlaybackService::class.java)
            context.startService(intent)
        }
    }
}