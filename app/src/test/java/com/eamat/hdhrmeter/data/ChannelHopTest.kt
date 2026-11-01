package net.etekweb.hdhrmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelHopTest {
    private val lineup = listOf(
        channel("2.1"),
        channel("2.2", drm = true),
        channel("4.1"),
        channel("7.1"),
    )

    @Test
    fun oneTapSkipsProtectedChannels() {
        val next = previewHop(lineup, playing = lineup[0], pending = null, delta = 1)
        assertEquals("4.1", next?.guideNumber)
    }

    @Test
    fun repeatedTapsWalkFromThePreview() {
        val first = previewHop(lineup, playing = lineup[0], pending = null, delta = 1)
        val second = previewHop(lineup, playing = lineup[0], pending = first, delta = 1)
        assertEquals("7.1", second?.guideNumber)
    }

    @Test
    fun steppingBackOntoThePlayingChannelClearsThePreview() {
        val away = previewHop(lineup, playing = lineup[0], pending = null, delta = 1)
        val back = previewHop(lineup, playing = lineup[0], pending = away, delta = -1)
        assertNull(back)
    }

    @Test
    fun downFromTheFirstPlayableChannelWraps() {
        val next = previewHop(lineup, playing = lineup[0], pending = null, delta = -1)
        assertEquals("7.1", next?.guideNumber)
    }

    @Test
    fun nothingPlayableHasNoPreview() {
        val onlyProtected = listOf(channel("9.1", drm = true))
        assertNull(previewHop(onlyProtected, playing = onlyProtected[0], pending = null, delta = 1))
    }

    private fun channel(number: String, drm: Boolean = false) = Channel(
        guideNumber = number,
        guideName = number,
        streamUrl = "http://x/auto/v$number",
        hd = false,
        drm = drm,
        favorite = false,
        videoCodec = null,
        audioCodec = null,
        frequencyHz = null,
        atsc3 = false,
    )
}
