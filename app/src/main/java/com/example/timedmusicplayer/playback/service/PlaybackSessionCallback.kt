package com.example.timedmusicplayer.playback.service

import android.os.Bundle
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.timedmusicplayer.playback.SleepTimerCommands
import com.example.timedmusicplayer.playback.timer.SleepTimerManager
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackSessionCallback(
    private val sleepTimerManager: SleepTimerManager
) : MediaSession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
            .buildUpon()
            .add(SleepTimerCommands.setTimer)
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands)
            .setSessionExtras(sleepTimerManager.sessionExtras())
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction != SleepTimerCommands.ACTION_SET) {
            return super.onCustomCommand(session, controller, customCommand, args)
        }
        sleepTimerManager.set(args.getLong(SleepTimerCommands.ARG_DURATION_MS, 0L))
        return Futures.immediateFuture(
            SessionResult(SessionResult.RESULT_SUCCESS, sleepTimerManager.sessionExtras())
        )
    }
}
