package com.example.timedmusicplayer.playback

/**
 * UI 层播放状态。
 *
 * 约定：
 * 1. 由 PlaybackService 将 ExoPlayer 内核状态映射到该枚举。
 * 2. MainActivity / PlayerActivity 仅消费该抽象状态，不直接依赖底层状态码。
 */
enum class PlaybackUiState {
    // 空闲态：尚未加载队列或已停止。
    IDLE,
    // 加载态：已设置媒体项，等待 prepare 完成。
    LOADING,
    // 播放态：播放器正在输出音频。
    PLAYING,
    // 暂停态：媒体已就绪但当前不播放。
    PAUSED,
    // 缓冲态：播放中断并等待更多数据到达。
    BUFFERING,
    // 错误态：不可恢复错误，需提示用户或重试。
    ERROR
}