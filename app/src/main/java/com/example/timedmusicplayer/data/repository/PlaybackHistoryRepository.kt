package com.example.timedmusicplayer.data.repository

import com.example.timedmusicplayer.data.db.MintDatabase
import com.example.timedmusicplayer.data.db.entity.PlaybackHistoryEntity
import com.example.timedmusicplayer.data.migration.LegacyDataMigrator
import com.example.timedmusicplayer.data.model.LastPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaybackHistoryRepository internal constructor(
    private val database: MintDatabase,
    private val migrator: LegacyDataMigrator
) {
    suspend fun saveLastPlayback(trackId: String, positionMs: Long) = onIo {
        migrator.migrateIfNeeded()
        val dao = database.playback()
        dao.checkpoint(
            PlaybackHistoryEntity(
                trackId = trackId,
                positionMs = positionMs.coerceAtLeast(0L),
                playedAtMs = System.currentTimeMillis(),
                checkpointSequence = (dao.sequenceFor(trackId) ?: 0L) + 1L
            )
        )
    }

    suspend fun lastPlayback(): LastPlayback? = onIo {
        migrator.migrateIfNeeded()
        database.playback().lastPlayback()?.let { LastPlayback(it.trackId, it.positionMs) }
    }

    suspend fun markPlayed(trackId: String) = onIo {
        migrator.migrateIfNeeded()
        val dao = database.playback()
        val existing = dao.lastPlayback()?.takeIf { it.trackId == trackId }
        dao.checkpoint(
            PlaybackHistoryEntity(
                trackId = trackId,
                positionMs = existing?.positionMs ?: 0L,
                playedAtMs = System.currentTimeMillis(),
                checkpointSequence = (dao.sequenceFor(trackId) ?: 0L) + 1L
            )
        )
    }

    suspend fun recentTrackIds(): List<String> = onIo {
        migrator.migrateIfNeeded()
        database.playback().recentIds(50)
    }

    suspend fun saveQueue(trackIds: List<String>) = onIo {
        migrator.migrateIfNeeded()
        database.playback().replaceQueue(trackIds)
    }

    private suspend fun <T> onIo(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { block() }
}
