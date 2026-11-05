package net.etekweb.hdhrmeter.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSkipTest {
    @Test
    fun repeatedTapsAddOntoTheFirstPosition() {
        val first = accumulateSkip(null, positionMs = 100_000, deltaMs = -SKIP_STEP_MS, seekStartMs = 0, seekEndMs = 200_000)
        val second = accumulateSkip(first, positionMs = 101_000, deltaMs = -SKIP_STEP_MS, seekStartMs = 0, seekEndMs = 200_000)
        assertEquals(100_000L, second?.anchorMs)
        assertEquals(-20_000L, second?.deltaMs)
        assertEquals(80_000L, second?.targetMs)
    }

    @Test
    fun oppositeTapsReduceTheSameBurst() {
        val back = accumulateSkip(null, 100_000, -SKIP_STEP_MS, 0, 200_000)
        val further = accumulateSkip(back, 100_000, -SKIP_STEP_MS, 0, 200_000)
        val eased = accumulateSkip(further, 100_000, SKIP_STEP_MS, 0, 200_000)
        assertEquals(-10_000L, eased?.deltaMs)
        assertEquals(90_000L, eased?.targetMs)
    }

    @Test
    fun returningToTheAnchorClearsTheBurst() {
        val back = accumulateSkip(null, 100_000, -SKIP_STEP_MS, 0, 200_000)
        assertNull(accumulateSkip(back, 100_000, SKIP_STEP_MS, 0, 200_000))
    }

    @Test
    fun theLandingSpotStaysInsideTheSeekWindow() {
        val back = accumulateSkip(null, 4_000, -SKIP_STEP_MS, 0, 100_000)
        assertEquals(0L, back?.targetMs)
        assertEquals(-4_000L, back?.deltaMs)
        assertEquals(-4_000L, accumulateSkip(back, 4_000, -SKIP_STEP_MS, 0, 100_000)?.deltaMs)

        val forward = accumulateSkip(null, 96_000, SKIP_STEP_MS, 0, 100_000)
        assertEquals(100_000L, forward?.targetMs)
        assertEquals(4_000L, forward?.deltaMs)
    }

    @Test
    fun theLandingStaysUntilThePlayheadShowsIt() {
        assertEquals(90_000L, settleSkipLanding(positionMs = 40_000L, landedMs = 90_000L))
        assertNull(settleSkipLanding(positionMs = 90_400L, landedMs = 90_000L))
        assertNull(settleSkipLanding(positionMs = 88_500L, landedMs = 90_000L))
    }

    @Test
    fun watchingPastAPublishedLandingSkipsFromThePlayhead() {
        var landed: Long? = 50_000L
        // The seek is in flight. The playhead is still the old picture, so the
        // next tap keeps counting from the landing.
        landed = settleSkipLanding(positionMs = 20_000L, landedMs = landed)
        val early = accumulateSkip(null, landed ?: 20_000L, SKIP_STEP_MS, 0L, 200_000L)
        assertEquals(60_000L, early?.targetMs)

        // The clock shows the landing, then the viewer watches on past it.
        landed = settleSkipLanding(positionMs = 50_200L, landedMs = landed)
        assertNull(landed)
        val forward = accumulateSkip(null, 80_000L, SKIP_STEP_MS, 0L, 200_000L)
        assertEquals(90_000L, forward?.targetMs)
        val backward = accumulateSkip(null, 80_000L, -SKIP_STEP_MS, 0L, 200_000L)
        assertEquals(70_000L, backward?.targetMs)
    }

    @Test
    fun formatSkipDeltaUsesSecondsThenMinutes() {
        assertEquals("\u221210s", formatSkipDelta(-10_000))
        assertEquals("+20s", formatSkipDelta(20_000))
        assertEquals("\u22121s", formatSkipDelta(-400))
        assertEquals("\u22121:30", formatSkipDelta(-90_000))
        assertEquals("+1:00", formatSkipDelta(60_000))
    }
}
