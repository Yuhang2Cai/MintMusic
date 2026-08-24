package com.example.timedmusicplayer.domain.model

/**
 * 云端流媒体条目模型。
 *
 * 设计目的：
 * 1. 在本地持久化层保存用户维护的在线音源。
 * 2. 作为仓库层组装 Track（sourceType=CLOUD）的基础输入。
 */
data class CloudSource(
    // 音源唯一标识（UUID），用于编辑/删除时精确定位记录。
    val id: String,
    // 音源展示名称，优先使用用户输入，便于在列表中识别。
    val name: String,
    // 实际可播放的流媒体地址（http/https）。
    val url: String,
    val coverUrl: String? = null
)
