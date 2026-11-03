package net.etekweb.hdhrmeter.ui

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLayoutTest {
    @Test
    fun portraitPhoneKeepsTheBottomBar() {
        assertFalse(useNavigationRail(Configuration.ORIENTATION_PORTRAIT, 360))
        assertFalse(useNavigationRail(Configuration.ORIENTATION_PORTRAIT, 411))
        assertFalse(useNavigationRail(Configuration.ORIENTATION_PORTRAIT, NavigationRailMinWidthDp - 1))
    }

    @Test
    fun landscapeUsesARail() {
        assertTrue(useNavigationRail(Configuration.ORIENTATION_LANDSCAPE, 320))
        assertTrue(useNavigationRail(Configuration.ORIENTATION_LANDSCAPE, 569))
        assertTrue(useNavigationRail(Configuration.ORIENTATION_LANDSCAPE, 914))
    }

    @Test
    fun widePortraitWindowUsesARail() {
        assertTrue(useNavigationRail(Configuration.ORIENTATION_PORTRAIT, NavigationRailMinWidthDp))
        assertTrue(useNavigationRail(Configuration.ORIENTATION_PORTRAIT, 840))
    }
}
