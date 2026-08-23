package com.example.timedmusicplayer.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkManager
import com.example.timedmusicplayer.data.MusicRepository
import com.example.timedmusicplayer.emotion.MoodAnalysisStore
import com.example.timedmusicplayer.playback.PlaybackController
import com.example.timedmusicplayer.ui.cloud.CloudSourceViewModel
import com.example.timedmusicplayer.ui.main.MainViewModel
import com.example.timedmusicplayer.ui.player.PlayerViewModel

class AppViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {

    private val repository by lazy { MusicRepository.getInstance(application) }
    private val playbackController by lazy { PlaybackController.getInstance(application) }
    private val moodAnalysisStore by lazy { MoodAnalysisStore(application) }
    private val workManager by lazy { WorkManager.getInstance(application) }

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return when {
            modelClass.isAssignableFrom(MainViewModel::class.java) -> {
                MainViewModel(application, repository, playbackController, moodAnalysisStore) as T
            }

            modelClass.isAssignableFrom(PlayerViewModel::class.java) -> {
                PlayerViewModel(application, playbackController, moodAnalysisStore, workManager) as T
            }

            modelClass.isAssignableFrom(CloudSourceViewModel::class.java) -> {
                CloudSourceViewModel(application, repository, playbackController) as T
            }

            else -> error("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
