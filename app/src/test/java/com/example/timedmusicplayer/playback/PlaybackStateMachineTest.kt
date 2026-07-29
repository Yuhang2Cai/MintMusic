package com.example.timedmusicplayer.playback

import com.example.timedmusicplayer.network.RecoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateMachineTest {
    @Test fun `buffer play pause follows explicit states`() {
        var state: PlaybackState = PlaybackState.Idle
        state = PlaybackStateMachine.reduce(state, PlaybackEvent.Prepare)
        assertEquals(PlaybackState.Preparing, state)
        state = PlaybackStateMachine.reduce(state, PlaybackEvent.Buffer)
        assertEquals(PlaybackState.Buffering, state)
        state = PlaybackStateMachine.reduce(state, PlaybackEvent.Play)
        assertEquals(PlaybackState.Playing, state)
        assertEquals(PlaybackState.Paused, PlaybackStateMachine.reduce(state, PlaybackEvent.Pause))
    }

    @Test fun `recovery uses bounded exponential schedule`() {
        val policy = RecoveryPolicy()
        assertEquals(1_000L, policy.delayMs(1, false))
        assertEquals(8_000L, policy.delayMs(4, false))
        assertNull(policy.delayMs(5, false))
    }

    @Test fun `checkpoint policy coalesces sub threshold progress`() {
        val policy = CheckpointPolicy(5_000L)
        assertTrue(policy.shouldWrite("a", 0L, false))
        assertFalse(policy.shouldWrite("a", 500L, false))
        assertTrue(policy.shouldWrite("a", 5_000L, false))
        assertTrue(policy.shouldWrite("a", 5_100L, true))
        assertTrue(policy.shouldWrite("b", 0L, false))
    }
}
