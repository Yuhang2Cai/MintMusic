package com.example.timedmusicplayer.ui.cloud

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.data.MusicRepository
import com.example.timedmusicplayer.model.CloudSource
import com.example.timedmusicplayer.model.TrackFilter
import com.example.timedmusicplayer.playback.PlaybackController
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class CloudSourceViewModel(
    application: Application,
    private val repository: MusicRepository,
    private val playbackController: PlaybackController
) : AndroidViewModel(application) {

    private val app = application.applicationContext
    private val uiStateValue = MutableStateFlow(CloudSourceUiState())
    private val eventChannel = Channel<CloudSourceEvent>(Channel.BUFFERED)

    val uiState: StateFlow<CloudSourceUiState> = uiStateValue.asStateFlow()
    val events = eventChannel.receiveAsFlow()
    init {
        loadSources()
    }

    fun refresh() {
        loadSources()
    }

    fun onNameChanged(value: String) {
        if (uiStateValue.value.inputName != value) uiStateValue.value = uiStateValue.value.copy(inputName = value)
    }

    fun onUrlChanged(value: String) {
        if (uiStateValue.value.inputUrl != value) uiStateValue.value = uiStateValue.value.copy(inputUrl = value)
    }

    fun onCoverUrlChanged(value: String) {
        if (uiStateValue.value.inputCoverUrl != value) uiStateValue.value = uiStateValue.value.copy(inputCoverUrl = value)
    }

    fun onAddSource() {
        val form = uiStateValue.value
        val safeUrl = form.inputUrl.trim()
        val safeInputName = form.inputName.trim()
        val safeCoverUrl = form.inputCoverUrl.trim()
        viewModelScope.launch {
            when {
                safeUrl.isBlank() -> sendMessage(app.getString(R.string.no_stream_url))
                !isValidUrl(safeUrl) -> sendMessage(app.getString(R.string.invalid_stream_url))
                safeCoverUrl.isNotBlank() && !isValidUrl(safeCoverUrl) -> sendMessage(app.getString(R.string.invalid_stream_url))
                repository.hasDuplicateCloudUrl(safeUrl) -> sendMessage(app.getString(R.string.duplicate_stream_url))
                else -> {
                    uiStateValue.value = uiStateValue.value.copy(isSaving = true)
                    val finalName = if (safeInputName.isBlank()) deriveName(safeUrl) else safeInputName
                    repository.addCloudSource(finalName, safeUrl, safeCoverUrl)
                    uiStateValue.value = uiStateValue.value.copy(inputName = "", isSaving = false)
                    loadSourcesNow()
                }
            }
        }
    }

    fun onRenameSource(sourceId: String, newName: String) {
        val safeName = newName.trim()
        if (safeName.isBlank()) {
            sendMessage(app.getString(R.string.stream_name_empty))
            return
        }

        viewModelScope.launch {
            repository.renameCloudSource(sourceId, safeName)
            loadSourcesNow()
        }
    }

    fun onDeleteSource(sourceId: String) {
        viewModelScope.launch {
            repository.deleteCloudSource(sourceId)
            loadSourcesNow()
        }
    }

    fun onSourceSelected(source: CloudSource) {
        if (!isValidUrl(source.url)) {
            sendMessage(app.getString(R.string.invalid_stream_url))
            return
        }
        viewModelScope.launch {
            val cloudTracks = repository.getTracks(TrackFilter.CLOUD)
            val targetIndex = cloudTracks.indexOfFirst { it.id == "cloud:${source.id}" }
            if (targetIndex == -1) return@launch
            playbackController.playQueue(cloudTracks, targetIndex, forcePlay = true)
            eventChannel.send(CloudSourceEvent.OpenPlayerScreen)
        }
    }

    fun getSourceById(sourceId: String): CloudSource? {
        return uiStateValue.value.entries.firstOrNull { it.id == sourceId }
    }

    private fun loadSources() {
        viewModelScope.launch { loadSourcesNow() }
    }

    private suspend fun loadSourcesNow() {
        val entries = repository.getCloudSources()
        uiStateValue.value = uiStateValue.value.copy(
            entries = entries,
            isEmpty = entries.isEmpty()
        )
    }

    private fun sendMessage(message: String) {
        viewModelScope.launch {
            eventChannel.send(CloudSourceEvent.ShowMessage(message))
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return url.toHttpUrlOrNull() != null
    }

    private fun deriveName(url: String): String {
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        return when {
            host.isNotBlank() -> host
            url.length <= 30 -> url
            else -> "${url.take(30)}..."
        }
    }

    companion object {
    }
}

sealed class CloudSourceEvent {
    data class ShowMessage(val message: String) : CloudSourceEvent()
    object OpenPlayerScreen : CloudSourceEvent()
}

