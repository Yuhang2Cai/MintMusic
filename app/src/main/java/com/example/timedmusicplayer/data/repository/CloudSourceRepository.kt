package com.example.timedmusicplayer.data.repository

import com.example.timedmusicplayer.data.db.MintDatabase
import com.example.timedmusicplayer.data.db.mapper.toEntity
import com.example.timedmusicplayer.data.db.mapper.toModel
import com.example.timedmusicplayer.data.migration.LegacyDataMigrator
import com.example.timedmusicplayer.domain.model.CloudSource
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudSourceRepository internal constructor(
    private val database: MintDatabase,
    private val migrator: LegacyDataMigrator,
    private val cloudTrackSynchronizer: CloudTrackSynchronizer
) {
    suspend fun getSources(): List<CloudSource> = onIo {
        migrator.migrateIfNeeded()
        database.cloudSources().getAll().map { it.toModel() }
    }

    suspend fun addSource(name: String, url: String, coverUrl: String? = null): CloudSource = onIo {
        migrator.migrateIfNeeded()
        val source = CloudSource(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            url = url.trim(),
            coverUrl = coverUrl?.trim()?.takeIf(String::isNotBlank)
        )
        database.cloudSources().upsert(source.toEntity())
        cloudTrackSynchronizer.synchronize()
        source
    }

    suspend fun renameSource(sourceId: String, newName: String): Boolean = onIo {
        migrator.migrateIfNeeded()
        val current = database.cloudSources().getAll().firstOrNull { it.id == sourceId }
            ?: return@onIo false
        database.cloudSources().upsert(current.copy(name = newName.trim()))
        cloudTrackSynchronizer.synchronize()
        true
    }

    suspend fun deleteSource(sourceId: String): Boolean = onIo {
        migrator.migrateIfNeeded()
        val removed = database.cloudSources().deleteById(sourceId) > 0
        if (removed) cloudTrackSynchronizer.synchronize()
        removed
    }

    suspend fun hasDuplicateUrl(url: String, ignoreId: String? = null): Boolean = onIo {
        migrator.migrateIfNeeded()
        database.cloudSources().hasUrl(url.trim(), ignoreId.orEmpty())
    }

    private suspend fun <T> onIo(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { block() }
}
