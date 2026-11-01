package net.etekweb.hdhrmeter.ui

import android.app.Application
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.etekweb.hdhrmeter.data.Channel
import net.etekweb.hdhrmeter.data.DeviceStore
import net.etekweb.hdhrmeter.data.previewHop
import net.etekweb.hdhrmeter.data.GuideIndex
import net.etekweb.hdhrmeter.data.HdhrClient
import net.etekweb.hdhrmeter.data.HdhrDevice
import net.etekweb.hdhrmeter.data.SignalReading
import net.etekweb.hdhrmeter.data.rememberViewedChannel
import net.etekweb.hdhrmeter.data.guideUrlForInput
import net.etekweb.hdhrmeter.data.hostOf
import net.etekweb.hdhrmeter.data.isIdle
import net.etekweb.hdhrmeter.data.mergeSignal
import net.etekweb.hdhrmeter.data.rewriteStreamUrl
import net.etekweb.hdhrmeter.data.selectTuner
import net.etekweb.hdhrmeter.data.withMobileTranscode
import net.etekweb.hdhrmeter.player.EnginePhase
import net.etekweb.hdhrmeter.player.PlaybackEngine
import net.etekweb.hdhrmeter.player.VideoStats
import net.etekweb.hdhrmeter.player.playbackFailure
import org.videolan.libvlc.util.VLCVideoLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quiet time after the last channel tap before the tuner is changed. */
private const val CHANNEL_HOP_MS = 500L

enum class ChannelFilter { All, Hd, Favorites, Recents }

/** Player overlay page. [Info] is the default until the viewer picks [Signal]. */
enum class OverlayTab { Info, Signal }

internal fun overlayTabFromStored(raw: String?): OverlayTab {
    return if (raw == OverlayTab.Signal.name) OverlayTab.Signal else OverlayTab.Info
}

enum class PlaybackPhase { Idle, Tuning, Buffering, Ready }

sealed interface Screen {
    data object Devices : Screen
    data class Channels(val device: HdhrDevice) : Screen
    data class Watch(val device: HdhrDevice, val channel: Channel) : Screen
}

data class SignalPeaks(
    val strength: Int? = null,
    val quality: Int? = null,
    val symbol: Int? = null,
) {
    fun raise(reading: SignalReading) = SignalPeaks(
        strength = upper(strength, reading.strengthPercent),
        quality = upper(quality, reading.qualityPercent),
        symbol = upper(symbol, reading.symbolPercent),
    )

    private fun upper(current: Int?, sample: Int?): Int? = when {
        current == null -> sample
        sample == null -> current
        else -> maxOf(current, sample)
    }
}

data class UiState(
    val screen: Screen = Screen.Devices,
    val devices: List<HdhrDevice> = emptyList(),
    val recent: HdhrDevice? = null,
    val scanning: Boolean = false,
    val connecting: Boolean = false,
    val scanError: String? = null,
    val manualHost: String = "",
    val guideHost: String = "",
    val appliedGuideHost: String = "",
    val guide: GuideIndex? = null,
    val guideNowMs: Long = System.currentTimeMillis(),
    val guideLoading: Boolean = false,
    val guideError: String? = null,
    val channels: List<Channel> = emptyList(),
    val channelsLoading: Boolean = false,
    val channelsError: String? = null,
    val query: String = "",
    val filter: ChannelFilter = ChannelFilter.All,
    val recentChannels: List<String> = emptyList(),
    /** Channel to reveal when the guide returns from the player. */
    val returnToChannel: Channel? = null,
    val pendingChannel: Channel? = null,
    val signal: SignalReading? = null,
    val peaks: SignalPeaks = SignalPeaks(),
    val signalNote: String? = null,
    val video: VideoStats = VideoStats(),
    val phase: PlaybackPhase = PlaybackPhase.Idle,
    val playbackError: String? = null,
    val offerTranscode: Boolean = false,
    val transcoding: Boolean = false,
    val overlayVisible: Boolean = true,
    /** Kept while the overlay is hidden, the channel changes, and the process restarts. */
    val overlayTab: OverlayTab = OverlayTab.Info,
    val tunerReleased: Boolean = false,
)

