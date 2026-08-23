package com.example.timedmusicplayer.playback

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.timedmusicplayer.data.MusicRepository
import com.example.timedmusicplayer.PlayerActivity
import com.example.timedmusicplayer.network.NetworkMonitor
import com.example.timedmusicplayer.network.PlaybackErrorClassifier
import com.example.timedmusicplayer.network.RecoveryPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit

/** Standard Media3 background playback owner. Media3 provides notification and system controls. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var repository: MusicRepository
    private lateinit var networkMonitor: NetworkMonitor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val checkpoints = Channel<Checkpoint>(Channel.CONFLATED)
    private val recoveryPolicy = RecoveryPolicy()
    private var recoveryGeneration = 0L
    private var recoveryAttempt = 0
    private var state: PlaybackState = PlaybackState.Idle
    private var sleepTimerJob: Job? = null
    private var sleepTimerEndElapsedRealtimeMs = 0L

    override fun onCreate() {
        super.onCreate()
        ensurePlaybackNotificationChannel()
        repository = MusicRepository.getInstance(this)
        val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val dataSourceFactory = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(http))
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 2_500, 5_000).build()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setSessionExtras(sleepTimerExtras())
            .setCallback(mediaSessionCallback)
            .build()
        networkMonitor = NetworkMonitor(this) { online -> if (online) resumeAfterNetworkReturns() }
        networkMonitor.start()
        player.addListener(playerListener)
        startCheckpointActor()
        startPeriodicCheckpoint()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SleepTimerCommands.setTimer)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setSessionExtras(sleepTimerExtras())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != SleepTimerCommands.ACTION_SET) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            setSleepTimer(args.getLong(SleepTimerCommands.ARG_DURATION_MS, 0L))
            return Futures.immediateFuture(
                SessionResult(SessionResult.RESULT_SUCCESS, sleepTimerExtras())
            )
        }
    }

    private fun ensurePlaybackNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
            getString(com.example.timedmusicplayer.R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(com.example.timedmusicplayer.R.string.playback_channel_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        enqueueCheckpoint()
        sleepTimerJob?.cancel()
        networkMonitor.stop()
        player.removeListener(playerListener)
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun setSleepTimer(durationMs: Long) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (durationMs <= 0L) {
            sleepTimerEndElapsedRealtimeMs = 0L
            publishSleepTimer()
            return
        }

        val deadline = SystemClock.elapsedRealtime() + durationMs
        sleepTimerEndElapsedRealtimeMs = deadline
        publishSleepTimer()
        sleepTimerJob = scope.launch {
            delay(durationMs)
            if (sleepTimerEndElapsedRealtimeMs != deadline) return@launch
            sleepTimerEndElapsedRealtimeMs = 0L
            player.pause()
            publishSleepTimer()
        }
    }

    private fun publishSleepTimer() {
        if (::session.isInitialized) {
            session.setSessionExtras(sleepTimerExtras())
        }
    }

    private fun sleepTimerExtras(): Bundle = Bundle().apply {
        putLong(
            SleepTimerCommands.EXTRA_END_ELAPSED_REALTIME_MS,
            sleepTimerEndElapsedRealtimeMs
        )
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            state = when {
                player.playerError != null -> state
                player.playbackState == Player.STATE_BUFFERING -> PlaybackStateMachine.reduce(state, PlaybackEvent.Buffer)
                player.isPlaying -> PlaybackStateMachine.reduce(state, PlaybackEvent.Play)
                player.playbackState == Player.STATE_READY -> PlaybackStateMachine.reduce(state, PlaybackEvent.Pause)
                player.playbackState == Player.STATE_IDLE -> PlaybackStateMachine.reduce(state, PlaybackEvent.Stop)
                else -> state
            }
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAY_WHEN_READY_CHANGED)) enqueueCheckpoint()
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !player.playWhenReady) recoveryGeneration++
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                recoveryAttempt = 0
                recoveryGeneration++
                val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                val currentMediaId = player.currentMediaItem?.mediaId
                scope.launch(Dispatchers.IO) {
                    repository.saveQueue(ids)
                    currentMediaId?.let(repository::markPlayed)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            enqueueCheckpoint()
            if (!PlaybackErrorClassifier.isRetryable(error)) {
                state = PlaybackStateMachine.reduce(state, PlaybackEvent.Error(error.localizedMessage ?: "Playback failed"))
                return
            }
            if (!networkMonitor.isOnline()) {
                state = PlaybackStateMachine.reduce(state, PlaybackEvent.Offline)
                return
            }
            scheduleRecovery()
        }
    }

    private fun scheduleRecovery() {
        recoveryAttempt += 1
        val delayMs = recoveryPolicy.delayMs(recoveryAttempt) ?: run {
            state = PlaybackStateMachine.reduce(state, PlaybackEvent.Error("Network recovery exhausted")); return
        }
        val generation = ++recoveryGeneration
        val shouldResume = player.playWhenReady
        state = PlaybackStateMachine.reduce(state, PlaybackEvent.Retry(recoveryAttempt))
        scope.launch {
            delay(delayMs)
            if (generation != recoveryGeneration || !networkMonitor.isOnline()) return@launch
            if (player.isCurrentMediaItemLive) player.seekToDefaultPosition()
            player.prepare()
            player.playWhenReady = shouldResume
        }
    }

    private fun resumeAfterNetworkReturns() {
        if (state !is PlaybackState.WaitingForNetwork) return
        recoveryAttempt = 0
        scheduleRecovery()
    }

    private fun startPeriodicCheckpoint() = scope.launch {
        while (isActive) {
            delay(CHECKPOINT_INTERVAL_MS)
            if (player.isPlaying) enqueueCheckpoint(force = false)
        }
    }

    private fun startCheckpointActor() = scope.launch(Dispatchers.IO) {
        val policy = CheckpointPolicy(CHECKPOINT_MIN_DELTA_MS)
        for (item in checkpoints) {
            if (policy.shouldWrite(item.trackId, item.positionMs, item.force)) {
                repository.saveLastPlayback(item.trackId, item.positionMs)
            }
        }
    }

    private fun enqueueCheckpoint(force: Boolean = true) {
        val id = player.currentMediaItem?.mediaId ?: return
        checkpoints.trySend(Checkpoint(id, player.currentPosition.coerceAtLeast(0L), force))
    }

    private data class Checkpoint(val trackId: String, val positionMs: Long, val force: Boolean)
    companion object { private const val CHECKPOINT_INTERVAL_MS = 5_000L; private const val CHECKPOINT_MIN_DELTA_MS = 5_000L }
}

internal class CheckpointPolicy(private val minimumDeltaMs: Long) {
    private var lastTrackId = ""
    private var lastPositionMs = -minimumDeltaMs
    fun shouldWrite(trackId: String, positionMs: Long, force: Boolean): Boolean {
        val write = force || trackId != lastTrackId || kotlin.math.abs(positionMs - lastPositionMs) >= minimumDeltaMs
        if (write) { lastTrackId = trackId; lastPositionMs = positionMs }
        return write
    }
}
