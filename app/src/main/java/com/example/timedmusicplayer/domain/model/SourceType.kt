package com.example.timedmusicplayer.domain.model

/**
 * 曲目来源类型。
 *
 * 用途：
 * 1. 区分本地文件与在线流媒体。
 * 2. 在 UI 层决定标签文案、时长展示与缓冲逻辑。
 * 3. 在播放服务层决定是否启用流媒体相关保活策略。
 */
enum class SourceType {
    // 本地目录扫描得到的音频文件。
    LOCAL,
    // 在线音源映射得到的流媒体条目。
    CLOUD
}