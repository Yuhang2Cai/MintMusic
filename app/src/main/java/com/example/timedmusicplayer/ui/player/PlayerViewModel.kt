package com.example.timedmusicplayer.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.playback.PlaybackController
import com.example.timedmusicplayer.playback.PlaybackMode
import com.example.timedmusicplayer.playback.PlaybackSnapshot
import com.example.timedmusicplayer.playback.PlaybackUiState
import com.example.timedmusicplayer.ui.common.PlaybackUiFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class PlayerViewModel(
    application: Application,
    private val playbackController: PlaybackController
) : AndroidViewModel(application) {

    private val app = application.applicationContext
    private val emptyState = PlayerUiState(
        title = app.getString(R.string.no_resume_item),
        statusText = app.getString(R.string.ready_to_play),
        playbackModeText = app.getString(
            R.string.playback_mode_with_value,
            PlaybackUiFormatter.modeLabel(app, PlaybackMode.ORDER)
        )
    )

    private val uiStateValue = MutableStateFlow(emptyState)
    private var isUserSeeking = false
    private var previewSeekPositionMs: Long = 0L

    val uiState: StateFlow<PlayerUiState> = uiStateValue.asStateFlow()

    init {
        viewModelScope.launch {
            playbackController.snapshot.collect { snapshot ->
                uiStateValue.value = buildUiState(snapshot)
            }
        }
    }

    fun onStart() {
        playbackController.connect()
    }

    fun onStop() {
        playbackController.disconnect()
    }

    fun onQueueReceived(queue: List<Track>, startIndex: Int) {
        if (queue.isEmpty()) {
            return
        }
        playbackController.playQueue(queue, startIndex, forcePlay = true)
    }

    fun onPlayPauseClicked() {
        playbackController.togglePlayPause()
    }

    fun onPreviousClicked() {
        playbackController.playPrevious()
    }

    fun onNextClicked() {
        playbackController.playNext()
    }

    fun onPlaybackModeClicked() {
        playbackController.cyclePlaybackMode()
    }

    fun onSeekStarted() {
        isUserSeeking = true
    }

    fun onSeekPreview(progress: Int) {
        isUserSeeking = true
        previewSeekPositionMs = progress.toLong().coerceAtLeast(0L)
        uiStateValue.value = uiStateValue.value.copy(
            currentTimeText = PlaybackUiFormatter.formatTime(previewSeekPositionMs)
        )
    }

    fun onSeekCompleted(progress: Int) {
        isUserSeeking = false
        previewSeekPositionMs = 0L
        playbackController.seekTo(progress.toLong())
    }

    private fun buildUiState(snapshot: PlaybackSnapshot?): PlayerUiState {
        if (snapshot == null || snapshot.queue.isEmpty()) {
            return emptyState
        }

        val current = snapshot.currentTrack
        val duration = snapshot.durationMs.coerceAtLeast(0L)
        val position = snapshot.positionMs.coerceAtLeast(0L)
        val buffered = snapshot.bufferedPositionMs.coerceAtLeast(position)
        val progressMax = PlaybackUiFormatter.resolveProgressMax(
            durationMs = duration,
            positionMs = position,
            isStream = current?.isStream == true
        )
        val safeMax = progressMax.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0)
        val displayPosition = if (isUserSeeking) previewSeekPositionMs else position
        val safeProgress = displayPosition.coerceAtMost(safeMax.toLong()).toInt()
        val safeBuffered = buffered.coerceIn(safeProgress.toLong(), safeMax.toLong()).toInt()
        val showBufferedInfo = current?.isStream == true
        val bufferedPercent = if (safeMax > 0) {
            ((safeBuffered * 100L) / safeMax).toInt().coerceIn(0, 100)
        } else {
            0
        }

        return PlayerUiState(
            title = current?.title ?: app.getString(R.string.no_resume_item),
            subtitle = when {
                current == null -> null
                current.isStream -> app.getString(R.string.source_cloud)
                else -> app.getString(R.string.source_local)
            },
            statusText = PlaybackUiFormatter.statusText(app, snapshot.state, snapshot.errorMessage),
            playbackModeText = app.getString(
                R.string.playback_mode_with_value,
                PlaybackUiFormatter.modeLabel(app, snapshot.playbackMode)
            ),
            showLoading = snapshot.state == PlaybackUiState.LOADING ||
                snapshot.state == PlaybackUiState.BUFFERING,
            currentTimeText = PlaybackUiFormatter.formatTime(displayPosition),
            totalTimeText = if (duration > 0L) {
                PlaybackUiFormatter.formatTime(duration)
            } else {
                "--:--"
            },
            bufferedInfoText = app.getString(R.string.buffered_percent_with_value, bufferedPercent),
            showBufferedInfo = showBufferedInfo,
            isPlaying = snapshot.isPlaying,
            canSkip = snapshot.queue.size > 1,
            seekMax = safeMax,
            seekProgress = safeProgress,
            bufferedProgress = safeBuffered,
            currentTrack = current
        )
    }
}
