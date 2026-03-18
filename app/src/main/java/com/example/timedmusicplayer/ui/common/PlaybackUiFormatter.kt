package com.example.timedmusicplayer.ui.common

import android.content.Context
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.playback.PlaybackMode
import com.example.timedmusicplayer.playback.PlaybackUiState
import java.util.Locale
import kotlin.math.max

object PlaybackUiFormatter {
    private const val MIN_STREAM_PROGRESS_MAX_MS = 10 * 60 * 1000L
    private const val STREAM_PROGRESS_WINDOW_MS = 3 * 60 * 1000L

    fun formatTime(millis: Long): String {
        val safeMillis = millis.coerceAtLeast(0L)
        val totalSeconds = (safeMillis / 1000L).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    fun statusText(context: Context, state: PlaybackUiState, errorMessage: String?): String {
        return when (state) {
            PlaybackUiState.LOADING -> context.getString(R.string.status_loading)
            PlaybackUiState.BUFFERING -> context.getString(R.string.status_buffering)
            PlaybackUiState.PLAYING -> context.getString(R.string.status_playing)
            PlaybackUiState.PAUSED -> context.getString(R.string.status_paused)
            PlaybackUiState.ERROR -> errorMessage ?: context.getString(R.string.status_error)
            PlaybackUiState.IDLE -> context.getString(R.string.ready_to_play)
        }
    }

    fun modeLabel(context: Context, mode: PlaybackMode): String {
        return when (mode) {
            PlaybackMode.ORDER -> context.getString(R.string.mode_order)
            PlaybackMode.REPEAT_ONE -> context.getString(R.string.mode_repeat_one)
            PlaybackMode.REPEAT_ALL -> context.getString(R.string.mode_repeat_all)
            PlaybackMode.SHUFFLE -> context.getString(R.string.mode_shuffle)
        }
    }

    fun resolveProgressMax(durationMs: Long, positionMs: Long, isStream: Boolean): Long {
        return when {
            durationMs > 0L -> durationMs
            isStream -> max(positionMs + STREAM_PROGRESS_WINDOW_MS, MIN_STREAM_PROGRESS_MAX_MS)
            else -> 0L
        }
    }
}
