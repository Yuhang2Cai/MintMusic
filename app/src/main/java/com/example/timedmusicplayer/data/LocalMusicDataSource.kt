package com.example.timedmusicplayer.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.timedmusicplayer.domain.model.SourceType
import com.example.timedmusicplayer.domain.model.Track
import java.util.ArrayDeque
import java.util.Locale

/**
 * 本地数据源：递归扫描用户选择的本地目录。
 */
class LocalMusicDataSource(private val context: Context) {

    // 函数： saveFolderUri
    // 说明：保存关键状态到持久层，保证下次启动可恢复。
    fun saveFolderUri(uri: Uri) {
        prefs.edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
    }

    // 函数： getFolderUri
    // 说明：读取并返回当前数据或状态快照。
    fun getFolderUri(): Uri? {
    // 属性： raw
    // 说明：运行期状态变量，承载 raw 相关上下文信息。
        val raw = prefs.getString(KEY_FOLDER_URI, null).orEmpty()
        return if (raw.isBlank()) null else Uri.parse(raw)
    }

// 函数： loadTracks
// 说明：加载并整理数据，必要时命中缓存或触发刷新。
fun loadTracks(): List<Track> {
    // 属性： folderUri
    // 说明：资源定位地址，指向本地文件或在线媒体流。
        val folderUri = getFolderUri() ?: return emptyList()
    // 属性： root
    // 说明：运行期状态变量，承载 root 相关上下文信息。
        val root = DocumentFile.fromTreeUri(context, folderUri)
        if (root == null || !root.exists() || !root.isDirectory) {
            return emptyList()
        }

    // 属性： tracks
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
        val tracks = mutableListOf<Track>()
    // 属性： queue
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
        val queue = ArrayDeque<DocumentFile>()
        queue.add(root)

        while (queue.isNotEmpty()) {
    // 属性： current
    // 说明：运行期状态变量，承载 current 相关上下文信息。
            val current = queue.removeFirst()
    // 属性： children
    // 说明：运行期状态变量，承载 children 相关上下文信息。
            val children = runCatching { current.listFiles() }.getOrElse { emptyArray() }
            for (child in children) {
                when {
                    child.isDirectory -> queue.add(child)
                    child.isFile && isAudioFile(child) -> {
    // 属性： uri
    // 说明：资源定位地址，指向本地文件或在线媒体流。
                        val uri = child.uri
    // 属性： rawName
    // 说明：运行期状态变量，承载 rawName 相关上下文信息。
                        val rawName = child.name ?: uri.lastPathSegment ?: uri.toString()
    // 属性： title
    // 说明：运行期状态变量，承载 title 相关上下文信息。
                        val title = rawName.substringBeforeLast('.').ifBlank { rawName }
                        tracks.add(
                            Track(
                                id = "local:${uri}",
                                title = title,
                                artist = "\u672C\u5730\u97F3\u4E50",
                                durationMs = 0L,
                                sourceType = SourceType.LOCAL,
                                uri = uri.toString()
                            )
                        )
                    }
                }
            }
        }

        return tracks.sortedBy { it.title.lowercase(Locale.getDefault()) }
    }

    // 函数： isAudioFile
    // 说明：封装 isAudioFile 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun isAudioFile(file: DocumentFile): Boolean {
    // 属性： mime
    // 说明：运行期状态变量，承载 mime 相关上下文信息。
        val mime = file.type.orEmpty()
        if (mime.startsWith("audio/")) {
            return true
        }

    // 属性： name
    // 说明：运行期状态变量，承载 name 相关上下文信息。
        val name = file.name?.lowercase(Locale.getDefault()) ?: return false
        return SUPPORTED_EXTENSIONS.any { name.endsWith(it) }
    }

    // 属性： prefs
    // 说明：持久层/数据源对象，负责本地读写与数据解析。
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREFS_NAME = "local_music_prefs"
        private const val KEY_FOLDER_URI = "selected_folder_uri"

        private val SUPPORTED_EXTENSIONS = setOf(
            ".mp3", ".wav", ".flac", ".aac", ".m4a", ".ogg", ".opus", ".amr", ".mid", ".midi"
        )
    }
}