package com.example.timedmusicplayer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "cloud_sources", indices = [Index(value = ["url"], unique = true)])
data class CloudSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val coverUrl: String?
)
