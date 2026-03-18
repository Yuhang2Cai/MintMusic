package com.example.timedmusicplayer.ui.cloud

import com.example.timedmusicplayer.model.CloudSource

data class CloudSourceUiState(
    val entries: List<CloudSource> = emptyList(),
    val isEmpty: Boolean = true
)
