package com.example.timedmusicplayer.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkManager
import com.example.timedmusicplayer.data.AppDataContainer
import com.example.timedmusicplayer.emotion.MoodAnalysisRepository
import com.example.timedmusicplayer.lyrics.LyricsRepository
import com.example.timedmusicplayer.playback.PlaybackController
import com.example.timedmusicplayer.ui.cloud.CloudSourceViewModel
import com.example.timedmusicplayer.ui.main.MainViewModel
import com.example.timedmusicplayer.ui.player.PlayerViewModel
import com.example.timedmusicplayer.ui.theme.AppearanceRepository

class AppViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {

    private val dataContainer by lazy { AppDataContainer.get(application) }
    private val playbackController by lazy { PlaybackController.getInstance(application) }
    private val workManager by lazy { WorkManager.getInstance(application) }
    private val moodRepository by lazy { MoodAnalysisRepository(application, workManager) }
    private val lyricsRepository by lazy { LyricsRepository(application, workManager) }
    private val appearanceRepository by lazy { AppearanceRepository(application) }

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return when {
            modelClass.isAssignableFrom(MainViewModel::class.java) -> {
                MainViewModel(
                    application,
                    dataContainer.libraryRepository,
                    dataContainer.settingsRepository,
                    dataContainer.playbackHistoryRepository,
                    dataContainer.deleteLibraryContent,
                    playbackController,
                    moodRepository,
                    appearanceRepository
                ) as T
            }

            modelClass.isAssignableFrom(PlayerViewModel::class.java) -> {
                PlayerViewModel(application, playbackController, moodRepository, lyricsRepository) as T
            }

            modelClass.isAssignableFrom(CloudSourceViewModel::class.java) -> {
                CloudSourceViewModel(
                    application,
                    dataContainer.cloudSourceRepository,
                    dataContainer.libraryRepository,
                    playbackController
                ) as T
            }

            else -> error("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
