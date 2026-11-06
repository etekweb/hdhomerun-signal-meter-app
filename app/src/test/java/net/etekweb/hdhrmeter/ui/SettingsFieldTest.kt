package net.etekweb.hdhrmeter.ui

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFieldTest {
    @Test
    fun remoteSelectActivatesTheField() {
        val select = listOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_SELECT,
        )
        select.forEach { key -> assertTrue(settingsFieldAcceptsRemoteSelect(key)) }
        assertFalse(settingsFieldAcceptsRemoteSelect(KeyEvent.KEYCODE_DPAD_UP))
        assertFalse(settingsFieldAcceptsRemoteSelect(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(settingsFieldAcceptsRemoteSelect(KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(settingsFieldAcceptsRemoteSelect(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(settingsFieldAcceptsRemoteSelect(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun televisionFieldStaysClosedUntilSelected() {
        assertTrue(settingsFieldReadOnly(television = true, editing = false))
        assertFalse(settingsFieldReadOnly(television = true, editing = true))
        assertFalse(settingsFieldReadOnly(television = false, editing = false))
        assertFalse(settingsFieldReadOnly(television = false, editing = true))
    }
}
