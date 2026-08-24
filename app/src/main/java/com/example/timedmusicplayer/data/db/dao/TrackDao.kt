package com.example.timedmusicplayer.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.timedmusicplayer.data.db.entity.TrackEntity

@Dao
interface TrackDao {
    @Query("SELECT COUNT(*) FROM tracks") fun countAll(): Int
    @Query("SELECT COUNT(*) FROM tracks WHERE sourceType = :source") fun countBySource(source: String): Int
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE") fun getAll(): List<TrackEntity>
    @Query("SELECT * FROM tracks WHERE sourceType = :source ORDER BY title COLLATE NOCASE") fun getBySource(source: String): List<TrackEntity>
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE") fun pagingAll(): PagingSource<Int, TrackEntity>
    @Query("SELECT * FROM tracks WHERE sourceType = :source ORDER BY title COLLATE NOCASE") fun pagingBySource(source: String): PagingSource<Int, TrackEntity>
    @Query("SELECT * FROM tracks WHERE folderUri = :folderUri") fun getByFolder(folderUri: String): List<TrackEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsertAll(items: List<TrackEntity>)
    @Query("DELETE FROM tracks WHERE folderUri = :folderUri AND scanGeneration != :generation") fun deleteNotSeen(folderUri: String, generation: Long)
    @Query("DELETE FROM tracks WHERE sourceType = 'CLOUD'") fun deleteCloudTracks()
    @Query("DELETE FROM tracks WHERE id IN (:ids)") fun deleteByIds(ids: List<String>): Int
    @Query("DELETE FROM tracks") fun deleteAll(): Int
    @Query("DELETE FROM tracks WHERE sourceType = :source") fun deleteBySource(source: String): Int
}
