package com.example.timedmusicplayer.data.db.mapper

import com.example.timedmusicplayer.data.db.entity.TrackEntity
import com.example.timedmusicplayer.domain.model.SourceType
import com.example.timedmusicplayer.domain.model.Track

fun TrackEntity.toModel(): Track {
    val safeSourceType = runCatching { SourceType.valueOf(sourceType) }.getOrElse {
        if (mediaUri.startsWith("http://") || mediaUri.startsWith("https://")) {
            SourceType.CLOUD
        } else {
            SourceType.LOCAL
        }
    }
    return Track(
        id,
        title,
        artist,
        durationMs,
        safeSourceType,
        mediaUri,
        album,
        coverUrl,
        folderUri,
        sizeBytes,
        modifiedAtMs,
        mimeType
    )
}

fun Track.toEntity(generation: Long = 0L) = TrackEntity(
    id,
    title,
    artist,
    album,
    durationMs,
    sourceType.name,
    uri,
    folderUri,
    sizeBytes,
    modifiedAtMs,
    mimeType,
    coverUrl,
    generation
)