class MeterViewModel(app: Application) : AndroidViewModel(app) {
    private val client = HdhrClient(app)
    private val store = DeviceStore(app)
    private val engine = PlaybackEngine(app)

    private val _ui = MutableStateFlow(
        UiState(
            manualHost = store.loadHost(),
            guideHost = store.loadGuideHost(),
            recent = store.loadRecent(),
            overlayTab = overlayTabFromStored(store.loadOverlayTab()),
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var scanJob: Job? = null
    private var guideJob: Job? = null
    private var guideClockJob: Job? = null
    private var guideGeneration = 0
    private var playJob: Job? = null
    private var pollJob: Job? = null
    private var hopJob: Job? = null
    private var playGeneration = 0
    private var hopGeneration = 0
    private var preferredTuner: Int? = null
    private var localIp: String? = null
    private var usingSpecificTuner = false
    private var autoRetried = false
    private var wifiLock: WifiManager.WifiLock? = null

    init {
        viewModelScope.launch {
            engine.stats.collect { stats -> _ui.update { it.copy(video = stats) } }
        }
        viewModelScope.launch {
            engine.phase.collect { phase ->
                _ui.update { it.copy(phase = phase.toPlaybackPhase()) }
            }
        }
        viewModelScope.launch {
            engine.error.collect { error ->
                if (error == null) return@collect
                val screen = _ui.value.screen as? Screen.Watch ?: return@collect
                if (usingSpecificTuner && !autoRetried && error.hdhrCode == 804) {
                    autoRetried = true
                    tune(screen.device, screen.channel, transcode = _ui.value.transcoding, forceAuto = true)
                    return@collect
                }
                _ui.update {
                    it.copy(
                        playbackError = error.message,
                        offerTranscode = error.offerTranscode && !it.transcoding,
                        phase = PlaybackPhase.Idle,
                    )
                }
            }
        }
        if (_ui.value.guideHost.isNotBlank()) refreshGuide()
        scan()
    }

    fun scan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _ui.update { it.copy(scanning = true, scanError = null) }
            val result = withContext(Dispatchers.IO) { runCatching { client.discover() } }
            result.onSuccess { devices ->
                _ui.update {
                    it.copy(
                        scanning = false,
                        devices = devices,
                        scanError = if (devices.isEmpty()) {
                            "No HDHomeRun answered. Enter the tuner IP address."
                        } else {
                            null
                        },
                    )
                }
            }.onFailure { error ->
                _ui.update {
                    it.copy(scanning = false, scanError = error.message ?: "Discovery failed.")
                }
            }
        }
    }

    fun setManualHost(value: String) {
        _ui.update { it.copy(manualHost = value) }
    }

    fun setGuideHost(value: String) {
        _ui.update { it.copy(guideHost = value) }
    }

    fun saveGuide() {
        val raw = _ui.value.guideHost.trim()
        if (raw.isEmpty()) {
            guideGeneration++
            guideJob?.cancel()
            guideClockJob?.cancel()
            store.saveGuideHost("")
            _ui.update {
                it.copy(
                    guideHost = "",
                    appliedGuideHost = "",
                    guide = null,
                    guideLoading = false,
                    guideError = null,
                )
            }
            return
        }
        if (guideUrlForInput(raw) == null) {
            _ui.update { it.copy(guideError = "Enter an IP address or hostname.") }
            return
        }
        store.saveGuideHost(raw)
        _ui.update { it.copy(guideHost = raw, guideError = null) }
        refreshGuide()
    }

    fun connectManual() {
        val host = _ui.value.manualHost
        viewModelScope.launch {
            _ui.update { it.copy(connecting = true, scanError = null) }
            val result = withContext(Dispatchers.IO) { runCatching { client.deviceForHost(host) } }
            result.onSuccess { device ->
                store.saveHost(host.trim())
                store.saveRecent(device)
                _ui.update { state ->
                    val rest = state.devices.filter { it.deviceId != device.deviceId }
                    state.copy(
                        connecting = false,
                        devices = listOf(device) + rest,
                        recent = device,
                    )
                }
                open(device)
            }.onFailure { error ->
                _ui.update {
                    it.copy(connecting = false, scanError = error.message ?: "Could not reach that address.")
                }
            }
        }
    }

