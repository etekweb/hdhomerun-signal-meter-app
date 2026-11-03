package net.etekweb.hdhrmeter.player

import net.etekweb.hdhrmeter.data.TIMESHIFT_LIVE_BYTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTimelineTest {
    @Test
    fun availableStartIsTheLaterOfTheShowAndTheBuffer() {
        assertEquals(1_000_000L, availableRecordingStartMs(1_000_000L, 500_000L))
        assertEquals(1_500_000L, availableRecordingStartMs(1_000_000L, 1_500_000L))
        assertEquals(1_500_000L, availableRecordingStartMs(null, 1_500_000L))
    }

    @Test
    fun lateTuneInClampsTheBarSeekToTheBuffer() {
        val bar = playbackBar(
            bufferStartMs = 1_500_000L,
            positionMs = 1_800_000L,
            liveMs = 1_800_000L,
            programStartMs = 1_000_000L,
            programStopMs = 2_000_000L,
            seekable = true,
        )
        assertEquals(1_000_000L, bar.startMs)
        assertEquals(2_000_000L, bar.endMs)
        assertEquals(1_500_000L, bar.seekStartMs)
        assertEquals(1_800_000L, bar.seekEndMs)
        assertEquals(1_500_000L, bar.wallAt(0f))
    }

    @Test
    fun measuredRateBackdatesTheBufferAndPlacesTheShowStart() {
        val map = TimeshiftMap()
        map.noteEdges(oldestByte = 0L, liveByte = 1_000_000L, nowMs = 20_000L)
        map.noteOpened(
            actualByte = 1_000_000L,
            joinLive = true,
            requestedWallMs = 20_000L,
            openedAtMs = 20_000L,
            generation = 1,
        )
        assertNull(map.shiftTarget(19_500L, joinLive = false, liveMs = 20_000L))
        assertNull(map.availableStartTarget(programStartMs = 10_000L, liveMs = 20_000L))
        map.noteBytes(bytesSent = 2_000_000L, mediaTimeMs = 2_000L, nowMs = 22_000L)
        assertNull(map.shiftTarget(17_500L, joinLive = false, liveMs = 22_000L))
        map.noteBytes(bytesSent = 4_000_000L, mediaTimeMs = 4_000L, nowMs = 24_000L)
        assertEquals(19_000L, map.bufferStartMs)
        assertEquals("bytes=0-", map.availableStartTarget(10_000L, 20_000L)?.range)
        assertEquals("bytes=800000-", map.availableStartTarget(19_800L, 20_000L)?.range)
        assertEquals("bytes=0-", map.availableStartTarget(programStartMs = null, liveMs = 20_000L)?.range)
    }

    @Test
    fun scrubUsesTheSteadyRateAndIgnoresALaterBurst() {
        val map = TimeshiftMap()
        map.noteEdges(oldestByte = 0L, liveByte = 1_000_000L, nowMs = 20_000L)
        map.noteOpened(
            actualByte = 1_000_000L,
            joinLive = true,
            requestedWallMs = 20_000L,
            openedAtMs = 20_000L,
            generation = 1,
        )
        map.noteBytes(bytesSent = 100_000L, mediaTimeMs = 200L, nowMs = 20_200L)
        map.noteBytes(bytesSent = 2_000_000L, mediaTimeMs = 2_000L, nowMs = 22_000L)
        map.noteBytes(bytesSent = 2_400_000L, mediaTimeMs = 4_000L, nowMs = 24_000L)
        assertEquals(15_000L, map.bufferStartMs)
        val middle = map.shiftTarget(17_500L, joinLive = false, liveMs = 20_000L)
        assertEquals("bytes=500000-", middle?.range)
        map.noteBytes(bytesSent = 80_000_000L, mediaTimeMs = 4_000L, nowMs = 40_000L)
        assertEquals(middle, map.shiftTarget(17_500L, joinLive = false, liveMs = 20_000L))
    }

    @Test
    fun bufferStartAndLiveWorkBeforeTheRateWhenThereIsNoEarlierShow() {
        val map = TimeshiftMap()
        map.noteEdges(oldestByte = 0L, liveByte = 0L, nowMs = 20_000L)
        assertEquals("bytes=0-", map.shiftTarget(20_000L, joinLive = false, liveMs = 20_000L)?.range)
        assertEquals(
            "bytes=$TIMESHIFT_LIVE_BYTE-",
            map.shiftTarget(20_000L, joinLive = true, liveMs = 20_000L)?.range,
        )
        val history = TimeshiftMap()
        history.noteEdges(oldestByte = 0L, liveByte = 1_000_000L, nowMs = 20_000L)
        assertEquals("bytes=0-", history.availableStartTarget(programStartMs = null, liveMs = 20_000L)?.range)
        assertNull(history.shiftTarget(20_000L, joinLive = false, liveMs = 20_000L))
        assertEquals(
            "bytes=$TIMESHIFT_LIVE_BYTE-",
            history.shiftTarget(20_000L, joinLive = true, liveMs = 20_000L)?.range,
        )
    }

    @Test
    fun aHotRateSeeksBackFromTheRealLiveEdge() {
        val map = TimeshiftMap()
        map.noteEdges(oldestByte = 0L, liveByte = 1_000_000L, nowMs = 20_000L)
        map.noteOpened(
            actualByte = 1_000_000L,
            joinLive = true,
            requestedWallMs = 20_000L,
            openedAtMs = 20_000L,
            generation = 1,
        )
        map.noteBytes(bytesSent = 2_000_000L, mediaTimeMs = 2_000L, nowMs = 22_000L)
        map.noteBytes(bytesSent = 4_000_000L, mediaTimeMs = 4_000L, nowMs = 24_000L)
        val now = 80_000L
        val observedLive = 31_000_000L
        val fallenOff = map.shiftTarget(now - 10_000L, joinLive = false, liveMs = now)
        val fallenByte = fallenOff?.range?.removePrefix("bytes=")?.removeSuffix("-")?.toLong()
        assertTrue(fallenByte != null && fallenByte > observedLive)
        val held = map.shiftTarget(now - 10_000L, joinLive = false, liveMs = now, observedLiveByte = observedLive)
        assertEquals("bytes=${observedLive - 10_000L * 1000L}-", held?.range)
    }

    @Test
    fun holdKeepsTheOldPositionUntilTheNewStreamRestarts() {
        val map = TimeshiftMap()
        map.noteEdges(oldestByte = 0L, liveByte = 1_000_000L, nowMs = 20_000L)
        map.armHold(wallMs = 20_000L, generation = 1)
        assertEquals(20_000L, map.positionMs(mediaTimeMs = 50_000L, nowMs = 21_000L))
        map.noteOpened(
            actualByte = 1_000_000L,
            joinLive = true,
            requestedWallMs = 20_000L,
            openedAtMs = 20_500L,
            generation = 1,
        )
        assertEquals(20_000L, map.positionMs(mediaTimeMs = 50_000L, nowMs = 21_000L))
        assertEquals(20_900L, map.positionMs(mediaTimeMs = 400L, nowMs = 21_000L))
    }
}
