package com.example.timedmusicplayer.playback.recovery

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.example.timedmusicplayer.network.NetworkMonitor
import com.example.timedmusicplayer.network.PlaybackErrorClassifier
import com.example.timedmusicplayer.network.RecoveryPolicy
import com.example.timedmusicplayer.playback.PlaybackEvent
import com.example.timedmusicplayer.playback.PlaybackState
import com.example.timedmusicplayer.playback.PlaybackStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns network recovery generations, retry attempts, and playback state transitions. */
class PlaybackRecoveryCoordinator(
    context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
    private val recoveryPolicy: RecoveryPolicy = RecoveryPolicy()
) {
    private val networkMonitor = NetworkMonitor(context) { online ->
        if (online) resumeAfterNetworkReturns()
    }
    private var recoveryGeneration = 0L
    private var recoveryAttempt = 0
    private var state: PlaybackState = PlaybackState.Idle

    fun start() = networkMonitor.start()

    fun stop() = networkMonitor.stop()

    fun onEvents(events: Player.Events) {
        state = when {
            player.playerError != null -> state
            player.playbackState == Player.STATE_BUFFERING -> reduce(PlaybackEvent.Buffer)
            player.isPlaying -> reduce(PlaybackEvent.Play)
            player.playbackState == Player.STATE_READY -> reduce(PlaybackEvent.Pause)
            player.playbackState == Player.STATE_IDLE -> reduce(PlaybackEvent.Stop)
            else -> state
        }
        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !player.playWhenReady) {
            recoveryGeneration++
        }
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            recoveryAttempt = 0
            recoveryGeneration++
        }
    }

    fun onPlayerError(error: PlaybackException) {
        if (!PlaybackErrorClassifier.isRetryable(error)) {
            state = reduce(
                PlaybackEvent.Error(error.localizedMessage ?: "Playback failed")
            )
            return
        }
        if (!networkMonitor.isOnline()) {
            state = reduce(PlaybackEvent.Offline)
            return
        }
        scheduleRecovery()
    }

    private fun scheduleRecovery() {
        recoveryAttempt += 1
        val delayMs = recoveryPolicy.delayMs(recoveryAttempt) ?: run {
            state = reduce(PlaybackEvent.Error("Network recovery exhausted"))
            return
        }
        val generation = ++recoveryGeneration
        val shouldResume = player.playWhenReady
        state = reduce(PlaybackEvent.Retry(recoveryAttempt))
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

    private fun reduce(event: PlaybackEvent): PlaybackState =
        PlaybackStateMachine.reduce(state, event)
}
