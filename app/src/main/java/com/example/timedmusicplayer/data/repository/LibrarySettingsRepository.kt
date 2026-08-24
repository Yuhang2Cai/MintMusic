package com.example.timedmusicplayer.data.repository

import android.net.Uri
import com.example.timedmusicplayer.data.SettingsStore
import com.example.timedmusicplayer.data.migration.LegacyDataMigrator
import com.example.timedmusicplayer.model.TrackFilter
import com.example.timedmusicplayer.playback.PlaybackMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibrarySettingsRepository internal constructor(
    private val settings: SettingsStore,
    private val migrator: LegacyDataMigrator
) {
    suspend fun localFolderUri(): Uri? = onIo {
        migrator.migrateIfNeeded()
        settings.folderUri()?.takeIf(String::isNotBlank)?.let(Uri::parse)
    }

    suspend fun saveLocalFolderUri(uri: Uri) = onIo {
        migrator.migrateIfNeeded()
        settings.setFolderUri(uri.toString())
    }

    suspend fun selectedFilter(): TrackFilter = onIo {
        migrator.migrateIfNeeded()
        runCatching { TrackFilter.valueOf(settings.selectedFilter(TrackFilter.ALL.name)) }
            .getOrDefault(TrackFilter.ALL)
    }

    suspend fun saveSelectedFilter(filter: TrackFilter) = onIo {
        migrator.migrateIfNeeded()
        settings.setSelectedFilter(filter.name)
    }

    suspend fun playbackMode(): PlaybackMode = onIo {
        migrator.migrateIfNeeded()
        PlaybackMode.fromRaw(settings.playbackMode(PlaybackMode.ORDER.name))
    }

    suspend fun savePlaybackMode(mode: PlaybackMode) = onIo {
        migrator.migrateIfNeeded()
        settings.setPlaybackMode(mode.name)
    }

    private suspend fun <T> onIo(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { block() }
}
