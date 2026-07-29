package com.example.timedmusicplayer.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.*
import com.example.timedmusicplayer.model.CloudSource
import com.example.timedmusicplayer.model.SourceType
import com.example.timedmusicplayer.model.Track

@Entity(tableName = "tracks", indices = [Index("sourceType"), Index("folderUri"), Index("title"), Index(value = ["folderUri", "scanGeneration"])])
data class TrackEntity(
    @PrimaryKey val id: String, val title: String, val artist: String, val album: String,
    val durationMs: Long, val sourceType: String, val mediaUri: String, val folderUri: String?,
    val sizeBytes: Long, val modifiedAtMs: Long, val mimeType: String?, val coverUrl: String?,
    val scanGeneration: Long
)

@Entity(tableName = "cloud_sources", indices = [Index(value = ["url"], unique = true)])
data class CloudSourceEntity(@PrimaryKey val id: String, val name: String, val url: String, val coverUrl: String?)

@Entity(tableName = "library_folders")
data class LibraryFolderEntity(@PrimaryKey val uri: String, val displayName: String, val lastSuccessfulScanMs: Long, val lastScanGeneration: Long)

@Entity(tableName = "playback_history", indices = [Index("playedAtMs")])
data class PlaybackHistoryEntity(@PrimaryKey val trackId: String, val positionMs: Long, val playedAtMs: Long, val checkpointSequence: Long)

@Entity(tableName = "playback_queue")
data class PlaybackQueueEntity(@PrimaryKey val queueIndex: Int, val trackId: String)

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
}

@Dao
interface CloudSourceDao {
    @Query("SELECT * FROM cloud_sources ORDER BY name COLLATE NOCASE") fun getAll(): List<CloudSourceEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(item: CloudSourceEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insertAll(items: List<CloudSourceEntity>)
    @Query("DELETE FROM cloud_sources WHERE id = :id") fun deleteById(id: String): Int
    @Query("SELECT EXISTS(SELECT 1 FROM cloud_sources WHERE lower(url) = lower(:url) AND id != :ignoreId)") fun hasUrl(url: String, ignoreId: String): Boolean
}

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback_history ORDER BY playedAtMs DESC LIMIT 1") fun lastPlayback(): PlaybackHistoryEntity?
    @Query("SELECT trackId FROM playback_history ORDER BY playedAtMs DESC LIMIT :limit") fun recentIds(limit: Int): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun checkpoint(item: PlaybackHistoryEntity)
    @Query("SELECT checkpointSequence FROM playback_history WHERE trackId = :trackId") fun sequenceFor(trackId: String): Long?
    @Query("DELETE FROM playback_queue") fun clearQueue()
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertQueue(items: List<PlaybackQueueEntity>)
    @Query("SELECT trackId FROM playback_queue ORDER BY queueIndex") fun queueIds(): List<String>
    @Transaction fun replaceQueue(trackIds: List<String>) { clearQueue(); insertQueue(trackIds.mapIndexed { index, id -> PlaybackQueueEntity(index, id) }) }
}

@Dao
interface LibraryFolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(item: LibraryFolderEntity)
    @Query("SELECT * FROM library_folders WHERE uri = :uri") fun get(uri: String): LibraryFolderEntity?
}

@Database(entities = [TrackEntity::class, CloudSourceEntity::class, LibraryFolderEntity::class, PlaybackHistoryEntity::class, PlaybackQueueEntity::class], version = 1, exportSchema = true)
abstract class MintDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun cloudSources(): CloudSourceDao
    abstract fun playback(): PlaybackDao
    abstract fun folders(): LibraryFolderDao
    companion object {
        @Volatile private var instance: MintDatabase? = null
        fun get(context: Context): MintDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MintDatabase::class.java, "mint_music.db").build().also { instance = it }
        }
    }
}

fun TrackEntity.toModel() = Track(id, title, artist, durationMs, SourceType.valueOf(sourceType), mediaUri, album, coverUrl, folderUri, sizeBytes, modifiedAtMs, mimeType)
fun Track.toEntity(generation: Long = 0L) = TrackEntity(id, title, artist, album, durationMs, sourceType.name, uri, folderUri, sizeBytes, modifiedAtMs, mimeType, coverUrl, generation)
fun CloudSourceEntity.toModel() = CloudSource(id, name, url, coverUrl)
fun CloudSource.toEntity() = CloudSourceEntity(id, name, url, coverUrl)
