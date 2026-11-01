package net.etekweb.hdhrmeter.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.libvlc.interfaces.IMedia

class DisplaySizeTest {
    @Test
    fun squarePixelsPassThrough() {
        assertEquals(1920 to 1080, displayResolution(1920, 1080, 1, 1))
        assertEquals(1280 to 720, displayResolution(1280, 720, 0, 0))
        assertEquals(640 to 480, displayResolution(640, 480, 1, 0))
        assertEquals(3840 to 2160, displayResolution(3840, 2160, 1, 1))
    }

    @Test
    fun mpeg2PaddingBecomes1080() {
        assertEquals(1920 to 1080, displayResolution(1920, 1088, 1, 1))
        // Sample aspect built from the padded height. Shortening the height
        // without the 135/136 correction would report 1934×1080.
        assertEquals(1920 to 1080, displayResolution(1920, 1088, 136, 135))
        assertEquals(1920 to 1080, displayResolution(1440, 1088, 4, 3))
    }

    @Test
    fun anamorphicSizeMatchesThePicture() {
        assertEquals(1920 to 1080, displayResolution(1440, 1080, 4, 3))
        assertEquals(853 to 480, displayResolution(720, 480, 32, 27))
        assertEquals(640 to 480, displayResolution(720, 480, 8, 9))
        assertEquals(853 to 480, displayResolution(704, 480, 40, 33))
        assertEquals(640 to 480, displayResolution(704, 480, 10, 11))
    }

    @Test
    fun real1080KeepsAnamorphicSampleAspect() {
        assertEquals(1934 to 1080, displayResolution(1920, 1080, 136, 135))
    }

    @Test
    fun otherStoredHeightsStayStoredUntilTheDrawnSizeIsKnown() {
        assertEquals(1280 to 768, displayResolution(1280, 768, 1, 1))
        assertEquals(3840 to 2176, displayResolution(3840, 2176, 1, 1))
        assertEquals(720 to 512, displayResolution(720, 512, 1, 1))
    }

    @Test
    fun rotationSwapsTheDisplayedEdges() {
        val sideways = IMedia.VideoTrack.Orientation.RightTop
        assertEquals(1080 to 1920, displayResolution(1920, 1080, 1, 1, sideways))
        assertEquals(480 to 640, displayResolution(720, 480, 8, 9, sideways))
        assertEquals(1920 to 1080, displayResolution(1920, 1080, 1, 1, IMedia.VideoTrack.Orientation.BottomRight))
    }

    @Test
    fun unknownSizeStaysUnknown() {
        assertNull(displayResolution(0, 1080, 1, 1))
        assertNull(displayResolution(1920, 0, 1, 1))
        assertNull(displayResolution(-1, 1080, 1, 1))
    }

    @Test
    fun codingPaddingIsOnlyAShortAlignedExtension() {
        assertTrue(fitsCodingAlignment(1920, 1920))
        assertTrue(fitsCodingAlignment(1088, 1080))
        assertTrue(fitsCodingAlignment(2176, 2160))
        assertTrue(fitsCodingAlignment(768, 720))
        assertTrue(fitsCodingAlignment(512, 480))
        assertTrue(fitsCodingAlignment(736, 720))
        assertFalse(fitsCodingAlignment(1920, 1280))
        assertFalse(fitsCodingAlignment(1080, 720))
        assertFalse(fitsCodingAlignment(1090, 1080))
        assertFalse(fitsCodingAlignment(0, 1080))
    }
}
