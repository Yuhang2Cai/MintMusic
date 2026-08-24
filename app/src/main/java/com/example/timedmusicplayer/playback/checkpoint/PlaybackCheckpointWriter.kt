package com.example.timedmusicplayer.playback.checkpoint

import androidx.media3.common.Player
import com.example.timedmusicplayer.data.repository.PlaybackHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Serializes checkpoints and playback queue/history writes away from the service lifecycle. */
class PlaybackCheckpointWriter(
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val scope: CoroutineScope
) {
    private val checkpoints = Channel<Checkpoint>(Channel.CONFLATED)

    fun start(player: Player) {
        scope.launch(Dispatchers.IO) {
            val policy = CheckpointPolicy(CHECKPOINT_MIN_DELTA_MS)
            for (item in checkpoints) {
                if (policy.shouldWrite(item.trackId, item.positionMs, item.force)) {
                    playbackHistoryRepository.saveLastPlayback(item.trackId, item.positionMs)
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(CHECKPOINT_INTERVAL_MS)
                if (player.isPlaying) enqueue(player, force = false)
            }
        }
    }

    fun onEvents(player: Player, events: Player.Events) {
        if (
            events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_PLAY_WHEN_READY_CHANGED
            )
        ) {
            enqueue(player)
        }
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            val currentMediaId = player.currentMediaItem?.mediaId
            scope.launch(Dispatchers.IO) {
                playbackHistoryRepository.saveQueue(ids)
                currentMediaId?.let { playbackHistoryRepository.markPlayed(it) }
            }
        }
    }

    fun enqueue(player: Player, force: Boolean = true) {
        val trackId = player.currentMediaItem?.mediaId ?: return
        checkpoints.trySend(
            Checkpoint(trackId, player.currentPosition.coerceAtLeast(0L), force)
        )
    }

    private data class Checkpoint(
        val trackId: String,
        val positionMs: Long,
        val force: Boolean
    )

    private companion object {
        const val CHECKPOINT_INTERVAL_MS = 5_000L
        const val CHECKPOINT_MIN_DELTA_MS = 5_000L
    }
}
