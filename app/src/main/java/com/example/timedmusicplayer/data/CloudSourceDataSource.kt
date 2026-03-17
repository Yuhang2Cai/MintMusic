package com.example.timedmusicplayer.data

import android.content.Context
import com.example.timedmusicplayer.model.CloudSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 云端流媒体数据源，包含向后兼容迁移逻辑。
 */
class CloudSourceDataSource(private val context: Context) {

    // 函数： getSources
    // 说明：读取并返回当前数据或状态快照。
    fun getSources(): List<CloudSource> {
        migrateLegacyIfNeeded()
    // 属性： raw
    // 说明：运行期状态变量，承载 raw 相关上下文信息。
        val raw = prefs.getString(KEY_CLOUD_JSON, "[]").orEmpty()
        return parse(raw)
    }

    // 函数： addSource
    // 说明：封装 addSource 相关业务流程，负责参数校验、状态流转与异常兜底。
    fun addSource(name: String, url: String): CloudSource {
    // 属性： source
    // 说明：运行期状态变量，承载 source 相关上下文信息。
        val source = CloudSource(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            url = url.trim()
        )
    // 属性： updated
    // 说明：运行期状态变量，承载 updated 相关上下文信息。
        val updated = getSources().toMutableList().apply { add(source) }
        save(updated)
        return source
    }

    // 函数： updateSourceName
    // 说明：更新状态并同步到相关依赖组件或持久层。
    fun updateSourceName(id: String, newName: String): Boolean {
    // 属性： items
    // 说明：运行期状态变量，承载 items 相关上下文信息。
        val items = getSources().toMutableList()
    // 属性： index
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val index = items.indexOfFirst { it.id == id }
        if (index == -1) {
            return false
        }

        items[index] = items[index].copy(name = newName.trim())
        save(items)
        return true
    }

    // 函数： removeSource
    // 说明：封装 removeSource 相关业务流程，负责参数校验、状态流转与异常兜底。
    fun removeSource(id: String): Boolean {
    // 属性： items
    // 说明：运行期状态变量，承载 items 相关上下文信息。
        val items = getSources().toMutableList()
    // 属性： removed
    // 说明：运行期状态变量，承载 removed 相关上下文信息。
        val removed = items.removeAll { it.id == id }
        if (removed) {
            save(items)
        }
        return removed
    }

    // 函数： hasDuplicateUrl
    // 说明：封装 hasDuplicateUrl 相关业务流程，负责参数校验、状态流转与异常兜底。
    fun hasDuplicateUrl(url: String, ignoreId: String? = null): Boolean {
    // 属性： compare
    // 说明：运行期状态变量，承载 compare 相关上下文信息。
        val compare = url.trim()
        return getSources().any { it.id != ignoreId && it.url.equals(compare, ignoreCase = true) }
    }

    // 函数： parse
    // 说明：解析外部输入并转换为内部模型结构。
    private fun parse(raw: String): List<CloudSource> {
        return try {
    // 属性： array
    // 说明：运行期状态变量，承载 array 相关上下文信息。
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
    // 属性： obj
    // 说明：运行期状态变量，承载 obj 相关上下文信息。
                    val obj = array.optJSONObject(i) ?: continue
    // 属性： id
    // 说明：运行期状态变量，承载 id 相关上下文信息。
                    val id = obj.optString("id").trim().ifBlank { UUID.randomUUID().toString() }
    // 属性： name
    // 说明：运行期状态变量，承载 name 相关上下文信息。
                    val name = obj.optString("name").trim()
    // 属性： url
    // 说明：资源定位地址，指向本地文件或在线媒体流。
                    val url = obj.optString("url").trim()
                    if (url.isBlank()) continue
                    add(
                        CloudSource(
                            id = id,
                            name = if (name.isBlank()) deriveName(url) else name,
                            url = url
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // 函数： save
    // 说明：保存关键状态到持久层，保证下次启动可恢复。
    private fun save(items: List<CloudSource>) {
    // 属性： array
    // 说明：运行期状态变量，承载 array 相关上下文信息。
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("url", item.url)
                }
            )
        }
        prefs.edit().putString(KEY_CLOUD_JSON, array.toString()).apply()
    }

// 函数： migrateLegacyIfNeeded
// 说明：封装 migrateLegacyIfNeeded 相关业务流程，负责参数校验、状态流转与异常兜底。
private fun migrateLegacyIfNeeded() {
        if (prefs.contains(KEY_CLOUD_JSON)) {
            return
        }

    // 属性： legacyPrefs
    // 说明：持久层/数据源对象，负责本地读写与数据解析。
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
    // 属性： legacyJson
    // 说明：运行期状态变量，承载 legacyJson 相关上下文信息。
        val legacyJson = legacyPrefs.getString(LEGACY_STREAM_JSON, null)
        if (!legacyJson.isNullOrBlank()) {
    // 属性： migrated
    // 说明：运行期状态变量，承载 migrated 相关上下文信息。
            val migrated = try {
    // 属性： arr
    // 说明：运行期状态变量，承载 arr 相关上下文信息。
                val arr = JSONArray(legacyJson)
                buildList {
                    for (i in 0 until arr.length()) {
    // 属性： item
    // 说明：运行期状态变量，承载 item 相关上下文信息。
                        val item = arr.optJSONObject(i) ?: continue
    // 属性： url
    // 说明：资源定位地址，指向本地文件或在线媒体流。
                        val url = item.optString("url").trim()
                        if (url.isBlank()) continue
    // 属性： name
    // 说明：运行期状态变量，承载 name 相关上下文信息。
                        val name = item.optString("name").trim().ifBlank { deriveName(url) }
                        add(
                            CloudSource(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                url = url
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }

            save(migrated)
            return
        }

    // 属性： legacyLines
    // 说明：运行期状态变量，承载 legacyLines 相关上下文信息。
        val legacyLines = legacyPrefs.getString(LEGACY_STREAM_LINES, "").orEmpty()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .map {
                CloudSource(
                    id = UUID.randomUUID().toString(),
                    name = deriveName(it),
                    url = it
                )
            }

        save(legacyLines)
    }

    // 函数： deriveName
    // 说明：封装 deriveName 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun deriveName(url: String): String {
    // 属性： host
    // 说明：运行期状态变量，承载 host 相关上下文信息。
        val host = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
        return when {
            host.isNotBlank() -> host
            url.length <= 30 -> url
            else -> "${url.take(30)}..."
        }
    }

    // 属性： prefs
    // 说明：持久层/数据源对象，负责本地读写与数据解析。
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREFS_NAME = "cloud_source_prefs"
        private const val KEY_CLOUD_JSON = "cloud_sources_json"

        private const val LEGACY_PREFS = "stream_list_prefs"
        private const val LEGACY_STREAM_JSON = "streams_json"
        private const val LEGACY_STREAM_LINES = "stream_list"
    }
}