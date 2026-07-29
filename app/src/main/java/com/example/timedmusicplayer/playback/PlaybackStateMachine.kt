package com.example.timedmusicplayer.playback

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Preparing : PlaybackState
    data object Buffering : PlaybackState
    data object Playing : PlaybackState
    data object Paused : PlaybackState
    data object WaitingForNetwork : PlaybackState
    data class Recovering(val attempt: Int) : PlaybackState
    data class Failed(val message: String) : PlaybackState
}

sealed interface PlaybackEvent {
    data object Prepare : PlaybackEvent
    data object Ready : PlaybackEvent
    data object Play : PlaybackEvent
    data object Pause : PlaybackEvent
    data object Buffer : PlaybackEvent
    data object Offline : PlaybackEvent
    data class Retry(val attempt: Int) : PlaybackEvent
    data class Error(val message: String) : PlaybackEvent
    data object Stop : PlaybackEvent
}

object PlaybackStateMachine {
    fun reduce(current: PlaybackState, event: PlaybackEvent): PlaybackState = when (event) {
        PlaybackEvent.Prepare -> PlaybackState.Preparing
        PlaybackEvent.Ready -> if (current is PlaybackState.Paused) current else PlaybackState.Buffering
        PlaybackEvent.Play -> PlaybackState.Playing
        PlaybackEvent.Pause -> PlaybackState.Paused
        PlaybackEvent.Buffer -> PlaybackState.Buffering
        PlaybackEvent.Offline -> PlaybackState.WaitingForNetwork
        is PlaybackEvent.Retry -> PlaybackState.Recovering(event.attempt)
        is PlaybackEvent.Error -> PlaybackState.Failed(event.message)
        PlaybackEvent.Stop -> PlaybackState.Idle
    }
}
