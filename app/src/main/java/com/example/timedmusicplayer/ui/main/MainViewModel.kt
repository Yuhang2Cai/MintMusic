package com.example.timedmusicplayer.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.data.repository.LibraryRepository
import com.example.timedmusicplayer.data.repository.LibrarySettingsRepository
import com.example.timedmusicplayer.data.repository.PlaybackHistoryRepository
import com.example.timedmusicplayer.domain.library.DeleteLibraryContentUseCase
import com.example.timedmusicplayer.emotion.MoodAnalysisRepository
import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.model.TrackFilter
import com.example.timedmusicplayer.model.SourceType
import com.example.timedmusicplayer.playback.PlaybackController
import com.example.timedmusicplayer.playback.PlaybackController.PlaybackTickMode
import com.example.timedmusicplayer.playback.PlaybackSnapshot
import com.example.timedmusicplayer.ui.common.PlaybackUiFormatter
import com.example.timedmusicplayer.ui.theme.AppearanceRepository
import com.example.timedmusicplayer.ui.theme.ThemeColorOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(
    application: Application,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: LibrarySettingsRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val deleteLibraryContent: DeleteLibraryContentUseCase,
    private val playbackController: PlaybackController,
    private val moodRepository: MoodAnalysisRepository,
    private val appearanceRepository: AppearanceRepository
) : AndroidViewModel(application) {

    private val app = application.applicationContext
    private val uiStateValue = MutableStateFlow(
        MainUiState(libraryCountText = app.getString(R.string.library_loading))
    )
    private val eventChannel = Channel<MainEvent>(Channel.BUFFERED)

    private var loadJob: Job? = null
    private val filterValue = MutableStateFlow(TrackFilter.ALL)
    private val pagingRefreshVersion = MutableStateFlow(0L)
    private val selectedTracks = linkedMapOf<String, Track>()

    val uiState: StateFlow<MainUiState> = uiStateValue.asStateFlow()
    val events = eventChannel.receiveAsFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingData = combine(filterValue, pagingRefreshVersion) { filter, _ -> filter }
        .flatMapLatest(libraryRepository::pagingTracks)
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            val restoredFilter = settingsRepository.selectedFilter()
            filterValue.value = restoredFilter
            uiStateValue.value = uiStateValue.value.copy(
                activeFilter = restoredFilter,
                selectedTheme = appearanceRepository.selectedTheme()
            )
            loadLibrary(forceRefresh = false)
        }
        viewModelScope.launch {
            playbackController.snapshot.collect { snapshot ->
                uiStateValue.value = uiStateValue.value.copy(
                    miniPlayer = snapshot?.takeIf { it.queue.isNotEmpty() }?.toMiniPlayerUiState()
                )
            }
        }
        viewModelScope.launch {
            moodRepository.states.collect { states ->
                uiStateValue.value = uiStateValue.value.copy(moodStates = states)
            }
        }
    }

    fun onStart() {
        playbackController.connect(PlaybackTickMode.MINI_PLAYER)
    }

    fun onStop() {
        playbackController.disconnect(PlaybackTickMode.MINI_PLAYER)
    }

    fun onResume() {
        if (uiStateValue.value.isScanningLocalMusic) return
        loadLibrary(forceRefresh = false)
    }

    fun onFilterSelected(filter: TrackFilter) {
        if (uiStateValue.value.activeFilter == filter) {
            return
        }
        uiStateValue.value = uiStateValue.value.copy(activeFilter = filter)
        filterValue.value = filter
        viewModelScope.launch { settingsRepository.saveSelectedFilter(filter) }
        clearSelection()
        loadLibrary(forceRefresh = false)
    }

    fun onSelectFolderClicked() {
        viewModelScope.launch {
            eventChannel.send(MainEvent.OpenFolderPicker(settingsRepository.localFolderUri()))
        }
    }

    fun onLocalFolderSelected(uri: Uri?) {
        if (uri == null) {
            return
        }
        viewModelScope.launch {
            libraryRepository.saveLocalFolder(uri)
            loadLibrary(forceRefresh = true)
        }
    }

    fun onManageCloudClicked() {
        viewModelScope.launch {
            eventChannel.send(MainEvent.OpenCloudSourceScreen)
        }
    }

    fun onCloudSourceScreenReturned() {
        clearSelection()
        pagingRefreshVersion.value += 1L
        loadLibrary(forceRefresh = false)
    }

    fun onResumeLastClicked() {
        viewModelScope.launch {
            val lastPlayback = playbackHistoryRepository.lastPlayback()
            if (lastPlayback == null) {
                eventChannel.send(MainEvent.ShowMessage(app.getString(R.string.no_resume_item)))
                return@launch
            }

            val allTracks = withContext(Dispatchers.IO) {
                libraryRepository.getTracks(TrackFilter.ALL, forceRefresh = false)
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

    fun onTrackClicked(track: Track) {
        if (selectedTracks.isNotEmpty()) {
            toggleTrackSelection(track)
        } else {
            playTrack(track)
        }
    }

    fun onTrackLongClicked(track: Track) {
        toggleTrackSelection(track)
    }

    fun onTrackMoreClicked(track: Track) {
        if (track.sourceType != SourceType.LOCAL) return
        viewModelScope.launch {
            eventChannel.send(
                MainEvent.ShowMoodLabelPicker(
                    trackId = track.id,
                    currentLabel = moodRepository.states.value[track.id]?.label,
                    labels = moodRepository.labels
                )
            )
        }
    }

    fun onMoodLabelSelected(trackId: String, label: String?) {
        moodRepository.setLabel(trackId, label)
    }

    fun clearSelection() {
        if (selectedTracks.isEmpty()) return
        selectedTracks.clear()
        publishSelection()
    }

    fun onDeleteSelectionClicked() {
        requestDeleteConfirmation(selectedTracks.values.toList())
    }

    fun onDeleteAllClicked() {
        viewModelScope.launch {
            val filter = uiStateValue.value.activeFilter
            val count = libraryRepository.trackCount(filter)
            if (count > 0) eventChannel.send(MainEvent.ConfirmDeleteAll(count, filter))
        }
    }

    private fun requestDeleteConfirmation(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch { eventChannel.send(MainEvent.ConfirmTrackDeletion(tracks)) }
    }

    private fun toggleTrackSelection(track: Track) {
        if (selectedTracks.remove(track.id) == null) selectedTracks[track.id] = track
        publishSelection()
    }

    private fun publishSelection() {
        uiStateValue.value = uiStateValue.value.copy(selectedTrackIds = selectedTracks.keys.toSet())
    }

    private fun playTrack(track: Track) {
        viewModelScope.launch {
            val tracks = withContext(Dispatchers.IO) {
                libraryRepository.getTracks(uiStateValue.value.activeFilter, forceRefresh = false)
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
            val result = withContext(Dispatchers.IO) { libraryRepository.deleteTracks(tracks) }
            clearSelection()
            loadLibrary(forceRefresh = false)
            val message = if (result.failed == 0) {
                app.getString(R.string.delete_tracks_success, result.deleted)
            } else {
                app.getString(R.string.delete_tracks_partial, result.deleted, result.failed)
            }
            eventChannel.send(MainEvent.TracksDeleted(message))
        }
    }

    fun onDeleteAllConfirmed(filter: TrackFilter) {
        viewModelScope.launch {
            val result = deleteLibraryContent(filter)
            clearSelection()
            loadLibrary(forceRefresh = false)
            val message = if (result.failed == 0) {
                app.getString(R.string.delete_tracks_success, result.deleted)
            } else {
                app.getString(R.string.delete_tracks_partial, result.deleted, result.failed)
            }
            eventChannel.send(MainEvent.TracksDeleted(message))
        }
    }

    fun onThemeSelected(option: ThemeColorOption) {
        if (option == uiStateValue.value.selectedTheme) return
        viewModelScope.launch {
            appearanceRepository.selectTheme(option)
            uiStateValue.value = uiStateValue.value.copy(selectedTheme = option)
            eventChannel.send(MainEvent.RecreateForTheme)
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
                libraryCountText = app.getString(R.string.library_loading),
                showEmpty = false,
                isScanningLocalMusic = true
            )
        }

        loadJob = viewModelScope.launch {
            val filter = uiStateValue.value.activeFilter
            val count = withContext(Dispatchers.IO) {
                libraryRepository.refresh(forceRefresh)
                libraryRepository.trackCount(filter)
            }
            uiStateValue.value = uiStateValue.value.copy(
                libraryCountText = app.getString(R.string.library_count, count),
                showEmpty = count == 0,
                isScanningLocalMusic = false
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
    data class ShowMoodLabelPicker(
        val trackId: String,
        val currentLabel: String?,
        val labels: List<String>
    ) : MainEvent()
    data class ConfirmTrackDeletion(val tracks: List<Track>) : MainEvent()
    data class ConfirmDeleteAll(val count: Int, val filter: TrackFilter) : MainEvent()
    object OpenPlayerScreen : MainEvent()
    object OpenCloudSourceScreen : MainEvent()
    object RecreateForTheme : MainEvent()
}
