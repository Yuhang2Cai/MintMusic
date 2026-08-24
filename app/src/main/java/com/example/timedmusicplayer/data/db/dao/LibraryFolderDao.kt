package com.example.timedmusicplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.timedmusicplayer.data.db.entity.LibraryFolderEntity

@Dao
interface LibraryFolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(item: LibraryFolderEntity)
    @Query("SELECT * FROM library_folders WHERE uri = :uri") fun get(uri: String): LibraryFolderEntity?
}
