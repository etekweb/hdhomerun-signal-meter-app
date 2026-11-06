package net.etekweb.hdhrmeter.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecoderPathTest {
    @Test
    fun softwareStaysSoftwareEvenWhenTheDeviceHasADecoder() {
        assertEquals(false, usingHardwareDecoder(requested = false, videoPresent = true, available = true))
    }

    @Test
    fun hardwareIsUsedOnlyWhenTheDeviceHasThatDecoder() {
        assertEquals(true, usingHardwareDecoder(requested = true, videoPresent = true, available = true))
        assertEquals(false, usingHardwareDecoder(requested = true, videoPresent = true, available = false))
    }

    @Test
    fun unknownUntilAVideoTrackArrives() {
        assertNull(usingHardwareDecoder(requested = false, videoPresent = false, available = true))
        assertNull(usingHardwareDecoder(requested = true, videoPresent = false, available = true))
    }

    @Test
    fun videoMimeReadsTheFourccWhenTheCodecTextIsADescription() {
        val mpgv = fourcc('m', 'p', 'g', 'v')
        assertEquals("video/mpeg2", videoMimeOf("MPEG-1/2 Video", null, mpgv, null))
        assertEquals("video/avc", videoMimeOf(null, null, 0, "H264 - MPEG-4 AVC (part 10)"))
        assertEquals("video/hevc", videoMimeOf(null, null, 0, "MPEG-H Part2/HEVC (H.265)"))
        assertNull(videoMimeOf(null, null, 0, null))
    }

    private fun fourcc(a: Char, b: Char, c: Char, d: Char): Int {
        return a.code or (b.code shl 8) or (c.code shl 16) or (d.code shl 24)
    }
}
