package com.example.timedmusicplayer.playback

/**
 * 播放模式选项，控制队列切歌行为。
 */
enum class PlaybackMode {
    ORDER,
    REPEAT_ONE,
    REPEAT_ALL,
    SHUFFLE;

    // 函数： next
    // 说明：返回下一个可用枚举状态。
    fun next(): PlaybackMode {
        return when (this) {
            ORDER -> REPEAT_ONE
            REPEAT_ONE -> REPEAT_ALL
            REPEAT_ALL -> SHUFFLE
            SHUFFLE -> ORDER
        }
    }

    companion object {
        // 函数： fromRaw
        // 说明：将持久化原始值转换回内部枚举类型。
        fun fromRaw(raw: String): PlaybackMode {
            return entries.firstOrNull { it.name == raw } ?: ORDER
        }
    }
}