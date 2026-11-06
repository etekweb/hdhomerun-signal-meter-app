package net.etekweb.hdhrmeter.ui

import android.content.res.Configuration
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRemoteTest {
    @Test
    fun televisionUiModeIsRecognized() {
        val television = Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES
        assertTrue(isTelevisionUi(television))
        assertFalse(isTelevisionUi(Configuration.UI_MODE_TYPE_NORMAL))
        assertFalse(isTelevisionUi(Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES))
    }

    @Test
    fun directionAndOkRevealTheControls() {
        val reveal = listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_SELECT,
        )
        reveal.forEach { key ->
            assertTrue(isPlayerRevealKeyCode(key))
            assertTrue(
                televisionRemoteRevealsOverlay(
                    television = true,
                    playing = true,
                    overlayVisible = false,
                    chromeVisible = true,
                    keyCode = key,
                ),
            )
        }
    }

    @Test
    fun revealIgnoresPhonesVisibleChromeAndOtherKeys() {
        assertFalse(
            televisionRemoteRevealsOverlay(
                television = false,
                playing = true,
                overlayVisible = false,
                chromeVisible = true,
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            ),
        )
        assertFalse(
            televisionRemoteRevealsOverlay(
                television = true,
                playing = true,
                overlayVisible = true,
                chromeVisible = true,
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            ),
        )
        assertFalse(
            televisionRemoteRevealsOverlay(
                television = true,
                playing = true,
                overlayVisible = false,
                chromeVisible = false,
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
            ),
        )
        assertFalse(isPlayerRevealKeyCode(KeyEvent.KEYCODE_BACK))
        assertFalse(isPlayerRevealKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertFalse(isPlayerRevealKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun backHidesTelevisionChromeBeforeLeaving() {
        assertTrue(
            televisionBackHidesPlayerOverlay(
                television = true,
                playing = true,
                overlayVisible = true,
                chromeVisible = true,
            ),
        )
        assertFalse(
            televisionBackHidesPlayerOverlay(
                television = true,
                playing = true,
                overlayVisible = false,
                chromeVisible = true,
            ),
        )
        assertFalse(
            televisionBackHidesPlayerOverlay(
                television = false,
                playing = true,
                overlayVisible = true,
                chromeVisible = true,
            ),
        )
        assertFalse(
            televisionBackHidesPlayerOverlay(
                television = true,
                playing = false,
                overlayVisible = true,
                chromeVisible = true,
            ),
        )
        assertFalse(
            televisionBackHidesPlayerOverlay(
                television = true,
                playing = true,
                overlayVisible = true,
                chromeVisible = false,
            ),
        )
    }

    @Test
    fun transportRowSkipsDisabledButtonsAndExitsRightIntoTabs() {
        val live = enabledTransportStops(controls = true, timeshift = true, liveButton = true)
        assertEquals(TransportStop.Rewind, adjacentOrNull(TransportStop.Pause, live, forward = false))
        assertEquals(TransportStop.Forward, adjacentOrNull(TransportStop.Pause, live, forward = true))
        assertEquals(TransportFocusTarget.Tabs, transportFocusTarget(TransportStop.Live, live, forward = true))
        assertEquals(TransportFocusTarget.Stay, transportFocusTarget(TransportStop.Start, live, forward = false))
        assertEquals(TransportStop.Live, rightmostTransport(live))

        val recording = enabledTransportStops(controls = true, timeshift = true, liveButton = false)
        assertEquals(
            TransportFocusTarget.Tabs,
            transportFocusTarget(TransportStop.Forward, recording, forward = true),
        )
        assertEquals(TransportStop.Forward, rightmostTransport(recording))

        val pausedLive = enabledTransportStops(controls = true, timeshift = false, liveButton = true)
        assertEquals(TransportFocusTarget.Stop(TransportStop.Live), transportFocusTarget(TransportStop.Pause, pausedLive, forward = true))
        assertEquals(TransportFocusTarget.Tabs, transportFocusTarget(TransportStop.Live, pausedLive, forward = true))
        assertFalse(TransportStop.Start in pausedLive)
        assertFalse(TransportStop.Forward in pausedLive)

        assertTrue(enabledTransportStops(controls = false, timeshift = true, liveButton = true).isEmpty())
        assertNull(rightmostTransport(emptySet()))
    }

    @Test
    fun infoTabsContinueTheRowAndReturnLeftToTheControls() {
        val liveTabs = overlayTabsFor(recording = false)
        assertEquals(TabFocusTarget.Tab(OverlayTab.Signal), tabFocusTarget(liveTabs, OverlayTab.Info, forward = true))
        assertEquals(TabFocusTarget.Tab(OverlayTab.Playback), tabFocusTarget(liveTabs, OverlayTab.Signal, forward = true))
        assertEquals(TabFocusTarget.Stay, tabFocusTarget(liveTabs, OverlayTab.Playback, forward = true))
        assertEquals(TabFocusTarget.Transport, tabFocusTarget(liveTabs, OverlayTab.Info, forward = false))
        assertEquals(TabFocusTarget.Tab(OverlayTab.Info), tabFocusTarget(liveTabs, OverlayTab.Signal, forward = false))

        val fileTabs = overlayTabsFor(recording = true)
        assertEquals(TabFocusTarget.Tab(OverlayTab.Playback), tabFocusTarget(fileTabs, OverlayTab.Info, forward = true))
        assertEquals(TabFocusTarget.Transport, tabFocusTarget(fileTabs, OverlayTab.Info, forward = false))
        assertEquals(TabFocusTarget.Stay, tabFocusTarget(fileTabs, OverlayTab.Playback, forward = true))
    }

    private fun adjacentOrNull(
        from: TransportStop,
        enabled: Set<TransportStop>,
        forward: Boolean,
    ): TransportStop? {
        val target = transportFocusTarget(from, enabled, forward)
        return (target as? TransportFocusTarget.Stop)?.stop
    }
}
