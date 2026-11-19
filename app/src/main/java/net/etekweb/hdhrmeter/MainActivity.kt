package net.etekweb.hdhrmeter

import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import net.etekweb.hdhrmeter.player.PlaybackService
import net.etekweb.hdhrmeter.ui.AppNavigation
import net.etekweb.hdhrmeter.ui.AppTab
import net.etekweb.hdhrmeter.ui.ChannelsScreen
import net.etekweb.hdhrmeter.ui.DvrScreen
import net.etekweb.hdhrmeter.ui.HdhrTheme
import net.etekweb.hdhrmeter.ui.MeterViewModel
import net.etekweb.hdhrmeter.ui.PlayerScreen
import net.etekweb.hdhrmeter.ui.RecordConfirmDialog
import net.etekweb.hdhrmeter.ui.Screen
import net.etekweb.hdhrmeter.ui.inPlayer
import net.etekweb.hdhrmeter.ui.isTelevision
import net.etekweb.hdhrmeter.ui.isTelevisionUi
import net.etekweb.hdhrmeter.ui.televisionBackHidesPlayerOverlay
import net.etekweb.hdhrmeter.ui.televisionRemoteRevealsOverlay
import net.etekweb.hdhrmeter.ui.SettingsScreen
import net.etekweb.hdhrmeter.ui.TunerGate
import org.videolan.libvlc.util.VLCVideoLayout

