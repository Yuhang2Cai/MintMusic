package com.example.timedmusicplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
class PlaybackController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val repository = MusicRepository.getInstance(appContext)
    private val snapshotState = MutableStateFlow<PlaybackSnapshot?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var clients = 0
    private var pendingQueue: Triple<List<Track>, Int, Boolean>? = null
    private var ticker: Job? = null

    val snapshot: StateFlow<PlaybackSnapshot?> = snapshotState.asStateFlow()

    @Synchronized fun connect() {
        clients++
        ensureController()
    }

    private fun ensureController() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(appContext, token).buildAsync().also { future ->
            future.addListener({
                runCatching { future.get() }.onSuccess {
                    controller = it
                    controllerFuture = null
                    it.addListener(listener)
                    applyPlaybackMode(it, PlaybackMode.fromRaw(repository.getPlaybackMode(PlaybackMode.ORDER.name)))
                    pendingQueue?.let { request -> playQueue(request.first, request.second, request.third) }
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

    fun playQueue(tracks: List<Track>, startIndex: Int, forcePlay: Boolean) {
        val active = controller
        if (active == null) { pendingQueue = Triple(tracks.toList(), startIndex, forcePlay); ensureController(); return }
        active.setMediaItems(tracks.map { it.toMediaItem() }, startIndex.coerceIn(tracks.indices), 0L)
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

    private fun updateTicker() {
        if (clients <= 0 || controller?.isPlaying != true) { ticker?.cancel(); ticker = null; return }
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
        snapshotState.value = PlaybackSnapshot(queue, active.currentMediaItemIndex.coerceAtLeast(0), active.currentPosition.coerceAtLeast(0L),
            active.bufferedPosition.coerceAtLeast(0L), active.duration.coerceAtLeast(0L), active.isPlaying, state, playbackMode(active),
            active.playerError?.localizedMessage)
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
        }
        val metadata = MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).setExtras(extras)
        coverUrl?.let { metadata.setArtworkUri(Uri.parse(it)) }
        return MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(metadata.build()).build()
    }

    private fun MediaItem.toTrack(): Track {
        val extras = mediaMetadata.extras
        val source = extras?.getString("source")?.let { runCatching { SourceType.valueOf(it) }.getOrNull() }
            ?: if (mediaId.startsWith("cloud:")) SourceType.CLOUD else SourceType.LOCAL
        return Track(mediaId, mediaMetadata.title?.toString().orEmpty(), mediaMetadata.artist?.toString().orEmpty(), extras?.getLong("duration") ?: 0L, source,
            localConfiguration?.uri?.toString().orEmpty(), mediaMetadata.albumTitle?.toString().orEmpty(), extras?.getString("cover"),
            folderUri = extras?.getString("folder"), sizeBytes = extras?.getLong("size") ?: 0L, modifiedAtMs = extras?.getLong("modified") ?: 0L)
    }

    companion object {
        @Volatile private var instance: PlaybackController? = null
        fun getInstance(context: Context): PlaybackController = instance ?: synchronized(this) { instance ?: PlaybackController(context).also { instance = it } }
    }
}
