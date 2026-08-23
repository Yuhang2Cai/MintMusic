package com.example.timedmusicplayer.emotion

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** WorkManager adapter; network and parsing details live in [MoodAnalyzer]. */
class AnalyzeMoodWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trackId = inputData.getString(KEY_TRACK_ID)
        runCatching {
            val output = MoodAnalysisProcessor(applicationContext).analyze(
                trackId,
                inputData.getString(KEY_URI) ?: error("找不到本地歌曲"),
                inputData.getString(KEY_TITLE).orEmpty().ifBlank { "music" },
                inputData.getString(KEY_MIME_TYPE).orEmpty().ifBlank { "audio/*" }
            )
            Result.success(workDataOf(
                KEY_MOODS to output.moods.joinToString(" · "),
                KEY_VALENCE to output.valence,
                KEY_AROUSAL to output.arousal
            ))
        }.getOrElse { error ->
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: "歌曲情绪分析失败")))
        }
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
    }
}
