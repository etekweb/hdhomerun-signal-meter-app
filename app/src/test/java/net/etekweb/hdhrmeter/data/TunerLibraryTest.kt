package net.etekweb.hdhrmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunerLibraryTest {
    @Test
    fun discoveredTunersStayAheadOfSavedOnes() {
        val live = device("AAAA1111", "10.0.0.25", "Live")
        val older = device("AAAA1111", "10.0.0.25", "Saved name")
        val extra = device("BBBB2222", "10.0.0.30", "Shed")
        val merged = mergeTuners(listOf(live), listOf(older, extra))
        assertEquals(listOf("Live", "Shed"), merged.map { it.friendlyName })
        assertEquals(listOf("Live"), mergeTuners(listOf(live), listOf(device("CCCC3333", "10.0.0.25", "Other id"))).map { it.friendlyName })
        assertTrue(sameTuner(live, older))
        assertFalse(sameTuner(live, extra))
        assertEquals(listOf("Live"), rememberTuner(listOf(older), live).map { it.friendlyName })
        assertEquals(
            listOf("Shed", "Live"),
            rememberTuner(listOf(extra), live).map { it.friendlyName },
        )
    }

    @Test
    fun tunerJsonRoundTrips() {
        val original = device("ABCD1234", "192.168.1.20", "HDHomeRun FLEX")
        val decoded = decodeTuners(encodeTuners(listOf(original)))
        assertEquals(listOf(original.copy(firmwareName = "")), decoded)
        assertTrue(decodeTuners("not json").isEmpty())
        assertTrue(decodeTuners("[{}]").isEmpty())
    }

    @Test
    fun missingTunerMessageNamesTheLastAddress() {
        assertEquals(
            "No tuner found at 10.0.0.25. Choose a tuner in the Settings tab.",
            tunerUnavailableMessage("10.0.0.25"),
        )
        assertEquals(
            "No tuner selected. Choose a tuner in the Settings tab.",
            tunerUnavailableMessage(null),
        )
        assertEquals(
            "No tuner selected. Choose a tuner in the Settings tab.",
            tunerUnavailableMessage("  "),
        )
    }

    private fun device(id: String, host: String, name: String) = HdhrDevice(
        deviceId = id,
        friendlyName = name,
        model = "HDHR5-2US",
        firmwareVersion = "20251016",
        firmwareName = "hdhomerun",
        baseUrl = "http://$host",
        host = host,
        tunerCount = 2,
    )
}
