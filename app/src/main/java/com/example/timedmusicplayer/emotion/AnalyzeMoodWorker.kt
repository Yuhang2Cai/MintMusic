package com.example.timedmusicplayer.emotion

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.timedmusicplayer.network.ComputerServiceEndpoint
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Uploads one local song to the self-hosted Music2Emo endpoint for this run only. */
class AnalyzeMoodWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val sourceUri = inputData.getString(KEY_URI) ?: return failure("找不到本地歌曲")
        val sourceName = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "music" }
        val mimeType = inputData.getString(KEY_MIME_TYPE).orEmpty().ifBlank { "audio/*" }
        val uploadExtension = uploadExtensionFor(mimeType)
        var upload: File? = null
        return try {
            val localUpload = File.createTempFile("music2emo-", uploadExtension, applicationContext.cacheDir)
            upload = localUpload
            applicationContext.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
                localUpload.outputStream().use { output -> input.copyTo(output) }
            } ?: return failure("无法读取本地歌曲")
            if (localUpload.length() > MAX_UPLOAD_BYTES) return failure("音频文件不能超过 80 MB")
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    "$sourceName$uploadExtension",
                    localUpload.asRequestBody(mimeType.toMediaTypeOrNull())
                ).build()
            val request = Request.Builder()
                .url("${ComputerServiceEndpoint.baseUrl}/v1/music-emotions")
                .post(body)
                .build()
            val response = http.newCall(request).execute().use { reply ->
                val text = reply.body?.string().orEmpty()
                if (!reply.isSuccessful) {
                    val detail = runCatching { JSONObject(text).optString("detail") }.getOrDefault("")
                    error(detail.ifBlank { "服务请求失败：${reply.code}" })
                }
                JSONObject(text)
            }
            val moods = response.optJSONArray("moods")?.let { list ->
                List(list.length()) { index -> list.optString(index) }.filter { it.isNotBlank() }
            }.orEmpty()
            inputData.getString(KEY_TRACK_ID)?.let { MoodAnalysisStore(applicationContext, observeChanges = false).markCompleted(it, moods) }
            Result.success(workDataOf(
                KEY_MOODS to moods.joinToString(" · "),
                KEY_VALENCE to response.optDouble("valence", Double.NaN),
                KEY_AROUSAL to response.optDouble("arousal", Double.NaN),
            ))
        } catch (error: Exception) {
            inputData.getString(KEY_TRACK_ID)?.let { MoodAnalysisStore(applicationContext, observeChanges = false).markFailed(it) }
            failure(error.message ?: "歌曲情绪分析失败")
        } finally {
            upload?.delete()
        }
    }

    private fun failure(message: String) = Result.failure(workDataOf(KEY_ERROR to message))

    private fun uploadExtensionFor(mimeType: String): String = when (mimeType.substringBefore(';').lowercase()) {
        "audio/mpeg", "audio/mp3" -> ".mp3"
        "audio/mp4", "audio/x-m4a" -> ".m4a"
        "audio/flac", "audio/x-flac" -> ".flac"
        "audio/wav", "audio/x-wav", "audio/wave" -> ".wav"
        "audio/ogg" -> ".ogg"
        "audio/aac" -> ".aac"
        else -> ".audio"
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_TRACK_ID = "track_id"
        const val KEY_TITLE = "title"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_MOODS = "moods"
        const val KEY_VALENCE = "valence"
        const val KEY_AROUSAL = "arousal"
        const val KEY_ERROR = "error"
        private const val MAX_UPLOAD_BYTES = 80L * 1024L * 1024L
    }
}
