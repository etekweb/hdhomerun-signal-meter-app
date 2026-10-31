package com.eamat.hdhrmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RecentsTest {
    @Test
    fun newestViewingIsFirstAndARepeatMovesToTheFront() {
        val once = rememberViewedChannel(emptyList(), "2.1")
        val twice = rememberViewedChannel(once, "4.1")
        val again = rememberViewedChannel(twice, "2.1")
        assertEquals(listOf("2.1", "4.1"), again)
    }

    @Test
    fun alreadyNewestChannelIsLeftAlone() {
        val recent = listOf("7.1", "2.1")
        assertSame(recent, rememberViewedChannel(recent, "7.1"))
    }

    @Test
    fun oldestViewingFallsOffTheEnd() {
        val recent = rememberViewedChannel(listOf("2.1", "4.1", "7.1"), "11.1", limit = 3)
        assertEquals(listOf("11.1", "2.1", "4.1"), recent)
    }

    @Test
    fun lineupFollowsViewingOrderAndSkipsChannelsNeverWatched() {
        val lineup = listOf(
            channel("2.1"),
            channel("2.2"),
            channel("4.1"),
            channel("7.1"),
        )
        val shown = channelsInViewOrder(lineup, listOf("7.1", "2.1", "50.1"))
        assertEquals(listOf("7.1", "2.1"), shown.map { it.guideNumber })
    }

    @Test
    fun sharedGuideNumberKeepsLineupOrder() {
        val lineup = listOf(channel("2.1", "First"), channel("2.1", "Second"))
        val shown = channelsInViewOrder(lineup, listOf("2.1"))
        assertEquals(listOf("First", "Second"), shown.map { it.guideName })
    }

    private fun channel(number: String, name: String = number) = Channel(
        guideNumber = number,
        guideName = name,
        streamUrl = "http://x/auto/v$number",
        hd = false,
        drm = false,
        favorite = false,
        videoCodec = null,
        audioCodec = null,
        frequencyHz = null,
        atsc3 = false,
    )
}
