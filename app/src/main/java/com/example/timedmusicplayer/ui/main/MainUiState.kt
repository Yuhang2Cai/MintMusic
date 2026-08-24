package com.example.timedmusicplayer.ui.main

import com.example.timedmusicplayer.domain.model.Track
import com.example.timedmusicplayer.domain.model.TrackFilter
import com.example.timedmusicplayer.emotion.MoodAnalysisState
import com.example.timedmusicplayer.ui.theme.ThemeColorOption

data class MainUiState(
    val activeFilter: TrackFilter = TrackFilter.ALL,
    val libraryCountText: String = "",
    val showEmpty: Boolean = false,
    val isScanningLocalMusic: Boolean = false,
    val miniPlayer: MiniPlayerUiState? = null,
    val selectedTrackIds: Set<String> = emptySet(),
    val moodStates: Map<String, MoodAnalysisState> = emptyMap(),
    val selectedTheme: ThemeColorOption = ThemeColorOption.MINT
)

data class MiniPlayerUiState(
    val track: Track?,
    val title: String,
    val status: String,
    val isPlaying: Boolean,
    val canSkip: Boolean,
    val progressMax: Int,
    val progress: Int,
    val bufferedProgress: Int,
    val currentTimeText: String,
    val totalTimeText: String
)
