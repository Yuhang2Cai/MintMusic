package com.example.timedmusicplayer.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.example.timedmusicplayer.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlaybackController private constructor(context: Context) : PlaybackService.PlaybackListener {

    private val appContext = context.applicationContext
    private val snapshotState = MutableStateFlow<PlaybackSnapshot?>(null)

    private var playbackService: PlaybackService? = null
    private var activeClients = 0
    private var isBinding = false
    private var pendingQueueRequest: PendingQueueRequest? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PlaybackService.LocalBinder ?: return
            playbackService = binder.getService()
            isBinding = false
            playbackService?.registerListener(this@PlaybackController)
            consumePendingQueue()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService?.unregisterListener(this@PlaybackController)
            playbackService = null
            isBinding = false
            snapshotState.value = null
        }
    }

    val snapshot: StateFlow<PlaybackSnapshot?> = snapshotState.asStateFlow()

    @Synchronized
    fun connect() {
        activeClients += 1
        if (playbackService != null || isBinding) {
            return
        }

        PlaybackService.startService(appContext)
        val intent = Intent(appContext, PlaybackService::class.java)
        isBinding = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    @Synchronized
    fun disconnect() {
        if (activeClients > 0) {
            activeClients -= 1
        }

        if (activeClients > 0 || (!isBinding && playbackService == null)) {
            return
        }

        playbackService?.unregisterListener(this)
        playbackService = null
        isBinding = false
        runCatching { appContext.unbindService(serviceConnection) }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int, forcePlay: Boolean) {
        val service = playbackService
        if (service != null) {
            service.playQueue(tracks, startIndex, forcePlay)
            return
        }

        pendingQueueRequest = PendingQueueRequest(
            tracks = tracks.toList(),
            startIndex = startIndex,
            forcePlay = forcePlay
        )
        PlaybackService.startService(appContext)
    }

    fun togglePlayPause() {
        playbackService?.togglePlayPause()
    }

    fun playNext() {
        playbackService?.playNext()
    }

    fun playPrevious() {
        playbackService?.playPrevious()
    }

    fun seekTo(positionMs: Long) {
        playbackService?.seekTo(positionMs)
    }

    fun cyclePlaybackMode() {
        playbackService?.cyclePlaybackMode()
    }

    override fun onSnapshotChanged(snapshot: PlaybackSnapshot) {
        snapshotState.value = snapshot
    }

    private fun consumePendingQueue() {
        val request = pendingQueueRequest ?: return
        pendingQueueRequest = null
        playbackService?.playQueue(
            tracks = request.tracks,
            startIndex = request.startIndex,
            forcePlay = request.forcePlay
        )
    }

    private data class PendingQueueRequest(
        val tracks: List<Track>,
        val startIndex: Int,
        val forcePlay: Boolean
    )

    companion object {
        @Volatile
        private var instance: PlaybackController? = null

        fun getInstance(context: Context): PlaybackController {
            return instance ?: synchronized(this) {
                instance ?: PlaybackController(context).also { instance = it }
            }
        }
    }
}
