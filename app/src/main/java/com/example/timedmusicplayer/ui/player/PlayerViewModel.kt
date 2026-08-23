package com.example.timedmusicplayer.ui.player

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.timedmusicplayer.R
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
import kotlin.math.abs

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
        ),
        sleepTimerText = app.getString(R.string.sleep_timer)
    )

    private val uiStateValue = MutableStateFlow(emptyState)
    private var isUserSeeking = false
    private var previewSeekPositionMs: Long = 0L
    private var pendingSeekPositionMs: Long? = null
    private var pendingSeekTrackId: String? = null
    private var pendingSeekStartedAtMs: Long = 0L

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

    fun onSleepTimerSelected(minutes: Int) {
        playbackController.setSleepTimer(minutes.coerceAtLeast(0) * 60_000L)
    }

    fun onSleepTimerCancelled() {
        playbackController.setSleepTimer(0L)
    }

    fun onSeekStarted() {
        if (!uiStateValue.value.canSeek) return
        isUserSeeking = true
        pendingSeekPositionMs = null
        previewSeekPositionMs = uiStateValue.value.seekProgress.toLong()
    }

    fun onSeekPreview(progress: Int): String {
        if (!uiStateValue.value.canSeek) return uiStateValue.value.currentTimeText
        isUserSeeking = true
        previewSeekPositionMs = progress.toLong().coerceAtLeast(0L)
        // The Activity updates the label directly while the finger is moving. Avoid emitting
        // a complete screen state for every pixel, which previously caused visible jank.
        return PlaybackUiFormatter.formatTime(previewSeekPositionMs)
    }

    fun onSeekCompleted(progress: Int) {
        if (!uiStateValue.value.canSeek) return
        val targetPositionMs = progress.toLong().coerceAtLeast(0L)
        isUserSeeking = false
        previewSeekPositionMs = targetPositionMs
        pendingSeekPositionMs = targetPositionMs
        pendingSeekTrackId = uiStateValue.value.currentTrack?.id
        pendingSeekStartedAtMs = SystemClock.elapsedRealtime()
        uiStateValue.value = uiStateValue.value.let { current ->
            val safeProgress = targetPositionMs
                .coerceAtMost(current.seekMax.toLong())
                .toInt()
            current.copy(
                currentTimeText = PlaybackUiFormatter.formatTime(targetPositionMs),
                seekProgress = safeProgress,
                bufferedProgress = current.bufferedProgress.coerceAtLeast(safeProgress)
            )
        }
        playbackController.seekTo(targetPositionMs)
    }

    private fun buildUiState(snapshot: PlaybackSnapshot?): PlayerUiState {
        if (snapshot == null || snapshot.queue.isEmpty()) {
            return emptyState
        }

        val current = snapshot.currentTrack
        val duration = snapshot.durationMs.coerceAtLeast(0L)
        val canSeek = snapshot.isSeekable && duration > 0L
        val position = snapshot.positionMs.coerceAtLeast(0L)
        val buffered = snapshot.bufferedPositionMs.coerceAtLeast(position)
        val progressMax = PlaybackUiFormatter.resolveProgressMax(
            durationMs = duration,
            positionMs = position,
            isStream = current?.isStream == true
        )
        val safeMax = progressMax.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0)
        val pendingPosition = pendingSeekPositionMs
        val pendingIsForCurrentTrack = pendingPosition != null && pendingSeekTrackId == current?.id
        val pendingWasAcknowledged = pendingPosition != null &&
            abs(position - pendingPosition) <= SEEK_ACK_TOLERANCE_MS
        val pendingTimedOut = pendingPosition != null &&
            SystemClock.elapsedRealtime() - pendingSeekStartedAtMs >= SEEK_ACK_TIMEOUT_MS
        if (!pendingIsForCurrentTrack || pendingWasAcknowledged || pendingTimedOut) {
            pendingSeekPositionMs = null
            pendingSeekTrackId = null
        }
        val displayPosition = when {
            isUserSeeking -> previewSeekPositionMs
            pendingSeekPositionMs != null -> pendingSeekPositionMs!!
            else -> position
        }
        val safeProgress = displayPosition.coerceAtMost(safeMax.toLong()).toInt()
        val safeBuffered = buffered.coerceIn(safeProgress.toLong(), safeMax.toLong()).toInt()
        val showBufferedInfo = current?.isStream == true
        val bufferedPercent = if (safeMax > 0) {
            ((safeBuffered * 100L) / safeMax).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val sleepTimerRemainingMs = snapshot.sleepTimerRemainingMs
        val roundedTimerRemainingMs = if (sleepTimerRemainingMs > 0L) {
            ((sleepTimerRemainingMs + 999L) / 1_000L) * 1_000L
        } else {
            0L
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
            totalTimeText = if (!canSeek) {
                app.getString(R.string.seek_unavailable)
            } else if (duration > 0L) {
                PlaybackUiFormatter.formatTime(duration)
            } else {
                "--:--"
            },
            bufferedInfoText = app.getString(R.string.buffered_percent_with_value, bufferedPercent),
            showBufferedInfo = showBufferedInfo,
            isPlaying = snapshot.isPlaying,
            canSkip = snapshot.queue.size > 1,
            canSeek = canSeek,
            seekMax = safeMax,
            seekProgress = safeProgress,
            bufferedProgress = safeBuffered,
            currentTrack = current,
            audioSessionId = snapshot.audioSessionId,
            sleepTimerText = if (roundedTimerRemainingMs > 0L) {
                app.getString(
                    R.string.sleep_timer_remaining,
                    PlaybackUiFormatter.formatTime(roundedTimerRemainingMs)
                )
            } else {
                app.getString(R.string.sleep_timer)
            },
            sleepTimerRemainingMs = sleepTimerRemainingMs,
            positionMs = displayPosition
        )
    }

    private companion object {
        const val SEEK_ACK_TOLERANCE_MS = 1_500L
        const val SEEK_ACK_TIMEOUT_MS = 2_500L
    }
}
