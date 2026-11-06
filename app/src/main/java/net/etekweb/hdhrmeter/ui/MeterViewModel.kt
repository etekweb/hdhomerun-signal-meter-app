package net.etekweb.hdhrmeter.ui

import android.app.Application
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.etekweb.hdhrmeter.data.Channel
import net.etekweb.hdhrmeter.data.ChannelStretch
import net.etekweb.hdhrmeter.data.channelStretchKey
import net.etekweb.hdhrmeter.data.normalized
import net.etekweb.hdhrmeter.data.GuideProgram
import net.etekweb.hdhrmeter.data.Recording
import net.etekweb.hdhrmeter.data.RecordingRule
import net.etekweb.hdhrmeter.data.ScheduledRecording
import net.etekweb.hdhrmeter.data.StorageSpace
import net.etekweb.hdhrmeter.data.oneOffRuleFor
import net.etekweb.hdhrmeter.data.DeviceStore
import net.etekweb.hdhrmeter.data.previewHop
import net.etekweb.hdhrmeter.data.GuideIndex
import net.etekweb.hdhrmeter.data.HdhrClient
import net.etekweb.hdhrmeter.data.HdhrDevice
import net.etekweb.hdhrmeter.data.favoritePostUrl
import net.etekweb.hdhrmeter.data.SignalReading
import net.etekweb.hdhrmeter.data.rememberViewedChannel
import net.etekweb.hdhrmeter.data.GUIDE_URL_HINT
import net.etekweb.hdhrmeter.data.GuideMode
import net.etekweb.hdhrmeter.data.isIdle
import net.etekweb.hdhrmeter.data.hdhrGuideKey
import net.etekweb.hdhrmeter.data.normalizeGuideUrl
import net.etekweb.hdhrmeter.data.rememberTuner
import net.etekweb.hdhrmeter.data.sameTuner
import net.etekweb.hdhrmeter.data.mergeSignal
import net.etekweb.hdhrmeter.data.rewriteStreamUrl
import net.etekweb.hdhrmeter.data.sameChannel
import net.etekweb.hdhrmeter.data.selectTuner
import net.etekweb.hdhrmeter.data.DeviceStatus
import net.etekweb.hdhrmeter.data.TunerStation
import net.etekweb.hdhrmeter.data.tunerStations
import net.etekweb.hdhrmeter.data.StreamFailure
import net.etekweb.hdhrmeter.data.probeRecording
import net.etekweb.hdhrmeter.data.probeTimeshift
import net.etekweb.hdhrmeter.data.recordingResumeToStore
import net.etekweb.hdhrmeter.data.recordingResumeWall
import net.etekweb.hdhrmeter.data.recordingSpan
import net.etekweb.hdhrmeter.data.withMobileTranscode
import net.etekweb.hdhrmeter.player.EnginePhase
import net.etekweb.hdhrmeter.player.PendingSkip
import net.etekweb.hdhrmeter.player.PlaybackEngine
import net.etekweb.hdhrmeter.player.PlaybackShift
import net.etekweb.hdhrmeter.player.RecordingPlay
import net.etekweb.hdhrmeter.player.VideoStats
import net.etekweb.hdhrmeter.player.accumulateSkip
import net.etekweb.hdhrmeter.player.availableRecordingStartMs
import net.etekweb.hdhrmeter.player.settleSkipLanding
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

/** Quiet time after the last −10s / +10s tap before the picture seeks. */
private const val SKIP_SETTLE_MS = 500L

enum class ChannelFilter { All, Hd, Favorites, Recents }

/** Player overlay page. [Info] is the default until the viewer picks another page. */
enum class OverlayTab { Info, Signal, Playback }

/** [Info] when [raw] is missing or not a known tab name. */
internal fun overlayTabFromStored(raw: String?): OverlayTab {
    return OverlayTab.entries.firstOrNull { it.name == raw } ?: OverlayTab.Info
}

/** Recordings have no signal meters, so that page is live-only. */
internal fun overlayTabsFor(recording: Boolean): List<OverlayTab> {
    return if (recording) {
        listOf(OverlayTab.Info, OverlayTab.Playback)
    } else {
        OverlayTab.entries
    }
}

/** The stored page, or [Info] when that page is not on screen for this picture. */
internal fun visibleOverlayTab(stored: OverlayTab, recording: Boolean): OverlayTab {
    return stored.takeIf { it in overlayTabsFor(recording) } ?: OverlayTab.Info
}

/** Guide number of the picture on screen, before canonicalizing. */
internal fun playingGuideNumber(screen: Screen): String? = when (screen) {
    is Screen.Watch -> screen.channel.guideNumber
    is Screen.PlayRecording -> screen.recording.channelNumber
    Screen.Shell -> null
}

enum class PlaybackPhase { Idle, Tuning, Buffering, Ready }

enum class AppTab { Lineup, Dvr, Settings }

/** Whether the selected tuner answered. [Missing] sends Lineup and DVR to Settings. */
enum class TunerReach { Checking, Ready, Missing }

sealed interface Screen {
    data object Shell : Screen
    data class Watch(val device: HdhrDevice, val channel: Channel) : Screen
    /** A stored recording, played through the same picture as live TV. */
    data class PlayRecording(val recording: Recording) : Screen
}

internal val Screen.inPlayer: Boolean
    get() = this is Screen.Watch || this is Screen.PlayRecording

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
    val screen: Screen = Screen.Shell,
    val tab: AppTab = AppTab.Lineup,
    val devices: List<HdhrDevice> = emptyList(),
    val savedTuners: List<HdhrDevice> = emptyList(),
    val selectedDevice: HdhrDevice? = null,
    val tunerReach: TunerReach = TunerReach.Missing,
    val scanning: Boolean = false,
    val connecting: Boolean = false,
    val scanError: String? = null,
    val tunerAddError: String? = null,
    val manualHost: String = "",
    val guideSources: List<String> = emptyList(),
    val guideMode: GuideMode = GuideMode.HdHomeRun,
    val activeGuideUrl: String = "",
    val guideDraft: String = "",
    val guideDraftError: String? = null,
    /**
     * Identity of the guide held in [guide]. A URL in URL mode, or [hdhrGuideKey]
     * while the HDHomeRun guide for that tuner is showing. Empty while a different guide is loading.
     */
    val appliedGuideUrl: String = "",
    val guide: GuideIndex? = null,
    val guideNowMs: Long = System.currentTimeMillis(),
    val guideLoading: Boolean = false,
    val guideError: String? = null,
    val channels: List<Channel> = emptyList(),
    val channelsLoading: Boolean = false,
    val channelsError: String? = null,
    /** Guide numbers whose favorite write is still in flight. */
    val favoritePending: Set<String> = emptySet(),
    val favoriteError: String? = null,
    val favoriteErrorGuide: String? = null,
    val query: String = "",
    val filter: ChannelFilter = ChannelFilter.All,
    val recentChannels: List<String> = emptyList(),
    /** Channel to reveal when the guide returns from the player. */
    val returnToChannel: Channel? = null,
    val pendingChannel: Channel? = null,
    /** Skip taps still combining. The picture seeks when this clears. */
    val pendingSkip: PendingSkip? = null,
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
    /** Stretch sliders keyed by guide number. Shared by live TV and recordings. */
    val channelStretches: Map<String, ChannelStretch> = emptyMap(),
    val tunerReleased: Boolean = false,
    val tuners: TunerPanel = TunerPanel(),
    val dvr: DvrState = DvrState(),
    val schedule: ScheduleState = ScheduleState(),
    val playbackClock: PlaybackClock = PlaybackClock(),
    /** Software decoding unless the Settings checkbox is on. */
    val hardwareDecode: Boolean = false,
)

/**
 * Where the picture is in the timeshift buffer.
 * [bufferStartMs] is the oldest moment the player can show.
 * [positionMs] is the frame on screen. [liveMs] is the broadcast edge.
 */
data class PlaybackClock(
    val active: Boolean = false,
    val timeshift: Boolean = false,
    val playing: Boolean = false,
    val bufferStartMs: Long = 0,
    val positionMs: Long = 0,
    val liveMs: Long = 0,
)

/** The airing the confirm dialog is asking about. */
data class PendingRecording(
    val channelNumber: String,
    val channelName: String,
    val program: GuideProgram,
)

/** Recording rules from the HDHomeRun account, plus the confirm dialog. */
data class ScheduleState(
    val rules: List<RecordingRule> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val pending: PendingRecording? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val noticeStartMs: Long? = null,
)

enum class DvrSection { Recordings, Scheduled }

/** Recordings from HDHomeRun DVR storage, and the future airings still on the schedule. */
data class DvrState(
    val section: DvrSection = DvrSection.Recordings,
    val recordings: List<Recording> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val warning: String? = null,
    val enginesFound: Int = 0,
    val storage: StorageSpace? = null,
    val selectedId: String? = null,
    val scheduled: List<ScheduledRecording> = emptyList(),
    val scheduledLoading: Boolean = false,
    val scheduledLoaded: Boolean = false,
    val scheduledError: String? = null,
    val scheduledWarning: String? = null,
    val selectedScheduledId: String? = null,
    val acting: Boolean = false,
    val actionError: String? = null,
    val notice: String? = null,
    val confirmDelete: Boolean = false,
    val editingPadding: Boolean = false,
)

/** A favorite write the next lineup read must not undo if that read started first. */
private data class FavoriteEdit(val favorite: Boolean, val lineupGeneration: Int)

/** Guide-page list of the open device's tuners. */
data class TunerPanel(
    val open: Boolean = false,
    val loading: Boolean = false,
    val rows: List<TunerStation> = emptyList(),
    val error: String? = null,
    val clearing: Int? = null,
)

