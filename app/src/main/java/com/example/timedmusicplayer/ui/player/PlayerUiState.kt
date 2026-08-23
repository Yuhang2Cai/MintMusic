package com.example.timedmusicplayer.ui.player

import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.lyrics.LyricLine

data class PlayerUiState(
    val title: String = "",
    val subtitle: String? = null,
    val statusText: String = "",
    val playbackModeText: String = "",
    val showLoading: Boolean = false,
    val currentTimeText: String = "00:00",
    val totalTimeText: String = "00:00",
    val bufferedInfoText: String = "",
    val showBufferedInfo: Boolean = false,
    val isPlaying: Boolean = false,
    val canSkip: Boolean = false,
    val canSeek: Boolean = false,
    val seekMax: Int = 0,
    val seekProgress: Int = 0,
    val bufferedProgress: Int = 0,
    val currentTrack: Track? = null,
    val audioSessionId: Int = -1,
    val sleepTimerText: String = "",
    val sleepTimerRemainingMs: Long = 0L,
    val positionMs: Long = 0L,
    val lyricTrackId: String? = null,
    val lyrics: List<LyricLine> = emptyList(),
    val isLyricsLoading: Boolean = false,
    val moodLabel: String? = null,
    val isMoodAnalyzing: Boolean = false
)

data class MoodResultUi(
    val trackTitle: String,
    val labels: List<String>,
    val valence: Double,
    val arousal: Double
)
