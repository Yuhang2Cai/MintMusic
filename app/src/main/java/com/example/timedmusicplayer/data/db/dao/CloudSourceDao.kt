package com.example.timedmusicplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.timedmusicplayer.data.db.entity.CloudSourceEntity

@Dao
interface CloudSourceDao {
    @Query("SELECT * FROM cloud_sources ORDER BY name COLLATE NOCASE") fun getAll(): List<CloudSourceEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(item: CloudSourceEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertAll(items: List<CloudSourceEntity>)
    @Query("DELETE FROM cloud_sources WHERE id = :id") fun deleteById(id: String): Int
    @Query("DELETE FROM cloud_sources") fun deleteAll(): Int
    @Query("SELECT EXISTS(SELECT 1 FROM cloud_sources WHERE lower(url) = lower(:url) AND id != :ignoreId)") fun hasUrl(url: String, ignoreId: String): Boolean
}
