package com.example.timedmusicplayer.data

import android.content.Context
import org.json.JSONArray

/**
 * 持久化存储：保存续播位置、最近播放与播放模式。
 */
class PlaybackStore(private val context: Context) {

    // 函数： saveLastPlayback
    // 说明：保存关键状态到持久层，保证下次启动可恢复。
    fun saveLastPlayback(trackId: String, positionMs: Long) {
        prefs.edit()
            .putString(KEY_LAST_TRACK_ID, trackId)
            .putLong(KEY_LAST_POSITION_MS, positionMs.coerceAtLeast(0L))
            .apply()
    }

    // 函数： getLastPlayback
    // 说明：读取并返回当前数据或状态快照。
    fun getLastPlayback(): LastPlayback? {
    // 属性： trackId
    // 说明：运行期状态变量，承载 trackId 相关上下文信息。
        val trackId = prefs.getString(KEY_LAST_TRACK_ID, null).orEmpty()
        if (trackId.isBlank()) {
            return null
        }
    // 属性： position
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val position = prefs.getLong(KEY_LAST_POSITION_MS, 0L).coerceAtLeast(0L)
        return LastPlayback(trackId, position)
    }

// 函数： addRecentTrack
// 说明：封装 addRecentTrack 相关业务流程，负责参数校验、状态流转与异常兜底。
fun addRecentTrack(trackId: String) {
    // 属性： current
    // 说明：运行期状态变量，承载 current 相关上下文信息。
        val current = getRecentTrackIds().toMutableList()
        current.removeAll { it == trackId }
        current.add(0, trackId)
        if (current.size > MAX_RECENT_SIZE) {
            current.subList(MAX_RECENT_SIZE, current.size).clear()
        }
    // 属性： array
    // 说明：运行期状态变量，承载 array 相关上下文信息。
        val array = JSONArray()
        current.forEach { array.put(it) }
        prefs.edit().putString(KEY_RECENT_IDS, array.toString()).apply()
    }

    // 函数： getRecentTrackIds
    // 说明：读取并返回当前数据或状态快照。
    fun getRecentTrackIds(): List<String> {
    // 属性： raw
    // 说明：运行期状态变量，承载 raw 相关上下文信息。
        val raw = prefs.getString(KEY_RECENT_IDS, "[]").orEmpty()
        return try {
    // 属性： array
    // 说明：运行期状态变量，承载 array 相关上下文信息。
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
    // 属性： id
    // 说明：运行期状态变量，承载 id 相关上下文信息。
                    val id = array.optString(i).trim()
                    if (id.isNotBlank()) {
                        add(id)
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // 函数： savePlaybackMode
    // 说明：保存关键状态到持久层，保证下次启动可恢复。
    fun savePlaybackMode(modeValue: String) {
        prefs.edit().putString(KEY_PLAYBACK_MODE, modeValue).apply()
    }

    // 函数： getPlaybackMode
    // 说明：读取并返回当前数据或状态快照。
    fun getPlaybackMode(defaultValue: String): String {
        return prefs.getString(KEY_PLAYBACK_MODE, defaultValue).orEmpty().ifBlank { defaultValue }
    }

    // 属性： prefs
    // 说明：持久层/数据源对象，负责本地读写与数据解析。
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    data class LastPlayback(
        val trackId: String,
        val positionMs: Long
    )

    companion object {
        private const val PREFS_NAME = "playback_store"
        private const val KEY_LAST_TRACK_ID = "last_track_id"
        private const val KEY_LAST_POSITION_MS = "last_position_ms"
        private const val KEY_RECENT_IDS = "recent_ids"
        private const val KEY_PLAYBACK_MODE = "playback_mode"
        private const val MAX_RECENT_SIZE = 40
    }
}