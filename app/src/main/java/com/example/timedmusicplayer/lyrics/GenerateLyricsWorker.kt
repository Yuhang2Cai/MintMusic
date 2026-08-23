package com.example.timedmusicplayer.lyrics

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.timedmusicplayer.network.ComputerServiceEndpoint
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class GenerateLyricsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return Result.failure()
        return runCatching {
            setProgress(workDataOf(KEY_STAGE to "searching_online", KEY_PROGRESS to 10))
            val onlineLyrics = findOnlineLyrics(
                title = inputData.getString(KEY_TITLE).orEmpty(),
                artist = inputData.getString(KEY_ARTIST).orEmpty(),
                album = inputData.getString(KEY_ALBUM).orEmpty(),
                durationSeconds = inputData.getLong(KEY_DURATION_SECONDS, 0L),
            )
            if (onlineLyrics != null) {
                LyricFiles.file(applicationContext, trackId).writeText(onlineLyrics)
                return@runCatching Result.success(workDataOf(KEY_TRACK_ID to trackId, KEY_SOURCE to "online"))
            }
            error("未找到可用的同步歌词")
        }.getOrElse { Result.failure(workDataOf(KEY_ERROR to (it.message ?: "歌词生成失败"))) }
    }

    private fun executeJson(request: Request): JSONObject = http.newCall(request).execute().use {
        val text = it.body?.string().orEmpty()
        if (!it.isSuccessful) error(JSONObject(text).optString("detail", "请求失败：${it.code}"))
        JSONObject(text)
    }

    private fun findOnlineLyrics(title: String, artist: String, album: String, durationSeconds: Long): String? {
        if (title.isBlank()) return null
        fun part(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        val query = buildList {
            add("title=${part(title)}")
            if (artist.isNotBlank()) add("artist=${part(artist)}")
            if (album.isNotBlank()) add("album=${part(album)}")
            if (durationSeconds > 0) add("duration=$durationSeconds")
        }.joinToString("&")
        val lookup = runCatching {
            executeJson(Request.Builder()
                .url("${ComputerServiceEndpoint.baseUrl}/v1/lyrics/lookup?$query")
                .get().build())
        }.getOrNull() ?: return null
        return lookup.optString("synced_lyrics").takeIf { lookup.optBoolean("found") && it.isNotBlank() }
    }

    companion object {
        const val KEY_TRACK_ID = "track_id"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_DURATION_SECONDS = "duration_seconds"
        const val KEY_STAGE = "stage"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_SOURCE = "source"
    }
}