    fun open(device: HdhrDevice) {
        store.saveRecent(device)
        _ui.update {
            it.copy(
                screen = Screen.Channels(device),
                recent = device,
                channels = emptyList(),
                channelsLoading = true,
                channelsError = null,
                query = "",
                recentChannels = store.loadRecentChannels(device.deviceId),
                returnToChannel = null,
            )
        }
        loadLineup(device, keepExisting = false)
    }

    fun refreshLineup() {
        val device = (_ui.value.screen as? Screen.Channels)?.device ?: return
        loadLineup(device, keepExisting = true)
        if (store.loadGuideHost().isNotBlank()) refreshGuide()
    }

    fun setQuery(value: String) {
        _ui.update { it.copy(query = value) }
    }

    fun setFilter(filter: ChannelFilter) {
        _ui.update { it.copy(filter = filter, returnToChannel = null) }
    }

    fun consumeReturnChannel() {
        _ui.update { state ->
            if (state.returnToChannel == null) state else state.copy(returnToChannel = null)
        }
    }

    fun watch(channel: Channel) {
        val device = when (val screen = _ui.value.screen) {
            is Screen.Watch -> screen.device
            is Screen.Channels -> screen.device
            Screen.Devices -> return
        }
        if (channel.drm) {
            _ui.update {
                it.copy(channelsError = "${channel.guideNumber} is copy protected and cannot be played here.")
            }
            return
        }
        cancelChannelHop()
        _ui.update {
            it.copy(
                screen = Screen.Watch(device, channel),
                channelsError = null,
                overlayVisible = true,
                transcoding = false,
                pendingChannel = null,
            )
        }
        tune(device, channel, transcode = false, forceAuto = false)
    }

    fun tryTranscode() {
        val screen = _ui.value.screen as? Screen.Watch ?: return
        cancelChannelHop()
        _ui.update { it.copy(transcoding = true, pendingChannel = null) }
        tune(screen.device, screen.channel, transcode = true, forceAuto = false)
    }

    fun resume() {
        val screen = _ui.value.screen as? Screen.Watch ?: return
        cancelChannelHop()
        tune(screen.device, screen.channel, transcode = _ui.value.transcoding, forceAuto = false)
    }

    fun stepChannel(delta: Int) {
        val screen = _ui.value.screen as? Screen.Watch ?: return
        val next = previewHop(_ui.value.channels, screen.channel, _ui.value.pendingChannel, delta)
        val generation = ++hopGeneration
        hopJob?.cancel()
        if (next == null) {
            _ui.update { state ->
                if (state.pendingChannel == null) state else state.copy(pendingChannel = null)
            }
            return
        }
        val device = screen.device
        _ui.update { it.copy(pendingChannel = next) }
        hopJob = viewModelScope.launch {
            delay(CHANNEL_HOP_MS)
            if (generation != hopGeneration) return@launch
            _ui.update { state ->
                val watching = state.screen as? Screen.Watch
                if (watching == null || state.pendingChannel == null || generation != hopGeneration) {
                    state
                } else {
                    state.copy(
                        pendingChannel = null,
                        screen = Screen.Watch(watching.device, next),
                        transcoding = false,
                    )
                }
            }
            if (generation != hopGeneration) return@launch
            val watching = _ui.value.screen as? Screen.Watch ?: return@launch
            if (watching.channel.guideNumber != next.guideNumber || watching.channel.streamUrl != next.streamUrl) {
                return@launch
            }
            tune(device, next, transcode = false, forceAuto = false)
        }
    }

    fun toggleOverlay() {
        _ui.update { it.copy(overlayVisible = !it.overlayVisible) }
    }

    fun selectOverlayTab(tab: OverlayTab) {
        if (_ui.value.overlayTab == tab) return
        _ui.update { it.copy(overlayTab = tab) }
        store.saveOverlayTab(tab.name)
    }

    fun showOverlay() {
        _ui.update { state ->
            if (state.overlayVisible) state else state.copy(overlayVisible = true)
        }
    }

    fun attachVideo(layout: VLCVideoLayout) {
        engine.attach(layout)
    }

    fun detachVideo(layout: VLCVideoLayout) {
        engine.detach(layout)
    }