class MeterViewModel(app: Application) : AndroidViewModel(app) {
    private val client = HdhrClient(app)
    private val store = DeviceStore(app)
    private val engine = PlaybackEngine(app)
    private val shift = PlaybackShift()
    private val recordingPlay = RecordingPlay()

    private val _ui = MutableStateFlow(
        store.loadGuideLibrary().let { guides ->
            val recent = store.loadRecent()
            UiState(
                guideSources = guides.sources,
                guideMode = guides.mode,
                activeGuideUrl = guides.activeUrl,
                savedTuners = store.loadSavedTuners(),
                selectedDevice = recent,
                tunerReach = if (recent == null) TunerReach.Missing else TunerReach.Checking,
                overlayTab = overlayTabFromStored(store.loadOverlayTab()),
                channelStretches = store.loadChannelStretches(),
                hardwareDecode = store.loadHardwareDecode(),
            )
        },
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var scanJob: Job? = null
    private var recordingJob: Job? = null
    private var recordingGeneration = 0
    private var resumeSaveGeneration = 0
    private var scheduledJob: Job? = null
    private var scheduledGeneration = 0
    private var dvrActionJob: Job? = null
    private var dvrActionGeneration = 0
    private var rulesJob: Job? = null
    private var rulesGeneration = 0
    private var scheduleJob: Job? = null
    private var scheduleGeneration = 0
    private var guideJob: Job? = null
    private var guideClockJob: Job? = null
    private var guideGeneration = 0
    private var playJob: Job? = null
    private var pollJob: Job? = null
    private var hopJob: Job? = null
    private var skipJob: Job? = null
    private var tunerJob: Job? = null
    private var clearJob: Job? = null
    private var playGeneration = 0
    private var hopGeneration = 0
    private var skipGeneration = 0
    /** Last seek, until a clock sample shows that picture. Cleared in [publishClock]. */
    private var skipLandedMs: Long? = null
    private var tunerGeneration = 0
    private var probeGeneration = 0
    private var lineupGeneration = 0
    /** Desired favorite flags that a lineup read started before the write must not undo. */
    private val favoriteEdits = LinkedHashMap<String, FavoriteEdit>()
    private var initialScanSettled = false
    private var startupProbeFailed = false
    private var preferredTuner: Int? = null
    private var localIp: String? = null
    private var usingSpecificTuner = false
    private var autoRetried = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var engineOrigin: String? = null
    private var engineOriginKnown = false
    private var showClock = false
    private var directOn = false
    private var directWall0 = 0L
    private var directMedia0 = 0L
    private var clockJob: Job? = null

    init {
        engine.setHardwareDecode(store.loadHardwareDecode())
        shift.watchInterlace(
            token = { engine.playSerial() },
            listener = { serial, interlaced -> engine.noteInterlaced(serial, interlaced) },
        )
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
                val screen = _ui.value.screen
                if (screen is Screen.PlayRecording) {
                    if (error.ended) return@collect
                    _ui.update {
                        it.copy(
                            playbackError = error.message,
                            offerTranscode = false,
                            phase = PlaybackPhase.Idle,
                        )
                    }
                    return@collect
                }
                val watch = screen as? Screen.Watch ?: return@collect
                if (usingSpecificTuner && !autoRetried && error.hdhrCode == 804) {
                    autoRetried = true
                    tune(watch.device, watch.channel, transcode = _ui.value.transcoding, forceAuto = true)
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
        when (_ui.value.guideMode) {
            GuideMode.Url -> if (_ui.value.activeGuideUrl.isNotBlank()) refreshGuide()
            GuideMode.HdHomeRun -> refreshGuide()
            GuideMode.None -> Unit
        }
        _ui.value.selectedDevice?.let { beginProbe(it, userInitiated = false) }
        scan()
    }

    fun selectTab(tab: AppTab) {
        if (_ui.value.screen.inPlayer) return
        _ui.update { state -> if (state.tab == tab) state else state.copy(tab = tab) }
    }

    private fun authDevices(): List<HdhrDevice> {
        val state = _ui.value
        return buildList {
            state.selectedDevice?.let { add(it) }
            addAll(state.devices)
        }.distinctBy { device -> device.deviceId.ifBlank { device.baseUrl } }
    }

    private fun ensureRecordingRules() {
        refreshRecordingRules(force = false)
    }

    private fun refreshRecordingRules(force: Boolean) {
        val devices = authDevices()
        if (devices.isEmpty()) return
        val schedule = _ui.value.schedule
        if (!force && (schedule.loaded || schedule.loading)) return
        val generation = ++rulesGeneration
        rulesJob?.cancel()
        rulesJob = viewModelScope.launch {
            _ui.update { it.copy(schedule = it.schedule.copy(loading = true)) }
            val result = withContext(Dispatchers.IO) {
                runCatching { client.loadRecordingRules(devices) }
            }
            if (!isActive || generation != rulesGeneration) return@launch
            result.onSuccess { rules ->
                _ui.update {
                    it.copy(schedule = it.schedule.copy(rules = rules, loaded = true, loading = false))
                }
            }.onFailure {
                _ui.update { it.copy(schedule = it.schedule.copy(loading = false)) }
            }
        }
    }

    /** Loads the DVR library the first time the tab is shown. */
    fun ensureRecordings() {
        val dvr = _ui.value.dvr
        if (dvr.loading || dvr.loaded) return
        refreshRecordings()
    }

    fun refreshRecordings() {
        val generation = ++recordingGeneration
        recordingJob?.cancel()
        recordingJob = viewModelScope.launch {
            _ui.update { it.copy(dvr = it.dvr.copy(loading = true, error = null)) }
            val result = withContext(Dispatchers.IO) { runCatching { client.loadRecordings() } }
            if (!isActive || generation != recordingGeneration) return@launch
            result.onSuccess { library ->
                _ui.update { state ->
                    val selected = state.dvr.selectedId?.takeIf { id ->
                        library.recordings.any { it.id == id }
                    }
                    val recordingClosed = state.dvr.selectedId != null && selected == null &&
                        state.dvr.section == DvrSection.Recordings
                    state.copy(
                        dvr = state.dvr.copy(
                            recordings = library.recordings,
                            loading = false,
                            loaded = true,
                            error = null,
                            warning = library.warning,
                            enginesFound = library.enginesFound,
                            storage = library.storage,
                            selectedId = selected,
                            confirmDelete = if (recordingClosed) false else state.dvr.confirmDelete,
                            actionError = if (recordingClosed) null else state.dvr.actionError,
                        ),
                    )
                }
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        dvr = it.dvr.copy(
                            loading = false,
                            loaded = true,
                            error = error.message ?: "Could not read recordings.",
                        ),
                    )
                }
            }
        }
    }

    fun selectDvrSection(section: DvrSection) {
        if (_ui.value.dvr.section == section) return
        dvrActionGeneration++
        dvrActionJob?.cancel()
        _ui.update {
            it.copy(
                dvr = it.dvr.copy(
                    section = section,
                    selectedId = null,
                    selectedScheduledId = null,
                    confirmDelete = false,
                    editingPadding = false,
                    acting = false,
                    actionError = null,
                    notice = null,
                ),
            )
        }
    }

    fun openRecording(id: String) {
        if (_ui.value.dvr.recordings.none { it.id == id }) return
        _ui.update {
            it.copy(
                dvr = it.dvr.copy(
                    selectedId = id,
                    selectedScheduledId = null,
                    confirmDelete = false,
                    editingPadding = false,
                    actionError = null,
                    notice = null,
                ),
            )
        }
    }

    fun openScheduled(id: String) {
        if (_ui.value.dvr.scheduled.none { it.id == id }) return
        _ui.update {
            it.copy(
                dvr = it.dvr.copy(
                    selectedId = null,
                    selectedScheduledId = id,
                    confirmDelete = false,
                    editingPadding = false,
                    actionError = null,
                    notice = null,
                ),
            )
        }
    }

    fun closeRecording() {
        val dvr = _ui.value.dvr
        if (dvr.acting) return
        if (dvr.selectedId == null && dvr.selectedScheduledId == null) return
        _ui.update {
            it.copy(
                dvr = it.dvr.copy(
                    selectedId = null,
                    selectedScheduledId = null,
                    confirmDelete = false,
                    editingPadding = false,
                    actionError = null,
                ),
            )
        }
    }

    /** Opens the recording that is showing its info, from the beginning. */
    fun playRecording() {
        openRecording(fromStart = true)
    }

    /** Opens the recording that is showing its info at its saved resume point. */
    fun resumeRecording() {
        openRecording(fromStart = false)
    }

    private fun openRecording(fromStart: Boolean) {
        val state = _ui.value
        val recording = state.dvr.recordings.firstOrNull { it.id == state.dvr.selectedId } ?: return
        if (recording.playUrl.isNullOrBlank()) return
        val playing = if (fromStart) recording.copy(resumeSec = null) else recording
        cancelChannelHop()
        _ui.update {
            it.copy(
                screen = Screen.PlayRecording(playing),
                overlayVisible = true,
                transcoding = false,
                pendingChannel = null,
                playbackError = null,
                tunerReleased = false,
            )
        }
        startRecording(playing)
    }

    /** Loads future recordings the first time the Scheduled tab is shown. */
    fun ensureScheduled() {
        val dvr = _ui.value.dvr
        if (dvr.scheduledLoading || dvr.scheduledLoaded) return
        fetchScheduled(clearNotice = false)
    }

    fun refreshScheduled() {
        fetchScheduled(clearNotice = true)
    }

