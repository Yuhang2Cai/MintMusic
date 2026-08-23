package com.example.timedmusicplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.timedmusicplayer.model.SourceType
import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.data.MusicRepository
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI facade over a Media3 MediaController; it never binds to service implementation details. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val repository = MusicRepository.getInstance(appContext)
    private val snapshotState = MutableStateFlow<PlaybackSnapshot?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var clients = 0
    private var pendingQueue: QueueRequest? = null
    private var ticker: Job? = null

    val snapshot: StateFlow<PlaybackSnapshot?> = snapshotState.asStateFlow()

    @Synchronized fun connect() {
        clients++
        ensureController()
    }

    private fun ensureController() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(appContext, token)
            .setListener(controllerListener)
            .buildAsync().also { future ->
            future.addListener({
                val result = runCatching { future.get() }
                controllerFuture = null
                result.onSuccess {
                    controller = it
                    it.addListener(listener)
                    applyPlaybackMode(it, PlaybackMode.fromRaw(repository.getPlaybackMode(PlaybackMode.ORDER.name)))
                    pendingQueue?.let { request ->
                        playQueue(request.tracks, request.startIndex, request.forcePlay, request.startPositionMs)
                    }
                    pendingQueue = null
                    emitSnapshot()
                    updateTicker()
                }
            }, ContextCompat.getMainExecutor(appContext))
        }
    }

    @Synchronized fun disconnect() {
        clients = (clients - 1).coerceAtLeast(0)
        if (clients > 0) return
        ticker?.cancel(); ticker = null
        controller?.removeListener(listener)
        controller?.release(); controller = null
        controllerFuture?.cancel(false); controllerFuture = null
    }

    fun playQueue(
        tracks: List<Track>,
        startIndex: Int,
        forcePlay: Boolean,
        startPositionMs: Long = 0L
    ) {
        if (tracks.isEmpty()) return
        val active = controller
        if (active == null) {
            pendingQueue = QueueRequest(tracks.toList(), startIndex, forcePlay, startPositionMs)
            ensureController()
            return
        }
        active.setMediaItems(
            tracks.map { it.toMediaItem() },
            startIndex.coerceIn(tracks.indices),
            startPositionMs.coerceAtLeast(0L)
        )
        active.prepare()
        if (forcePlay) active.play()
        emitSnapshot()
    }

    fun togglePlayPause() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun playNext() { controller?.seekToNextMediaItem() }
    fun playPrevious() { controller?.let { if (it.currentPosition > 3_000L) it.seekTo(0L) else it.seekToPreviousMediaItem() } }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0L)) }
    fun cyclePlaybackMode() {
        val active = controller ?: return
        val next = playbackMode(active).next()
        applyPlaybackMode(active, next)
        scope.launch(Dispatchers.IO) { repository.savePlaybackMode(next.name) }
        emitSnapshot()
    }

    fun setSleepTimer(durationMs: Long) {
        val active = controller ?: return
        val args = Bundle().apply {
            putLong(SleepTimerCommands.ARG_DURATION_MS, durationMs.coerceAtLeast(0L))
        }
        active.sendCustomCommand(SleepTimerCommands.setTimer, args).addListener({
            emitSnapshot()
            updateTicker()
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun applyPlaybackMode(active: Player, mode: PlaybackMode) {
        when (mode) {
            PlaybackMode.ORDER -> { active.shuffleModeEnabled = false; active.repeatMode = Player.REPEAT_MODE_OFF }
            PlaybackMode.REPEAT_ONE -> { active.shuffleModeEnabled = false; active.repeatMode = Player.REPEAT_MODE_ONE }
            PlaybackMode.REPEAT_ALL -> { active.shuffleModeEnabled = false; active.repeatMode = Player.REPEAT_MODE_ALL }
            PlaybackMode.SHUFFLE -> { active.repeatMode = Player.REPEAT_MODE_ALL; active.shuffleModeEnabled = true }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { emitSnapshot(); updateTicker() }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            emitSnapshot()
            updateTicker()
        }
    }

    private fun updateTicker() {
        val active = controller
        val needsTicks = active?.isPlaying == true || sleepTimerRemainingMs(active) > 0L
        if (clients <= 0 || !needsTicks) { ticker?.cancel(); ticker = null; return }
        if (ticker?.isActive == true) return
        ticker = scope.launch { while (isActive) { emitSnapshot(); delay(500L) } }
    }

    private fun emitSnapshot() {
        val active = controller ?: return
        val queue = (0 until active.mediaItemCount).map { active.getMediaItemAt(it).toTrack() }
        val state = when {
            active.playerError != null -> PlaybackUiState.ERROR
            active.playbackState == Player.STATE_BUFFERING -> PlaybackUiState.BUFFERING
            active.playbackState == Player.STATE_IDLE && queue.isNotEmpty() -> PlaybackUiState.LOADING
            active.isPlaying -> PlaybackUiState.PLAYING
            queue.isEmpty() -> PlaybackUiState.IDLE
            else -> PlaybackUiState.PAUSED
        }
        snapshotState.value = PlaybackSnapshot(
            queue = queue,
            currentIndex = active.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = active.currentPosition.coerceAtLeast(0L),
            bufferedPositionMs = active.bufferedPosition.coerceAtLeast(0L),
            durationMs = active.duration.coerceAtLeast(0L),
            isSeekable = active.isCurrentMediaItemSeekable,
            isPlaying = active.isPlaying,
            state = state,
            playbackMode = playbackMode(active),
            errorMessage = active.playerError?.localizedMessage,
            audioSessionId = active.audioSessionId,
            sleepTimerRemainingMs = sleepTimerRemainingMs(active)
        )
    }

    private fun sleepTimerRemainingMs(active: MediaController?): Long {
        if (active == null) return 0L
        val deadline = active.sessionExtras.getLong(
            SleepTimerCommands.EXTRA_END_ELAPSED_REALTIME_MS,
            0L
        )
        return (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    private fun playbackMode(player: Player): PlaybackMode = when {
        player.shuffleModeEnabled -> PlaybackMode.SHUFFLE
        player.repeatMode == Player.REPEAT_MODE_ONE -> PlaybackMode.REPEAT_ONE
        player.repeatMode == Player.REPEAT_MODE_ALL -> PlaybackMode.REPEAT_ALL
        else -> PlaybackMode.ORDER
    }

    private fun Track.toMediaItem(): MediaItem {
        val extras = Bundle().apply {
            putString("source", sourceType.name); putString("album", album); putString("cover", coverUrl)
            putLong("size", sizeBytes); putLong("modified", modifiedAtMs); putLong("duration", durationMs); putString("folder", folderUri)
            putString("mime", mimeType)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setDurationMs(durationMs.takeIf { it > 0L })
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
        coverUrl?.let { metadata.setArtworkUri(Uri.parse(it)) }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .apply { mimeType?.takeIf(String::isNotBlank)?.let(::setMimeType) }
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun MediaItem.toTrack(): Track {
        val extras = mediaMetadata.extras
        val source = extras?.getString("source")?.let { runCatching { SourceType.valueOf(it) }.getOrNull() }
            ?: if (mediaId.startsWith("cloud:")) SourceType.CLOUD else SourceType.LOCAL
        return Track(mediaId, mediaMetadata.title?.toString().orEmpty(), mediaMetadata.artist?.toString().orEmpty(), extras?.getLong("duration") ?: 0L, source,
            localConfiguration?.uri?.toString().orEmpty(), mediaMetadata.albumTitle?.toString().orEmpty(), extras?.getString("cover"),
            folderUri = extras?.getString("folder"), sizeBytes = extras?.getLong("size") ?: 0L, modifiedAtMs = extras?.getLong("modified") ?: 0L,
            mimeType = extras?.getString("mime"))
    }

    private data class QueueRequest(
        val tracks: List<Track>,
        val startIndex: Int,
        val forcePlay: Boolean,
        val startPositionMs: Long
    )

    companion object {
        @Volatile private var instance: PlaybackController? = null
        fun getInstance(context: Context): PlaybackController = instance ?: synchronized(this) { instance ?: PlaybackController(context).also { instance = it } }
    }
}
