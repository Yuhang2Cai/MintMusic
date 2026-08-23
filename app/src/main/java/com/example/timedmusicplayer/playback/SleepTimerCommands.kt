package com.example.timedmusicplayer.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

object SleepTimerCommands {
    const val ACTION_SET = "com.example.timedmusicplayer.action.SET_SLEEP_TIMER"
    const val ARG_DURATION_MS = "duration_ms"
    const val EXTRA_END_ELAPSED_REALTIME_MS = "sleep_timer_end_elapsed_realtime_ms"

    val setTimer = SessionCommand(ACTION_SET, Bundle.EMPTY)
}