    fun requestDelete() {
        val dvr = _ui.value.dvr
        if (dvr.acting || dvr.editingPadding) return
        when (dvr.section) {
            DvrSection.Recordings -> {
                val recording = dvr.recordings.firstOrNull { it.id == dvr.selectedId } ?: return
                if (recording.cmdUrl.isNullOrBlank()) {
                    _ui.update {
                        it.copy(dvr = it.dvr.copy(actionError = "This recording cannot be deleted."))
                    }
                    return
                }
            }
            DvrSection.Scheduled -> {
                val item = dvr.scheduled.firstOrNull { it.id == dvr.selectedScheduledId } ?: return
                if (!item.oneOff && item.programId.isNullOrBlank()) {
                    _ui.update {
                        it.copy(dvr = it.dvr.copy(actionError = "This scheduled recording cannot be canceled."))
                    }
                    return
                }
            }
        }
        _ui.update { it.copy(dvr = it.dvr.copy(confirmDelete = true, actionError = null)) }
    }

    fun dismissDelete() {
        if (_ui.value.dvr.acting) return
        _ui.update { it.copy(dvr = it.dvr.copy(confirmDelete = false, actionError = null)) }
    }

    fun confirmDelete() {
        val dvr = _ui.value.dvr
        if (dvr.acting || !dvr.confirmDelete) return
        when (dvr.section) {
            DvrSection.Recordings -> {
                val recording = dvr.recordings.firstOrNull { it.id == dvr.selectedId } ?: return
                val cmd = recording.cmdUrl
                if (cmd.isNullOrBlank()) {
                    _ui.update {
                        it.copy(dvr = it.dvr.copy(actionError = "This recording cannot be deleted."))
                    }
                    return
                }
                val generation = ++dvrActionGeneration
                dvrActionJob?.cancel()
                _ui.update { it.copy(dvr = it.dvr.copy(acting = true, actionError = null)) }
                dvrActionJob = viewModelScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { client.deleteRecording(cmd) }
                    }
                    if (!isActive || generation != dvrActionGeneration) return@launch
                    result.onSuccess {
                        _ui.update {
                            it.copy(
                                dvr = it.dvr.copy(
                                    acting = false,
                                    actionError = null,
                                    confirmDelete = false,
                                    editingPadding = false,
                                    selectedId = null,
                                    notice = null,
                                ),
                            )
                        }
                        refreshRecordings()
                    }.onFailure { error ->
                        _ui.update {
                            it.copy(
                                dvr = it.dvr.copy(
                                    acting = false,
                                    actionError = error.message ?: "Could not delete the recording.",
                                ),
                            )
                        }
                    }
                }
            }
            DvrSection.Scheduled -> {
                val item = dvr.scheduled.firstOrNull { it.id == dvr.selectedScheduledId } ?: return
                val devices = authDevices()
                val generation = ++dvrActionGeneration
                dvrActionJob?.cancel()
                _ui.update { it.copy(dvr = it.dvr.copy(acting = true, actionError = null)) }
                dvrActionJob = viewModelScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { client.cancelScheduled(devices, item) }
                    }
                    if (!isActive || generation != dvrActionGeneration) return@launch
                    result.onSuccess { outcome ->
                        _ui.update { state ->
                            state.copy(
                                dvr = state.dvr.copy(
                                    acting = false,
                                    actionError = null,
                                    confirmDelete = false,
                                    editingPadding = false,
                                    selectedScheduledId = null,
                                    notice = outcome.warning,
                                ),
                                schedule = if (outcome.replaceRules) {
                                    state.schedule.copy(rules = outcome.rules, loaded = true)
                                } else {
                                    state.schedule
                                },
                            )
                        }
                        fetchScheduled(clearNotice = false)
                    }.onFailure { error ->
                        _ui.update {
                            it.copy(
                                dvr = it.dvr.copy(
                                    acting = false,
                                    actionError = error.message ?: "Could not cancel the recording.",
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun requestEditPadding() {
        val dvr = _ui.value.dvr
        if (dvr.acting || dvr.confirmDelete || dvr.section != DvrSection.Scheduled) return
        if (dvr.scheduled.none { it.id == dvr.selectedScheduledId }) return
        _ui.update { it.copy(dvr = it.dvr.copy(editingPadding = true, actionError = null)) }
    }

    fun dismissEditPadding() {
        if (_ui.value.dvr.acting) return
        _ui.update { it.copy(dvr = it.dvr.copy(editingPadding = false, actionError = null)) }
    }

    fun saveScheduledPadding(startPaddingSec: Int, endPaddingSec: Int) {
        val dvr = _ui.value.dvr
        if (dvr.acting || !dvr.editingPadding) return
        val item = dvr.scheduled.firstOrNull { it.id == dvr.selectedScheduledId } ?: return
        val devices = authDevices()
        val ruleId = item.ruleId
        val generation = ++dvrActionGeneration
        dvrActionJob?.cancel()
        _ui.update { it.copy(dvr = it.dvr.copy(acting = true, actionError = null)) }
        dvrActionJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.changeRulePadding(devices, ruleId, startPaddingSec, endPaddingSec) }
            }
            if (!isActive || generation != dvrActionGeneration) return@launch
            result.onSuccess { outcome ->
                val saved = outcome.rules.firstOrNull { it.id == ruleId }
                val startPad = saved?.startPaddingSec ?: startPaddingSec
                val endPad = saved?.endPaddingSec ?: endPaddingSec
                _ui.update { state ->
                    state.copy(
                        dvr = state.dvr.copy(
                            acting = false,
                            actionError = null,
                            editingPadding = false,
                            notice = outcome.warning,
                            scheduled = state.dvr.scheduled.map { scheduled ->
                                if (scheduled.ruleId != ruleId) {
                                    scheduled
                                } else {
                                    scheduled.copy(startPaddingSec = startPad, endPaddingSec = endPad)
                                }
                            },
                        ),
                        schedule = if (outcome.replaceRules) {
                            state.schedule.copy(rules = outcome.rules, loaded = true)
                        } else {
                            state.schedule
                        },
                    )
                }
                fetchScheduled(clearNotice = false)
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        dvr = it.dvr.copy(
                            acting = false,
                            actionError = error.message ?: "Could not update the recording.",
                        ),
                    )
                }
            }
        }
    }

    private fun fetchScheduled(clearNotice: Boolean) {
        val devices = authDevices()
        val generation = ++scheduledGeneration
        scheduledJob?.cancel()
        if (devices.isEmpty()) {
            _ui.update {
                it.copy(
                    dvr = it.dvr.copy(
                        scheduledLoading = false,
                        scheduledLoaded = true,
                        scheduledError = "Choose a tuner before scheduling a recording.",
                        notice = if (clearNotice) null else it.dvr.notice,
                    ),
                )
            }
            return
        }
        val recordedIds = _ui.value.dvr.recordings.mapNotNull { it.programId }.toSet()
        scheduledJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    dvr = it.dvr.copy(
                        scheduledLoading = true,
                        scheduledError = null,
                        notice = if (clearNotice) null else it.dvr.notice,
                    ),
                )
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { client.loadScheduled(devices, recordedIds) }
            }
            if (!isActive || generation != scheduledGeneration) return@launch
            result.onSuccess { library ->
                _ui.update { state ->
                    val selected = state.dvr.selectedScheduledId?.takeIf { id ->
                        library.recordings.any { it.id == id }
                    }
                    val scheduledClosed = state.dvr.selectedScheduledId != null && selected == null &&
                        state.dvr.section == DvrSection.Scheduled
                    state.copy(
                        dvr = state.dvr.copy(
                            scheduled = library.recordings,
                            scheduledLoading = false,
                            scheduledLoaded = true,
                            scheduledError = null,
                            scheduledWarning = library.warning,
                            selectedScheduledId = selected,
                            confirmDelete = if (scheduledClosed) false else state.dvr.confirmDelete,
                            editingPadding = if (scheduledClosed) false else state.dvr.editingPadding,
                            actionError = if (scheduledClosed) null else state.dvr.actionError,
                        ),
                        schedule = state.schedule.copy(rules = library.rules, loaded = true),
                    )
                }
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        dvr = it.dvr.copy(
                            scheduledLoading = false,
                            scheduledLoaded = true,
                            scheduledError = error.message ?: "Could not read scheduled recordings.",
                        ),
                    )
                }
            }
        }
    }

    fun scan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _ui.update { it.copy(scanning = true, scanError = null) }
            val result = withContext(Dispatchers.IO) { runCatching { client.discover() } }
            if (!isActive) return@launch
            val first = !initialScanSettled
            initialScanSettled = true
            result.onSuccess { devices ->
                _ui.update {
                    it.copy(
                        scanning = false,
                        devices = devices,
                        scanError = if (devices.isEmpty() && it.savedTuners.isEmpty() && it.selectedDevice == null) {
                            "No HDHomeRun answered. Add the tuner address below."
                        } else {
                            null
                        },
                    )
                }
                if (first) reconcileStartup(devices) else refreshSelectedFromScan(devices)
            }.onFailure { error ->
                _ui.update {
                    it.copy(scanning = false, scanError = error.message ?: "Discovery failed.")
                }
                if (first) reconcileStartup(_ui.value.devices)
            }
        }
    }

    fun setHardwareDecode(enabled: Boolean) {
        if (enabled == _ui.value.hardwareDecode) return
        store.saveHardwareDecode(enabled)
        _ui.update { it.copy(hardwareDecode = enabled) }
        engine.setHardwareDecode(enabled)
    }

    fun setManualHost(value: String) {
        _ui.update { it.copy(manualHost = value, tunerAddError = null) }
    }

    fun setGuideDraft(value: String) {
        _ui.update { it.copy(guideDraft = value, guideDraftError = null) }
    }

    fun addGuideSource() {
        val url = normalizeGuideUrl(_ui.value.guideDraft)
        if (url == null) {
            _ui.update { it.copy(guideDraftError = GUIDE_URL_HINT) }
            return
        }
        val sources = (_ui.value.guideSources + url).distinct()
        store.saveGuideLibrary(sources, url, GuideMode.Url)
        _ui.update {
            it.copy(
                guideSources = sources,
                guideMode = GuideMode.Url,
                activeGuideUrl = url,
                guideDraft = "",
                guideDraftError = null,
                guideError = null,
            )
        }
        refreshGuide()
    }

    fun selectGuideNone() {
        val state = _ui.value
        if (state.guideMode == GuideMode.None && state.guide == null && !state.guideLoading && state.guideError == null) {
            return
        }
        store.saveGuideLibrary(state.guideSources, "", GuideMode.None)
        guideGeneration++
        guideJob?.cancel()
        guideClockJob?.cancel()
        _ui.update {
            it.copy(
                guideMode = GuideMode.None,
                activeGuideUrl = "",
                guide = null,
                appliedGuideUrl = "",
                guideError = null,
                guideLoading = false,
            )
        }
    }

    fun selectGuideHdHomeRun() {
        val state = _ui.value
        val readyId = state.selectedDevice
            ?.takeIf { state.tunerReach == TunerReach.Ready }
            ?.deviceId
        val loaded = readyId != null &&
            state.appliedGuideUrl == hdhrGuideKey(readyId) &&
            state.guide != null
        if (state.guideMode == GuideMode.HdHomeRun && (state.guideLoading || loaded)) return
        store.saveGuideLibrary(state.guideSources, "", GuideMode.HdHomeRun)
        _ui.update {
            it.copy(guideMode = GuideMode.HdHomeRun, activeGuideUrl = "", guideError = null)
        }
        refreshGuide()
    }

    fun selectGuideSource(url: String) {
        val normalized = normalizeGuideUrl(url) ?: return
        val state = _ui.value
        if (normalized !in state.guideSources) return
        if (
            state.guideMode == GuideMode.Url &&
            normalized == state.activeGuideUrl &&
            (state.guide != null || state.guideLoading)
        ) {
            return
        }
        store.saveGuideLibrary(state.guideSources, normalized, GuideMode.Url)
        _ui.update {
            it.copy(guideMode = GuideMode.Url, activeGuideUrl = normalized, guideError = null)
        }
        refreshGuide()
    }

    fun removeGuideSource(url: String) {
        val current = _ui.value
        val sources = current.guideSources.filterNot { it == url }
        val removingActiveUrl = current.guideMode == GuideMode.Url && current.activeGuideUrl == url
        val nextUrl = when {
            current.guideMode != GuideMode.Url -> ""
            removingActiveUrl -> sources.firstOrNull().orEmpty()
            else -> current.activeGuideUrl
        }
        val mode = when {
            current.guideMode != GuideMode.Url -> current.guideMode
            nextUrl.isEmpty() -> GuideMode.None
            else -> GuideMode.Url
        }
        store.saveGuideLibrary(sources, nextUrl, mode)
        val dropGuide = current.guideMode == GuideMode.Url && (removingActiveUrl || mode == GuideMode.None)
        if (dropGuide) {
            guideGeneration++
            guideJob?.cancel()
            if (mode != GuideMode.Url) guideClockJob?.cancel()
        }
        _ui.update {
            it.copy(
                guideSources = sources,
                guideMode = mode,
                activeGuideUrl = if (mode == GuideMode.Url) nextUrl else "",
                guide = if (dropGuide) null else it.guide,
                appliedGuideUrl = if (dropGuide) "" else it.appliedGuideUrl,
                guideError = if (dropGuide) null else it.guideError,
                guideLoading = if (dropGuide) false else it.guideLoading,
            )
        }
        if (dropGuide && mode == GuideMode.Url) refreshGuide()
    }

    fun addDevice() {
        val host = _ui.value.manualHost.trim()
        if (host.isEmpty() || _ui.value.connecting) return
        viewModelScope.launch {
            _ui.update { it.copy(connecting = true, tunerAddError = null) }
            val result = withContext(Dispatchers.IO) { runCatching { client.deviceForHost(host) } }
            result.onSuccess { device ->
                _ui.update { it.copy(connecting = false, manualHost = "") }
                adoptTuner(device, fromUser = true)
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        connecting = false,
                        tunerAddError = error.message ?: "Could not reach that address.",
                    )
                }
            }
        }
    }

    fun selectDevice(device: HdhrDevice) {
        val live = _ui.value.devices.firstOrNull { sameTuner(it, device) }
        val already = _ui.value.tunerReach == TunerReach.Ready &&
            _ui.value.selectedDevice?.let { sameTuner(it, live ?: device) } == true
        if (already) {
            _ui.update { it.copy(tab = AppTab.Lineup) }
            return
        }
        if (live != null) {
            adoptTuner(live, fromUser = true)
        } else {
            beginProbe(device, userInitiated = true)
        }
    }

    fun removeSavedDevice(device: HdhrDevice) {
        val saved = store.loadSavedTuners().filterNot { sameTuner(it, device) }
        store.saveSavedTuners(saved)
        val onNetwork = _ui.value.devices.any { sameTuner(it, device) }
        val selected = _ui.value.selectedDevice
        val dropSelection = selected != null && sameTuner(selected, device) && !onNetwork
        if (!dropSelection) {
            _ui.update { it.copy(savedTuners = saved) }
            return
        }
        probeGeneration++
        lineupGeneration++
        dismissTuners()
        store.clearRecent()
        _ui.update {
            it.copy(
                savedTuners = saved,
                selectedDevice = null,
                tunerReach = TunerReach.Missing,
                channels = emptyList(),
                channelsLoading = false,
                channelsError = null,
                returnToChannel = null,
                favoritePending = emptySet(),
                favoriteError = null,
                favoriteErrorGuide = null,
            )
        }
        forgetFavoriteEdits()
        reloadGuideForTuner()
    }

    fun refreshLineup() {
        val device = readyDevice() ?: return
        loadLineup(device, keepExisting = true)
        when (_ui.value.guideMode) {
            GuideMode.Url -> if (_ui.value.activeGuideUrl.isNotBlank()) refreshGuide()
            GuideMode.HdHomeRun -> refreshGuide()
            GuideMode.None -> Unit
        }
    }

    fun openTuners() {
        val device = readyDevice() ?: return
        _ui.update { it.copy(tuners = TunerPanel(open = true, loading = true)) }
        reloadTuners(device)
    }

    fun refreshTuners() {
        val device = readyDevice() ?: return
        val panel = _ui.value.tuners
        if (!panel.open || panel.loading || panel.clearing != null) return
        reloadTuners(device)
    }

    private fun stationsFrom(status: DeviceStatus, tunerCount: Int): List<TunerStation> {
        return tunerStations(status.tuners, tunerCount, status.viewers)
    }

    private fun reloadTuners(device: HdhrDevice) {
        tunerJob?.cancel()
        val generation = ++tunerGeneration
        _ui.update { state ->
            if (!state.tuners.open) state
            else state.copy(tuners = state.tuners.copy(loading = true, error = null))
        }
        tunerJob = viewModelScope.launch {
            clearJob?.join()
            if (generation != tunerGeneration) return@launch
            val result = withContext(Dispatchers.IO) {
                runCatching { stationsFrom(client.tunerStatus(device), device.tunerCount) }
            }
            if (generation != tunerGeneration) return@launch
            _ui.update { state ->
                if (!state.tuners.open) return@update state
                result.fold(
                    onSuccess = { rows ->
                        state.copy(tuners = state.tuners.copy(loading = false, rows = rows, error = null))
                    },
                    onFailure = { error ->
                        state.copy(
                            tuners = state.tuners.copy(
                                loading = false,
                                error = error.message ?: "Could not read tuners.",
                            ),
                        )
                    },
                )
            }
        }
    }

    fun closeTuners() {
        dismissTuners()
        _ui.update { state ->
            if (state.tuners == TunerPanel()) state else state.copy(tuners = TunerPanel())
        }
    }

    fun forceClearTuner(index: Int) {
        val device = readyDevice() ?: return
        val panel = _ui.value.tuners
        if (!panel.open || panel.clearing != null || index < 0) return
        tunerJob?.cancel()
        val generation = ++tunerGeneration
        clearJob = viewModelScope.launch {
            _ui.update { state ->
                if (!state.tuners.open) state
                else state.copy(tuners = state.tuners.copy(clearing = index, error = null))
            }
            val cleared = withContext(Dispatchers.IO) {
                runCatching { client.forceClearTuner(device, index) }
            }
            val reload = withContext(Dispatchers.IO) {
                runCatching { stationsFrom(client.tunerStatus(device), device.tunerCount) }
            }
            if (generation != tunerGeneration) return@launch
            _ui.update { state ->
                val current = state.tuners
                if (!current.open) return@update state
                val error = when {
                    cleared.isFailure -> cleared.exceptionOrNull()?.message ?: "Could not clear the tuner."
                    reload.isFailure -> reload.exceptionOrNull()?.message ?: "Could not read tuners."
                    else -> null
                }
                state.copy(
                    tuners = current.copy(
                        clearing = null,
                        loading = false,
                        rows = reload.getOrNull() ?: current.rows,
                        error = error,
                    ),
                )
            }
        }
    }

    fun setQuery(value: String) {
        _ui.update { it.copy(query = value) }
    }

    fun setFilter(filter: ChannelFilter) {
        _ui.update { it.copy(filter = filter, returnToChannel = null) }
    }

    /**
     * Adds or removes this channel on the tuner's favorite list.
     * The tuner keeps the flag, so other devices pick it up from the lineup.
     */
    fun toggleFavorite(channel: Channel) {
        val device = readyDevice() ?: return
        val number = channel.guideNumber
        if (number in _ui.value.favoritePending) return
        val next = !channel.favorite
        if (favoritePostUrl(device.baseUrl, number, next) == null) {
            _ui.update {
                it.copy(
                    favoriteError = "That channel cannot be marked as a favorite.",
                    favoriteErrorGuide = number,
                )
            }
            return
        }
        favoriteEdits[number] = FavoriteEdit(next, lineupGeneration)
        _ui.update {
            it.copy(
                channels = applyFavoriteEdits(it.channels, lineupGeneration),
                favoritePending = it.favoritePending + number,
                favoriteError = null,
                favoriteErrorGuide = null,
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { client.setChannelFavorite(device, number, next) }
            }
            val current = favoriteEdits[number]
            if (current == null || current.favorite != next) {
                _ui.update { state ->
                    state.copy(favoritePending = state.favoritePending - number)
                }
                return@launch
            }
            if (result.isSuccess) {
                favoriteEdits[number] = FavoriteEdit(next, lineupGeneration)
            } else {
                favoriteEdits.remove(number)
            }
            _ui.update { state ->
                val here = state.tunerReach == TunerReach.Ready &&
                    state.selectedDevice?.let { sameTuner(it, device) } == true
                if (!here) {
                    state.copy(favoritePending = state.favoritePending - number)
                } else if (result.isSuccess) {
                    state.copy(
                        channels = state.channels.map { item ->
                            if (item.guideNumber == number) item.copy(favorite = next) else item
                        }.let { applyFavoriteEdits(it, lineupGeneration) },
                        favoritePending = state.favoritePending - number,
                    )
                } else {
                    state.copy(
                        channels = state.channels.map { item ->
                            if (item.guideNumber == number) item.copy(favorite = !next) else item
                        }.let { applyFavoriteEdits(it, lineupGeneration) },
                        favoritePending = state.favoritePending - number,
                        favoriteError = result.exceptionOrNull()?.message ?: "Could not update favorites.",
                        favoriteErrorGuide = number,
                    )
                }
            }
        }
    }

    /** Loads rules and the library once, so a program can show that it is already recording. */
    fun ensureScheduleStatus() {
        ensureRecordingRules()
        ensureRecordings()
    }

    fun requestRecording(channelNumber: String, channelName: String, program: GuideProgram) {
        if (_ui.value.schedule.saving) return
        val number = channelNumber.trim()
        if (number.isEmpty()) return
        _ui.update {
            it.copy(
                schedule = it.schedule.copy(
                    pending = PendingRecording(number, channelName.trim(), program),
                    error = null,
                    notice = null,
                    noticeStartMs = null,
                ),
            )
        }
        ensureRecordingRules()
    }

    fun dismissRecording() {
        scheduleGeneration++
        _ui.update {
            it.copy(schedule = it.schedule.copy(pending = null, saving = false, error = null))
        }
    }

    fun confirmRecording(startPaddingSec: Int, endPaddingSec: Int) {
        val pending = _ui.value.schedule.pending ?: return
        if (_ui.value.schedule.saving) return
        val generation = ++scheduleGeneration
        val devices = authDevices()
        val existing = oneOffRuleFor(
            _ui.value.schedule.rules,
            pending.channelNumber,
            pending.program.startEpochMs,
        )
        _ui.update { it.copy(schedule = it.schedule.copy(saving = true, error = null)) }
        scheduleJob?.cancel()
        scheduleJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    client.scheduleAiring(
                        devices,
                        pending.channelNumber,
                        pending.program.startEpochMs,
                        pending.program.title,
                        startPaddingSec,
                        endPaddingSec,
                        existing?.id,
                    )
                }
            }
            if (!isActive) return@launch
            val current = generation == scheduleGeneration
            result.onSuccess { outcome ->
                _ui.update { state ->
                    state.copy(
                        schedule = state.schedule.copy(
                            rules = if (outcome.replaceRules) outcome.rules else state.schedule.rules,
                            loaded = true,
                            pending = if (current) null else state.schedule.pending,
                            saving = if (current) false else state.schedule.saving,
                            error = if (current) null else state.schedule.error,
                            notice = if (current) outcome.warning else state.schedule.notice,
                            noticeStartMs = when {
                                !current -> state.schedule.noticeStartMs
                                outcome.warning != null -> pending.program.startEpochMs
                                else -> null
                            },
                        ),
                    )
                }
                refreshRecordings()
                if (_ui.value.dvr.scheduledLoaded) fetchScheduled(clearNotice = true)
            }.onFailure { error ->
                if (!current) return@launch
                _ui.update {
                    it.copy(
                        schedule = it.schedule.copy(
                            saving = false,
                            error = error.message ?: "Could not schedule the recording.",
                        ),
                    )
                }
            }
        }
    }

    fun dismissFavoriteError() {
        _ui.update { state ->
            if (state.favoriteError == null && state.favoriteErrorGuide == null) state
            else state.copy(favoriteError = null, favoriteErrorGuide = null)
        }
    }

    fun consumeReturnChannel() {
        _ui.update { state ->
            if (state.returnToChannel == null) state else state.copy(returnToChannel = null)
        }
    }

    fun watch(channel: Channel) {
        val device = when (val screen = _ui.value.screen) {
            is Screen.Watch -> screen.device
            is Screen.PlayRecording -> return
            Screen.Shell -> {
                if (_ui.value.tunerReach != TunerReach.Ready) return
                _ui.value.selectedDevice ?: return
            }
        }
        if (channel.drm) {
            _ui.update {
                it.copy(channelsError = "${channel.guideNumber} is copy protected and cannot be played here.")
            }
            return
        }
        cancelChannelHop()
        dismissTuners()
        _ui.update {
            it.copy(
                screen = Screen.Watch(device, channel),
                channelsError = null,
                overlayVisible = true,
                transcoding = false,
                pendingChannel = null,
                tuners = TunerPanel(),
                schedule = it.schedule.copy(pending = null, saving = false, error = null),
            )
        }
        refreshRecordingRules(force = true)
        refreshRecordings()
        tune(
            device,
            channel,
            transcode = false,
            forceAuto = false,
            refreshEngine = !engineOriginKnown || engineOrigin == null,
        )
    }

    fun tryTranscode() {
        val screen = _ui.value.screen as? Screen.Watch ?: return
        cancelChannelHop()
        _ui.update { it.copy(transcoding = true, pendingChannel = null) }
        tune(screen.device, screen.channel, transcode = true, forceAuto = false)
    }

    fun resume() {
        when (val screen = _ui.value.screen) {
            is Screen.Watch -> {
                cancelChannelHop()
                tune(
                    screen.device,
                    screen.channel,
                    transcode = _ui.value.transcoding,
                    forceAuto = false,
                    refreshEngine = engineOrigin == null,
                )
            }
            is Screen.PlayRecording -> startRecording(screen.recording)
            Screen.Shell -> return
        }
    }

    fun stepChannel(delta: Int) {
        val screen = _ui.value.screen as? Screen.Watch ?: return
        cancelSkip()
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

    /** Saves stretch for the channel on screen. Turning it off keeps the slider positions. */
    fun setChannelStretch(stretch: ChannelStretch) {
        val key = channelStretchKey(playingGuideNumber(_ui.value.screen)) ?: return
        val next = stretch.normalized()
        val current = _ui.value.channelStretches[key]?.normalized() ?: ChannelStretch()
        if (next == current) return
        val updated = _ui.value.channelStretches + (key to next)
        store.saveChannelStretches(updated)
        _ui.update { it.copy(channelStretches = updated) }
    }

    fun showOverlay() {
        _ui.update { state ->
            if (state.overlayVisible) state else state.copy(overlayVisible = true)
        }
    }

    /** Television back dismisses the chrome and leaves the picture playing. */
    fun hideOverlay() {
        _ui.update { state ->
            if (state.overlayVisible) state.copy(overlayVisible = false) else state
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
                        screen = Screen.Shell,
                        tab = AppTab.Lineup,
                        playbackError = null,
                        playbackClock = PlaybackClock(),
                        signal = null,
                        signalNote = null,
                        tunerReleased = false,
                        offerTranscode = false,
                        pendingChannel = null,
                        returnToChannel = screen.channel,
                    )
                }
            }
            is Screen.PlayRecording -> {
                saveRecordingPosition()
                cancelChannelHop()
                _ui.update {
                    it.copy(
                        screen = Screen.Shell,
                        tab = AppTab.Dvr,
                        playbackError = null,
                        playbackClock = PlaybackClock(),
                        signal = null,
                        signalNote = null,
                        tunerReleased = false,
                        offerTranscode = false,
                        pendingChannel = null,
                    )
                }
            }
            Screen.Shell -> return
        }
        // The previous page is already showing. Stopping the stream can block
        // while the socket closes, so it runs off the UI thread.
        stopPlayback()
    }

    fun onHostStop() {
        if (!_ui.value.screen.inPlayer || _ui.value.tunerReleased) return
        saveRecordingPosition()
        cancelChannelHop()
        stopPlayback()
        _ui.update {
            it.copy(
                tunerReleased = true,
                phase = PlaybackPhase.Idle,
                signal = null,
                playbackError = null,
                playbackClock = PlaybackClock(),
                pendingChannel = null,
            )
        }
    }

    fun togglePlayback() {
        if (!showClock || _ui.value.playbackError != null || _ui.value.tunerReleased) return
        if (engine.transport.value.playing) engine.pause() else engine.resumePlayback()
    }

    /** Beginning of the show, or the oldest buffered moment when the opening is already gone. */
    fun jumpToAvailableStart() {
        cancelSkip()
        if (_ui.value.screen is Screen.PlayRecording) {
            seekRecording(recordingPlay.startMs)
            return
        }
        val live = System.currentTimeMillis()
        if (!shift.canSeekAvailableStart(playingProgram()?.startEpochMs, live)) return
        seekAvailableStart(live)
    }

    fun jumpToLive() {
        if (_ui.value.screen is Screen.PlayRecording) return
        val screen = _ui.value.screen as? Screen.Watch ?: return
        if (!shift.active) {
            if (engine.transport.value.playing) return
            tune(screen.device, screen.channel, transcode = _ui.value.transcoding, forceAuto = false)
            return
        }
        cancelSkip()
        seekShift(System.currentTimeMillis(), joinLive = true)
    }

    fun seekPlayback(wallMs: Long) {
        cancelSkip()
        if (_ui.value.screen is Screen.PlayRecording) {
            seekRecording(wallMs)
            return
        }
        performSeek(wallMs)
    }

    /**
     * Adds [deltaMs] to a skip burst. Quick repeats share one anchor and one
     * seek, and the center card shows the combined offset until the quiet time ends.
     */
    fun skipPlayback(deltaMs: Long) {
        val state = _ui.value
        if (state.screen is Screen.PlayRecording) {
            skipRecording(deltaMs)
            return
        }
        if (state.screen !is Screen.Watch) return
        if (!shift.active || !showClock || state.playbackError != null || state.tunerReleased) return
        if (deltaMs == 0L) return
        val clock = state.playbackClock
        val seekStart = availableRecordingStartMs(playingProgram()?.startEpochMs, clock.bufferStartMs)
        val seekEnd = maxOf(clock.liveMs, seekStart)
        val pending = accumulateSkip(
            pending = state.pendingSkip,
            positionMs = skipOrigin(clock.positionMs),
            deltaMs = deltaMs,
            seekStartMs = seekStart,
            seekEndMs = seekEnd,
        )
        if (pending != null && !canSeekWall(pending.targetMs)) return
        if (pending == null && state.pendingSkip == null) return
        cancelChannelHop()
        val generation = ++skipGeneration
        skipJob?.cancel()
        if (pending == null) {
            _ui.update { current ->
                if (current.pendingSkip == null) current else current.copy(pendingSkip = null)
            }
            return
        }
        _ui.update { it.copy(pendingSkip = pending) }
        skipJob = viewModelScope.launch {
            delay(SKIP_SETTLE_MS)
            if (generation != skipGeneration) return@launch
            val skip = _ui.value.pendingSkip ?: return@launch
            if (generation != skipGeneration) return@launch
            _ui.update { current ->
                if (generation != skipGeneration || current.pendingSkip == null) {
                    current
                } else {
                    current.copy(pendingSkip = null)
                }
            }
            if (generation != skipGeneration) return@launch
            performSeek(skip.targetMs)
        }
    }

    override fun onCleared() {
        guideJob?.cancel()
        guideClockJob?.cancel()
        hopJob?.cancel()
        skipJob?.cancel()
        tunerJob?.cancel()
        stopPlayback()
        shift.close()
        recordingPlay.close()
        engine.release()
    }

    private fun dismissTuners() {
        tunerGeneration++
        tunerJob?.cancel()
    }

    private fun reloadGuideForTuner() {
        if (_ui.value.guideMode == GuideMode.HdHomeRun) refreshGuide()
    }

    private fun refreshGuide() {
        when (_ui.value.guideMode) {
            GuideMode.None -> Unit
            GuideMode.Url -> refreshUrlGuide(_ui.value.activeGuideUrl)
            GuideMode.HdHomeRun -> refreshHdhrGuide()
        }
    }

    private fun refreshUrlGuide(rawUrl: String) {
        val url = normalizeGuideUrl(rawUrl) ?: return
        val generation = ++guideGeneration
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            _ui.update { state ->
                if (state.guideMode != GuideMode.Url) return@update state
                val same = state.appliedGuideUrl == url && state.guide != null
                state.copy(
                    guideLoading = true,
                    guideError = null,
                    guide = if (same) state.guide else null,
                    appliedGuideUrl = if (same) state.appliedGuideUrl else "",
                )
            }
            val result = withContext(Dispatchers.IO) { runCatching { client.loadGuide(url) } }
            if (generation != guideGeneration) return@launch
            result.onSuccess { index ->
                _ui.update {
                    val still = it.guideMode == GuideMode.Url && normalizeGuideUrl(it.activeGuideUrl) == url
                    if (!still) {
                        it
                    } else {
                        it.copy(
                            guideLoading = false,
                            guide = index,
                            guideNowMs = System.currentTimeMillis(),
                            guideError = null,
                            appliedGuideUrl = url,
                        )
                    }
                }
                if (generation == guideGeneration && _ui.value.appliedGuideUrl == url && _ui.value.guide != null) {
                    startGuideClock()
                }
            }.onFailure { error ->
                _ui.update {
                    if (it.guideMode != GuideMode.Url) it
                    else it.copy(
                        guideLoading = false,
                        guideError = error.message ?: "Could not load the guide.",
                    )
                }
            }
        }
    }

    private fun refreshHdhrGuide() {
        val state = _ui.value
        val device = state.selectedDevice
        when {
            device == null -> showHdhrUnavailable("No tuner selected.")
            state.tunerReach == TunerReach.Ready -> loadHdhrGuide(device)
            state.tunerReach == TunerReach.Checking -> showHdhrWaiting(device)
            device.host.isBlank() -> showHdhrUnavailable("No tuner selected.")
            else -> showHdhrUnavailable("No tuner found at ${device.host}.")
        }
    }

    private fun showHdhrUnavailable(message: String) {
        guideGeneration++
        guideJob?.cancel()
        guideClockJob?.cancel()
        _ui.update { state ->
            if (state.guideMode != GuideMode.HdHomeRun) state
            else state.copy(
                guideLoading = false,
                guide = null,
                appliedGuideUrl = "",
                guideError = message,
            )
        }
    }

    private fun showHdhrWaiting(device: HdhrDevice) {
        guideGeneration++
        guideJob?.cancel()
        val key = hdhrGuideKey(device.deviceId)
        val keep = _ui.value.guideMode == GuideMode.HdHomeRun &&
            _ui.value.appliedGuideUrl == key &&
            _ui.value.guide != null
        if (!keep) guideClockJob?.cancel()
        _ui.update { state ->
            if (state.guideMode != GuideMode.HdHomeRun) return@update state
            state.copy(
                guideLoading = true,
                guideError = null,
                guide = if (keep) state.guide else null,
                appliedGuideUrl = if (keep) state.appliedGuideUrl else "",
            )
        }
    }

    private fun loadHdhrGuide(device: HdhrDevice) {
        val key = hdhrGuideKey(device.deviceId)
        val generation = ++guideGeneration
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            _ui.update { state ->
                if (state.guideMode != GuideMode.HdHomeRun) return@update state
                val same = state.appliedGuideUrl == key && state.guide != null
                state.copy(
                    guideLoading = true,
                    guideError = null,
                    guide = if (same) state.guide else null,
                    appliedGuideUrl = if (same) state.appliedGuideUrl else "",
                )
            }
            val result = withContext(Dispatchers.IO) { runCatching { client.loadHdhrGuide(device) } }
            if (generation != guideGeneration) return@launch
            result.onSuccess { index ->
                _ui.update {
                    val current = it.selectedDevice
                    val still = it.guideMode == GuideMode.HdHomeRun &&
                        it.tunerReach == TunerReach.Ready &&
                        current != null &&
                        sameTuner(current, device)
                    if (!still) {
                        it
                    } else {
                        it.copy(
                            guideLoading = false,
                            guide = index,
                            guideNowMs = System.currentTimeMillis(),
                            guideError = null,
                            appliedGuideUrl = key,
                        )
                    }
                }
                if (generation == guideGeneration && _ui.value.appliedGuideUrl == key && _ui.value.guide != null) {
                    startGuideClock()
                }
            }.onFailure { error ->
                _ui.update {
                    if (it.guideMode != GuideMode.HdHomeRun) it
                    else it.copy(
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

    private fun readyDevice(): HdhrDevice? {
        if (_ui.value.screen.inPlayer) return null
        if (_ui.value.tunerReach != TunerReach.Ready) return null
        return _ui.value.selectedDevice
    }

    private fun beginProbe(device: HdhrDevice, userInitiated: Boolean) {
        val generation = ++probeGeneration
        if (userInitiated) {
            val saved = rememberTuner(store.loadSavedTuners(), device)
            store.saveSavedTuners(saved)
            store.saveRecent(device)
            dismissTuners()
            _ui.update {
                it.copy(
                    savedTuners = saved,
                    selectedDevice = device,
                    tunerReach = TunerReach.Checking,
                    tab = AppTab.Lineup,
                    channels = emptyList(),
                    channelsLoading = false,
                    channelsError = null,
                    query = "",
                    returnToChannel = null,
                    tunerAddError = null,
                    recentChannels = store.loadRecentChannels(device.deviceId),
                    favoritePending = emptySet(),
                    favoriteError = null,
                    favoriteErrorGuide = null,
                )
            }
            forgetFavoriteEdits()
            reloadGuideForTuner()
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { client.deviceForHost(device.host) } }
            if (generation != probeGeneration) return@launch
            result.onSuccess { live ->
                adoptTuner(live, fromUser = false)
            }.onFailure {
                if (!userInitiated) startupProbeFailed = true
                if (userInitiated || initialScanSettled) {
                    val found = _ui.value.devices.firstOrNull { sameTuner(it, device) }
                    if (found != null) {
                        adoptTuner(found, fromUser = false)
                    } else {
                        markTunerMissing(device)
                    }
                }
            }
        }
    }

    private fun adoptTuner(device: HdhrDevice, fromUser: Boolean) {
        if (fromUser) probeGeneration++
        val previous = _ui.value.selectedDevice
        val same = _ui.value.tunerReach == TunerReach.Ready &&
            previous != null &&
            sameTuner(previous, device)
        val saved = rememberTuner(store.loadSavedTuners(), device)
        store.saveSavedTuners(saved)
        store.saveRecent(device)
        if (same) {
            _ui.update {
                it.copy(
                    selectedDevice = device,
                    savedTuners = saved,
                    tab = if (fromUser) AppTab.Lineup else it.tab,
                    tunerAddError = null,
                )
            }
            return
        }
        dismissTuners()
        _ui.update {
            it.copy(
                selectedDevice = device,
                savedTuners = saved,
                tunerReach = TunerReach.Ready,
                tab = if (fromUser) AppTab.Lineup else it.tab,
                channels = emptyList(),
                channelsLoading = true,
                channelsError = null,
                query = "",
                returnToChannel = null,
                recentChannels = store.loadRecentChannels(device.deviceId),
                tunerAddError = null,
                favoritePending = emptySet(),
                favoriteError = null,
                favoriteErrorGuide = null,
            )
        }
        forgetFavoriteEdits()
        loadLineup(device, keepExisting = false)
        reloadGuideForTuner()
    }

    private fun reconcileStartup(discovered: List<HdhrDevice>) {
        val state = _ui.value
        val selected = state.selectedDevice ?: return
        if (state.tunerReach == TunerReach.Ready) return
        val found = discovered.firstOrNull { sameTuner(it, selected) }
        if (found != null) {
            probeGeneration++
            adoptTuner(found, fromUser = false)
        } else if (startupProbeFailed && state.tunerReach == TunerReach.Checking) {
            markTunerMissing(selected)
        }
    }

    private fun markTunerMissing(device: HdhrDevice) {
        _ui.update { state ->
            if (state.selectedDevice?.let { sameTuner(it, device) } != true) state
            else state.copy(tunerReach = TunerReach.Missing, channelsLoading = false)
        }
        val selected = _ui.value.selectedDevice
        if (selected != null && sameTuner(selected, device) && _ui.value.tunerReach == TunerReach.Missing) {
            reloadGuideForTuner()
        }
    }

    private fun refreshSelectedFromScan(discovered: List<HdhrDevice>) {
        val selected = _ui.value.selectedDevice ?: return
        if (_ui.value.tunerReach != TunerReach.Ready) return
        val found = discovered.firstOrNull { sameTuner(it, selected) } ?: return
        val saved = rememberTuner(store.loadSavedTuners(), found)
        store.saveSavedTuners(saved)
        store.saveRecent(found)
        _ui.update { it.copy(selectedDevice = found, savedTuners = saved) }
    }

    private fun forgetFavoriteEdits() {
        favoriteEdits.clear()
    }

    private fun applyFavoriteEdits(channels: List<Channel>, fetchGeneration: Int): List<Channel> {
        if (favoriteEdits.isEmpty()) return channels
        val drop = ArrayList<String>()
        val updated = channels.map { channel ->
            val edit = favoriteEdits[channel.guideNumber] ?: return@map channel
            if (fetchGeneration > edit.lineupGeneration) {
                drop += channel.guideNumber
                channel
            } else if (channel.favorite == edit.favorite) {
                channel
            } else {
                channel.copy(favorite = edit.favorite)
            }
        }
        for (number in drop) favoriteEdits.remove(number)
        return updated
    }

    private fun loadLineup(device: HdhrDevice, keepExisting: Boolean) {
        val generation = ++lineupGeneration
        viewModelScope.launch {
            _ui.update { it.copy(channelsLoading = true, channelsError = null) }
            val result = withContext(Dispatchers.IO) { runCatching { client.lineup(device) } }
            if (generation != lineupGeneration) return@launch
            val stillHere = _ui.value.tunerReach == TunerReach.Ready &&
                _ui.value.selectedDevice?.let { sameTuner(it, device) } == true
            if (!stillHere) return@launch
            result.onSuccess { channels ->
                _ui.update {
                    val shown = applyFavoriteEdits(channels, generation)
                    it.copy(
                        channelsLoading = false,
                        channels = shown,
                        channelsError = if (shown.isEmpty()) {
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

    private fun tune(
        device: HdhrDevice,
        channel: Channel,
        transcode: Boolean,
        forceAuto: Boolean,
        refreshEngine: Boolean = false,
    ) {
        cancelSkip()
        rememberViewed(device.deviceId, channel.guideNumber)
        if (!forceAuto) autoRetried = false
        val generation = ++playGeneration
        playJob?.cancel()
        pollJob?.cancel()
        showClock = false
        directOn = false
        engine.expectRestart()
        shift.stop()
        acquireWifi()
        _ui.update {
            it.copy(
                signal = null,
                peaks = SignalPeaks(),
                signalNote = null,
                video = VideoStats(),
                playbackError = null,
                playbackClock = PlaybackClock(),
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
            val shiftUrl = if (transcode) {
                null
            } else {
                resolveEngine(refreshEngine)?.let { shift.openSession(it, channel.guideNumber) }
            }
            val shifted = if (shiftUrl == null) {
                null
            } else {
                withContext(Dispatchers.IO) { runCatching { probeTimeshift(shiftUrl) }.getOrNull() }
                    ?.takeIf { it.failure == null }
            }
            if (generation != playGeneration) return@launch
            val playUrl = if (shifted != null && shiftUrl != null) {
                shift.begin(shiftUrl, shifted)
                shift.localUrl
            } else {
                shift.stop()
                val failure = withContext(Dispatchers.IO) { client.probeStream(prepared.url) }
                if (generation != playGeneration) return@launch
                if (failure != null) {
                    if (prepared.tunerIndex != null && !autoRetried && failure.hdhrCode == 804) {
                        autoRetried = true
                        tune(device, channel, transcode = transcode, forceAuto = true)
                        return@launch
                    }
                    showStreamFailure(failure)
                    return@launch
                }
                prepared.url
            }
            // The record engine chooses its own tuner. The idle index captured
            // while the previous stream was still up is a spare, and polling it
            // stays at zero for the rest of the session.
            val engineOwnsTuner = shifted != null && shiftUrl != null
            preferredTuner = if (engineOwnsTuner) null else prepared.tunerIndex
            localIp = prepared.localIp
            usingSpecificTuner = prepared.tunerIndex != null && !engineOwnsTuner
            val now = System.currentTimeMillis()
            showClock = true
            _ui.update {
                it.copy(
                    playbackClock = PlaybackClock(
                        active = true,
                        timeshift = shift.active,
                        playing = true,
                        bufferStartMs = if (shift.active) shift.bufferStartMs else now,
                        positionMs = now,
                        liveMs = now,
                    ),
                )
            }
            ensureClock()
            engine.play(playUrl)
            pollSignal(generation, device, channel)
        }
    }

    private suspend fun resolveEngine(refresh: Boolean): String? {
        if (!refresh && engineOriginKnown) return engineOrigin
        val found = withContext(Dispatchers.IO) {
            runCatching { client.recordEngineOrigin() }.getOrNull()
        }
        engineOrigin = found
        engineOriginKnown = true
        return found
    }

    private fun showStreamFailure(failure: StreamFailure) {
        val playback = playbackFailure(failure)
        _ui.update {
            it.copy(
                playbackError = playback.message,
                offerTranscode = false,
                phase = PlaybackPhase.Idle,
            )
        }
    }

    private fun playingProgram() = (_ui.value.screen as? Screen.Watch)?.let { screen ->
        _ui.value.guide?.airing(screen.channel.guideNumber, screen.channel.guideName, _ui.value.guideNowMs)
    }

    private fun canSeekWall(wallMs: Long): Boolean {
        if (!shift.active) return false
        val live = System.currentTimeMillis()
        return if (wallMs >= live - 750L) shift.canSeek(live, true, live) else shift.canSeek(wallMs, false, live)
    }

    private fun performSeek(wallMs: Long) {
        if (!shift.active) return
        val live = System.currentTimeMillis()
        if (wallMs >= live - 750L) seekShift(live, joinLive = true)
        else seekShift(wallMs, joinLive = false)
    }

    private fun seekAvailableStart(liveMs: Long) {
        val programStart = playingProgram()?.startEpochMs
        if (!shift.canSeekAvailableStart(programStart, liveMs)) return
        engine.expectRestart()
        if (!shift.seekAvailableStart(programStart, liveMs)) return
        skipLandedMs = availableRecordingStartMs(programStart, shift.bufferStartMs)
        engine.play(shift.localUrl, keepPicture = true)
    }

    private fun seekShift(wallMs: Long, joinLive: Boolean) {
        val live = System.currentTimeMillis()
        if (!shift.canSeek(wallMs, joinLive, live)) return
        engine.expectRestart()
        if (!shift.seek(wallMs, joinLive, live)) return
        skipLandedMs = if (joinLive) live else wallMs
        engine.play(shift.localUrl, keepPicture = true)
    }

    /**
     * Position to skip from. A seek the clock has not published yet still counts.
     * [publishClock] clears that landing once the playhead shows it, so watching
     * past it does not drag the next skip back there.
     */
    private fun skipOrigin(positionMs: Long): Long {
        val landed = settleSkipLanding(positionMs, skipLandedMs)
        skipLandedMs = landed
        return landed ?: positionMs
    }

    private fun ensureClock() {
        if (clockJob?.isActive == true) return
        clockJob = viewModelScope.launch {
            while (isActive) {
                publishClock()
                delay(250)
            }
        }
    }

    private fun publishClock() {
        if (!showClock) return
        if (_ui.value.screen is Screen.PlayRecording) {
            publishRecordingClock()
            return
        }
        if (_ui.value.screen !is Screen.Watch) return
        val now = System.currentTimeMillis()
        val media = engine.transport.value
        val clock = if (shift.active) {
            val sample = shift.sample(media.mediaTimeMs, now, media.playing)
            // Drop the remembered seek once this sample is showing it. A later
            // skip then starts from the playhead, not from that landing.
            skipLandedMs = settleSkipLanding(sample.positionMs, skipLandedMs)
            PlaybackClock(
                active = true,
                timeshift = true,
                playing = media.playing,
                bufferStartMs = sample.bufferStartMs,
                positionMs = sample.positionMs,
                liveMs = sample.liveMs,
            )
        } else {
            val freshStart = media.mediaTimeMs < 1_500L || _ui.value.phase == PlaybackPhase.Ready
            if (!directOn && media.playing && freshStart) {
                directOn = true
                directMedia0 = media.mediaTimeMs
                directWall0 = now - media.mediaTimeMs.coerceAtLeast(0L)
            }
            val position = if (directOn) {
                directWall0 + (media.mediaTimeMs - directMedia0).coerceAtLeast(0L)
            } else {
                now
            }
            PlaybackClock(
                active = true,
                timeshift = false,
                playing = media.playing,
                bufferStartMs = if (directOn) directWall0 else now,
                positionMs = position,
                liveMs = now,
            )
        }
        _ui.update { it.copy(playbackClock = clock) }
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
        val chosen = selectTuner(
            tuners,
            channel.guideNumber,
            localIp,
            preferredTuner,
            channel.frequencyHz,
        )
        val channelTaken = tuners.any { sameChannel(channel.guideNumber, it.vctNumber) }
        val index = when {
            chosen?.index != null -> chosen.index
            preferredTuner == null -> null
            channelTaken -> null
            else -> preferredTuner
        }
        if (index == null) return null
        val text = client.tunerText(device, index)
        if (chosen == null && text == null) return null
        val reading = mergeSignal(chosen, text, channel.frequencyHz)
        if (reading.locked) preferredTuner = chosen?.index ?: index
        return reading
    }

    private fun cancelChannelHop() {
        hopGeneration++
        hopJob?.cancel()
        hopJob = null
        if (_ui.value.pendingChannel != null) {
            _ui.update { it.copy(pendingChannel = null) }
        }
    }

    private fun cancelSkip() {
        skipGeneration++
        skipJob?.cancel()
        skipJob = null
        skipLandedMs = null
        if (_ui.value.pendingSkip != null) {
            _ui.update { it.copy(pendingSkip = null) }
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
        cancelSkip()
        playGeneration++
        playJob?.cancel()
        pollJob?.cancel()
        clockJob?.cancel()
        showClock = false
        directOn = false
        // Drop the player URL before the relay sockets close. A socket error
        // after that is ignored, so leaving the channel does not show a false failure.
        engine.stop()
        shift.stop()
        recordingPlay.stop()
        releaseWifi()
    }

    /**
     * Writes the frame on screen to the record engine.
     * A position already stored is left alone. A failed write puts the previous value back.
     */
    private fun saveRecordingPosition() {
        val screen = _ui.value.screen as? Screen.PlayRecording ?: return
        val clock = _ui.value.playbackClock
        if (!clock.active) return
        val start = clock.bufferStartMs
        val position = _ui.value.pendingSkip?.targetMs ?: clock.positionMs
        if (position < start) return
        val durationMs = if (clock.timeshift && clock.liveMs > start) clock.liveMs - start else null
        val resume = recordingResumeToStore(position - start, durationMs)
        val stored = _ui.value.dvr.recordings.firstOrNull { it.id == screen.recording.id }
        val previous = stored?.resumeSec
        if (previous == resume) return
        if (resume == 0L && (previous == null || previous == 0L)) return
        val cmd = screen.recording.cmdUrl ?: stored?.cmdUrl
        if (cmd.isNullOrBlank()) return
        val recordingId = screen.recording.id
        val generation = ++resumeSaveGeneration
        applyRecordingResume(recordingId, resume)
        viewModelScope.launch(Dispatchers.IO) {
            val saved = runCatching { client.setRecordingResume(cmd, resume) }.isSuccess
            if (generation != resumeSaveGeneration) return@launch
            if (saved) return@launch
            withContext(Dispatchers.Main) {
                if (generation != resumeSaveGeneration) return@withContext
                applyRecordingResume(recordingId, previous)
            }
        }
    }

    private fun applyRecordingResume(recordingId: String, resumeSec: Long?) {
        _ui.update { state ->
            val recordings = state.dvr.recordings.map { item ->
                if (item.id == recordingId) item.copy(resumeSec = resumeSec) else item
            }
            val playing = state.screen as? Screen.PlayRecording
            val screen = if (playing != null && playing.recording.id == recordingId) {
                Screen.PlayRecording(playing.recording.copy(resumeSec = resumeSec))
            } else {
                state.screen
            }
            state.copy(screen = screen, dvr = state.dvr.copy(recordings = recordings))
        }
    }

    private fun startRecording(recording: Recording) {
        val url = recording.playUrl ?: return
        cancelSkip()
        val generation = ++playGeneration
        playJob?.cancel()
        pollJob?.cancel()
        showClock = false
        directOn = false
        engine.expectRestart()
        shift.stop()
        recordingPlay.stop()
        acquireWifi()
        _ui.update {
            it.copy(
                signal = null,
                peaks = SignalPeaks(),
                signalNote = null,
                video = VideoStats(),
                playbackError = null,
                playbackClock = PlaybackClock(),
                offerTranscode = false,
                tunerReleased = false,
                phase = PlaybackPhase.Buffering,
                transcoding = false,
            )
        }
        playJob = viewModelScope.launch {
            val probed = withContext(Dispatchers.IO) { probeRecording(url) }
            if (generation != playGeneration) return@launch
            val failure = probed.failure
            if (failure != null) {
                showStreamFailure(failure)
                return@launch
            }
            val now = System.currentTimeMillis()
            val span = recordingSpan(recording, now, if (probed.range == null) 0L else probed.totalBytes)
            val resumeAt = if (probed.range != null) recordingResumeWall(recording, span) else null
            recordingPlay.begin(url, span, probed.range, startWallMs = resumeAt ?: span.startMs)
            if (generation != playGeneration) {
                recordingPlay.stop()
                return@launch
            }
            showClock = true
            _ui.update {
                it.copy(
                    playbackClock = PlaybackClock(
                        active = true,
                        timeshift = recordingPlay.seekable,
                        playing = true,
                        bufferStartMs = span.startMs,
                        positionMs = resumeAt ?: span.startMs,
                        liveMs = if (span.bounded) span.endMs else span.startMs + 1L,
                    ),
                )
            }
            ensureClock()
            engine.play(recordingPlay.localUrl)
        }
    }

    private fun publishRecordingClock() {
        if (!showClock || _ui.value.screen !is Screen.PlayRecording || !recordingPlay.active) return
        val now = System.currentTimeMillis()
        val media = engine.transport.value
        val sample = recordingPlay.sample(media.mediaTimeMs, now, media.playing)
        skipLandedMs = settleSkipLanding(sample.positionMs, skipLandedMs)
        _ui.update {
            it.copy(
                playbackClock = PlaybackClock(
                    active = true,
                    timeshift = recordingPlay.seekable,
                    playing = media.playing,
                    bufferStartMs = sample.startMs,
                    positionMs = sample.positionMs,
                    liveMs = sample.endMs,
                ),
            )
        }
    }

    private fun seekRecording(wallMs: Long) {
        if (!recordingPlay.seekable) return
        engine.expectRestart()
        val landed = recordingPlay.seek(wallMs) ?: return
        skipLandedMs = landed
        engine.play(recordingPlay.localUrl, keepPicture = true)
    }

    private fun skipRecording(deltaMs: Long) {
        val state = _ui.value
        if (state.screen !is Screen.PlayRecording) return
        if (!recordingPlay.seekable || !showClock || state.playbackError != null || state.tunerReleased) return
        if (deltaMs == 0L) return
        val clock = state.playbackClock
        val pending = accumulateSkip(
            pending = state.pendingSkip,
            positionMs = skipOrigin(clock.positionMs),
            deltaMs = deltaMs,
            seekStartMs = clock.bufferStartMs,
            seekEndMs = clock.liveMs,
        )
        if (pending == null && state.pendingSkip == null) return
        cancelChannelHop()
        val generation = ++skipGeneration
        skipJob?.cancel()
        if (pending == null) {
            _ui.update { current ->
                if (current.pendingSkip == null) current else current.copy(pendingSkip = null)
            }
            return
        }
        _ui.update { it.copy(pendingSkip = pending) }
        skipJob = viewModelScope.launch {
            delay(SKIP_SETTLE_MS)
            if (generation != skipGeneration) return@launch
            val skip = _ui.value.pendingSkip ?: return@launch
            if (generation != skipGeneration) return@launch
            _ui.update { current ->
                if (generation != skipGeneration || current.pendingSkip == null) {
                    current
                } else {
                    current.copy(pendingSkip = null)
                }
            }
            if (generation != skipGeneration) return@launch
            seekRecording(skip.targetMs)
        }
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
