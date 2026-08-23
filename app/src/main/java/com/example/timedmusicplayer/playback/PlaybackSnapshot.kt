package com.example.timedmusicplayer.playback

import com.example.timedmusicplayer.model.Track

/**
 * 从服务层暴露给 UI 的不可变播放快照。
 */
data class PlaybackSnapshot(
    val queue: List<Track>,
    val currentIndex: Int,
    val positionMs: Long,
    val bufferedPositionMs: Long,
    val durationMs: Long,
    val isSeekable: Boolean,
    val isPlaying: Boolean,
    val state: PlaybackUiState,
    val playbackMode: PlaybackMode,
    // 属性： errorMessage
    // 说明：错误信息缓存，用于 UI 展示与日志上报。
    val errorMessage: String? = null,
    val audioSessionId: Int = -1,
    val sleepTimerRemainingMs: Long = 0L
) {
    val currentTrack: Track?
        get() = queue.getOrNull(currentIndex)
}
