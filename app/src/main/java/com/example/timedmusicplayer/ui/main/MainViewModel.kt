package com.example.timedmusicplayer.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.data.MusicRepository
import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.model.TrackFilter
import com.example.timedmusicplayer.playback.PlaybackController
import com.example.timedmusicplayer.playback.PlaybackSnapshot
import com.example.timedmusicplayer.ui.common.PlaybackUiFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(
    application: Application,
    private val repository: MusicRepository,
    private val playbackController: PlaybackController
) : AndroidViewModel(application) {

    private val app = application.applicationContext
    private val uiStateValue = MutableStateFlow(
        MainUiState(libraryCountText = app.getString(R.string.library_loading))
    )
    private val eventChannel = Channel<MainEvent>(Channel.BUFFERED)

    private var loadJob: Job? = null
    private val filterValue = MutableStateFlow(TrackFilter.ALL)

    val uiState: StateFlow<MainUiState> = uiStateValue.asStateFlow()
    val events = eventChannel.receiveAsFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingData = filterValue.flatMapLatest(repository::pagingTracks).cachedIn(viewModelScope)

    init {
        val restoredFilter = runCatching {
            TrackFilter.valueOf(repository.getSelectedFilter(TrackFilter.ALL.name))
        }.getOrDefault(TrackFilter.ALL)
        filterValue.value = restoredFilter
        uiStateValue.value = uiStateValue.value.copy(activeFilter = restoredFilter)
        viewModelScope.launch {
            playbackController.snapshot.collect { snapshot ->
                uiStateValue.value = uiStateValue.value.copy(
                    miniPlayer = snapshot?.takeIf { it.queue.isNotEmpty() }?.toMiniPlayerUiState()
                )
            }
        }
        loadLibrary(forceRefresh = false)
    }

    fun onStart() {
        playbackController.connect()
    }

    fun onStop() {
        playbackController.disconnect()
    }

    fun onResume() {
        loadLibrary(forceRefresh = false)
    }

    fun onFilterSelected(filter: TrackFilter) {
        if (uiStateValue.value.activeFilter == filter) {
            return
        }
        uiStateValue.value = uiStateValue.value.copy(activeFilter = filter)
        filterValue.value = filter
        repository.saveSelectedFilter(filter.name)
        loadLibrary(forceRefresh = false)
    }

    fun onSelectFolderClicked() {
        viewModelScope.launch {
            eventChannel.send(MainEvent.OpenFolderPicker(repository.getLocalFolderUri()))
        }
    }

    fun onLocalFolderSelected(uri: Uri?) {
        if (uri == null) {
            return
        }
        repository.saveLocalFolder(uri)
        loadLibrary(forceRefresh = true)
    }

    fun onManageCloudClicked() {
        viewModelScope.launch {
            eventChannel.send(MainEvent.OpenCloudSourceScreen)
        }
    }

    fun onResumeLastClicked() {
        viewModelScope.launch {
            val lastPlayback = repository.getLastPlayback()
            if (lastPlayback == null) {
                eventChannel.send(MainEvent.ShowMessage(app.getString(R.string.no_resume_item)))
                return@launch
            }

            val allTracks = withContext(Dispatchers.IO) {
                repository.getTracks(TrackFilter.ALL, forceRefresh = false)
            }
            if (allTracks.isEmpty()) {
                eventChannel.send(MainEvent.ShowMessage(app.getString(R.string.library_empty_tip)))
                return@launch
            }

            val index = allTracks.indexOfFirst { it.id == lastPlayback.trackId }
            if (index == -1) {
                eventChannel.send(MainEvent.ShowMessage(app.getString(R.string.resume_item_missing)))
                return@launch
            }

            playbackController.playQueue(
                tracks = allTracks,
                startIndex = index,
                forcePlay = true,
                startPositionMs = lastPlayback.positionMs
            )
            eventChannel.send(MainEvent.OpenPlayerScreen)
        }
    }

    fun onTrackSelected(track: Track) {
        viewModelScope.launch {
            val tracks = withContext(Dispatchers.IO) {
                repository.getTracks(uiStateValue.value.activeFilter, forceRefresh = false)
            }
            val index = tracks.indexOfFirst { it.id == track.id }
            if (index == -1) return@launch
            playbackController.playQueue(tracks, index, forcePlay = true)
            eventChannel.send(MainEvent.OpenPlayerScreen)
        }
    }

    fun onDeleteTracksConfirmed(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.deleteTracks(tracks) }
            loadLibrary(forceRefresh = false)
            val message = if (result.failed == 0) {
                app.getString(R.string.delete_tracks_success, result.deleted)
            } else {
                app.getString(R.string.delete_tracks_partial, result.deleted, result.failed)
            }
            eventChannel.send(MainEvent.TracksDeleted(message))
        }
    }

    fun onMiniPlayerClicked() {
        if (uiStateValue.value.miniPlayer == null) {
            return
        }

        viewModelScope.launch {
            eventChannel.send(MainEvent.OpenPlayerScreen)
        }
    }

    fun onMiniPreviousClicked() {
        playbackController.playPrevious()
    }

    fun onMiniPlayPauseClicked() {
        playbackController.togglePlayPause()
    }

    fun onMiniNextClicked() {
        playbackController.playNext()
    }

    private fun loadLibrary(forceRefresh: Boolean) {
        loadJob?.cancel()
        val currentState = uiStateValue.value
        if (forceRefresh) {
            uiStateValue.value = currentState.copy(
                libraryCountText = app.getString(R.string.library_loading)
            )
        }

        loadJob = viewModelScope.launch {
            val filter = uiStateValue.value.activeFilter
            val count = withContext(Dispatchers.IO) {
                repository.refreshLibrary(forceRefresh)
                repository.trackCount(filter)
            }
            uiStateValue.value = uiStateValue.value.copy(
                libraryCountText = app.getString(R.string.library_count, count),
                showEmpty = count == 0
            )
        }
    }

    private fun PlaybackSnapshot.toMiniPlayerUiState(): MiniPlayerUiState {
        val current = currentTrack ?: return MiniPlayerUiState(
            track = null,
            title = app.getString(R.string.no_resume_item),
            status = app.getString(R.string.ready_to_play),
            isPlaying = false,
            canSkip = false,
            progressMax = 1,
            progress = 0,
            bufferedProgress = 0,
            currentTimeText = PlaybackUiFormatter.formatTime(0L),
            totalTimeText = "--:--"
        )
        val position = positionMs.coerceAtLeast(0L)
        val duration = durationMs.coerceAtLeast(0L)
        val buffered = bufferedPositionMs.coerceAtLeast(position)
        val progressMax = PlaybackUiFormatter.resolveProgressMax(duration, position, current.isStream)
        val safeMax = progressMax.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        val safeProgress = position.coerceAtMost(safeMax.toLong()).toInt()
        val safeBuffered = buffered.coerceIn(safeProgress.toLong(), safeMax.toLong()).toInt()

        return MiniPlayerUiState(
            track = current,
            title = current.title,
            status = PlaybackUiFormatter.statusText(app, state, errorMessage),
            isPlaying = isPlaying,
            canSkip = queue.size > 1,
            progressMax = safeMax,
            progress = safeProgress,
            bufferedProgress = safeBuffered,
            currentTimeText = PlaybackUiFormatter.formatTime(position),
            totalTimeText = if (duration > 0L) {
                PlaybackUiFormatter.formatTime(duration)
            } else {
                "--:--"
            }
        )
    }
}

sealed class MainEvent {
    data class ShowMessage(val message: String) : MainEvent()
    data class TracksDeleted(val message: String) : MainEvent()
    data class OpenFolderPicker(val initialUri: Uri?) : MainEvent()
    object OpenPlayerScreen : MainEvent()
    object OpenCloudSourceScreen : MainEvent()
}
