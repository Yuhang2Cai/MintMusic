package com.example.timedmusicplayer.ui.cloud

import com.example.timedmusicplayer.domain.model.CloudSource

data class CloudSourceUiState(
    val entries: List<CloudSource> = emptyList(),
    val isEmpty: Boolean = true,
    val inputName: String = "ice1.somafm.com",
    val inputUrl: String = "http://ice1.somafm.com/groovesalad-128-mp3",
    val inputCoverUrl: String = "",
    val isSaving: Boolean = false
)
