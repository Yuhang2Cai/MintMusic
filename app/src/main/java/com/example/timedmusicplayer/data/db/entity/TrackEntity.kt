package com.example.timedmusicplayer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [
        Index("sourceType"),
        Index("folderUri"),
        Index("title"),
        Index(value = ["folderUri", "scanGeneration"])
    ]
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sourceType: String,
    val mediaUri: String,
    val folderUri: String?,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    val mimeType: String?,
    val coverUrl: String?,
    val scanGeneration: Long
)
