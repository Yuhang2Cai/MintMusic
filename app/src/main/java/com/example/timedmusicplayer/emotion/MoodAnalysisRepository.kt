package com.example.timedmusicplayer.emotion

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.timedmusicplayer.model.Track
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

sealed interface MoodTaskResult {
    data class Success(val moods: String, val valence: Double, val arousal: Double) : MoodTaskResult
    data class Failure(val message: String) : MoodTaskResult
}

/** Coordinates mood persistence and background analysis for presentation models. */
class MoodAnalysisRepository(context: Context, private val workManager: WorkManager) {
    private val store = MoodAnalysisStore(context)
    val states: StateFlow<Map<String, MoodAnalysisState>> = store.states
    val labels: List<String> = listOf("温暖", "浪漫", "治愈", "忧郁", "激昂", "恢弘")

    fun setLabel(trackId: String, label: String?) = store.setLabel(trackId, label)

    suspend fun analyze(track: Track): MoodTaskResult {
        store.markAnalyzing(track.id)
        val request = OneTimeWorkRequestBuilder<AnalyzeMoodWorker>()
            .setInputData(Data.Builder()
                .putString(AnalyzeMoodWorker.KEY_URI, track.uri)
                .putString(AnalyzeMoodWorker.KEY_TRACK_ID, track.id)
                .putString(AnalyzeMoodWorker.KEY_TITLE, track.title)
                .putString(AnalyzeMoodWorker.KEY_MIME_TYPE, track.mimeType)
                .build())
            .addTag("music-emotion:${track.id}")
            .build()
        workManager.enqueueUniqueWork("music-emotion:${track.id}", ExistingWorkPolicy.REPLACE, request)
        val info = workManager.getWorkInfoByIdFlow(request.id).filterNotNull().filter { it.state.isFinished }.first()
        return if (info.state == WorkInfo.State.SUCCEEDED) {
            MoodTaskResult.Success(
                info.outputData.getString(AnalyzeMoodWorker.KEY_MOODS).orEmpty(),
                info.outputData.getDouble(AnalyzeMoodWorker.KEY_VALENCE, Double.NaN),
                info.outputData.getDouble(AnalyzeMoodWorker.KEY_AROUSAL, Double.NaN)
            )
        } else MoodTaskResult.Failure(info.outputData.getString(AnalyzeMoodWorker.KEY_ERROR).orEmpty())
    }
}
