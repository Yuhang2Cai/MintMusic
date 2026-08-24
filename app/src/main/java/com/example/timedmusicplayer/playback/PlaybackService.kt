package com.example.timedmusicplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.timedmusicplayer.PlayerActivity
import com.example.timedmusicplayer.data.AppDataContainer
import com.example.timedmusicplayer.playback.checkpoint.PlaybackCheckpointWriter
import com.example.timedmusicplayer.playback.recovery.PlaybackRecoveryCoordinator
import com.example.timedmusicplayer.playback.service.PlaybackNotificationChannel
import com.example.timedmusicplayer.playback.service.PlaybackPlayerFactory
import com.example.timedmusicplayer.playback.service.PlaybackSessionCallback
import com.example.timedmusicplayer.playback.timer.SleepTimerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Media3 lifecycle owner; playback policies and persistence live in dedicated collaborators. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var checkpointWriter: PlaybackCheckpointWriter
    private lateinit var recoveryCoordinator: PlaybackRecoveryCoordinator
    private lateinit var sleepTimerManager: SleepTimerManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        PlaybackNotificationChannel.ensureCreated(this)
        player = PlaybackPlayerFactory.create(this)
        sleepTimerManager = SleepTimerManager(
            scope = scope,
            onExpired = player::pause,
            onChanged = ::publishSleepTimer
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(createSessionActivity())
            .setSessionExtras(sleepTimerManager.sessionExtras())
            .setCallback(PlaybackSessionCallback(sleepTimerManager))
            .build()

        val historyRepository = AppDataContainer.get(this).playbackHistoryRepository
        checkpointWriter = PlaybackCheckpointWriter(historyRepository, scope).also {
            it.start(player)
        }
        recoveryCoordinator = PlaybackRecoveryCoordinator(this, player, scope).also {
            it.start()
        }
        player.addListener(playerListener)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        checkpointWriter.enqueue(player)
        sleepTimerManager.cancel()
        recoveryCoordinator.stop()
        player.removeListener(playerListener)
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun createSessionActivity(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun publishSleepTimer() {
        if (::session.isInitialized) {
            session.setSessionExtras(sleepTimerManager.sessionExtras())
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            recoveryCoordinator.onEvents(events)
            checkpointWriter.onEvents(player, events)
        }

        override fun onPlayerError(error: PlaybackException) {
            checkpointWriter.enqueue(player)
            recoveryCoordinator.onPlayerError(error)
        }
    }
}
