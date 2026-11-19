package net.etekweb.hdhrmeter.ui

import net.etekweb.hdhrmeter.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test
    fun queryMatchesUpcomingProgramTitle() {
        assertTrue(channelMatchesQuery(channel, "news", "Jeopardy!", "Nightly News"))
        assertFalse(channelMatchesQuery(channel, "news", "Jeopardy!", "Later Show"))
        assertFalse(channelMatchesQuery(channel, "news", "Jeopardy!", null))
    }

    @Test
    fun surfReturnClearsSearchAndFilterOnlyWhenTheyHideTheTunedChannel() {
        val state = UiState(
            channels = listOf(channel),
            filter = ChannelFilter.Favorites,
            query = "another station",
            lineupReturn = LineupReturn(channel, programStartMs = null, scrollToChannel = true),
        )

        val revealed = state.revealSurfedChannel()
        assertEquals(ChannelFilter.All, revealed.filter)
        assertEquals("", revealed.query)
        assertEquals(channel, revealed.lineupReturn?.channel)

        val matching = state.copy(filter = ChannelFilter.Recents, query = "WJBK").revealSurfedChannel()
        assertEquals(ChannelFilter.Recents, matching.filter)
        assertEquals("WJBK", matching.query)
    }

    @Test
    fun returnWithoutSurfingKeepsTheOriginalLineupState() {
        val state = UiState(
            query = "WJBK",
            lineupReturn = LineupReturn(channel, programStartMs = 123L),
        )
        assertSame(state, state.revealSurfedChannel())
    }
}
