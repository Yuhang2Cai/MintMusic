package com.example.timedmusicplayer.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.example.timedmusicplayer.data.index.LocalTrackIndexStore
import com.example.timedmusicplayer.model.CloudSource
import com.example.timedmusicplayer.model.SourceType
import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.model.TrackFilter
import java.util.Locale

/**
 * 仓库层：合并本地与云端数据，并负责播放持久化。
 */
class MusicRepository private constructor(context: Context) {

    // 属性： appContext
    // 说明：运行期状态变量，承载 appContext 相关上下文信息。
    private val appContext = context.applicationContext
    // 属性： localDataSource
    // 说明：持久层/数据源对象，负责本地读写与数据解析。
    private val localDataSource = LocalMusicDataSource(appContext)
    // 属性： cloudDataSource
    // 说明：持久层/数据源对象，负责本地读写与数据解析。
    private val cloudDataSource = CloudSourceDataSource(appContext)
    // 属性： playbackStore
    // 说明：持久层/数据源对象，负责本地读写与数据解析。
    private val playbackStore = PlaybackStore(appContext)
    // 属性： localIndexStore
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
    private val localIndexStore = LocalTrackIndexStore(appContext)

    // 属性： repoPrefs
    // 说明：持久层/数据源对象，负责本地读写与数据解析。
    private val repoPrefs by lazy {
        appContext.getSharedPreferences(REPO_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // 属性： localCacheLock
    // 说明：运行期状态变量，承载 localCacheLock 相关上下文信息。
    private val localCacheLock = Any()
    // 属性： cachedLocalTracks
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
    private var cachedLocalTracks: List<Track> = emptyList()
    // 属性： cachedLocalFolderUri
    // 说明：资源定位地址，指向本地文件或在线媒体流。
    private var cachedLocalFolderUri: String = ""
    // 属性： cachedLocalAtMs
    // 说明：运行期状态变量，承载 cachedLocalAtMs 相关上下文信息。
    private var cachedLocalAtMs: Long = 0L
    // 属性： localCacheReady
    // 说明：运行期状态变量，承载 localCacheReady 相关上下文信息。
    private var localCacheReady: Boolean = false

    // 函数： saveLocalFolder
    // 说明：保存关键状态到持久层，保证下次启动可恢复。
    fun saveLocalFolder(uri: Uri) {
        localDataSource.saveFolderUri(uri)
        invalidateLocalCache()
    }

    // 函数： getLocalFolderUri
    // 说明：读取并返回当前数据或状态快照。
    fun getLocalFolderUri(): Uri? {
        return localDataSource.getFolderUri()
    }

    // 函数： getCloudSources
    // 说明：读取并返回当前数据或状态快照。
    fun getCloudSources(): List<CloudSource> {
        return cloudDataSource.getSources()
    }

    // 函数： addCloudSource
    // 说明：封装 addCloudSource 相关业务流程，负责参数校验、状态流转与异常兜底。
    fun addCloudSource(name: String, url: String): CloudSource {
        return cloudDataSource.addSource(name = name, url = url)
    }

    // 函数： renameCloudSource
    // 说明：封装 renameCloudSource 相关业务流程，负责参数校验、状态流转与异常兜底。
    fun renameCloudSource(sourceId: String, newName: String): Boolean {
        return cloudDataSource.updateSourceName(sourceId, newName)
    }

    // 函数： deleteCloudSource
    // 说明：封装 deleteCloudSource 相关业务流程，负责参数校验、状态流转与异常兜底。
    fun deleteCloudSource(sourceId: String): Boolean {
        return cloudDataSource.removeSource(sourceId)
    }

    // 函数： hasDuplicateCloudUrl
    // 说明：封装 hasDuplicateCloudUrl 相关业务流程，负责参数校验、状态流转与异常兜底。
    fun hasDuplicateCloudUrl(url: String, ignoreId: String? = null): Boolean {
        return cloudDataSource.hasDuplicateUrl(url = url, ignoreId = ignoreId)
    }

    // 函数： getTracks
    // 说明：读取并返回当前数据或状态快照。
    fun getTracks(filter: TrackFilter, forceRefresh: Boolean = false): List<Track> {
        return when (filter) {
            TrackFilter.LOCAL -> getLocalTracks(forceRefresh)
            TrackFilter.CLOUD -> getCloudTracks()
            TrackFilter.ALL -> {
    // 属性： merged
    // 说明：运行期状态变量，承载 merged 相关上下文信息。
                val merged = getLocalTracks(forceRefresh) + getCloudTracks()
                merged.sortedBy { it.title.lowercase(Locale.getDefault()) }
            }
        }
    }

    // 函数： saveLastPlayback
    // 说明：保存关键状态到持久层，保证下次启动可恢复。
    fun saveLastPlayback(trackId: String, positionMs: Long) {
        playbackStore.saveLastPlayback(trackId, positionMs)
    }

    // 函数： getLastPlayback
    // 说明：读取并返回当前数据或状态快照。
    fun getLastPlayback(): PlaybackStore.LastPlayback? {
        return playbackStore.getLastPlayback()
    }

    // 函数： savePlaybackMode
    // 说明：保存关键状态到持久层，保证下次启动可恢复。
    fun savePlaybackMode(modeValue: String) {
        playbackStore.savePlaybackMode(modeValue)
    }

    // 函数： getPlaybackMode
    // 说明：读取并返回当前数据或状态快照。
    fun getPlaybackMode(defaultValue: String): String {
        return playbackStore.getPlaybackMode(defaultValue)
    }

    // 函数： markPlayed
    // 说明：标记业务状态，用于统计或恢复流程。
    fun markPlayed(trackId: String) {
        playbackStore.addRecentTrack(trackId)
    }

    // 函数： getRecentTrackIds
    // 说明：读取并返回当前数据或状态快照。
    fun getRecentTrackIds(): List<String> {
        return playbackStore.getRecentTrackIds()
    }

// 函数： getLocalTracks
// 说明：读取并返回当前数据或状态快照。
private fun getLocalTracks(forceRefresh: Boolean): List<Track> {
    // 属性： currentFolder
    // 说明：运行期状态变量，承载 currentFolder 相关上下文信息。
        val currentFolder = localDataSource.getFolderUri()?.toString().orEmpty()
        if (currentFolder.isBlank()) {
            return emptyList()
        }

    // 属性： memoryTracks
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
        // 快速路径：优先使用短时内存缓存。
        val memoryTracks = getMemoryCachedLocalTracks(currentFolder, forceRefresh)
        if (memoryTracks != null) {
            return memoryTracks
        }

    // 属性： indexedTracks
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
        // 回退路径：使用该目录已持久化的索引缓存。
        val indexedTracks = loadIndexedLocalTracks(currentFolder)
    // 属性： indexFresh
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val indexFresh = isDiskIndexFresh(currentFolder)

        if (!forceRefresh && indexedTracks.isNotEmpty() && indexFresh) {
            updateMemoryCache(currentFolder, indexedTracks)
            return indexedTracks
        }

    // 属性： scannedTracks
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
        // 慢速路径：执行完整 SAF 目录扫描。
        val scannedTracks = localDataSource.loadTracks()
    // 属性： result
    // 说明：运行期状态变量，承载 result 相关上下文信息。
        val result = if (scannedTracks.isNotEmpty()) {
            persistLocalIndex(currentFolder, scannedTracks)
            scannedTracks
        } else {
            if (indexedTracks.isNotEmpty() && !forceRefresh) {
                indexedTracks
            } else {
                persistLocalIndex(currentFolder, emptyList())
                emptyList()
            }
        }

        updateMemoryCache(currentFolder, result)
        return result
    }

// 函数： getMemoryCachedLocalTracks
// 说明：读取并返回当前数据或状态快照。
private fun getMemoryCachedLocalTracks(currentFolder: String, forceRefresh: Boolean): List<Track>? {
        synchronized(localCacheLock) {
    // 属性： now
    // 说明：运行期状态变量，承载 now 相关上下文信息。
            val now = SystemClock.elapsedRealtime()
    // 属性： isFolderChanged
    // 说明：布尔标记位，用于控制分支逻辑与 UI 可用性。
            val isFolderChanged = currentFolder != cachedLocalFolderUri
    // 属性： isCacheExpired
    // 说明：布尔标记位，用于控制分支逻辑与 UI 可用性。
            val isCacheExpired = now - cachedLocalAtMs > MEMORY_CACHE_TTL_MS

            if (!forceRefresh && localCacheReady && !isFolderChanged && !isCacheExpired) {
                return cachedLocalTracks
            }
        }
        return null
    }

    // 函数： loadIndexedLocalTracks
    // 说明：加载并整理数据，必要时命中缓存或触发刷新。
    private fun loadIndexedLocalTracks(folderUri: String): List<Track> {
        return localIndexStore.getTracksByFolder(folderUri)
    }

    // 函数： persistLocalIndex
    // 说明：封装 persistLocalIndex 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun persistLocalIndex(folderUri: String, tracks: List<Track>) {
    // 属性： indexedAt
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        // 记录索引时间戳，用于判断磁盘缓存新鲜度。
        val indexedAt = System.currentTimeMillis()
        localIndexStore.replaceTracksForFolder(folderUri, tracks)
        repoPrefs.edit()
            .putString(KEY_LAST_INDEX_FOLDER_URI, folderUri)
            .putLong(KEY_LAST_INDEXED_AT_MS, indexedAt)
            .apply()
    }

// 函数： isDiskIndexFresh
// 说明：封装 isDiskIndexFresh 相关业务流程，负责参数校验、状态流转与异常兜底。
private fun isDiskIndexFresh(folderUri: String): Boolean {
    // 属性： indexedFolder
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val indexedFolder = repoPrefs.getString(KEY_LAST_INDEX_FOLDER_URI, "").orEmpty()
    // 属性： indexedAt
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val indexedAt = repoPrefs.getLong(KEY_LAST_INDEXED_AT_MS, 0L)
        if (indexedFolder != folderUri || indexedAt <= 0L) {
            return false
        }
        return System.currentTimeMillis() - indexedAt <= DISK_INDEX_TTL_MS
    }

    // 函数： updateMemoryCache
    // 说明：更新状态并同步到相关依赖组件或持久层。
    private fun updateMemoryCache(folderUri: String, tracks: List<Track>) {
        synchronized(localCacheLock) {
            cachedLocalTracks = tracks
            cachedLocalFolderUri = folderUri
            cachedLocalAtMs = SystemClock.elapsedRealtime()
            localCacheReady = true
        }
    }

    // 函数： getCloudTracks
    // 说明：读取并返回当前数据或状态快照。
    private fun getCloudTracks(): List<Track> {
        return cloudDataSource.getSources().map { source ->
            Track(
                id = "cloud:${source.id}",
                title = source.name,
                artist = "\u5728\u7EBF\u97F3\u6E90",
                durationMs = 0L,
                sourceType = SourceType.CLOUD,
                uri = source.url
            )
        }.sortedBy { it.title.lowercase(Locale.getDefault()) }
    }

    // 函数： invalidateLocalCache
    // 说明：封装 invalidateLocalCache 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun invalidateLocalCache() {
        synchronized(localCacheLock) {
            cachedLocalTracks = emptyList()
            cachedLocalFolderUri = ""
            cachedLocalAtMs = 0L
            localCacheReady = false
        }
    }

    companion object {
        private const val MEMORY_CACHE_TTL_MS = 45_000L
        private const val DISK_INDEX_TTL_MS = 8 * 60_000L

        private const val REPO_PREFS_NAME = "music_repo_cache"
        private const val KEY_LAST_INDEX_FOLDER_URI = "last_index_folder_uri"
        private const val KEY_LAST_INDEXED_AT_MS = "last_indexed_at_ms"

        @Volatile
        private var instance: MusicRepository? = null

        // 函数： getInstance
        // 说明：读取并返回当前数据或状态快照。
        fun getInstance(context: Context): MusicRepository {
            return instance ?: synchronized(this) {
                instance ?: MusicRepository(context).also { instance = it }
            }
        }
    }
}