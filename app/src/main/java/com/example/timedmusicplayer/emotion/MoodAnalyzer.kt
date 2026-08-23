package com.example.timedmusicplayer.emotion

import android.content.Context
import android.net.Uri
import com.example.timedmusicplayer.network.ComputerServiceEndpoint
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

data class MoodAnalysisOutput(val moods: List<String>, val valence: Double, val arousal: Double)

/** Uploads and parses one local track independently of WorkManager. */
class MoodAnalyzer(context: Context) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    fun analyze(sourceUri: String, sourceName: String, mimeType: String): MoodAnalysisOutput {
        val extension = uploadExtensionFor(mimeType)
        val upload = File.createTempFile("music2emo-", extension, appContext.cacheDir)
        try {
            appContext.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
                upload.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取本地歌曲")
            if (upload.length() > MAX_UPLOAD_BYTES) error("音频文件不能超过 80 MB")
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("audio", "$sourceName$extension", upload.asRequestBody(mimeType.toMediaTypeOrNull())).build()
            val response = http.newCall(Request.Builder().url("${ComputerServiceEndpoint.baseUrl}/v1/music-emotions").post(body).build()).execute().use { reply ->
                val text = reply.body?.string().orEmpty()
                if (!reply.isSuccessful) {
                    val detail = runCatching { JSONObject(text).optString("detail") }.getOrDefault("")
                    error(detail.ifBlank { "服务请求失败：${reply.code}" })
                }
                JSONObject(text)
            }
            val moods = response.optJSONArray("moods")?.let { list ->
                List(list.length()) { index -> list.optString(index) }.filter(String::isNotBlank)
            }.orEmpty()
            return MoodAnalysisOutput(moods, response.optDouble("valence", Double.NaN), response.optDouble("arousal", Double.NaN))
        } finally {
            upload.delete()
        }
    }

    private fun uploadExtensionFor(mimeType: String): String = when (mimeType.substringBefore(';').lowercase()) {
        "audio/mpeg", "audio/mp3" -> ".mp3"
        "audio/mp4", "audio/x-m4a" -> ".m4a"
        "audio/flac", "audio/x-flac" -> ".flac"
        "audio/wav", "audio/x-wav", "audio/wave" -> ".wav"
        "audio/ogg" -> ".ogg"
        "audio/aac" -> ".aac"
        else -> ".audio"
    }

    private companion object { const val MAX_UPLOAD_BYTES = 80L * 1024L * 1024L }
}
