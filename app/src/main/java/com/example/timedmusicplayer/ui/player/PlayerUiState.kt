package com.example.timedmusicplayer.ui.player

import com.example.timedmusicplayer.model.Track

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
    val seekMax: Int = 0,
    val seekProgress: Int = 0,
    val bufferedProgress: Int = 0,
    val currentTrack: Track? = null
)
