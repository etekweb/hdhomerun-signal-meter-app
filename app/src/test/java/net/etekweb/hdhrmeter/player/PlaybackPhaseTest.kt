package net.etekweb.hdhrmeter.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPhaseTest {
    @Test
    fun rebufferCompletesWithoutAnotherPlayingEvent() {
        val rebuffering = EnginePhase.Ready.afterBuffering(25f, playing = true)
        assertEquals(EnginePhase.Buffering, rebuffering)
        assertEquals(EnginePhase.Ready, rebuffering.afterBuffering(100f, playing = true))
    }

    @Test
    fun aFullCacheBeforePlaybackStartsDoesNotReportReady() {
        assertEquals(
            EnginePhase.Buffering,
            EnginePhase.Buffering.afterBuffering(100f, playing = false),
        )
    }

    @Test
    fun lateBufferEventsDoNotRestartStoppedPlayback() {
        for (phase in listOf(EnginePhase.Idle, EnginePhase.Ended)) {
            assertEquals(phase, phase.afterBuffering(25f, playing = true))
            assertEquals(phase, phase.afterBuffering(100f, playing = true))
        }
    }
}
