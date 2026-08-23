package com.example.timedmusicplayer.lyrics

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.timedmusicplayer.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed interface LyricsGenerationResult {
    data class Success(val lyrics: List<LyricLine>) : LyricsGenerationResult
    data class Failure(val message: String) : LyricsGenerationResult
}

/** Coordinates persistent lyrics work and exposes domain results to ViewModels. */
class LyricsRepository(context: Context, private val workManager: WorkManager) {
    private val appContext = context.applicationContext

    suspend fun load(trackId: String): List<LyricLine> = withContext(Dispatchers.IO) {
        LyricFiles.read(appContext, trackId)
    }

    suspend fun generate(track: Track): LyricsGenerationResult {
        val request = OneTimeWorkRequestBuilder<GenerateLyricsWorker>()
            .setInputData(Data.Builder()
                .putString(GenerateLyricsWorker.KEY_TRACK_ID, track.id)
                .putString(GenerateLyricsWorker.KEY_TITLE, track.title)
                .putString(GenerateLyricsWorker.KEY_ARTIST, track.artist)
                .putString(GenerateLyricsWorker.KEY_ALBUM, track.album)
                .putLong(GenerateLyricsWorker.KEY_DURATION_SECONDS, track.durationMs / 1_000L)
                .build())
            .addTag(workName(track.id))
            .build()
        workManager.enqueueUniqueWork(workName(track.id), ExistingWorkPolicy.REPLACE, request)
        val info = workManager.getWorkInfoByIdFlow(request.id).filterNotNull().filter { it.state.isFinished }.first()
        return if (info.state == WorkInfo.State.SUCCEEDED) {
            LyricsGenerationResult.Success(load(track.id))
        } else {
            LyricsGenerationResult.Failure(info.outputData.getString(GenerateLyricsWorker.KEY_ERROR).orEmpty())
        }
    }

    private fun workName(trackId: String) = "lyrics:$trackId"
}
