package com.example.timedmusicplayer.ui.cloud

import android.app.Application
import android.net.Uri
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.data.MusicRepository
import com.example.timedmusicplayer.model.CloudSource
import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.model.TrackFilter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class CloudSourceViewModel(
    application: Application,
    private val repository: MusicRepository
) : AndroidViewModel(application) {

    private val app = application.applicationContext
    private val uiStateValue = MutableStateFlow(CloudSourceUiState())
    private val eventChannel = Channel<CloudSourceEvent>(Channel.BUFFERED)

    val uiState: StateFlow<CloudSourceUiState> = uiStateValue.asStateFlow()
    val events = eventChannel.receiveAsFlow()
    val defaultSourceUrl: String = DEFAULT_STREAM_URL

    init {
        loadSources()
    }

    fun suggestName(url: String): String {
        return deriveName(url)
    }

    fun refresh() {
        loadSources()
    }

    fun onAddSource(inputName: String, url: String) {
        val safeUrl = url.trim()
        val safeInputName = inputName.trim()

        when {
            safeUrl.isBlank() -> sendMessage(app.getString(R.string.no_stream_url))
            !isValidUrl(safeUrl) -> sendMessage(app.getString(R.string.invalid_stream_url))
            repository.hasDuplicateCloudUrl(safeUrl) -> sendMessage(app.getString(R.string.duplicate_stream_url))
            else -> {
                val finalName = if (safeInputName.isBlank()) deriveName(safeUrl) else safeInputName
                repository.addCloudSource(finalName, safeUrl)
                loadSources()
                viewModelScope.launch {
                    eventChannel.send(CloudSourceEvent.ClearNameInput)
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

        repository.renameCloudSource(sourceId, safeName)
        loadSources()
    }

    fun onDeleteSource(sourceId: String) {
        repository.deleteCloudSource(sourceId)
        loadSources()
    }

    fun onSourceSelected(source: CloudSource) {
        val cloudTracks = repository.getTracks(TrackFilter.CLOUD)
        if (cloudTracks.isEmpty()) {
            return
        }

        val targetTrackId = "cloud:${source.id}"
        val targetIndex = cloudTracks.indexOfFirst { it.id == targetTrackId }
        if (targetIndex == -1) {
            return
        }

        viewModelScope.launch {
            eventChannel.send(
                CloudSourceEvent.OpenPlayer(ArrayList(cloudTracks), targetIndex)
            )
        }
    }

    fun getSourceById(sourceId: String): CloudSource? {
        return uiStateValue.value.entries.firstOrNull { it.id == sourceId }
    }

    private fun loadSources() {
        val entries = repository.getCloudSources()
        uiStateValue.value = CloudSourceUiState(
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
        return (url.startsWith("http://") || url.startsWith("https://")) &&
            Patterns.WEB_URL.matcher(url).matches()
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
        private const val DEFAULT_STREAM_URL = "http://ice1.somafm.com/groovesalad-128-mp3"
    }
}

sealed class CloudSourceEvent {
    data class ShowMessage(val message: String) : CloudSourceEvent()
    data class OpenPlayer(val queue: ArrayList<Track>, val startIndex: Int) : CloudSourceEvent()
    object ClearNameInput : CloudSourceEvent()
}