    fun onBack() {
        when (val screen = _ui.value.screen) {
            is Screen.Watch -> {
                cancelChannelHop()
                _ui.update {
                    it.copy(
                        screen = Screen.Channels(screen.device),
                        playbackError = null,
                        signal = null,
                        signalNote = null,
                        tunerReleased = false,
                        offerTranscode = false,
                        pendingChannel = null,
                        returnToChannel = screen.channel,
                    )
                }
                // The guide is already showing. Stopping the stream can block
                // while the tuner socket closes, so it runs off the UI thread.
                stopPlayback()
            }
            is Screen.Channels -> _ui.update { it.copy(screen = Screen.Devices, channelsError = null) }
            Screen.Devices -> Unit
        }
    }

    fun onHostStop() {
        if (_ui.value.screen !is Screen.Watch || _ui.value.tunerReleased) return
        cancelChannelHop()
        stopPlayback()
        _ui.update {
            it.copy(
                tunerReleased = true,
                phase = PlaybackPhase.Idle,
                signal = null,
                playbackError = null,
                pendingChannel = null,
            )
        }
    }

    override fun onCleared() {
        guideJob?.cancel()
        guideClockJob?.cancel()
        hopJob?.cancel()
        stopPlayback()
        engine.release()
    }

    private fun refreshGuide() {
        val raw = store.loadGuideHost()
        val url = guideUrlForInput(raw) ?: return
        val label = hostOf(url).orEmpty()
        val generation = ++guideGeneration
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            _ui.update { state ->
                val same = state.appliedGuideHost == label && state.guide != null
                state.copy(
                    guideLoading = true,
                    guideError = null,
                    guide = if (same) state.guide else null,
                    appliedGuideHost = if (same) state.appliedGuideHost else "",
                )
            }
            val result = withContext(Dispatchers.IO) { runCatching { client.loadGuide(url) } }
            if (generation != guideGeneration) return@launch
            result.onSuccess { index ->
                _ui.update {
                    it.copy(
                        guideLoading = false,
                        guide = index,
                        guideNowMs = System.currentTimeMillis(),
                        guideError = null,
                        appliedGuideHost = label,
                    )
                }
                startGuideClock()
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        guideLoading = false,
                        guideError = error.message ?: "Could not load the guide.",
                    )
                }
            }
        }
    }

    private fun startGuideClock() {
        if (guideClockJob?.isActive == true) return
        guideClockJob = viewModelScope.launch {
            while (isActive) {
                val index = _ui.value.guide ?: break
                val now = System.currentTimeMillis()
                val boundary = index.nextBoundary(now)
                val wait = if (boundary == null) {
                    30_000L
                } else {
                    (boundary - now + 250L).coerceIn(1_000L, 30_000L)
                }
                delay(wait)
                if (!isActive) return@launch
                _ui.update { state ->
                    if (state.guide == null) state else state.copy(guideNowMs = System.currentTimeMillis())
                }
            }
        }
    }

    private fun loadLineup(device: HdhrDevice, keepExisting: Boolean) {
        viewModelScope.launch {
            _ui.update { it.copy(channelsLoading = true, channelsError = null) }
            val result = withContext(Dispatchers.IO) { runCatching { client.lineup(device) } }
            val stillHere = (_ui.value.screen as? Screen.Channels)?.device?.deviceId == device.deviceId
            if (!stillHere) return@launch
            result.onSuccess { channels ->
                _ui.update {
                    it.copy(
                        channelsLoading = false,
                        channels = channels,
                        channelsError = if (channels.isEmpty()) {
                            "No channels yet. Run a channel scan in the HDHomeRun app, then refresh."
                        } else {
                            null
                        },
                    )
                }
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        channelsLoading = false,
                        channels = if (keepExisting) it.channels else emptyList(),
                        channelsError = error.message ?: "Could not load channels.",
                    )
                }
            }
        }
    }

    private fun tune(device: HdhrDevice, channel: Channel, transcode: Boolean, forceAuto: Boolean) {
        rememberViewed(device.deviceId, channel.guideNumber)
        if (!forceAuto) autoRetried = false
        val generation = ++playGeneration
        playJob?.cancel()
        pollJob?.cancel()
        acquireWifi()
        _ui.update {
            it.copy(
                signal = null,
                peaks = SignalPeaks(),
                signalNote = null,
                video = VideoStats(),
                playbackError = null,
                offerTranscode = false,
                tunerReleased = false,
                phase = PlaybackPhase.Tuning,
                transcoding = transcode,
            )
        }
        playJob = viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                val tuner = if (forceAuto) {
                    null
                } else {
                    runCatching { client.status(device).filter { row -> row.isIdle() }.mapNotNull { row -> row.index }.minOrNull() }
                        .getOrNull()
                }
                var url = rewriteStreamUrl(channel.streamUrl, device.host, tuner)
                if (transcode) url = withMobileTranscode(url)
                PreparedStream(url, tuner, client.localAddressToward(device.host))
            }
            if (generation != playGeneration) return@launch
            val failure = withContext(Dispatchers.IO) { client.probeStream(prepared.url) }
            if (generation != playGeneration) return@launch
            preferredTuner = prepared.tunerIndex
            localIp = prepared.localIp
            usingSpecificTuner = prepared.tunerIndex != null
            if (failure != null) {
                if (usingSpecificTuner && !autoRetried && failure.hdhrCode == 804) {
                    autoRetried = true
                    tune(device, channel, transcode = transcode, forceAuto = true)
                    return@launch
                }
                val playback = playbackFailure(failure)
                _ui.update {
                    it.copy(
                        playbackError = playback.message,
                        offerTranscode = false,
                        phase = PlaybackPhase.Idle,
                    )
                }
                return@launch
            }
            engine.play(prepared.url)
            pollSignal(generation, device, channel)
        }
    }

    private fun pollSignal(generation: Int, device: HdhrDevice, channel: Channel) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var misses = 0
            while (isActive && generation == playGeneration) {
                val started = SystemClock.elapsedRealtime()
                val reading = withContext(Dispatchers.IO) {
                    runCatching { readSignal(device, channel) }.getOrNull()
                }
                if (generation != playGeneration) return@launch
                if (reading == null) {
                    misses++
                    if (misses >= 4) {
                        _ui.update { it.copy(signalNote = "Waiting for tuner status…") }
                    }
                } else {
                    misses = 0
                    _ui.update { state ->
                        state.copy(
                            signal = reading,
                            signalNote = null,
                            peaks = state.peaks.raise(reading),
                        )
                    }
                }
                delay((1000L - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(150L))
            }
        }
    }

    private fun readSignal(device: HdhrDevice, channel: Channel): SignalReading? {
        val tuners = client.status(device)
        val chosen = selectTuner(tuners, channel.guideNumber, localIp, preferredTuner)
        val index = chosen?.index ?: preferredTuner
        if (chosen == null && index == null) return null
        val text = index?.let { client.tunerText(device, it) }
        if (chosen == null && text == null) return null
        return mergeSignal(chosen, text, channel.frequencyHz)
    }

    private fun cancelChannelHop() {
        hopGeneration++
        hopJob?.cancel()
        hopJob = null
        if (_ui.value.pendingChannel != null) {
            _ui.update { it.copy(pendingChannel = null) }
        }
    }

    private fun rememberViewed(deviceId: String, guideNumber: String) {
        val current = _ui.value.recentChannels
        val next = rememberViewedChannel(current, guideNumber)
        if (next == current) return
        store.saveRecentChannels(deviceId, next)
        _ui.update { it.copy(recentChannels = next) }
    }

    private fun stopPlayback() {
        playGeneration++
        playJob?.cancel()
        pollJob?.cancel()
        engine.stop()
        releaseWifi()
    }

    private fun acquireWifi() {
        if (wifiLock?.isHeld == true) return
        val wifi = getApplication<Application>().getSystemService(WifiManager::class.java) ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = try {
            wifi.createWifiLock(mode, "hdhr-play").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun releaseWifi() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: RuntimeException) {
            // The lock is already gone.
        }
        wifiLock = null
    }

    private fun EnginePhase.toPlaybackPhase(): PlaybackPhase = when (this) {
        EnginePhase.Buffering -> PlaybackPhase.Buffering
        EnginePhase.Ready -> PlaybackPhase.Ready
        EnginePhase.Idle, EnginePhase.Ended -> PlaybackPhase.Idle
    }

    private data class PreparedStream(val url: String, val tunerIndex: Int?, val localIp: String?)
}
