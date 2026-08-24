package com.example.timedmusicplayer.data

import android.content.Context
import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.example.timedmusicplayer.data.db.CloudSourceEntity
import com.example.timedmusicplayer.data.db.LibraryFolderEntity
import com.example.timedmusicplayer.data.db.MintDatabase
import com.example.timedmusicplayer.data.db.PlaybackHistoryEntity
import com.example.timedmusicplayer.data.db.toEntity
import com.example.timedmusicplayer.data.db.toModel
import com.example.timedmusicplayer.data.index.LocalTrackIndexStore
import com.example.timedmusicplayer.data.scanner.LibraryScanner
import com.example.timedmusicplayer.model.CloudSource
import com.example.timedmusicplayer.model.SourceType
import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.model.TrackFilter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DeleteTracksResult(val requested: Int, val deleted: Int, val failed: Int)

/** Room-backed source of truth. Disk operations are asynchronous at the API boundary. */
class MusicRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val database = MintDatabase.get(appContext)
    private val settings = SettingsStore(appContext)
    private val scanner = LibraryScanner(appContext, database)
    private val migrationMutex = Mutex()
    @Volatile private var migrationReady = false

    suspend fun saveLocalFolder(uri: Uri) = onIo {
        settings.setFolderUri(uri.toString())
        database.folders().upsert(LibraryFolderEntity(uri.toString(), "Music", 0L, 0L))
    }

    suspend fun getLocalFolderUri(): Uri? = onIo {
        ensureMigratedInternal()
        settings.folderUri()?.takeIf(String::isNotBlank)?.let(Uri::parse)
    }

    suspend fun getCloudSources(): List<CloudSource> = onIo {
        ensureMigratedInternal()
        database.cloudSources().getAll().map(CloudSourceEntity::toModel)
    }

    suspend fun addCloudSource(name: String, url: String, coverUrl: String? = null): CloudSource = onIo {
        ensureMigratedInternal()
        val source = CloudSource(UUID.randomUUID().toString(), name.trim(), url.trim(), coverUrl?.trim()?.takeIf(String::isNotBlank))
        database.cloudSources().upsert(source.toEntity())
        syncCloudTracks()
        source
    }

    suspend fun renameCloudSource(sourceId: String, newName: String): Boolean = onIo {
        ensureMigratedInternal()
        val current = database.cloudSources().getAll().firstOrNull { it.id == sourceId } ?: return@onIo false
        database.cloudSources().upsert(current.copy(name = newName.trim()))
        syncCloudTracks()
        true
    }

    suspend fun deleteCloudSource(sourceId: String): Boolean = onIo {
        ensureMigratedInternal()
        val removed = database.cloudSources().deleteById(sourceId) > 0
        if (removed) syncCloudTracks()
        removed
    }

    suspend fun hasDuplicateCloudUrl(url: String, ignoreId: String? = null): Boolean = onIo {
        ensureMigratedInternal()
        database.cloudSources().hasUrl(url.trim(), ignoreId.orEmpty())
    }

    suspend fun getTracks(filter: TrackFilter, forceRefresh: Boolean = false): List<Track> = onIo {
        ensureMigratedInternal()
        val folder = settings.folderUri()?.takeIf(String::isNotBlank)
        if (folder != null && shouldScan(folder, forceRefresh)) scanner.scan(Uri.parse(folder), forceMetadata = forceRefresh)
        rowsFor(filter).map { it.toModel() }
    }

    fun pagingTracks(filter: TrackFilter): Flow<PagingData<Track>> = flow {
        onIo { ensureMigratedInternal() }
        emitAll(Pager(PagingConfig(pageSize = 50, prefetchDistance = 15, enablePlaceholders = false)) {
            when (filter) {
                TrackFilter.ALL -> database.tracks().pagingAll()
                TrackFilter.LOCAL -> database.tracks().pagingBySource(SourceType.LOCAL.name)
                TrackFilter.CLOUD -> database.tracks().pagingBySource(SourceType.CLOUD.name)
            }
        }.flow.map { data -> data.map { it.toModel() } })
    }

    suspend fun refreshLibrary(forceRefresh: Boolean) = onIo {
        ensureMigratedInternal()
        val folder = settings.folderUri()?.takeIf(String::isNotBlank) ?: return@onIo
        if (shouldScan(folder, forceRefresh)) scanner.scan(Uri.parse(folder), forceMetadata = forceRefresh)
    }

    suspend fun trackCount(filter: TrackFilter): Int = onIo {
        ensureMigratedInternal()
        count(filter)
    }

    suspend fun saveLastPlayback(trackId: String, positionMs: Long) = onIo {
        ensureMigratedInternal()
        val dao = database.playback()
        dao.checkpoint(PlaybackHistoryEntity(trackId, positionMs.coerceAtLeast(0L), System.currentTimeMillis(), (dao.sequenceFor(trackId) ?: 0L) + 1L))
    }

    suspend fun getLastPlayback(): PlaybackStore.LastPlayback? = onIo {
        ensureMigratedInternal()
        database.playback().lastPlayback()?.let { PlaybackStore.LastPlayback(it.trackId, it.positionMs) }
    }

    suspend fun savePlaybackMode(modeValue: String) = settings.setPlaybackMode(modeValue)
    suspend fun getPlaybackMode(defaultValue: String): String = onIo { ensureMigratedInternal(); settings.playbackMode(defaultValue) }
    suspend fun saveSelectedFilter(value: String) = settings.setSelectedFilter(value)
    suspend fun getSelectedFilter(defaultValue: String): String = onIo { ensureMigratedInternal(); settings.selectedFilter(defaultValue) }

    suspend fun markPlayed(trackId: String) = onIo {
        ensureMigratedInternal()
        val dao = database.playback()
        val existing = dao.lastPlayback()?.takeIf { it.trackId == trackId }
        dao.checkpoint(PlaybackHistoryEntity(trackId, existing?.positionMs ?: 0L, System.currentTimeMillis(), (dao.sequenceFor(trackId) ?: 0L) + 1L))
    }

    suspend fun getRecentTrackIds(): List<String> = onIo { ensureMigratedInternal(); database.playback().recentIds(50) }
    suspend fun saveQueue(trackIds: List<String>) = onIo { ensureMigratedInternal(); database.playback().replaceQueue(trackIds) }

    suspend fun deleteTracks(tracks: List<Track>): DeleteTracksResult = onIo {
        ensureMigratedInternal()
        val ids = tracks.distinctBy(Track::id).map(Track::id)
        val deleted = ids.chunked(500).sumOf(database.tracks()::deleteByIds)
        DeleteTracksResult(ids.size, deleted, ids.size - deleted)
    }

    suspend fun deleteAllTracks(filter: TrackFilter): DeleteTracksResult = onIo {
        ensureMigratedInternal()
        val requested = count(filter)
        val deleted = database.runInTransaction<Int> {
            val deletedTracks = when (filter) {
                TrackFilter.ALL -> database.tracks().deleteAll()
                TrackFilter.LOCAL -> database.tracks().deleteBySource(SourceType.LOCAL.name)
                TrackFilter.CLOUD -> database.tracks().deleteBySource(SourceType.CLOUD.name)
            }
            if (filter == TrackFilter.ALL || filter == TrackFilter.CLOUD) {
                database.cloudSources().deleteAll()
            }
            deletedTracks
        }
        DeleteTracksResult(requested, deleted, (requested - deleted).coerceAtLeast(0))
    }

    private fun count(filter: TrackFilter): Int = when (filter) {
        TrackFilter.ALL -> database.tracks().countAll()
        TrackFilter.LOCAL -> database.tracks().countBySource(SourceType.LOCAL.name)
        TrackFilter.CLOUD -> database.tracks().countBySource(SourceType.CLOUD.name)
    }

    private fun rowsFor(filter: TrackFilter) = when (filter) {
        TrackFilter.ALL -> database.tracks().getAll()
        TrackFilter.LOCAL -> database.tracks().getBySource(SourceType.LOCAL.name)
        TrackFilter.CLOUD -> database.tracks().getBySource(SourceType.CLOUD.name)
    }

    private fun shouldScan(folder: String, forceRefresh: Boolean) = forceRefresh || database.folders().get(folder) == null

    private suspend fun ensureMigratedInternal() {
        if (migrationReady) return
        migrationMutex.withLock {
            if (migrationReady) return
            if (settings.migrationVersion() < 1) {
                val legacyLocal = appContext.getSharedPreferences("local_music_prefs", Context.MODE_PRIVATE)
                    .getString("selected_folder_uri", null)?.takeIf(String::isNotBlank)
                if (settings.folderUri().isNullOrBlank() && legacyLocal != null) settings.setFolderUri(legacyLocal)
                database.cloudSources().insertAll(CloudSourceDataSource(appContext).getSources().map(CloudSource::toEntity))
                syncCloudTracks()
                if (legacyLocal != null) {
                    val indexed = LocalTrackIndexStore(appContext).getTracksByFolder(legacyLocal)
                    if (indexed.isNotEmpty()) database.tracks().upsertAll(indexed.map { it.copy(folderUri = legacyLocal).toEntity() })
                }
                val old = PlaybackStore(appContext)
                old.getLastPlayback()?.let { database.playback().checkpoint(PlaybackHistoryEntity(it.trackId, it.positionMs, System.currentTimeMillis(), 1L)) }
                old.getRecentTrackIds().reversed().forEachIndexed { index, id ->
                    if (database.playback().sequenceFor(id) == null) database.playback().checkpoint(PlaybackHistoryEntity(id, 0L, System.currentTimeMillis() - index, 1L))
                }
                settings.setPlaybackMode(old.getPlaybackMode("ORDER"))
                settings.setMigrationVersion(1)
            }
            migrationReady = true
        }
    }

    private fun syncCloudTracks() {
        val cloudTracks = database.cloudSources().getAll().map {
            Track("cloud:${it.id}", it.name, "在线音源", 0L, SourceType.CLOUD, it.url, coverUrl = it.coverUrl).toEntity()
        }
        database.runInTransaction {
            database.tracks().deleteCloudTracks()
            database.tracks().upsertAll(cloudTracks)
        }
    }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        @Volatile private var instance: MusicRepository? = null
        fun getInstance(context: Context): MusicRepository = instance ?: synchronized(this) {
            instance ?: MusicRepository(context).also { instance = it }
        }
    }
}
