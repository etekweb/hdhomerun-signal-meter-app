package net.etekweb.hdhrmeter.ui

import net.etekweb.hdhrmeter.data.SignalReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun overlayTabDefaultsToInfoUnlessSignalWasStored() {
        assertEquals(OverlayTab.Info, overlayTabFromStored(null))
        assertEquals(OverlayTab.Info, overlayTabFromStored(""))
        assertEquals(OverlayTab.Info, overlayTabFromStored("info"))
        assertEquals(OverlayTab.Signal, overlayTabFromStored("Signal"))
    }

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
