package com.example.timedmusicplayer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.timedmusicplayer.data.db.entity.PlaybackHistoryEntity
import com.example.timedmusicplayer.data.db.entity.PlaybackQueueEntity

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback_history ORDER BY playedAtMs DESC LIMIT 1") fun lastPlayback(): PlaybackHistoryEntity?
    @Query("SELECT trackId FROM playback_history ORDER BY playedAtMs DESC LIMIT :limit") fun recentIds(limit: Int): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun checkpoint(item: PlaybackHistoryEntity)
    @Query("SELECT checkpointSequence FROM playback_history WHERE trackId = :trackId") fun sequenceFor(trackId: String): Long?
    @Query("DELETE FROM playback_queue") fun clearQueue()
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertQueue(items: List<PlaybackQueueEntity>)
    @Query("SELECT trackId FROM playback_queue ORDER BY queueIndex") fun queueIds(): List<String>

    @Transaction
    fun replaceQueue(trackIds: List<String>) {
        clearQueue()
        insertQueue(trackIds.mapIndexed { index, id -> PlaybackQueueEntity(index, id) })
    }
}
