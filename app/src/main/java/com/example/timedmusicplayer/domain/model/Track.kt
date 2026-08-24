package com.example.timedmusicplayer.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 统一曲目模型：列表、队列与播放器共用。
 *
 * 设计目标：
 * 1. 用单一数据结构承载本地/云端曲目，降低 UI 与播放层分支复杂度。
 * 2. 支持通过 Intent 在页面间传递队列（Parcelable）。
 */
@Parcelize
data class Track(
    // 曲目唯一标识：本地通常为 local:uri，云端通常为 cloud:sourceId。
    val id: String,
    // 曲目主标题，用于列表和播放器主展示。
    val title: String,
    // 曲目副标题：本地可用“本地音乐”占位，云端可用“在线音源”占位。
    val artist: String,
    // 曲目时长（毫秒）。流媒体未知时长时通常为 0。
    val durationMs: Long,
    // 曲目来源类型，驱动播放和展示差异化逻辑。
    val sourceType: SourceType,
    // 实际可播放地址：本地 content:// 或在线 http(s)://。
    val uri: String,
    val album: String = "",
    val coverUrl: String? = null,
    val folderUri: String? = null,
    val sizeBytes: Long = 0L,
    val modifiedAtMs: Long = 0L,
    val mimeType: String? = null
) : Parcelable {
    // 是否为流媒体曲目：true 表示来自云端音源。
    val isStream: Boolean
        get() = sourceType == SourceType.CLOUD
}
