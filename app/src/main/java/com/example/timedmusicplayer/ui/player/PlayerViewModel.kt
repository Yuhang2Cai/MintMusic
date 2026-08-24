package com.example.timedmusicplayer.ui.player

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.emotion.MoodAnalysisRepository
import com.example.timedmusicplayer.emotion.MoodTaskResult
import com.example.timedmusicplayer.lyrics.LyricsGenerationResult
import com.example.timedmusicplayer.lyrics.LyricsRepository
import com.example.timedmusicplayer.lyrics.LyricLine
import com.example.timedmusicplayer.domain.model.Track
import com.example.timedmusicplayer.playback.PlaybackController
import com.example.timedmusicplayer.playback.PlaybackController.PlaybackTickMode
import com.example.timedmusicplayer.playback.PlaybackMode
import com.example.timedmusicplayer.playback.PlaybackSnapshot
import com.example.timedmusicplayer.playback.PlaybackUiState
import com.example.timedmusicplayer.ui.common.PlaybackUiFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class PlayerViewModel(
    application: Application,
    private val playbackController: PlaybackController,
    private val moodRepository: MoodAnalysisRepository,
    private val lyricsRepository: LyricsRepository
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
    private val seekPreviewTextValue = MutableStateFlow<String?>(null)
    private val eventChannel = Channel<PlayerEvent>(Channel.BUFFERED)
    private var isUserSeeking = false
    private var previewSeekPositionMs: Long = 0L
    private var pendingSeekPositionMs: Long? = null
    private var pendingSeekTrackId: String? = null
    private var pendingSeekStartedAtMs: Long = 0L
    private var lyricTrackId: String? = null
    private var lyrics: List<LyricLine> = emptyList()
    private var isLyricsLoading = false
    private var isLyricsPageVisible = false

    val uiState: StateFlow<PlayerUiState> = uiStateValue.asStateFlow()
    val seekPreviewText: StateFlow<String?> = seekPreviewTextValue.asStateFlow()
    val events = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            playbackController.snapshot.collect { snapshot ->
                val state = buildUiState(snapshot)
                val track = state.currentTrack
                if (track?.id != lyricTrackId) loadLyrics(track)
                uiStateValue.value = withContentState(state)
            }
        }
        viewModelScope.launch {
            moodRepository.states.collect {
                uiStateValue.value = withContentState(uiStateValue.value)
            }
        }
    }

    fun onStart() {
        playbackController.connect(PlaybackTickMode.DETAIL)
    }

    fun onStop() {
        playbackController.disconnect(PlaybackTickMode.DETAIL)
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

    fun onSleepTimerClicked() {
        val options = TIMER_MINUTES.map { minutes ->
            SleepTimerOptionUi(app.getString(R.string.sleep_timer_minutes, minutes), minutes)
        }.toMutableList()
        if (uiStateValue.value.sleepTimerRemainingMs > 0L) {
            options += SleepTimerOptionUi(app.getString(R.string.sleep_timer_cancel), null)
        }
        viewModelScope.launch { eventChannel.send(PlayerEvent.ShowSleepTimerOptions(options)) }
    }

    fun onSleepTimerOptionSelected(option: SleepTimerOptionUi) {
        playbackController.setSleepTimer((option.minutes ?: 0).coerceAtLeast(0) * 60_000L)
    }

    fun onLyricsPageSelected(selected: Boolean) {
        val canShowLyrics = lyrics.isNotEmpty() && lyricTrackId == uiStateValue.value.currentTrack?.id
        val next = selected && canShowLyrics
        if (isLyricsPageVisible == next) return
        isLyricsPageVisible = next
        publishContentState()
    }

    fun onGenerateLyricsClicked() {
        val track = uiStateValue.value.currentTrack ?: return
        viewModelScope.launch {
            if (track.isStream) {
                eventChannel.send(PlayerEvent.ShowMessage(app.getString(R.string.lyrics_local_only)))
            } else {
                eventChannel.send(PlayerEvent.ConfirmLyricsGeneration)
            }
        }
    }

    fun onGenerateLyricsConfirmed() {
        val track = uiStateValue.value.currentTrack ?: return
        if (track.isStream || isLyricsLoading) return
        viewModelScope.launch {
            isLyricsLoading = true
            lyricTrackId = track.id
            publishContentState()
            eventChannel.send(PlayerEvent.ShowMessage(app.getString(R.string.lyrics_started)))
            val result = lyricsRepository.generate(track)
            if (lyricTrackId == track.id) isLyricsLoading = false
            when (result) {
                is LyricsGenerationResult.Success -> {
                    if (lyricTrackId == track.id) {
                        lyrics = result.lyrics
                        publishContentState()
                    }
                    eventChannel.send(PlayerEvent.ShowMessage(app.getString(R.string.lyrics_completed), long = true))
                }
                is LyricsGenerationResult.Failure -> {
                    publishContentState()
                    eventChannel.send(
                        PlayerEvent.ShowMessage(
                            app.getString(R.string.lyrics_failed, result.message),
                            long = true
                        )
                    )
                }
            }
        }
    }

    fun onAnalyzeMoodClicked() {
        val track = uiStateValue.value.currentTrack ?: return
        viewModelScope.launch {
            if (track.isStream) {
                eventChannel.send(PlayerEvent.ShowMessage(app.getString(R.string.music_mood_local_only)))
            } else {
                eventChannel.send(PlayerEvent.ConfirmMoodAnalysis)
            }
        }
    }

    fun onAnalyzeMoodConfirmed() {
        val track = uiStateValue.value.currentTrack ?: return
        if (track.isStream || uiStateValue.value.isMoodAnalyzing) return
        viewModelScope.launch {
            eventChannel.send(PlayerEvent.ShowMessage(app.getString(R.string.music_mood_started)))
            when (val result = moodRepository.analyze(track)) {
                is MoodTaskResult.Success -> eventChannel.send(
                    PlayerEvent.ShowMoodResult(
                        MoodResultUi(
                            trackTitle = track.title,
                            labels = translateMoodLabels(result.moods).ifEmpty { listOf(app.getString(R.string.music_mood_no_label)) },
                            valenceText = formatMoodScore(result.valence),
                            arousalText = formatMoodScore(result.arousal)
                        )
                    )
                )
                is MoodTaskResult.Failure -> eventChannel.send(
                    PlayerEvent.ShowMessage(
                        app.getString(R.string.music_mood_failed, result.message),
                        long = true
                    )
                )
            }
        }
    }

    fun onSeekStarted() {
        if (!uiStateValue.value.canSeek) return
        isUserSeeking = true
        pendingSeekPositionMs = null
        previewSeekPositionMs = uiStateValue.value.seekProgress.toLong()
    }

    fun onSeekPreview(progress: Int) {
        if (!uiStateValue.value.canSeek) return
        isUserSeeking = true
        previewSeekPositionMs = progress.toLong().coerceAtLeast(0L)
        // Keep high-frequency drag feedback separate from the complete screen state.
        seekPreviewTextValue.value = PlaybackUiFormatter.formatTime(previewSeekPositionMs)
    }

    fun onSeekCompleted(progress: Int) {
        if (!uiStateValue.value.canSeek) return
        val targetPositionMs = progress.toLong().coerceAtLeast(0L)
        isUserSeeking = false
        seekPreviewTextValue.value = null
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

    private fun loadLyrics(track: Track?) {
        lyricTrackId = track?.id
        lyrics = emptyList()
        isLyricsLoading = false
        isLyricsPageVisible = false
        if (track == null) return
        viewModelScope.launch {
            val loaded = lyricsRepository.load(track.id)
            if (lyricTrackId == track.id) {
                lyrics = loaded
                publishContentState()
                if (loaded.isNotEmpty()) {
                    eventChannel.send(PlayerEvent.ShowMessage(app.getString(R.string.lyrics_swipe_hint)))
                }
            }
        }
    }

    private fun publishContentState() {
        uiStateValue.value = withContentState(uiStateValue.value)
    }

    private fun withContentState(state: PlayerUiState): PlayerUiState {
        val mood = state.currentTrack?.id?.let(moodRepository.states.value::get)
        val lyricsLoading = isLyricsLoading && lyricTrackId == state.currentTrack?.id
        val moodAnalyzing = mood?.isAnalyzing == true
        val isProcessing = lyricsLoading || moodAnalyzing
        val statusText = when {
            lyricsLoading -> app.getString(R.string.lyrics_matching)
            moodAnalyzing -> app.getString(R.string.music_mood_analyzing)
            else -> mood?.label.orEmpty()
        }
        return state.copy(
            lyricTrackId = lyricTrackId,
            lyrics = lyrics,
            isLyricsPageVisible = isLyricsPageVisible && lyrics.isNotEmpty(),
            isLyricsLoading = lyricsLoading,
            moodLabel = mood?.label,
            isMoodAnalyzing = moodAnalyzing,
            showContentStatus = isProcessing || !mood?.label.isNullOrBlank(),
            isContentProcessing = isProcessing,
            contentStatusText = statusText
        )
    }

    private fun formatMoodScore(value: Double): String = if (value.isNaN()) {
        "—"
    } else {
        String.format(Locale.getDefault(), "%.2f", value)
    }

    private fun translateMoodLabels(raw: String): List<String> {
        val translated = mapOf(
            "sad" to "忧郁", "melancholic" to "忧郁", "romantic" to "浪漫", "love" to "浪漫",
            "powerful" to "激昂", "motivational" to "励志", "hopeful" to "希望", "ballad" to "抒情",
            "epic" to "史诗感", "dramatic" to "戏剧感", "drama" to "戏剧感", "adventure" to "冒险",
            "dark" to "暗黑", "emotional" to "感性"
        )
        return raw.split(',', '·', '|')
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(4)
            .map { translated[it.lowercase()] ?: it }
    }

    private companion object {
        val TIMER_MINUTES = intArrayOf(15, 30, 45, 60)
        const val SEEK_ACK_TOLERANCE_MS = 1_500L
        const val SEEK_ACK_TIMEOUT_MS = 2_500L
    }
}

sealed class PlayerEvent {
    data class ShowMessage(val message: String, val long: Boolean = false) : PlayerEvent()
    data class ShowMoodResult(val result: MoodResultUi) : PlayerEvent()
    data class ShowSleepTimerOptions(val options: List<SleepTimerOptionUi>) : PlayerEvent()
    object ConfirmLyricsGeneration : PlayerEvent()
    object ConfirmMoodAnalysis : PlayerEvent()
}
