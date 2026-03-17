package com.example.timedmusicplayer.data.index

import android.content.Context
import com.example.timedmusicplayer.model.SourceType
import com.example.timedmusicplayer.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * 以目录 URI 为键的本地歌曲索引持久化缓存。
 */
class LocalTrackIndexStore(context: Context) {

    // 属性： prefs
    // 说明：持久层/数据源对象，负责本地读写与数据解析。
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 函数： getTracksByFolder
    // 说明：读取并返回当前数据或状态快照。
    fun getTracksByFolder(folderUri: String): List<Track> {
        if (folderUri.isBlank()) {
            return emptyList()
        }

    // 属性： raw
    // 说明：运行期状态变量，承载 raw 相关上下文信息。
        val raw = prefs.getString(keyForFolder(folderUri), "[]").orEmpty()
        return parseTracks(raw)
    }

    // 函数： replaceTracksForFolder
    // 说明：封装 replaceTracksForFolder 相关业务流程，负责参数校验、状态流转与异常兜底。
    fun replaceTracksForFolder(folderUri: String, tracks: List<Track>) {
        if (folderUri.isBlank()) {
            return
        }

    // 属性： array
    // 说明：运行期状态变量，承载 array 相关上下文信息。
        val array = JSONArray()
        tracks.forEach { track ->
    // 属性： item
    // 说明：运行期状态变量，承载 item 相关上下文信息。
            val item = JSONObject()
                .put("id", track.id)
                .put("title", track.title)
                .put("artist", track.artist)
                .put("durationMs", track.durationMs)
                .put("uri", track.uri)
            array.put(item)
        }

        prefs.edit().putString(keyForFolder(folderUri), array.toString()).apply()
    }

    // 函数： parseTracks
    // 说明：解析外部输入并转换为内部模型结构。
    private fun parseTracks(raw: String): List<Track> {
        return try {
    // 属性： array
    // 说明：运行期状态变量，承载 array 相关上下文信息。
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
    // 属性： item
    // 说明：运行期状态变量，承载 item 相关上下文信息。
                    val item = array.optJSONObject(i) ?: continue
    // 属性： id
    // 说明：运行期状态变量，承载 id 相关上下文信息。
                    val id = item.optString("id").trim()
    // 属性： title
    // 说明：运行期状态变量，承载 title 相关上下文信息。
                    val title = item.optString("title").trim()
    // 属性： artist
    // 说明：运行期状态变量，承载 artist 相关上下文信息。
                    val artist = item.optString("artist").trim()
    // 属性： uri
    // 说明：资源定位地址，指向本地文件或在线媒体流。
                    val uri = item.optString("uri").trim()
                    if (id.isBlank() || title.isBlank() || uri.isBlank()) {
                        continue
                    }
                    add(
                        Track(
                            id = id,
                            title = title,
                            artist = if (artist.isBlank()) DEFAULT_ARTIST else artist,
                            durationMs = item.optLong("durationMs", 0L).coerceAtLeast(0L),
                            sourceType = SourceType.LOCAL,
                            uri = uri
                        )
                    )
                }
            }.sortedBy { it.title.lowercase(Locale.getDefault()) }
        } catch (_: Exception) {
            emptyList()
        }
    }

// 函数： keyForFolder
// 说明：封装 keyForFolder 相关业务流程，负责参数校验、状态流转与异常兜底。
private fun keyForFolder(folderUri: String): String {
    // 属性： digest
    // 说明：运行期状态变量，承载 digest 相关上下文信息。
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(folderUri.toByteArray(Charsets.UTF_8))
    // 属性： hex
    // 说明：运行期状态变量，承载 hex 相关上下文信息。
        val hex = digest.joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
        return "index_$hex"
    }

    companion object {
        private const val PREFS_NAME = "local_track_index_store"
        private const val DEFAULT_ARTIST = "\u672C\u5730\u97F3\u4E50"
    }
}