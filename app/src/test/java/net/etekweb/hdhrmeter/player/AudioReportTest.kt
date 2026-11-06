package net.etekweb.hdhrmeter.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioReportTest {
    @Test
    fun decoderAc3DoesNotHideAnEac3Stream() {
        val track = AudioReport(
            codec = "A52 Audio (aka AC3)",
            originalCodec = "A/52 B Audio (aka E-AC3)",
            fourcc = "a52",
        )
        assertEquals(AudioFamily.Eac3, audioFamilyOf(track))
        assertEquals("audio/eac3", audioMimeOf(track))
        assertFalse(audioIsAtmos(track))
    }

    @Test
    fun plainAc3StaysDolbyDigital() {
        val track = AudioReport(codec = "A52 Audio (aka AC3)", fourcc = "a52")
        assertEquals(AudioFamily.Ac3, audioFamilyOf(track))
        assertEquals("audio/ac3", audioMimeOf(track))
        assertEquals(AudioFamily.Eac3, audioFamilyOf("Enhanced Audio Coding 3 (E-AC-3)"))
        assertEquals(AudioFamily.Ac4, audioFamilyOf("ac-4"))
        assertEquals(AudioFamily.Aac, audioFamilyOf("MPEG AAC Audio"))
    }

    @Test
    fun atmosProfileAndJocTextAreRecognized() {
        val profile = AudioReport(fourcc = "eac3", profile = EAC3_ATMOS_PROFILE)
        val named = AudioReport(codec = "E-AC-3 JOC", fourcc = "eac3")
        assertTrue(audioIsAtmos(profile))
        assertTrue(audioIsAtmos(named))
        assertFalse(audioIsAtmos(AudioReport(fourcc = "a52", profile = EAC3_ATMOS_PROFILE)))
    }

    @Test
    fun fourccTextReadsLittleEndianCodes() {
        assertEquals("eac3", fourccText(fourcc("eac3")))
        assertEquals("a52", fourccText(fourcc("a52 ")))
        assertEquals("ec-3", fourccText(fourcc("ec-3")))
        assertNull(fourccText(0))
    }

    @Test
    fun heardAudioPrefersThePlayingTrack() {
        val first = AudioReport(id = 1, fourcc = "eac3")
        val second = AudioReport(id = 2, fourcc = "mp4a", playing = true)
        assertEquals(second, heardAudio(listOf(first, second)))
        assertEquals(first, heardAudio(listOf(first)))
        assertNull(heardAudio(emptyList()))
    }

    private fun fourcc(text: String): Int {
        val padded = text.padEnd(4, ' ').take(4)
        var value = 0
        padded.forEachIndexed { index, char ->
            value = value or (char.code shl (8 * index))
        }
        return value
    }
}
