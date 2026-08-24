package com.example.timedmusicplayer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_queue")
data class PlaybackQueueEntity(
    @PrimaryKey val queueIndex: Int,
    val trackId: String
)
