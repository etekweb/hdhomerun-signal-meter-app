package com.eamat.hdhrmeter

import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eamat.hdhrmeter.ui.ChannelsScreen
import com.eamat.hdhrmeter.ui.DevicesScreen
import com.eamat.hdhrmeter.ui.HdhrTheme
import com.eamat.hdhrmeter.ui.MeterViewModel
import com.eamat.hdhrmeter.ui.PlayerScreen
import com.eamat.hdhrmeter.ui.Screen

class MainActivity : ComponentActivity() {
    private val viewModel: MeterViewModel by viewModels()
    private val supportsPip by lazy(LazyThreadSafetyMode.NONE) {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    private var pipEligible = false
    private var pipWidth = 0
    private var pipHeight = 0
    private var pipTitle: String? = null
    private var pipSource: Rect? = null
    private var inPip by mutableStateOf(false)
    private var enteringPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inPip = isInPictureInPictureMode
        enableEdgeToEdge()
        setContent {
            val state by viewModel.ui.collectAsStateWithLifecycle()
            val watch = state.screen as? Screen.Watch
            val keepScreenOn = watch != null &&
                !state.tunerReleased &&
                state.playbackError == null
            val showChrome = !inPip && !enteringPip
            val hideSystemBars = watch != null && !state.overlayVisible && showChrome
            val pipTitle = watch?.let { "${it.channel.guideNumber} ${it.channel.guideName}" }
            val videoWidth = state.video.width ?: 0
            val videoHeight = state.video.height ?: 0
            LaunchedEffect(keepScreenOn, pipTitle, videoWidth, videoHeight) {
                updatePipSession(
                    eligible = keepScreenOn && supportsPip,
                    width = videoWidth,
                    height = videoHeight,
                    title = pipTitle,
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
            BackHandler(enabled = state.screen !is Screen.Devices) {
                viewModel.onBack()
            }
            HdhrTheme {
                when (val screen = state.screen) {
                    Screen.Devices -> DevicesScreen(
                        state = state,
                        onScan = viewModel::scan,
                        onHostChange = viewModel::setManualHost,
                        onConnect = viewModel::connectManual,
                        onOpen = viewModel::open,
                        onGuideHostChange = viewModel::setGuideHost,
                        onSaveGuide = viewModel::saveGuide,
                    )
                    is Screen.Channels -> ChannelsScreen(
                        state = state,
                        device = screen.device,
                        onBack = viewModel::onBack,
                        onRefresh = viewModel::refreshLineup,
                        onQuery = viewModel::setQuery,
                        onFilter = viewModel::setFilter,
                        onWatch = viewModel::watch,
                        onReturnScrollHandled = viewModel::consumeReturnChannel,
                    )
                    is Screen.Watch -> PlayerScreen(
                        state = state,
                        onAttach = viewModel::attachVideo,
                        onDetach = viewModel::detachVideo,
                        onToggleOverlay = viewModel::toggleOverlay,
                        onBack = viewModel::onBack,
                        onStep = viewModel::stepChannel,
                        onTranscode = viewModel::tryTranscode,
                        onResume = viewModel::resume,
                        showChrome = showChrome,
                        onVideoBounds = ::onVideoBounds,
                    )
                }
            }
        }
    }

    override fun onStop() {
        // Home leaves the activity started in the picture-in-picture window, so
        // this is not that path. Closing the window stops the activity while
        // isInPictureInPictureMode is still true; the flag changes only after
        // onStop. Release the tuner here, or playback continues until the
        // system freezes the process.
        viewModel.onHostStop()
        super.onStop()
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

    private fun updatePipSession(eligible: Boolean, width: Int, height: Int, title: String?) {
        pipEligible = eligible
        pipWidth = width
        pipHeight = height
        pipTitle = title
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
        }
        return builder.build()
    }

    private fun playbackAspect(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toFloat() / height.toFloat()
        // PictureInPictureParams rejects ratios outside about 1/2.39 to 2.39.
        if (ratio < 0.42f || ratio > 2.38f) return Rational(16, 9)
        return Rational(width, height)
    }
}
