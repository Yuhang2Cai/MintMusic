package com.example.timedmusicplayer.data.model

data class DeleteTracksResult(
    val requested: Int,
    val deleted: Int,
    val failed: Int
)
