package com.example.timedmusicplayer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playback_history", indices = [Index("playedAtMs")])
data class PlaybackHistoryEntity(
    @PrimaryKey val trackId: String,
    val positionMs: Long,
    val playedAtMs: Long,
    val checkpointSequence: Long
)
