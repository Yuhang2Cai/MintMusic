package com.example.timedmusicplayer.playback.checkpoint

import kotlin.math.abs

internal class CheckpointPolicy(
    private val minimumDeltaMs: Long
) {
    private var lastTrackId = ""
    private var lastPositionMs = -minimumDeltaMs

    fun shouldWrite(trackId: String, positionMs: Long, force: Boolean): Boolean {
        val shouldWrite = force ||
            trackId != lastTrackId ||
            abs(positionMs - lastPositionMs) >= minimumDeltaMs
        if (shouldWrite) {
            lastTrackId = trackId
            lastPositionMs = positionMs
        }
        return shouldWrite
    }
}
