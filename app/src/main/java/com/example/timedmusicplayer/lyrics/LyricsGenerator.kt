package com.example.timedmusicplayer.lyrics

import android.content.Context
import com.example.timedmusicplayer.network.ComputerServiceEndpoint
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Performs the lyrics lookup and persistence independently of WorkManager. */
class LyricsGenerator(context: Context) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

    fun generate(trackId: String, title: String, artist: String, album: String, durationSeconds: Long) {
        val lyrics = findOnlineLyrics(title, artist, album, durationSeconds) ?: error("未找到可用的同步歌词")
        LyricFiles.file(appContext, trackId).writeText(lyrics)
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
            executeJson(Request.Builder().url("${ComputerServiceEndpoint.baseUrl}/v1/lyrics/lookup?$query").get().build())
        }.getOrNull() ?: return null
        return lookup.optString("synced_lyrics").takeIf { lookup.optBoolean("found") && it.isNotBlank() }
    }
}
