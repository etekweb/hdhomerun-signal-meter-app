package net.etekweb.hdhrmeter.ui

import net.etekweb.hdhrmeter.data.GuideProgram
import net.etekweb.hdhrmeter.data.SignalReading
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
    fun overlayTabDefaultsToInfoUnlessSignalWasStored() {
        assertEquals(OverlayTab.Info, overlayTabFromStored(null))
        assertEquals(OverlayTab.Info, overlayTabFromStored(""))
        assertEquals(OverlayTab.Info, overlayTabFromStored("info"))
        assertEquals(OverlayTab.Signal, overlayTabFromStored("Signal"))
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
