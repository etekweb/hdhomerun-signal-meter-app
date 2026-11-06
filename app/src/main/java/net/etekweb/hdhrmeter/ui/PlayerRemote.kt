package net.etekweb.hdhrmeter.ui

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration

/** Playback buttons in left-to-right order. Pause is where the remote lands. */
internal enum class TransportStop { Start, Rewind, Pause, Forward, Live }

internal sealed interface TransportFocusTarget {
    data class Stop(val stop: TransportStop) : TransportFocusTarget
    data object Tabs : TransportFocusTarget
    data object Stay : TransportFocusTarget
}

internal sealed interface TabFocusTarget {
    data class Tab(val tab: OverlayTab) : TabFocusTarget
    data object Transport : TabFocusTarget
    data object Stay : TabFocusTarget
}

/**
 * Focus targets for the television transport row and the info tabs beside it.
 * Phone layouts never request these.
 */
internal class TvPlayerFocus {
    val pause = FocusRequester()
    private val stops: Map<TransportStop, FocusRequester> =
        TransportStop.entries.associateWith { stop ->
            if (stop == TransportStop.Pause) pause else FocusRequester()
        }
    val tabs: Map<OverlayTab, FocusRequester> =
        OverlayTab.entries.associateWith { FocusRequester() }

    fun requester(stop: TransportStop): FocusRequester = stops.getValue(stop)
}

@Composable
internal fun isTelevision(): Boolean = isTelevisionUi(LocalConfiguration.current.uiMode)

/** True on Android TV and other television-mode devices, not on phones or tablets. */
internal fun isTelevisionUi(uiMode: Int): Boolean {
    return uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
}

/** Direction pad and OK. Media keys stay on their own path. */
internal fun isPlayerRevealKeyCode(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_SELECT,
    -> true
    else -> false
}

/**
 * While the picture is up on a television, Back dismisses the chrome first.
 * The next Back leaves playback, which is what phones already do.
 * Picture-in-picture has no chrome, so Back keeps the existing leave path.
 */
internal fun televisionBackHidesPlayerOverlay(
    television: Boolean,
    playing: Boolean,
    overlayVisible: Boolean,
    chromeVisible: Boolean,
): Boolean = television && playing && chromeVisible && overlayVisible

/** A hidden television picture treats the direction pad and OK as "show the controls". */
internal fun televisionRemoteRevealsOverlay(
    television: Boolean,
    playing: Boolean,
    overlayVisible: Boolean,
    chromeVisible: Boolean,
    keyCode: Int,
): Boolean {
    return television &&
        playing &&
        chromeVisible &&
        !overlayVisible &&
        isPlayerRevealKeyCode(keyCode)
}

/** Buttons that can take focus. Pause is always one of them while the row is on screen. */
internal fun enabledTransportStops(
    controls: Boolean,
    timeshift: Boolean,
    liveButton: Boolean,
): Set<TransportStop> {
    if (!controls) return emptySet()
    return buildSet {
        add(TransportStop.Pause)
        if (timeshift) {
            add(TransportStop.Start)
            add(TransportStop.Rewind)
            add(TransportStop.Forward)
        }
        if (liveButton) add(TransportStop.Live)
    }
}

internal fun enabledTransportStopsFor(state: UiState): Set<TransportStop> {
    val clock = state.playbackClock
    val file = state.screen is Screen.PlayRecording
    return enabledTransportStops(
        controls = clock.active && state.playbackError == null && !state.tunerReleased,
        timeshift = clock.timeshift,
        liveButton = !file && (clock.timeshift || !clock.playing),
    )
}

/** The control a Right press leaves when heading into the info tabs. */
internal fun rightmostTransport(enabled: Set<TransportStop>): TransportStop? {
    return TransportStop.entries.lastOrNull { it in enabled }
}

internal fun transportFocusTarget(
    from: TransportStop,
    enabled: Set<TransportStop>,
    forward: Boolean,
): TransportFocusTarget {
    val next = adjacentTransport(from, enabled, forward)
    return when {
        next != null -> TransportFocusTarget.Stop(next)
        forward -> TransportFocusTarget.Tabs
        else -> TransportFocusTarget.Stay
    }
}

internal fun tabFocusTarget(
    tabs: List<OverlayTab>,
    from: OverlayTab,
    forward: Boolean,
): TabFocusTarget {
    val index = tabs.indexOf(from)
    if (index < 0) return TabFocusTarget.Stay
    val nextIndex = if (forward) index + 1 else index - 1
    return when {
        nextIndex in tabs.indices -> TabFocusTarget.Tab(tabs[nextIndex])
        !forward -> TabFocusTarget.Transport
        else -> TabFocusTarget.Stay
    }
}

internal fun TvPlayerFocus.transportModifier(
    stop: TransportStop,
    enabled: Set<TransportStop>,
    entryTab: OverlayTab,
): Modifier {
    return Modifier
        .focusRequester(requester(stop))
        .focusProperties {
            left = focusRequesterFor(transportFocusTarget(stop, enabled, forward = false), entryTab)
            right = focusRequesterFor(transportFocusTarget(stop, enabled, forward = true), entryTab)
            // Remote back hides the chrome. Up should not land on the on-screen arrow, which leaves playback.
            up = FocusRequester.Cancel
        }
}

internal fun TvPlayerFocus.tabModifier(
    tab: OverlayTab,
    tabsInOrder: List<OverlayTab>,
    returnStop: TransportStop?,
): Modifier {
    val backToControls = returnStop?.let(::requester) ?: FocusRequester.Cancel
    return Modifier
        .focusRequester(tabs.getValue(tab))
        .focusProperties {
            left = focusRequesterFor(tabFocusTarget(tabsInOrder, tab, forward = false), backToControls)
            right = focusRequesterFor(tabFocusTarget(tabsInOrder, tab, forward = true), backToControls)
            up = FocusRequester.Cancel
        }
}

private fun TvPlayerFocus.focusRequesterFor(
    target: TransportFocusTarget,
    entryTab: OverlayTab,
): FocusRequester = when (target) {
    TransportFocusTarget.Stay -> FocusRequester.Cancel
    TransportFocusTarget.Tabs -> tabs.getValue(entryTab)
    is TransportFocusTarget.Stop -> requester(target.stop)
}

private fun TvPlayerFocus.focusRequesterFor(
    target: TabFocusTarget,
    backToControls: FocusRequester,
): FocusRequester = when (target) {
    TabFocusTarget.Stay -> FocusRequester.Cancel
    TabFocusTarget.Transport -> backToControls
    is TabFocusTarget.Tab -> tabs.getValue(target.tab)
}

private fun adjacentTransport(
    from: TransportStop,
    enabled: Set<TransportStop>,
    forward: Boolean,
): TransportStop? {
    val order = TransportStop.entries
    val step = if (forward) 1 else -1
    var index = order.indexOf(from) + step
    while (index in order.indices) {
        val stop = order[index]
        if (stop in enabled) return stop
        index += step
    }
    return null
}
