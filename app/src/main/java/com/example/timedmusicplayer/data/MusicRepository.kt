package com.example.timedmusicplayer.data

import android.content.Context
import android.net.Uri
import com.example.timedmusicplayer.data.db.*
import com.example.timedmusicplayer.data.index.LocalTrackIndexStore
import com.example.timedmusicplayer.data.scanner.LibraryScanner
import com.example.timedmusicplayer.model.CloudSource
import com.example.timedmusicplayer.model.SourceType
import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.model.TrackFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Room-backed source of truth for library, cloud sources, queue and playback history. */
class MusicRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val database = MintDatabase.get(appContext)
    private val settings = SettingsStore(appContext)
    private val scanner = LibraryScanner(appContext, database)
    private val migrationLock = Any()
    @Volatile private var migrationReady = false

    fun saveLocalFolder(uri: Uri) { settings.setFolderUri(uri.toString()); blockingIo { database.folders().upsert(LibraryFolderEntity(uri.toString(), "Music", 0L, 0L)) } }
    fun getLocalFolderUri(): Uri? { ensureMigrated(); return settings.folderUri()?.takeIf(String::isNotBlank)?.let(Uri::parse) }
    fun getCloudSources(): List<CloudSource> = blockingIo { ensureMigratedInternal(); database.cloudSources().getAll().map(CloudSourceEntity::toModel) }

    fun addCloudSource(name: String, url: String, coverUrl: String? = null): CloudSource = blockingIo {
        ensureMigratedInternal(); val source = CloudSource(UUID.randomUUID().toString(), name.trim(), url.trim(), coverUrl?.trim()?.takeIf(String::isNotBlank))
        database.cloudSources().upsert(source.toEntity()); syncCloudTracks(); source
    }
    fun renameCloudSource(sourceId: String, newName: String): Boolean = blockingIo {
        ensureMigratedInternal(); val current = database.cloudSources().getAll().firstOrNull { it.id == sourceId } ?: return@blockingIo false
        database.cloudSources().upsert(current.copy(name = newName.trim())); syncCloudTracks(); true
    }
    fun deleteCloudSource(sourceId: String): Boolean = blockingIo {
        ensureMigratedInternal(); val removed = database.cloudSources().deleteById(sourceId) > 0; if (removed) syncCloudTracks(); removed
    }
    fun hasDuplicateCloudUrl(url: String, ignoreId: String? = null): Boolean = blockingIo {
        ensureMigratedInternal(); database.cloudSources().hasUrl(url.trim(), ignoreId.orEmpty())
    }

    fun getTracks(filter: TrackFilter, forceRefresh: Boolean = false): List<Track> = blockingIo {
        ensureMigratedInternal()
        val folder = settings.folderUri()?.takeIf(String::isNotBlank)
        if (folder != null && (forceRefresh || database.tracks().getBySource(SourceType.LOCAL.name).isEmpty())) scanner.scan(Uri.parse(folder), forceMetadata = forceRefresh)
        val rows = when (filter) {
            TrackFilter.ALL -> database.tracks().getAll()
            TrackFilter.LOCAL -> database.tracks().getBySource(SourceType.LOCAL.name)
            TrackFilter.CLOUD -> database.tracks().getBySource(SourceType.CLOUD.name)
        }
        rows.map(TrackEntity::toModel)
    }

    fun pagingTracks(filter: TrackFilter): Flow<PagingData<Track>> {
        ensureMigrated()
        return Pager(PagingConfig(pageSize = 50, prefetchDistance = 15, enablePlaceholders = false)) {
            when (filter) {
                TrackFilter.ALL -> database.tracks().pagingAll()
                TrackFilter.LOCAL -> database.tracks().pagingBySource(SourceType.LOCAL.name)
                TrackFilter.CLOUD -> database.tracks().pagingBySource(SourceType.CLOUD.name)
            }
        }.flow.map { data -> data.map(TrackEntity::toModel) }
    }

    fun refreshLibrary(forceRefresh: Boolean) = blockingIo {
        ensureMigratedInternal()
        val folder = settings.folderUri()?.takeIf(String::isNotBlank) ?: return@blockingIo
        if (forceRefresh || database.tracks().countBySource(SourceType.LOCAL.name) == 0) scanner.scan(Uri.parse(folder), forceRefresh)
    }

    fun trackCount(filter: TrackFilter): Int = blockingIo {
        ensureMigratedInternal()
        when (filter) {
            TrackFilter.ALL -> database.tracks().countAll()
            TrackFilter.LOCAL -> database.tracks().countBySource(SourceType.LOCAL.name)
            TrackFilter.CLOUD -> database.tracks().countBySource(SourceType.CLOUD.name)
        }
    }

    fun saveLastPlayback(trackId: String, positionMs: Long) = blockingIo {
        ensureMigratedInternal(); val dao = database.playback(); val sequence = (dao.sequenceFor(trackId) ?: 0L) + 1L
        dao.checkpoint(PlaybackHistoryEntity(trackId, positionMs.coerceAtLeast(0L), System.currentTimeMillis(), sequence))
    }
    fun getLastPlayback(): PlaybackStore.LastPlayback? = blockingIo {
        ensureMigratedInternal(); database.playback().lastPlayback()?.let { PlaybackStore.LastPlayback(it.trackId, it.positionMs) }
    }
    fun savePlaybackMode(modeValue: String) { settings.setPlaybackMode(modeValue) }
    fun getPlaybackMode(defaultValue: String): String { ensureMigrated(); return settings.playbackMode(defaultValue) }
    fun saveSelectedFilter(value: String) { settings.setSelectedFilter(value) }
    fun getSelectedFilter(defaultValue: String): String { ensureMigrated(); return settings.selectedFilter(defaultValue) }
    fun markPlayed(trackId: String) = blockingIo {
        ensureMigratedInternal(); val dao = database.playback(); val existing = dao.lastPlayback()?.takeIf { it.trackId == trackId }
        dao.checkpoint(PlaybackHistoryEntity(trackId, existing?.positionMs ?: 0L, System.currentTimeMillis(), (dao.sequenceFor(trackId) ?: 0L) + 1L))
    }
    fun getRecentTrackIds(): List<String> = blockingIo { ensureMigratedInternal(); database.playback().recentIds(50) }
    fun saveQueue(trackIds: List<String>) = blockingIo { ensureMigratedInternal(); database.playback().replaceQueue(trackIds) }

    private fun ensureMigrated() = blockingIo { ensureMigratedInternal() }
    private fun ensureMigratedInternal() {
        if (migrationReady) return
        synchronized(migrationLock) {
            if (migrationReady) return
            if (settings.migrationVersion() < 1) {
                val legacyLocal = appContext.getSharedPreferences("local_music_prefs", Context.MODE_PRIVATE).getString("selected_folder_uri", null)?.takeIf(String::isNotBlank)
                if (settings.folderUri().isNullOrBlank() && legacyLocal != null) settings.setFolderUri(legacyLocal)
                database.cloudSources().insertAll(CloudSourceDataSource(appContext).getSources().map(CloudSource::toEntity)); syncCloudTracks()
                if (legacyLocal != null) {
                    val indexed = LocalTrackIndexStore(appContext).getTracksByFolder(legacyLocal)
                    if (indexed.isNotEmpty()) database.tracks().upsertAll(indexed.map { it.copy(folderUri = legacyLocal).toEntity() })
                }
                val old = PlaybackStore(appContext)
                old.getLastPlayback()?.let { database.playback().checkpoint(PlaybackHistoryEntity(it.trackId, it.positionMs, System.currentTimeMillis(), 1L)) }
                old.getRecentTrackIds().reversed().forEachIndexed { index, id ->
                    if (database.playback().sequenceFor(id) == null) database.playback().checkpoint(PlaybackHistoryEntity(id, 0L, System.currentTimeMillis() - index, 1L))
                }
                settings.setPlaybackMode(old.getPlaybackMode("ORDER")); settings.setMigrationVersion(1)
            }
            migrationReady = true
        }
    }

    private fun syncCloudTracks() {
        database.tracks().deleteCloudTracks()
        database.tracks().upsertAll(database.cloudSources().getAll().map {
            Track("cloud:${it.id}", it.name, "在线音源", 0L, SourceType.CLOUD, it.url, coverUrl = it.coverUrl).toEntity()
        })
    }
    private fun <T> blockingIo(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }

    companion object {
        @Volatile private var instance: MusicRepository? = null
        fun getInstance(context: Context): MusicRepository = instance ?: synchronized(this) { instance ?: MusicRepository(context).also { instance = it } }
    }
}
