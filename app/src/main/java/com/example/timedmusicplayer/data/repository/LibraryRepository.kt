package com.example.timedmusicplayer.data.repository

import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.example.timedmusicplayer.data.db.MintDatabase
import com.example.timedmusicplayer.data.db.entity.LibraryFolderEntity
import com.example.timedmusicplayer.data.db.mapper.toModel
import com.example.timedmusicplayer.data.migration.LegacyDataMigrator
import com.example.timedmusicplayer.data.model.DeleteTracksResult
import com.example.timedmusicplayer.data.scanner.LibraryScanner
import com.example.timedmusicplayer.domain.model.SourceType
import com.example.timedmusicplayer.domain.model.Track
import com.example.timedmusicplayer.domain.model.TrackFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LibraryRepository internal constructor(
    private val database: MintDatabase,
    private val scanner: LibraryScanner,
    private val settingsRepository: LibrarySettingsRepository,
    private val migrator: LegacyDataMigrator
) {
    suspend fun saveLocalFolder(uri: Uri) = onIo {
        migrator.migrateIfNeeded()
        settingsRepository.saveLocalFolderUri(uri)
        database.folders().upsert(LibraryFolderEntity(uri.toString(), "Music", 0L, 0L))
    }

    suspend fun getTracks(filter: TrackFilter, forceRefresh: Boolean = false): List<Track> = onIo {
        migrator.migrateIfNeeded()
        val folder = settingsRepository.localFolderUri()?.toString()
        if (folder != null && shouldScan(folder, forceRefresh)) {
            scanner.scan(Uri.parse(folder), forceMetadata = forceRefresh)
        }
        rowsFor(filter).map { it.toModel() }
    }

    fun pagingTracks(filter: TrackFilter): Flow<PagingData<Track>> = flow {
        onIo { migrator.migrateIfNeeded() }
        emitAll(
            Pager(PagingConfig(pageSize = 50, prefetchDistance = 15, enablePlaceholders = false)) {
                when (filter) {
                    TrackFilter.ALL -> database.tracks().pagingAll()
                    TrackFilter.LOCAL -> database.tracks().pagingBySource(SourceType.LOCAL.name)
                    TrackFilter.CLOUD -> database.tracks().pagingBySource(SourceType.CLOUD.name)
                }
            }.flow.map { data -> data.map { it.toModel() } }
        )
    }

    suspend fun refresh(forceRefresh: Boolean) = onIo {
        migrator.migrateIfNeeded()
        val folder = settingsRepository.localFolderUri()?.toString() ?: return@onIo
        if (shouldScan(folder, forceRefresh)) {
            scanner.scan(Uri.parse(folder), forceMetadata = forceRefresh)
        }
    }

    suspend fun trackCount(filter: TrackFilter): Int = onIo {
        migrator.migrateIfNeeded()
        count(filter)
    }

    suspend fun deleteTracks(tracks: List<Track>): DeleteTracksResult = onIo {
        migrator.migrateIfNeeded()
        val ids = tracks.distinctBy(Track::id).map(Track::id)
        val deleted = ids.chunked(500).sumOf(database.tracks()::deleteByIds)
        DeleteTracksResult(ids.size, deleted, ids.size - deleted)
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

    private fun shouldScan(folder: String, forceRefresh: Boolean): Boolean =
        forceRefresh || database.folders().get(folder) == null

    private suspend fun <T> onIo(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { block() }
}
