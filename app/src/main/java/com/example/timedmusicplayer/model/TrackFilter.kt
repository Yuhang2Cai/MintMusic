package com.example.timedmusicplayer.model

/**
 * 音乐库筛选项。
 *
 * 用于主页列表按来源过滤，保持“统一音乐库”体验：
 * 1. ALL：本地+云端合并。
 * 2. LOCAL：仅展示本地文件。
 * 3. CLOUD：仅展示在线音源。
 */
enum class TrackFilter {
    ALL,
    LOCAL,
    CLOUD
}