package com.example.timedmusicplayer.ui.main

import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.model.TrackFilter

data class MainUiState(
    val activeFilter: TrackFilter = TrackFilter.ALL,
    val libraryCountText: String = "",
    val showEmpty: Boolean = false,
    val miniPlayer: MiniPlayerUiState? = null
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
