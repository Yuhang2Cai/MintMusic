package com.example.timedmusicplayer.lyrics

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** WorkManager adapter; generation details live in [LyricsGenerator]. */
class GenerateLyricsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return@withContext Result.failure()
        runCatching {
            setProgress(workDataOf(KEY_STAGE to "searching_online", KEY_PROGRESS to 10))
            LyricsGenerator(applicationContext).generate(
                trackId,
                inputData.getString(KEY_TITLE).orEmpty(),
                inputData.getString(KEY_ARTIST).orEmpty(),
                inputData.getString(KEY_ALBUM).orEmpty(),
                inputData.getLong(KEY_DURATION_SECONDS, 0L)
            )
            Result.success(workDataOf(KEY_TRACK_ID to trackId, KEY_SOURCE to "online"))
        }.getOrElse { Result.failure(workDataOf(KEY_ERROR to (it.message ?: "歌词生成失败"))) }
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
