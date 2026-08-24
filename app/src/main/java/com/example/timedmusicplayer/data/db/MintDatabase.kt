package com.example.timedmusicplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.timedmusicplayer.data.db.dao.CloudSourceDao
import com.example.timedmusicplayer.data.db.dao.LibraryFolderDao
import com.example.timedmusicplayer.data.db.dao.PlaybackDao
import com.example.timedmusicplayer.data.db.dao.TrackDao
import com.example.timedmusicplayer.data.db.entity.CloudSourceEntity
import com.example.timedmusicplayer.data.db.entity.LibraryFolderEntity
import com.example.timedmusicplayer.data.db.entity.PlaybackHistoryEntity
import com.example.timedmusicplayer.data.db.entity.PlaybackQueueEntity
import com.example.timedmusicplayer.data.db.entity.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        CloudSourceEntity::class,
        LibraryFolderEntity::class,
        PlaybackHistoryEntity::class,
        PlaybackQueueEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class MintDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun cloudSources(): CloudSourceDao
    abstract fun playback(): PlaybackDao
    abstract fun folders(): LibraryFolderDao

    companion object {
        @Volatile private var instance: MintDatabase? = null

        fun get(context: Context): MintDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MintDatabase::class.java,
                "mint_music.db"
            ).build().also { instance = it }
        }
    }
}
