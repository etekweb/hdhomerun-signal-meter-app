package net.etekweb.hdhrmeter.data

import net.etekweb.hdhrmeter.ui.Screen
import net.etekweb.hdhrmeter.ui.playingGuideNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelStretchTest {
    @Test
    fun blankChannelNumbersDoNotGetAStretch() {
        assertNull(channelStretchKey(null))
        assertNull(channelStretchKey("  "))
        assertEquals(ChannelStretch(), stretchFor(null, mapOf("5.1" to stretched())))
    }

    @Test
    fun liveAndRecordedItemsShareTheCanonicalChannel() {
        val stored = mapOf("5.1" to stretched())
        assertEquals(stretched(), stretchFor("05.1", stored))
        assertEquals(stretched(), stretchFor("5.1", stored))
        assertEquals("5.1", channelStretchKey(playingGuideNumber(Screen.Watch(device(), channel("05.1")))))
        assertEquals(
            "5.1",
            channelStretchKey(playingGuideNumber(Screen.PlayRecording(recording("05.1")))),
        )
        assertNull(channelStretchKey(playingGuideNumber(Screen.PlayRecording(recording(null)))))
    }

    @Test
    fun turningStretchOffKeepsTheSlidersAndLeavesThePictureAlone() {
        val saved = ChannelStretch(enabled = false, widthPercent = 133, heightPercent = 80)
        assertEquals(1f to 1f, saved.pictureScale())
        assertEquals(133 to 80, saved.widthPercent to saved.heightPercent)
        assertEquals(133f / 100f to 80f / 100f, saved.copy(enabled = true).pictureScale())
    }

    @Test
    fun storedStretchRoundTripsAndDropsBrokenLines() {
        val encoded = encodeChannelStretches(
            mapOf(
                "05.1" to ChannelStretch(enabled = true, widthPercent = 133, heightPercent = 80),
                "7.1" to ChannelStretch(enabled = false, widthPercent = 150, heightPercent = 100),
                "bad\tkey" to stretched(),
            ),
        )
        assertEquals(
            "5.1\t1\t133\t80\n7.1\t0\t150\t100",
            encoded,
        )
        val decoded = decodeChannelStretches(
            "noise\n$encoded\n11\t1\t10\t900\n",
        )
        assertEquals(
            ChannelStretch(enabled = true, widthPercent = 133, heightPercent = 80),
            decoded["5.1"],
        )
        assertEquals(
            ChannelStretch(enabled = false, widthPercent = 150, heightPercent = 100),
            decoded["7.1"],
        )
        assertEquals(
            ChannelStretch(enabled = true, widthPercent = 50, heightPercent = 200),
            decoded["11"],
        )
    }

    private fun stretched() = ChannelStretch(enabled = true, widthPercent = 120, heightPercent = 90)

    private fun channel(number: String) = Channel(
        guideNumber = number,
        guideName = "WJBK",
        streamUrl = "http://x/auto/v$number",
        hd = true,
        drm = false,
        favorite = false,
        videoCodec = null,
        audioCodec = null,
        frequencyHz = null,
        atsc3 = false,
    )

    private fun device() = HdhrDevice(
        deviceId = "1",
        friendlyName = "HDHomeRun",
        model = "",
        firmwareVersion = "",
        firmwareName = "",
        baseUrl = "http://x",
        host = "x",
        tunerCount = 1,
    )

    private fun recording(number: String?) = Recording(
        id = "file",
        title = "News",
        episodeTitle = null,
        episodeNumber = null,
        synopsis = null,
        channelNumber = number,
        channelName = "WJBK",
        startEpochMs = null,
        endEpochMs = null,
        recordStartEpochMs = null,
        recordEndEpochMs = null,
        originalAirdateEpochSec = null,
        stillRecording = false,
        problem = null,
        sourceName = null,
    )
}
