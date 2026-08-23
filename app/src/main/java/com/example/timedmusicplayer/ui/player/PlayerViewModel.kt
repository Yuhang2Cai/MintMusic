package com.example.timedmusicplayer.ui.player

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.emotion.AnalyzeMoodWorker
import com.example.timedmusicplayer.emotion.MoodAnalysisStore
import com.example.timedmusicplayer.lyrics.GenerateLyricsWorker
import com.example.timedmusicplayer.lyrics.LyricFiles
import com.example.timedmusicplayer.lyrics.LyricLine
import com.example.timedmusicplayer.model.Track
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class PlayerViewModel(
    application: Application,
    private val playbackController: PlaybackController,
    private val moodAnalysisStore: MoodAnalysisStore,
    private val workManager: WorkManager
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
    private val eventChannel = Channel<PlayerEvent>(Channel.BUFFERED)
    private var isUserSeeking = false
    private var previewSeekPositionMs: Long = 0L
    private var pendingSeekPositionMs: Long? = null
    private var pendingSeekTrackId: String? = null
    private var pendingSeekStartedAtMs: Long = 0L
    private var lyricTrackId: String? = null
    private var lyrics: List<LyricLine> = emptyList()
    private var isLyricsLoading = false

    val uiState: StateFlow<PlayerUiState> = uiStateValue.asStateFlow()
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
            moodAnalysisStore.states.collect {
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

    fun onSleepTimerSelected(minutes: Int) {
        playbackController.setSleepTimer(minutes.coerceAtLeast(0) * 60_000L)
    }

    fun onSleepTimerCancelled() {
        playbackController.setSleepTimer(0L)
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
            val request = OneTimeWorkRequestBuilder<GenerateLyricsWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(GenerateLyricsWorker.KEY_TRACK_ID, track.id)
                        .putString(GenerateLyricsWorker.KEY_TITLE, track.title)
                        .putString(GenerateLyricsWorker.KEY_ARTIST, track.artist)
                        .putString(GenerateLyricsWorker.KEY_ALBUM, track.album)
                        .putLong(GenerateLyricsWorker.KEY_DURATION_SECONDS, track.durationMs / 1_000L)
                        .build()
                )
                .addTag("lyrics:${track.id}")
                .build()
            workManager.enqueueUniqueWork("lyrics:${track.id}", ExistingWorkPolicy.REPLACE, request)
            eventChannel.send(PlayerEvent.ShowMessage(app.getString(R.string.lyrics_started)))
            val info = workManager.getWorkInfoByIdFlow(request.id)
                .filterNotNull()
                .filter { it.state.isFinished }
                .first()
            if (lyricTrackId == track.id) isLyricsLoading = false
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    if (lyricTrackId == track.id) {
                        lyrics = withContext(Dispatchers.IO) { LyricFiles.read(app, track.id) }
                        publishContentState()
                    }
                    eventChannel.send(PlayerEvent.ShowMessage(app.getString(R.string.lyrics_completed), long = true))
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    publishContentState()
                    eventChannel.send(
                        PlayerEvent.ShowMessage(
                            app.getString(
                                R.string.lyrics_failed,
                                info.outputData.getString(GenerateLyricsWorker.KEY_ERROR).orEmpty()
                            ),
                            long = true
                        )
                    )
                }
                else -> Unit
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
            moodAnalysisStore.markAnalyzing(track.id)
            val request = OneTimeWorkRequestBuilder<AnalyzeMoodWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(AnalyzeMoodWorker.KEY_URI, track.uri)
                        .putString(AnalyzeMoodWorker.KEY_TRACK_ID, track.id)
                        .putString(AnalyzeMoodWorker.KEY_TITLE, track.title)
                        .putString(AnalyzeMoodWorker.KEY_MIME_TYPE, track.mimeType)
                        .build()
                )
                .addTag("music-emotion:${track.id}")
                .build()
            workManager.enqueueUniqueWork("music-emotion:${track.id}", ExistingWorkPolicy.REPLACE, request)
            eventChannel.send(PlayerEvent.ShowMessage(app.getString(R.string.music_mood_started)))
            val info = workManager.getWorkInfoByIdFlow(request.id)
                .filterNotNull()
                .filter { it.state.isFinished }
                .first()
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> eventChannel.send(
                    PlayerEvent.ShowMoodResult(
                        MoodResultUi(
                            trackTitle = track.title,
                            labels = translateMoodLabels(info.outputData.getString(AnalyzeMoodWorker.KEY_MOODS).orEmpty()),
                            valence = info.outputData.getDouble(AnalyzeMoodWorker.KEY_VALENCE, Double.NaN),
                            arousal = info.outputData.getDouble(AnalyzeMoodWorker.KEY_AROUSAL, Double.NaN)
                        )
                    )
                )
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> eventChannel.send(
                    PlayerEvent.ShowMessage(
                        app.getString(
                            R.string.music_mood_failed,
                            info.outputData.getString(AnalyzeMoodWorker.KEY_ERROR).orEmpty()
                        ),
                        long = true
                    )
                )
                else -> Unit
            }
        }
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

    private fun loadLyrics(track: Track?) {
        lyricTrackId = track?.id
        lyrics = emptyList()
        isLyricsLoading = false
        if (track == null) return
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { LyricFiles.read(app, track.id) }
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
        val mood = state.currentTrack?.id?.let(moodAnalysisStore.states.value::get)
        return state.copy(
            lyricTrackId = lyricTrackId,
            lyrics = lyrics,
            isLyricsLoading = isLyricsLoading && lyricTrackId == state.currentTrack?.id,
            moodLabel = mood?.label,
            isMoodAnalyzing = mood?.isAnalyzing == true
        )
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
        const val SEEK_ACK_TOLERANCE_MS = 1_500L
        const val SEEK_ACK_TIMEOUT_MS = 2_500L
    }
}

sealed class PlayerEvent {
    data class ShowMessage(val message: String, val long: Boolean = false) : PlayerEvent()
    data class ShowMoodResult(val result: MoodResultUi) : PlayerEvent()
    object ConfirmLyricsGeneration : PlayerEvent()
    object ConfirmMoodAnalysis : PlayerEvent()
}
