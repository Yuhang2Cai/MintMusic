package com.example.timedmusicplayer.data.repository

import com.example.timedmusicplayer.data.db.MintDatabase
import com.example.timedmusicplayer.data.db.mapper.toEntity
import com.example.timedmusicplayer.domain.model.SourceType
import com.example.timedmusicplayer.domain.model.Track

/** Keeps the derived cloud rows in the unified track table consistent with cloud_sources. */
internal class CloudTrackSynchronizer(
    private val database: MintDatabase
) {
    fun synchronize() {
        val cloudTracks = database.cloudSources().getAll().map { source ->
            Track(
                id = "cloud:${source.id}",
                title = source.name,
                artist = "在线音源",
                durationMs = 0L,
                sourceType = SourceType.CLOUD,
                uri = source.url,
                coverUrl = source.coverUrl
            ).toEntity()
        }
        database.runInTransaction {
            database.tracks().deleteCloudTracks()
            database.tracks().upsertAll(cloudTracks)
        }
    }
}
