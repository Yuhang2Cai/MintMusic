package com.example.timedmusicplayer.data.migration

import android.content.Context
import com.example.timedmusicplayer.data.CloudSourceDataSource
import com.example.timedmusicplayer.data.PlaybackStore
import com.example.timedmusicplayer.data.SettingsStore
import com.example.timedmusicplayer.data.db.MintDatabase
import com.example.timedmusicplayer.data.db.entity.PlaybackHistoryEntity
import com.example.timedmusicplayer.data.db.mapper.toEntity
import com.example.timedmusicplayer.data.index.LocalTrackIndexStore
import com.example.timedmusicplayer.data.repository.CloudTrackSynchronizer
import com.example.timedmusicplayer.model.CloudSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Imports data from the pre-Room stores exactly once before repositories expose data. */
class LegacyDataMigrator internal constructor(
    context: Context,
    private val database: MintDatabase,
    private val settings: SettingsStore,
    private val cloudTrackSynchronizer: CloudTrackSynchronizer
) {
    private val appContext = context.applicationContext
    private val migrationMutex = Mutex()
    @Volatile private var migrationReady = false

    suspend fun migrateIfNeeded() {
        if (migrationReady) return
        migrationMutex.withLock {
            if (migrationReady) return
            if (settings.migrationVersion() < CURRENT_VERSION) {
                migrateVersionOne()
                settings.setMigrationVersion(CURRENT_VERSION)
            }
            migrationReady = true
        }
    }

    private suspend fun migrateVersionOne() {
        val legacyLocal = appContext
            .getSharedPreferences("local_music_prefs", Context.MODE_PRIVATE)
            .getString("selected_folder_uri", null)
            ?.takeIf(String::isNotBlank)

        if (settings.folderUri().isNullOrBlank() && legacyLocal != null) {
            settings.setFolderUri(legacyLocal)
        }

        database.cloudSources().insertAll(
            CloudSourceDataSource(appContext).getSources().map(CloudSource::toEntity)
        )
        cloudTrackSynchronizer.synchronize()

        if (legacyLocal != null) {
            val indexed = LocalTrackIndexStore(appContext).getTracksByFolder(legacyLocal)
            if (indexed.isNotEmpty()) {
                database.tracks().upsertAll(
                    indexed.map { it.copy(folderUri = legacyLocal).toEntity() }
                )
            }
        }

        val oldPlaybackStore = PlaybackStore(appContext)
        oldPlaybackStore.getLastPlayback()?.let { last ->
            database.playback().checkpoint(
                PlaybackHistoryEntity(
                    trackId = last.trackId,
                    positionMs = last.positionMs,
                    playedAtMs = System.currentTimeMillis(),
                    checkpointSequence = 1L
                )
            )
        }
        oldPlaybackStore.getRecentTrackIds().reversed().forEachIndexed { index, trackId ->
            if (database.playback().sequenceFor(trackId) == null) {
                database.playback().checkpoint(
                    PlaybackHistoryEntity(
                        trackId = trackId,
                        positionMs = 0L,
                        playedAtMs = System.currentTimeMillis() - index,
                        checkpointSequence = 1L
                    )
                )
            }
        }
        settings.setPlaybackMode(oldPlaybackStore.getPlaybackMode("ORDER"))
    }

    private companion object {
        const val CURRENT_VERSION = 1
    }
}
