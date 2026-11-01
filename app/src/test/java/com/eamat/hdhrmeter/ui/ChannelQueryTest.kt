package net.etekweb.hdhrmeter.ui

import net.etekweb.hdhrmeter.data.Channel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelQueryTest {
    private val channel = Channel(
        guideNumber = "2.1",
        guideName = "WJBK",
        streamUrl = "http://x/auto/v2.1",
        hd = true,
        drm = false,
        favorite = false,
        videoCodec = null,
        audioCodec = null,
        frequencyHz = null,
        atsc3 = false,
    )

    @Test
    fun blankQueryMatchesEveryChannel() {
        assertTrue(channelMatchesQuery(channel, "  ", "Jeopardy!"))
    }

    @Test
    fun queryMatchesChannelNumberOrName() {
        assertTrue(channelMatchesQuery(channel, "2.1", null))
        assertTrue(channelMatchesQuery(channel, "wjbk", null))
    }

    @Test
    fun queryMatchesCurrentProgramTitle() {
        assertTrue(channelMatchesQuery(channel, "jeopardy", "Jeopardy!"))
        assertFalse(channelMatchesQuery(channel, "jeopardy", "Wheel of Fortune"))
        assertFalse(channelMatchesQuery(channel, "jeopardy", null))
    }
}
