package com.example.timedmusicplayer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "library_folders")
data class LibraryFolderEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val lastSuccessfulScanMs: Long,
    val lastScanGeneration: Long
)
