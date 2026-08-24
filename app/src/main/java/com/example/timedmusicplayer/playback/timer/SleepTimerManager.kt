package com.example.timedmusicplayer.playback.timer

import android.os.Bundle
import android.os.SystemClock
import com.example.timedmusicplayer.playback.SleepTimerCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SleepTimerManager(
    private val scope: CoroutineScope,
    private val onExpired: () -> Unit,
    private val onChanged: () -> Unit
) {
    private var timerJob: Job? = null
    private var endElapsedRealtimeMs = 0L

    fun set(durationMs: Long) {
        timerJob?.cancel()
        timerJob = null
        if (durationMs <= 0L) {
            endElapsedRealtimeMs = 0L
            onChanged()
            return
        }

        val deadline = SystemClock.elapsedRealtime() + durationMs
        endElapsedRealtimeMs = deadline
        onChanged()
        timerJob = scope.launch {
            delay(durationMs)
            if (endElapsedRealtimeMs != deadline) return@launch
            endElapsedRealtimeMs = 0L
            onExpired()
            onChanged()
        }
    }

    fun sessionExtras(): Bundle = Bundle().apply {
        putLong(SleepTimerCommands.EXTRA_END_ELAPSED_REALTIME_MS, endElapsedRealtimeMs)
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
    }
}
