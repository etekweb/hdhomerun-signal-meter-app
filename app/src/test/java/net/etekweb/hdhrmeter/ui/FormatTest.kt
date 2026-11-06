package net.etekweb.hdhrmeter.ui

import net.etekweb.hdhrmeter.data.GuideProgram
import net.etekweb.hdhrmeter.data.SignalReading
import net.etekweb.hdhrmeter.player.AudioReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class FormatTest {
    @Test
    fun rfChannelPrefersTheTunerValueAndKeepsModulation() {
        assertEquals("31 · 8VSB", rfChannelText(reading(rfChannel = 31, modulation = "8vsb"), null))
        assertEquals("7", rfChannelText(reading(rfChannel = 7, modulation = "none"), 575_000_000L))
        assertEquals("14 · QAM256", rfChannelText(reading(rfChannel = null, frequencyHz = 473_000_000L, modulation = "qam256"), null))
    }

    @Test
    fun rfChannelFallsBackToMegahertzWhenTheFrequencyIsNotABroadcastChannel() {
        assertEquals("100 MHz", rfChannelText(null, 100_000_000L))
        assertEquals("31 · 8VSB", rfChannelText(reading(rfChannel = null, frequencyHz = 575_000_000L, modulation = "8vsb"), null))
        assertNull(rfChannelText(null, null))
        assertNull(rfChannelText(reading(rfChannel = null, frequencyHz = null, modulation = null), null))
    }

    @Test
    fun pictureSizeMarksOnlyAKnownInterlacedStream() {
        assertEquals("720×480(i)", formatPictureSize(720, 480, true))
        assertEquals("1920×1080(i)", formatPictureSize(1920, 1080, true))
        assertEquals("1280×720", formatPictureSize(1280, 720, false))
        assertEquals("1920×1080", formatPictureSize(1920, 1080, null))
    }

    @Test
    fun decoderNamesThePathInUse() {
        assertEquals("Hardware", formatDecoder(true))
        assertEquals("Software", formatDecoder(false))
        assertEquals("—", formatDecoder(null))
    }

    @Test
    fun episodeSummaryPutsTheNumberBeforeTheName() {
        assertEquals("S02E05 · Hare Today", formatEpisodeSummary("S02E05", "Hare Today"))
        assertEquals("S02E05", formatEpisodeSummary(" S02E05 ", " "))
        assertEquals("Hare Today", formatEpisodeSummary(null, "Hare Today"))
        assertNull(formatEpisodeSummary(" ", null))
    }

    @Test
    fun channelGuideGroupsLocalDaysAndMarksTheCurrentAiring() {
        val zone = ZoneId.of("America/New_York")
        val now = zoned(2026, 11, 1, 20, 30, zone)
        val programs = listOf(
            program("News", zoned(2026, 11, 1, 20, 0, zone), zoned(2026, 11, 1, 21, 0, zone)),
            program("Late", zoned(2026, 11, 1, 23, 0, zone), zoned(2026, 11, 2, 0, 30, zone)),
            program("Morning", zoned(2026, 11, 2, 8, 0, zone), zoned(2026, 11, 2, 9, 0, zone)),
            program("Movie", zoned(2026, 11, 3, 20, 0, zone), zoned(2026, 11, 3, 22, 0, zone)),
        )
        val rows = channelGuideEntries(programs, now, zone, Locale.US)
        assertEquals(
            listOf("Today", "News", "Late", "Tomorrow", "Morning", "Tue, Nov 3", "Movie"),
            rows.map { row ->
                when (row) {
                    is ChannelGuideEntry.Day -> row.label
                    is ChannelGuideEntry.Airing -> row.program.title
                }
            },
        )
        val news = rows[1] as ChannelGuideEntry.Airing
        val late = rows[2] as ChannelGuideEntry.Airing
        assertEquals(true, news.onNow)
        assertEquals(false, late.onNow)
        assertEquals(
            "Today · 8:00 PM – 9:00 PM",
            formatGuideWhen(programs[0].startEpochMs, programs[0].stopEpochMs, now, zone, Locale.US),
        )
    }

    @Test
    fun signalAudioShowsDolbyDigitalPlusWhenTheDecoderStillSaysAc3() {
        val track = AudioReport(
            codec = "A52 Audio (aka AC3)",
            originalCodec = "A/52 B Audio (aka E-AC3)",
            fourcc = "a52",
            channels = 6,
            sampleRateHz = 48_000,
            bitrateBps = 384_000,
            language = "eng",
        )
        assertEquals(
            listOf(
                "Audio" to "Dolby Digital Plus (E-AC-3) · 5.1 · 48 kHz · 384 kbps · English",
                "Codec" to "a52 · A52 Audio (aka AC3) · original A/52 B Audio (aka E-AC3)",
                "Lineup" to "AC-3",
            ),
            signalAudioRows(listOf(track), "AC3"),
        )
        assertEquals(
            "Passthrough. This device can send Dolby Digital Plus (E-AC-3) to the TV.",
            audioOutputNoteFor(listOf(track), "AC3", passthrough = true),
        )
    }

    @Test
    fun signalAudioKeepsPlainDolbyDigitalAndHidesAMatchingLineup() {
        val track = AudioReport(
            codec = "A52 Audio (aka AC3)",
            fourcc = "a52",
            channels = 2,
            sampleRateHz = 44_100,
            bitrateBps = 192_000,
            language = "eng",
            description = "English",
        )
        assertEquals(
            listOf(
                "Audio" to "AC-3 · stereo · 44.1 kHz · 192 kbps · English",
                "Codec" to "a52 · A52 Audio (aka AC3)",
            ),
            signalAudioRows(listOf(track), "AC3"),
        )
        assertEquals(
            "Decoded here. This device decodes AC-3 before it reaches the TV.",
            audioOutputNoteFor(listOf(track), "AC3", passthrough = false),
        )
    }

    @Test
    fun signalAudioMarksAtmosAndListsTheOtherTrack() {
        val surround = AudioReport(
            id = 1,
            codec = "Enhanced Audio Coding 3 (E-AC-3)",
            fourcc = "eac3",
            channels = 6,
            sampleRateHz = 48_000,
            bitrateBps = 448_000,
            language = "eng",
            profile = 30,
            playing = true,
        )
        val stereo = AudioReport(
            id = 2,
            codec = "MPEG AAC Audio",
            fourcc = "mp4a",
            channels = 2,
            sampleRateHz = 48_000,
            bitrateBps = 128_000,
            language = "spa",
            description = "Visually impaired",
        )
        assertEquals(
            listOf(
                "Audio 1" to "Dolby Atmos (E-AC-3) · 5.1 · 48 kHz · 448 kbps · English · playing",
                "Codec 1" to "eac3 · Enhanced Audio Coding 3 (E-AC-3) · profile 30",
                "Audio 2" to "AAC · stereo · 48 kHz · 128 kbps · Spanish · Visually impaired",
                "Codec 2" to "mp4a · MPEG AAC Audio",
                "Lineup" to "AC-3",
            ),
            signalAudioRows(listOf(surround, stereo), "AC3"),
        )
    }

    @Test
    fun signalAudioFallsBackToTheLineupUntilATrackArrives() {
        assertEquals(listOf("Audio" to "AC-3"), signalAudioRows(emptyList(), "AC3"))
        assertEquals(emptyList<Pair<String, String>>(), signalAudioRows(emptyList(), null))
        assertNull(audioOutputNoteFor(emptyList(), "AC3", passthrough = true))
    }

    @Test
    fun overlayTabDefaultsToInfoUnlessAKnownPageWasStored() {
        assertEquals(OverlayTab.Info, overlayTabFromStored(null))
        assertEquals(OverlayTab.Info, overlayTabFromStored(""))
        assertEquals(OverlayTab.Info, overlayTabFromStored("info"))
        assertEquals(OverlayTab.Signal, overlayTabFromStored("Signal"))
        assertEquals(OverlayTab.Playback, overlayTabFromStored("Playback"))
    }

    @Test
    fun recordingsHideTheSignalPageAndKeepPlayback() {
        assertEquals(listOf(OverlayTab.Info, OverlayTab.Signal, OverlayTab.Playback), overlayTabsFor(false))
        assertEquals(listOf(OverlayTab.Info, OverlayTab.Playback), overlayTabsFor(true))
        assertEquals(OverlayTab.Info, visibleOverlayTab(OverlayTab.Signal, recording = true))
        assertEquals(OverlayTab.Playback, visibleOverlayTab(OverlayTab.Playback, recording = true))
        assertEquals(OverlayTab.Signal, visibleOverlayTab(OverlayTab.Signal, recording = false))
    }

    private fun zoned(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId): Long {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    }

    private fun program(title: String, startMs: Long, stopMs: Long) = GuideProgram(
        title = title,
        subtitle = "Episode name",
        description = "A long description of $title.",
        episode = "S01E01",
        startEpochMs = startMs,
        stopEpochMs = stopMs,
    )

    private fun reading(
        rfChannel: Int?,
        frequencyHz: Long? = 575_000_000L,
        modulation: String?,
    ) = SignalReading(
        tunerIndex = 0,
        virtualChannel = "2.1",
        virtualName = "WJBK",
        strengthPercent = 80,
        qualityPercent = 90,
        symbolPercent = 100,
        strengthDetail = null,
        qualityDetail = null,
        rfChannel = rfChannel,
        frequencyHz = frequencyHz,
        modulation = modulation,
        channelRateBps = null,
        locked = true,
    )
}
