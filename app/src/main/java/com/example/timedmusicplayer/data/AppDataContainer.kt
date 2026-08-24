package com.example.timedmusicplayer.data

import android.content.Context
import com.example.timedmusicplayer.data.db.MintDatabase
import com.example.timedmusicplayer.data.migration.LegacyDataMigrator
import com.example.timedmusicplayer.data.repository.CloudSourceRepository
import com.example.timedmusicplayer.data.repository.CloudTrackSynchronizer
import com.example.timedmusicplayer.data.repository.LibraryRepository
import com.example.timedmusicplayer.data.repository.LibrarySettingsRepository
import com.example.timedmusicplayer.data.repository.PlaybackHistoryRepository
import com.example.timedmusicplayer.data.scanner.LibraryScanner
import com.example.timedmusicplayer.domain.library.DeleteLibraryContentUseCase

/** Application-scoped composition root for the data layer. */
class AppDataContainer private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val database = MintDatabase.get(appContext)
    private val rawSettings = SettingsStore(appContext)
    private val cloudTrackSynchronizer = CloudTrackSynchronizer(database)
    private val migrator = LegacyDataMigrator(
        appContext,
        database,
        rawSettings,
        cloudTrackSynchronizer
    )

    val settingsRepository = LibrarySettingsRepository(rawSettings, migrator)
    val playbackHistoryRepository = PlaybackHistoryRepository(database, migrator)
    val cloudSourceRepository = CloudSourceRepository(database, migrator, cloudTrackSynchronizer)
    val libraryRepository = LibraryRepository(
        database,
        LibraryScanner(appContext, database),
        settingsRepository,
        migrator
    )
    val deleteLibraryContent = DeleteLibraryContentUseCase(database, migrator)

    companion object {
        @Volatile private var instance: AppDataContainer? = null

        fun get(context: Context): AppDataContainer = instance ?: synchronized(this) {
            instance ?: AppDataContainer(context).also { instance = it }
        }
    }
}