class MainActivity : ComponentActivity() {
    private var playback by mutableStateOf<PlaybackService.Connection?>(null)
    private var bound = false
    private var hostStarted = false
    private var videoLayout: VLCVideoLayout? = null
    private var leavingPipForAudio = false
    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            playback = service as PlaybackService.Connection
            if (hostStarted) videoLayout?.let(service.viewModel::attachVideo)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            playback = null
        }
    }
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isInPictureInPictureMode) return
            val connection = playback ?: return
            when (intent.action) {
                ACTION_PIP_PLAY -> connection.viewModel.setPlaybackPlaying(true)
                ACTION_PIP_PAUSE -> connection.viewModel.setPlaybackPlaying(false)
                ACTION_PIP_CLOSE -> {
                    connection.viewModel.onHostStop()
                    finish()
                }
                ACTION_AUDIO_ONLY -> {
                    if (!connection.canKeepPlaying || !connection.viewModel.ui.value.screen.inPlayer) return
                    leavingPipForAudio = true
                    // The service retains the stream and reattaches the picture when the app opens.
                    finish()
                }
            }
        }
    }
    private val supportsPip by lazy(LazyThreadSafetyMode.NONE) {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    private var pipEligible = false
    private var pipWidth = 0
    private var pipHeight = 0
    private var pipTitle: String? = null
    private var pipCanTogglePlayback = false
    private var pipPlaying = false
    private var pipSource: Rect? = null
    private var inPip by mutableStateOf(false)
    private var enteringPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inPip = isInPictureInPictureMode
        enableEdgeToEdge()
        if (supportsPip) {
            ContextCompat.registerReceiver(
                this,
                pipActionReceiver,
                IntentFilter(ACTION_AUDIO_ONLY).apply {
                    addAction(ACTION_PIP_PLAY)
                    addAction(ACTION_PIP_PAUSE)
                    addAction(ACTION_PIP_CLOSE)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        bound = bindService(Intent(this, PlaybackService::class.java), playbackConnection, BIND_AUTO_CREATE)
        setContent {
            val viewModel = playback?.viewModel ?: return@setContent
            val state by viewModel.ui.collectAsStateWithLifecycle()
            val watch = state.screen as? Screen.Watch
            val playing = state.screen.inPlayer
            val keepScreenOn = playing &&
                !state.tunerReleased &&
                state.playbackError == null
            val showChrome = !inPip && !enteringPip
            val hideSystemBars = playing && !state.overlayVisible && showChrome
            val pipTitle = when {
                watch != null -> "${watch.channel.guideNumber} ${watch.channel.guideName}"
                state.screen is Screen.PlayRecording ->
                    (state.screen as Screen.PlayRecording).recording.title
                else -> null
            }
            val videoWidth = state.video.width ?: 0
            val videoHeight = state.video.height ?: 0
            val clock = state.playbackClock
            LaunchedEffect(keepScreenOn, pipTitle, videoWidth, videoHeight, clock.active, clock.playing) {
                updatePipSession(
                    eligible = keepScreenOn && supportsPip,
                    width = videoWidth,
                    height = videoHeight,
                    title = pipTitle,
                    canTogglePlayback = clock.active,
                    playing = clock.playing,
                )
            }
            val layoutDirection = LocalLayoutDirection.current
            val statusPadding = WindowInsets.statusBars.asPaddingValues()
            val statusHidden = statusPadding.calculateTopPadding() == 0.dp &&
                statusPadding.calculateBottomPadding() == 0.dp &&
                statusPadding.calculateStartPadding(layoutDirection) == 0.dp &&
                statusPadding.calculateEndPadding(layoutDirection) == 0.dp
            var statusWasHidden by remember { mutableStateOf(false) }
            DisposableEffect(hideSystemBars) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (hideSystemBars) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
                onDispose {
                    if (hideSystemBars) {
                        controller.show(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
            LaunchedEffect(hideSystemBars, statusHidden, showChrome) {
                if (!showChrome) return@LaunchedEffect
                if (!hideSystemBars) {
                    statusWasHidden = false
                } else if (statusHidden) {
                    statusWasHidden = true
                } else if (statusWasHidden) {
                    viewModel.showOverlay()
                }
            }
            DisposableEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            val television = isTelevision()
            BackHandler(enabled = state.schedule.pending != null || playing) {
                when {
                    state.schedule.saving -> Unit
                    state.schedule.pending != null -> viewModel.dismissRecording()
                    televisionBackHidesPlayerOverlay(
                        television,
                        playing,
                        state.overlayVisible,
                        showChrome,
                    ) -> viewModel.hideOverlay()
                    else -> viewModel.onBack()
                }
            }
            HdhrTheme {
                // The lineup leaves composition during playback. Keep its saved UI state
                // for as long as Lineup is the active tab on this tuner.
                val lineupState = key(state.tab, state.selectedDevice?.deviceId) {
                    rememberSaveableStateHolder()
                }
                if (playing) {
                    PlayerScreen(
                        state = state,
                        onAttach = ::attachVideo,
                        onDetach = ::detachVideo,
                        onToggleOverlay = viewModel::toggleOverlay,
                        onSelectOverlayTab = viewModel::selectOverlayTab,
                        onChannelStretch = viewModel::setChannelStretch,
                        onBack = viewModel::onBack,
                        onStep = viewModel::stepChannel,
                        onTranscode = viewModel::tryTranscode,
                        onResume = viewModel::resume,
                        onTogglePlay = viewModel::togglePlayback,
                        onJumpStart = viewModel::jumpToAvailableStart,
                        onJumpLive = viewModel::jumpToLive,
                        onSeek = viewModel::seekPlayback,
                        onSkip = viewModel::skipPlayback,
                        showChrome = showChrome,
                        onVideoBounds = ::onVideoBounds,
                        onRecord = viewModel::requestRecording,
                    )
                } else {
                    AppNavigation(
                        tab = state.tab,
                        onTab = viewModel::selectTab,
                        modifier = Modifier.fillMaxSize().imePadding(),
                    ) {
                        when (state.tab) {
                            AppTab.Lineup -> TunerGate(title = "Lineup", state = state) { device ->
                                lineupState.SaveableStateProvider(AppTab.Lineup.name) {
                                    ChannelsScreen(
                                        state = state,
                                        device = device,
                                        onRefresh = viewModel::refreshLineup,
                                        onQuery = viewModel::setQuery,
                                        onFilter = viewModel::setFilter,
                                        onWatch = viewModel::watch,
                                        onReturnHighlightFinished = viewModel::consumeLineupReturn,
                                        onOpenTuners = viewModel::openTuners,
                                        onCloseTuners = viewModel::closeTuners,
                                        onRefreshTuners = viewModel::refreshTuners,
                                        onClearTuner = viewModel::forceClearTuner,
                                        onToggleFavorite = viewModel::toggleFavorite,
                                        onDismissFavoriteError = viewModel::dismissFavoriteError,
                                        onEnsureSchedule = viewModel::ensureScheduleStatus,
                                        onRecord = viewModel::requestRecording,
                                    )
                                }
                            }
                            AppTab.Dvr -> TunerGate(title = "DVR", state = state) {
                                DvrScreen(
                                    state = state,
                                    onEnsure = viewModel::ensureRecordings,
                                    onRefresh = viewModel::refreshRecordings,
                                    onOpen = viewModel::openRecording,
                                    onClose = viewModel::closeRecording,
                                    onPlay = viewModel::playRecording,
                                    onResume = viewModel::resumeRecording,
                                    onSection = viewModel::selectDvrSection,
                                    onEnsureScheduled = viewModel::ensureScheduled,
                                    onRefreshScheduled = viewModel::refreshScheduled,
                                    onOpenScheduled = viewModel::openScheduled,
                                    onDelete = viewModel::requestDelete,
                                    onConfirmDelete = viewModel::confirmDelete,
                                    onDismissDelete = viewModel::dismissDelete,
                                    onEdit = viewModel::requestEditPadding,
                                    onDismissEdit = viewModel::dismissEditPadding,
                                    onSavePadding = viewModel::saveScheduledPadding,
                                    onStorageForecast = viewModel::setStorageForecast,
                                )
                            }
                            AppTab.Settings -> SettingsScreen(
                                state = state,
                                onScan = viewModel::scan,
                                onHostChange = viewModel::setManualHost,
                                onAddDevice = viewModel::addDevice,
                                onSelectDevice = viewModel::selectDevice,
                                onRemoveDevice = viewModel::removeSavedDevice,
                                onDvrAddressChange = viewModel::setDvrAddress,
                                onGuideDraft = viewModel::setGuideDraft,
                                onAddGuide = viewModel::addGuideSource,
                                onSelectNone = viewModel::selectGuideNone,
                                onSelectHdHomeRun = viewModel::selectGuideHdHomeRun,
                                onSelectGuide = viewModel::selectGuideSource,
                                onRemoveGuide = viewModel::removeGuideSource,
                                onHardwareDecode = viewModel::setHardwareDecode,
                                onShowChannelNumber = viewModel::setShowChannelNumber,
                            )
                        }
                    }
                }
                if (showChrome) {
                    state.schedule.pending?.let { pending ->
                        RecordConfirmDialog(
                            pending = pending,
                            rules = state.schedule.rules,
                            recordings = state.dvr.recordings,
                            dvr = state.dvr,
                            channels = state.channels,
                            nowMs = state.guideNowMs,
                            saving = state.schedule.saving,
                            error = state.schedule.error,
                            onDismiss = viewModel::dismissRecording,
                            onConfirm = viewModel::confirmRecording,
                        )
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val viewModel = playback?.viewModel ?: return super.dispatchKeyEvent(event)
        val state = viewModel.ui.value
        val reveal = televisionRemoteRevealsOverlay(
            television = isTelevisionUi(resources.configuration.uiMode),
            playing = state.screen.inPlayer,
            overlayVisible = state.overlayVisible,
            chromeVisible = !inPip && !enteringPip,
            keyCode = event.keyCode,
        )
        if (reveal) {
            // The key that opens the chrome should not also move focus or press pause.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                viewModel.showOverlay()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        hostStarted = true
        videoLayout?.let { playback?.viewModel?.attachVideo(it) }
    }

    override fun onStop() {
        hostStarted = false
        val connection = playback
        videoLayout?.let { connection?.viewModel?.detachVideo(it) }
        // Closing PiP while the display is on is a stop gesture. Locking the
        // screen also stops the activity, and should keep its audio going.
        val closingPip = isInPictureInPictureMode &&
            getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            !leavingPipForAudio
        if (closingPip || connection?.canKeepPlaying != true) {
            connection?.viewModel?.onHostStop()
        } else {
            connection.viewModel.saveRecordingPosition()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (supportsPip) unregisterReceiver(pipActionReceiver)
        if (bound) unbindService(playbackConnection)
        super.onDestroy()
    }

    private fun attachVideo(layout: VLCVideoLayout) {
        videoLayout = layout
        if (hostStarted) playback?.viewModel?.attachVideo(layout)
    }

    private fun detachVideo(layout: VLCVideoLayout) {
        if (videoLayout === layout) videoLayout = null
        playback?.viewModel?.detachVideo(layout)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12 and later enter from setAutoEnterEnabled, which also covers
        // the home gesture. Older releases only get this callback.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (!pipEligible || !supportsPip) return
        enteringPip = true
        val entered = runCatching { enterPictureInPictureMode(buildPipParams()) }.getOrDefault(false)
        if (!entered) enteringPip = false
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (!isInPictureInPictureMode) enteringPip = false
    }

    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        if (pipState.isTransitioningToPip()) {
            enteringPip = true
        } else if (!isInPictureInPictureMode) {
            // A cancelled home gesture never enters PiP, so put the meters back.
            enteringPip = false
        }
    }

    private fun onVideoBounds(rect: Rect) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        if (pipSource == rect) return
        pipSource = rect
        publishPipParams()
    }

    private fun updatePipSession(
        eligible: Boolean,
        width: Int,
        height: Int,
        title: String?,
        canTogglePlayback: Boolean,
        playing: Boolean,
    ) {
        pipEligible = eligible
        pipWidth = width
        pipHeight = height
        pipTitle = title
        pipCanTogglePlayback = canTogglePlayback
        pipPlaying = playing
        publishPipParams()
    }

    private fun publishPipParams() {
        if (!supportsPip) return
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(playbackAspect(pipWidth, pipHeight))
        pipSource?.takeIf { it.width() > 0 && it.height() > 0 }?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(pipEligible)
            builder.setSeamlessResizeEnabled(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.setTitle(pipTitle)
            builder.setCloseAction(
                pipAction(ACTION_PIP_CLOSE, android.R.drawable.ic_menu_close_clear_cancel, R.string.media_stop),
            )
        }
        builder.setActions(buildList {
            if (pipEligible) {
                add(pipAction(ACTION_AUDIO_ONLY, R.drawable.ic_headphones, R.string.media_audio_only))
                // App actions replace the default media buttons in Android's PiP menu.
                if (pipCanTogglePlayback) {
                    add(
                        if (pipPlaying) pipAction(ACTION_PIP_PAUSE, android.R.drawable.ic_media_pause, R.string.media_pause)
                        else pipAction(ACTION_PIP_PLAY, android.R.drawable.ic_media_play, R.string.media_play),
                    )
                }
            }
        })
        return builder.build()
    }

    private fun pipAction(action: String, icon: Int, title: Int): RemoteAction {
        val label = getString(title)
        val intent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(Icon.createWithResource(this, icon), label, label, intent)
    }

    private fun playbackAspect(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toFloat() / height.toFloat()
        // PictureInPictureParams rejects ratios outside about 1/2.39 to 2.39.
        if (ratio < 0.42f || ratio > 2.38f) return Rational(16, 9)
        return Rational(width, height)
    }

    private companion object {
        const val ACTION_AUDIO_ONLY = "net.etekweb.hdhrmeter.AUDIO_ONLY"
        const val ACTION_PIP_PLAY = "net.etekweb.hdhrmeter.PIP_PLAY"
        const val ACTION_PIP_PAUSE = "net.etekweb.hdhrmeter.PIP_PAUSE"
        const val ACTION_PIP_CLOSE = "net.etekweb.hdhrmeter.PIP_CLOSE"
    }
}
