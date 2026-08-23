package com.example.timedmusicplayer.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.timedmusicplayer.data.db.LibraryFolderEntity
import com.example.timedmusicplayer.data.db.MintDatabase
import com.example.timedmusicplayer.data.db.TrackEntity
import com.example.timedmusicplayer.data.db.toEntity
import com.example.timedmusicplayer.model.SourceType
import com.example.timedmusicplayer.model.Track
import java.util.ArrayDeque
import java.util.Locale

data class ScanResult(val total: Int, val added: Int, val changed: Int, val unchanged: Int, val removed: Int, val durationMs: Long)

class LibraryScanner(private val context: Context, private val database: MintDatabase) {
    @Synchronized
    fun scan(folder: Uri, forceMetadata: Boolean = false): ScanResult {
        val started = System.currentTimeMillis()
        val root = runCatching { DocumentFile.fromTreeUri(context, folder) }.getOrNull()
            ?: return ScanResult(0, 0, 0, 0, 0, System.currentTimeMillis() - started)
        val isAccessibleDirectory = runCatching { root.exists() && root.isDirectory }.getOrDefault(false)
        if (!isAccessibleDirectory) {
            return ScanResult(0, 0, 0, 0, 0, System.currentTimeMillis() - started)
        }

        val folderKey = folder.toString()
        val old = database.tracks().getByFolder(folderKey).associateBy { it.mediaUri }
        val generation = System.currentTimeMillis()
        val batch = ArrayList<TrackEntity>(100)
        val seen = HashSet<String>()
        val queue = ArrayDeque<DocumentFile>().apply { add(root) }
        var added = 0
        var changed = 0
        var unchanged = 0
        var total = 0

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (child in runCatching { current.listFiles() }.getOrElse { emptyArray() }) {
                if (child.isDirectory) {
                    queue.add(child)
                    continue
                }
                if (!child.isFile || !isAudio(child)) continue
                total++
                val uri = child.uri.toString()
                seen += uri
                val size = child.length().coerceAtLeast(0L)
                val modified = child.lastModified().coerceAtLeast(0L)
                val previous = old[uri]
                val hasReliableSignature = size > 0L || modified > 0L
                val signatureMatches = previous != null && hasReliableSignature &&
                    previous.sizeBytes == size && previous.modifiedAtMs == modified
                val entity = if (!forceMetadata && signatureMatches) {
                    unchanged++
                    previous.copy(scanGeneration = generation)
                } else if (!forceMetadata && previous != null && !hasReliableSignature) {
                    unchanged++
                    previous.copy(scanGeneration = generation)
                } else {
                    if (previous == null) added++ else changed++
                    readMetadata(child, folderKey, size, modified).toEntity(generation)
                }
                batch += entity
                if (batch.size == 100) { database.tracks().upsertAll(batch); batch.clear() }
            }
        }
        if (batch.isNotEmpty()) database.tracks().upsertAll(batch)
        val removed = old.keys.count { it !in seen }
        database.tracks().deleteNotSeen(folderKey, generation)
        database.folders().upsert(LibraryFolderEntity(folderKey, root.name ?: "Music", System.currentTimeMillis(), generation))
        return ScanResult(total, added, changed, unchanged, removed, System.currentTimeMillis() - started)
    }

    private fun readMetadata(file: DocumentFile, folder: String, size: Long, modified: Long): Track {
        val rawName = file.name ?: file.uri.lastPathSegment ?: file.uri.toString()
        val fallbackTitle = rawName.substringBeforeLast('.').ifBlank { rawName }
        var title = fallbackTitle
        var artist = "本地音乐"
        var album = ""
        var duration = 0L
        val retriever = MediaMetadataRetriever()
        runCatching {
            retriever.setDataSource(context, file.uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: fallbackTitle
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: artist
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
        runCatching { retriever.release() }
        return Track("local:${file.uri}", title, artist, duration, SourceType.LOCAL, file.uri.toString(), album = album,
            folderUri = folder, sizeBytes = size, modifiedAtMs = modified, mimeType = file.type)
    }

    private fun isAudio(file: DocumentFile): Boolean {
        if (file.type.orEmpty().startsWith("audio/")) return true
        val name = file.name?.lowercase(Locale.ROOT) ?: return false
        return EXTENSIONS.any(name::endsWith)
    }

    companion object { private val EXTENSIONS = setOf(".mp3", ".wav", ".flac", ".aac", ".m4a", ".ogg", ".opus", ".amr", ".mid", ".midi") }
}
