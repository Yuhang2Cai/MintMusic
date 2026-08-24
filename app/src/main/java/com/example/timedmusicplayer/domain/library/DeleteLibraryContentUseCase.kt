package com.example.timedmusicplayer.domain.library

import com.example.timedmusicplayer.data.db.MintDatabase
import com.example.timedmusicplayer.data.migration.LegacyDataMigrator
import com.example.timedmusicplayer.data.model.DeleteTracksResult
import com.example.timedmusicplayer.model.SourceType
import com.example.timedmusicplayer.model.TrackFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coordinates the cross-table delete that does not belong to a single repository. */
class DeleteLibraryContentUseCase internal constructor(
    private val database: MintDatabase,
    private val migrator: LegacyDataMigrator
) {
    suspend operator fun invoke(filter: TrackFilter): DeleteTracksResult = withContext(Dispatchers.IO) {
        migrator.migrateIfNeeded()
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
}
