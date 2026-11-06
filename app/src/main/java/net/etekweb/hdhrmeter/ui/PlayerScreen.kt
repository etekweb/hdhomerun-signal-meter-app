package net.etekweb.hdhrmeter.ui

import android.graphics.Rect
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.etekweb.hdhrmeter.data.AiringRecordStatus
import net.etekweb.hdhrmeter.data.Channel
import net.etekweb.hdhrmeter.data.ChannelStretch
import net.etekweb.hdhrmeter.data.GuideProgram
import net.etekweb.hdhrmeter.data.STRETCH_PERCENT_MAX
import net.etekweb.hdhrmeter.data.STRETCH_PERCENT_MIN
import net.etekweb.hdhrmeter.data.channelStretchKey
import net.etekweb.hdhrmeter.data.formatStretchPercent
import net.etekweb.hdhrmeter.data.pictureScale
import net.etekweb.hdhrmeter.data.stretchFor
import net.etekweb.hdhrmeter.data.stretchPercent
import net.etekweb.hdhrmeter.data.Recording
import net.etekweb.hdhrmeter.data.airingRecordStatus
import net.etekweb.hdhrmeter.data.SignalReading
import net.etekweb.hdhrmeter.player.PendingSkip
import net.etekweb.hdhrmeter.player.SKIP_STEP_MS
import net.etekweb.hdhrmeter.player.VideoStats
import net.etekweb.hdhrmeter.player.formatSkipDelta
import net.etekweb.hdhrmeter.player.playbackBar
import kotlin.math.roundToInt
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
fun PlayerScreen(
    state: UiState,
    onAttach: (VLCVideoLayout) -> Unit,
    onDetach: (VLCVideoLayout) -> Unit,
    onToggleOverlay: () -> Unit,
    onSelectOverlayTab: (OverlayTab) -> Unit,
    onChannelStretch: (ChannelStretch) -> Unit,
    onBack: () -> Unit,
    onStep: (Int) -> Unit,
    onTranscode: () -> Unit,
    onResume: () -> Unit,
    onTogglePlay: () -> Unit,
    onJumpStart: () -> Unit,
    onJumpLive: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip: (Long) -> Unit,
    showChrome: Boolean,
    onVideoBounds: (Rect) -> Unit,
    onRecord: (String, String, GuideProgram) -> Unit,
) {
    val watch = state.screen as? Screen.Watch
    val recording = (state.screen as? Screen.PlayRecording)?.recording
    if (watch == null && recording == null) return
    val television = isTelevision()
    val tvFocus = remember { TvPlayerFocus() }
    val tv = if (television) tvFocus else null
    val corner = watch?.channel?.guideNumber
        ?: recording?.channelNumber?.trim()?.ifBlank { null }
        ?: recording?.title.orEmpty()
    val (stretchX, stretchY) = stretchFor(
        playingGuideNumber(state.screen),
        state.channelStretches,
    ).pictureScale()
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds(),
    ) {
        AndroidView(
            factory = { context ->
                val video = VLCVideoLayout(context)
                FrameLayout(context).apply {
                    if (television) {
                        // The surface would otherwise take the direction pad away from the controls.
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        video.isFocusable = false
                        video.isFocusableInTouchMode = false
                    }
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    addView(
                        video,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }.also { onAttach(video) }
            },
            onRelease = { frame ->
                val video = frame.getChildAt(0) as? VLCVideoLayout ?: return@AndroidView
                onDetach(video)
            },
            update = { frame ->
                // Scale the drawn picture around its center. 1 leaves that side alone.
                // The sliders stay stored when stretch is off, and this stays at 1.
                if (frame.scaleX != stretchX) frame.scaleX = stretchX
                if (frame.scaleY != stretchY) frame.scaleY = stretchY
            },
            modifier = Modifier
                .fillMaxSize()
                .then(if (television) Modifier.focusProperties { canFocus = false } else Modifier)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    onVideoBounds(
                        Rect(
                            bounds.left.roundToInt(),
                            bounds.top.roundToInt(),
                            bounds.right.roundToInt(),
                            bounds.bottom.roundToInt(),
                        ),
                    )
                },
        )
        if (showChrome && state.overlayVisible) {
            SignalOverlay(
                channel = watch?.channel,
                recording = recording,
                state = state,
                onToggleOverlay = onToggleOverlay,
                onSelectTab = onSelectOverlayTab,
                onChannelStretch = onChannelStretch,
                onBack = onBack,
                onStep = onStep,
                onTranscode = onTranscode,
                onResume = onResume,
                onTogglePlay = onTogglePlay,
                onJumpStart = onJumpStart,
                onJumpLive = onJumpLive,
                onSeek = onSeek,
                onSkip = onSkip,
                onRecord = onRecord,
                tv = tv,
            )
        } else if (showChrome) {
            val touchSlop = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(onToggleOverlay, touchSlop) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var traveled = 0f
                            var revealed = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                traveled += change.positionChange().getDistance()
                                if (!revealed && traveled > touchSlop) {
                                    revealed = true
                                    onToggleOverlay()
                                }
                                if (!change.pressed) {
                                    if (!revealed) onToggleOverlay()
                                    break
                                }
                            }
                        }
                    },
            ) {
                Text(
                    corner,
                    color = Color.White.copy(alpha = 0.86f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .safeDrawingPadding()
                        .padding(16.dp),
                )
            }
        }
        if (showChrome) {
            val pending = state.pendingChannel
            if (pending != null) {
                ChannelHopCard(
                    channel = pending,
                    program = state.guide?.airing(pending.guideNumber, pending.guideName, state.guideNowMs),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                state.pendingSkip?.let { skip ->
                    SkipCard(skip, Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun SignalOverlay(
    channel: Channel?,
    recording: Recording?,
    state: UiState,
    onToggleOverlay: () -> Unit,
    onSelectTab: (OverlayTab) -> Unit,
    onChannelStretch: (ChannelStretch) -> Unit,
    onBack: () -> Unit,
    onStep: (Int) -> Unit,
    onTranscode: () -> Unit,
    onResume: () -> Unit,
    onTogglePlay: () -> Unit,
    onJumpStart: () -> Unit,
    onJumpLive: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip: (Long) -> Unit,
    onRecord: (String, String, GuideProgram) -> Unit,
    tv: TvPlayerFocus?,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        val sidePanel = maxWidth > maxHeight && maxWidth > 700.dp
        val program = if (channel != null) {
            state.guide?.airing(channel.guideNumber, channel.guideName, state.guideNowMs)
        } else {
            null
        }
        val infoProgram = recording?.let { recordingProgram(it) } ?: program
        val recordStatus = if (recording == null && channel != null && program != null) {
            airingRecordStatus(
                state.schedule.rules,
                state.dvr.recordings,
                channel.guideNumber,
                program,
                state.guideNowMs,
            )
        } else {
            null
        }
        val recordNotice = if (program != null && state.schedule.noticeStartMs == program.startEpochMs) {
            state.schedule.notice
        } else {
            null
        }
        val onRecordProgram = if (recordStatus != null && channel != null && program != null) {
            { onRecord(channel.guideNumber, channel.guideName, program) }
        } else {
            null
        }
        val upcoming = if (channel != null) {
            state.guide?.upcoming(channel.guideNumber, channel.guideName, state.guideNowMs)
        } else {
            null
        }
        val overlayHeight = maxHeight
        val number = channel?.guideNumber ?: recording?.channelNumber?.trim().orEmpty()
        val name = channel?.guideName ?: recording?.title.orEmpty()
        Box(Modifier.fillMaxSize().noRippleClick(onClick = onToggleOverlay, focusable = tv == null))
        if (sidePanel) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ChannelHeader(number, name, onBack, television = tv != null)
                    Spacer(Modifier.weight(1f))
                    NoticeBlock(state, onTranscode, onResume, Modifier.padding(horizontal = 12.dp))
                    Transport(state, program, tv, onStep, onTogglePlay, onJumpStart, onJumpLive, onSeek, onSkip)
                }
                OverlayPanel(
                    channel = channel,
                    recording = recording,
                    state = state,
                    program = infoProgram,
                    upcoming = upcoming,
                    recordStatus = recordStatus,
                    recordNotice = recordNotice,
                    onRecord = onRecordProgram,
                    recordBusy = state.schedule.saving,
                    onSelectTab = onSelectTab,
                    onChannelStretch = onChannelStretch,
                    tv = tv,
                    modifier = Modifier
                        .width(340.dp)
                        .heightIn(max = overlayHeight)
                        .padding(12.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ChannelHeader(number, name, onBack, television = tv != null)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Keep the channel buttons and any playback notice below a long description.
                    val panelMax = (maxHeight - 320.dp).coerceAtLeast(120.dp)
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                        OverlayPanel(
                            channel = channel,
                            recording = recording,
                            state = state,
                            program = infoProgram,
                            upcoming = upcoming,
                            recordStatus = recordStatus,
                            recordNotice = recordNotice,
                            onRecord = onRecordProgram,
                            recordBusy = state.schedule.saving,
                            onSelectTab = onSelectTab,
                            onChannelStretch = onChannelStretch,
                            tv = tv,
                            modifier = Modifier
                                .heightIn(max = panelMax)
                                .padding(horizontal = 12.dp),
                        )
                        NoticeBlock(
                            state,
                            onTranscode,
                            onResume,
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        Transport(state, program, tv, onStep, onTogglePlay, onJumpStart, onJumpLive, onSeek, onSkip)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelHopCard(channel: Channel, program: GuideProgram?, modifier: Modifier = Modifier) {
    val programTitle = program?.title?.takeIf { title ->
        title.isNotBlank() && !title.equals(channel.guideName, ignoreCase = true)
    }
    Surface(
        color = Color(0xE612181D),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .padding(horizontal = 32.dp)
            .widthIn(max = 440.dp)
            .fillMaxWidth(0.86f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Switching to",
                color = Muted,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                channel.guideNumber,
                color = Amber,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                channel.guideName,
                color = Paper,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (programTitle != null) {
                Text(
                    programTitle,
                    color = Paper,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SkipCard(skip: PendingSkip, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xE612181D),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .padding(horizontal = 32.dp)
            .widthIn(max = 440.dp)
            .fillMaxWidth(0.86f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Skipping",
                color = Muted,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                formatSkipDelta(skip.deltaMs),
                color = Amber,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatClockTime(skip.targetMs),
                color = Paper,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ChannelHeader(number: String, name: String, onBack: () -> Unit, television: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.tvFocusBorder(television && focused),
            interactionSource = if (television) interaction else null,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Paper)
        }
        Column(Modifier.weight(1f)) {
            if (number.isNotBlank()) {
                Text(
                    number,
                    color = Amber,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            if (name.isNotBlank()) {
                Text(
                    name,
                    color = Paper,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Info-tab program for a stored recording. Air times stay on the program, not the file padding. */
private fun recordingProgram(recording: Recording): GuideProgram? {
    val start = recording.startEpochMs ?: recording.recordStartEpochMs ?: return null
    val stop = recording.endEpochMs ?: recording.recordEndEpochMs ?: return null
    if (stop <= start) return null
    return GuideProgram(
        title = recording.title,
        subtitle = recording.episodeTitle,
        description = recording.synopsis,
        episode = recording.episodeNumber,
        startEpochMs = start,
        stopEpochMs = stop,
    )
}

@Composable
private fun OverlayPanel(
    channel: Channel?,
    recording: Recording?,
    state: UiState,
    program: GuideProgram?,
    upcoming: GuideProgram?,
    recordStatus: AiringRecordStatus?,
    recordNotice: String?,
    onRecord: (() -> Unit)?,
    recordBusy: Boolean,
    onSelectTab: (OverlayTab) -> Unit,
    onChannelStretch: (ChannelStretch) -> Unit,
    tv: TvPlayerFocus?,
    modifier: Modifier = Modifier,
) {
    val file = recording != null
    val tabs = overlayTabsFor(file)
    val tab = visibleOverlayTab(state.overlayTab, file)
    val showNext = tab == OverlayTab.Info && upcoming != null
    val infoScroll = rememberScrollState()
    val signalScroll = rememberScrollState()
    val playbackScroll = rememberScrollState()
    val scroll = when (tab) {
        OverlayTab.Info -> infoScroll
        OverlayTab.Signal -> signalScroll
        OverlayTab.Playback -> playbackScroll
    }
    Surface(
        color = Color(0xE612181D),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .holdClicks(focusable = tv == null),
    ) {
        Column(Modifier.fillMaxWidth()) {
            OverlayTabs(
                tabs = tabs,
                selected = tab,
                onSelect = onSelectTab,
                tv = tv,
                returnStop = rightmostTransport(enabledTransportStopsFor(state)),
            )
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = 14.dp)
                    .padding(top = 12.dp, bottom = if (showNext) 8.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    OverlayTab.Playback -> PlaybackStretchPanel(
                        guideNumber = channel?.guideNumber ?: recording?.channelNumber,
                        stretch = stretchFor(
                            channel?.guideNumber ?: recording?.channelNumber,
                            state.channelStretches,
                        ),
                        onStretch = onChannelStretch,
                    )
                    OverlayTab.Info -> if (recording != null) {
                        RecordingInfo(recording, program)
                    } else {
                        ProgramInfo(
                            program,
                            state.guideLoading,
                            state.guideError,
                            recordStatus = recordStatus,
                            recordNotice = recordNotice,
                            onRecord = onRecord,
                            recordBusy = recordBusy,
                        )
                    }
                    OverlayTab.Signal -> if (channel != null) {
                        SignalInfo(channel, state)
                    }
                }
            }
            if (tab == OverlayTab.Info && upcoming != null) UpNext(upcoming)
        }
    }
}

@Composable
private fun OverlayTabs(
    tabs: List<OverlayTab>,
    selected: OverlayTab,
    onSelect: (OverlayTab) -> Unit,
    tv: TvPlayerFocus?,
    returnStop: TransportStop?,
) {
    Row(Modifier.fillMaxWidth()) {
        tabs.forEach { tab ->
            key(tab) {
                val active = tab == selected
                val interaction = remember(tab) { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                val highlight = tv != null && focused
                val click = if (tv == null) {
                    Modifier.clickable(role = Role.Tab) { onSelect(tab) }
                } else {
                    Modifier
                        .then(tv.tabModifier(tab, tabs, returnStop))
                        .tvFocusBorder(highlight)
                        .background(if (highlight) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Tab,
                        ) { onSelect(tab) }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics { this.selected = active }
                        .then(click)
                        .padding(top = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        tab.name,
                        color = if (active) Amber else Muted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (active) Amber else Color(0xFF3A4550)),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackStretchPanel(
    guideNumber: String?,
    stretch: ChannelStretch,
    onStretch: (ChannelStretch) -> Unit,
) {
    val key = channelStretchKey(guideNumber)
    if (key == null) {
        Text(
            "This picture has no channel number, so a stretch can't be saved for it.",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Apply stretch", color = Paper, style = MaterialTheme.typography.titleMedium)
            Text("Channel $key", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = stretch.enabled,
            onCheckedChange = { enabled -> onStretch(stretch.copy(enabled = enabled)) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Amber,
                uncheckedThumbColor = Paper,
                uncheckedTrackColor = Color(0xFF3A4550),
                uncheckedBorderColor = Color(0xFF3A4550),
            ),
        )
    }
    Text(
        if (stretch.enabled) {
            "The picture is stretched for this channel. Live TV and recordings share these sliders."
        } else {
            "Off for now. The sliders stay saved and apply again when you turn this on."
        },
        color = Muted,
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "100% is the broadcast size. Each slider runs from 50% to 200%.",
        color = Muted,
        style = MaterialTheme.typography.bodySmall,
    )
    StretchSlider(
        label = "Width",
        percent = stretch.widthPercent,
        onChange = { percent -> onStretch(stretch.copy(widthPercent = percent)) },
    )
    StretchSlider(
        label = "Height",
        percent = stretch.heightPercent,
        onChange = { percent -> onStretch(stretch.copy(heightPercent = percent)) },
    )
}

@Composable
private fun StretchSlider(label: String, percent: Int, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelLarge)
            Text(
                formatStretchPercent(percent),
                color = Paper,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onChange(stretchPercent(it)) },
            valueRange = STRETCH_PERCENT_MIN.toFloat()..STRETCH_PERCENT_MAX.toFloat(),
            steps = STRETCH_PERCENT_MAX - STRETCH_PERCENT_MIN - 1,
            colors = SliderDefaults.colors(
                thumbColor = Amber,
                activeTrackColor = Amber,
                inactiveTrackColor = Color(0xFF3A4550),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RecordingInfo(recording: Recording, program: GuideProgram?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(recording.title, color = Paper, style = MaterialTheme.typography.titleLarge)
        recording.episodeNumber?.trim()?.ifBlank { null }?.let { episode ->
            Text(
                episode,
                color = Amber,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        recording.episodeTitle?.trim()?.ifBlank { null }?.let { subtitle ->
            Text(subtitle, color = Paper, style = MaterialTheme.typography.bodyLarge)
        }
        if (program != null) {
            Text(
                formatAirTime(program.startEpochMs, program.stopEpochMs),
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    recording.synopsis?.trim()?.ifBlank { null }?.let { synopsis ->
        Text(synopsis, color = Paper, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProgramInfo(
    program: GuideProgram?,
    guideLoading: Boolean,
    guideError: String?,
    recordStatus: AiringRecordStatus?,
    recordNotice: String?,
    onRecord: (() -> Unit)?,
    recordBusy: Boolean,
) {
    if (program == null) {
        val message = when {
            guideLoading -> "Loading the guide…"
            !guideError.isNullOrBlank() -> guideError
            else -> "No program listed right now."
        }
        Text(
            message,
            color = if (guideError.isNullOrBlank() || guideLoading) Muted else Amber,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            program.title,
            color = Paper,
            style = MaterialTheme.typography.titleLarge,
        )
        program.episode?.trim()?.ifBlank { null }?.let { episode ->
            Text(
                episode,
                color = Amber,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        program.subtitle?.trim()?.ifBlank { null }?.let { subtitle ->
            Text(
                subtitle,
                color = Paper,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            formatAirTime(program.startEpochMs, program.stopEpochMs),
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (onRecord != null && recordStatus != null) {
        RecordProgramButton(
            status = recordStatus,
            notice = recordNotice,
            enabled = !recordBusy,
            onClick = onRecord,
        )
    }
    program.description?.trim()?.ifBlank { null }?.let { description ->
        Text(
            description,
            color = Paper,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun UpNext(program: GuideProgram) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HorizontalDivider(color = Color(0xFF3A4550))
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Up next", color = Muted, style = MaterialTheme.typography.labelMedium)
            Text(
                formatClockTime(program.startEpochMs),
                color = Muted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(
            program.title,
            color = Paper,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        formatProgramDetail(program.subtitle, program.episode)?.let { detail ->
            Text(
                detail,
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SignalInfo(channel: Channel, state: UiState) {
    val signal = state.signal
    rfChannelText(signal, channel.frequencyHz)?.let { text ->
        Fact("RF channel", text)
    }
    Meter(
        label = "Strength",
        percent = signal?.strengthPercent,
        detail = signal?.strengthDetail,
        peak = state.peaks.strength,
        kind = MeterKind.Strength,
    )
    Meter(
        label = "Quality",
        percent = signal?.qualityPercent,
        detail = signal?.qualityDetail,
        peak = state.peaks.quality,
        kind = MeterKind.Quality,
    )
    Meter(
        label = "Symbol",
        percent = signal?.symbolPercent,
        detail = null,
        peak = state.peaks.symbol,
        kind = MeterKind.Symbol,
    )
    state.signalNote?.let { Text(it, color = Amber, style = MaterialTheme.typography.bodySmall) }
    StreamFacts(channel, state.video, signal, state.transcoding)
}

@Composable
private fun Meter(
    label: String,
    percent: Int?,
    detail: String?,
    peak: Int?,
    kind: MeterKind,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelLarge)
            Text(
                readingText(percent, detail),
                color = Paper,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        MeterBar(percent, peak, kind)
    }
}

@Composable
private fun MeterBar(percent: Int?, peak: Int?, kind: MeterKind) {
    val fill = meterColor(percent, kind)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFF3A4550)),
    ) {
        val fraction = (percent ?: 0).coerceIn(0, 100) / 100f
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(fill),
            )
        }
        val mark = peak?.takeIf { it in 1..100 }
        if (mark != null) {
            val raw = maxWidth * (mark / 100f) - 1.dp
            val x = if (raw.value < 0f) 0.dp else raw
            Box(
                Modifier
                    .offset(x = x)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun StreamFacts(
    channel: Channel,
    video: VideoStats,
    signal: SignalReading?,
    transcoding: Boolean,
) {
    val codec = videoCodecLabel(video.videoMime, channel.videoCodec)
    val picture = buildList {
        if (video.width != null && video.height != null) {
            add(formatPictureSize(video.width, video.height, video.interlaced))
        }
        codec?.let { add(it) }
        formatFps(video.frameRate)?.let { add("$it fps") }
    }
    val audioRows = signalAudioRows(video.audioTracks, channel.audioCodec)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Fact("Video", picture.joinToString(" · ").ifBlank { "Waiting for the stream" })
        Fact("Decode", formatDecoder(video.hardwareDecoded))
        audioRows.forEach { (label, value) -> Fact(label, value) }
        audioOutputNoteFor(video.audioTracks, channel.audioCodec, video.audioPassthrough)?.let { note ->
            Text(note, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Fact("Live bitrate", formatBitrate(video.bitrateBps))
        Fact("RF rate", formatBitrate(signal?.channelRateBps))
        if (transcoding) {
            Text(
                "Compatible transcode. Codec and bitrate are not the original broadcast.",
                color = Amber,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            color = Paper,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun NoticeBlock(
    state: UiState,
    onTranscode: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.playbackError?.let { message ->
            Text(message, color = Bad, style = MaterialTheme.typography.bodyMedium)
            if (state.offerTranscode) {
                Button(onClick = onTranscode) { Text("Play compatible stream") }
                Text(
                    "This asks an HDHomeRun EXTEND to transcode. Other models cannot.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.tunerReleased) {
            Text(
                if (state.screen is Screen.PlayRecording) {
                    "Playback stopped."
                } else {
                    "The tuner was released so another device can use it."
                },
                color = Paper,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onResume) { Text("Resume") }
        }
    }
}

@Composable
private fun Transport(
    state: UiState,
    program: GuideProgram?,
    tv: TvPlayerFocus?,
    onStep: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onJumpStart: () -> Unit,
    onJumpLive: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip: (Long) -> Unit,
) {
    val clock = state.playbackClock
    val file = state.screen is Screen.PlayRecording
    val enabledStops = enabledTransportStopsFor(state)
    val controls = enabledStops.isNotEmpty()
    val entryTab = overlayTabsFor(file).first()
    val endLabel = when {
        file && clock.timeshift -> formatClockTime(clock.liveMs)
        file -> ""
        else -> program?.stopEpochMs?.let { formatClockTime(it) } ?: "Live"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (controls) {
            PlaybackBar(clock, program, onSeek, endLabel = endLabel, markLive = !file)
            if (tv != null) {
                LaunchedEffect(Unit) { tv.pause.requestFocus() }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportStopButton(
                    stop = TransportStop.Start,
                    label = "Start",
                    enabledStops = enabledStops,
                    entryTab = entryTab,
                    tv = tv,
                    onClick = onJumpStart,
                )
                TransportStopButton(
                    stop = TransportStop.Rewind,
                    label = formatSkipDelta(-SKIP_STEP_MS),
                    enabledStops = enabledStops,
                    entryTab = entryTab,
                    tv = tv,
                    onClick = { onSkip(-SKIP_STEP_MS) },
                )
                TransportStopButton(
                    stop = TransportStop.Pause,
                    label = if (clock.playing) "Pause" else "Play",
                    enabledStops = enabledStops,
                    entryTab = entryTab,
                    tv = tv,
                    onClick = onTogglePlay,
                )
                TransportStopButton(
                    stop = TransportStop.Forward,
                    label = formatSkipDelta(SKIP_STEP_MS),
                    enabledStops = enabledStops,
                    entryTab = entryTab,
                    tv = tv,
                    onClick = { onSkip(SKIP_STEP_MS) },
                )
                val behind = clock.liveMs - clock.positionMs
                TransportStopButton(
                    stop = TransportStop.Live,
                    label = "Live",
                    enabledStops = enabledStops,
                    entryTab = entryTab,
                    tv = tv,
                    color = if (behind < 3_000L) Good else Amber,
                    onClick = onJumpLive,
                )
            }
        }
        if (!file) Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelStepButton("CH −", television = tv != null) { onStep(-1) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    state.playbackError != null || state.tunerReleased -> Unit
                    state.phase == PlaybackPhase.Tuning || state.phase == PlaybackPhase.Buffering -> {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp).width(18.dp),
                            strokeWidth = 2.dp,
                            color = Amber,
                        )
                        Text(
                            if (state.phase == PlaybackPhase.Tuning) "Tuning" else "Buffering",
                            color = Paper,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    state.signal?.locked == false -> Text("No lock", color = Bad)
                }
                state.signal?.tunerIndex?.let { index ->
                    Text("Tuner $index", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
            ChannelStepButton("CH +", television = tv != null) { onStep(1) }
        }
        if (file && state.playbackError == null && !state.tunerReleased &&
            (state.phase == PlaybackPhase.Tuning || state.phase == PlaybackPhase.Buffering)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    strokeWidth = 2.dp,
                    color = Amber,
                )
                Text(
                    "Buffering",
                    color = Paper,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaybackBar(
    clock: PlaybackClock,
    program: GuideProgram?,
    onSeek: (Long) -> Unit,
    endLabel: String,
    markLive: Boolean,
) {
    val bar = playbackBar(
        bufferStartMs = clock.bufferStartMs,
        positionMs = clock.positionMs,
        liveMs = clock.liveMs,
        programStartMs = program?.startEpochMs,
        programStopMs = program?.stopEpochMs,
        seekable = clock.timeshift,
    )
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val seek = rememberUpdatedState(onSeek)
    val latestBar = rememberUpdatedState(bar)
    val shown = dragFraction?.let { bar.wallAt(it) } ?: bar.positionMs
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatClockTime(shown),
                color = Paper,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                endLabel,
                color = Muted,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(bar.seekable) {
                    if (!bar.seekable) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val width = size.width
                        if (width <= 0) return@awaitEachGesture
                        var fraction = (down.position.x / width).coerceIn(0f, 1f)
                        dragFraction = fraction
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            fraction = (change.position.x / width).coerceIn(0f, 1f)
                            dragFraction = fraction
                            change.consume()
                            if (!change.pressed) {
                                seek.value(latestBar.value.wallAt(fraction))
                                dragFraction = null
                                break
                            }
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF3A4550)),
            )
            Box(
                Modifier
                    .fillMaxWidth(bar.fraction(shown))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Amber),
            )
            val liveGap = kotlin.math.abs(bar.liveMs - shown)
            if (markLive && liveGap > 3_000L) {
                Box(
                    Modifier.fillMaxWidth(bar.fraction(bar.liveMs)),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(Modifier.width(2.dp).height(10.dp).background(Good))
                }
            }
            Box(
                Modifier.fillMaxWidth(bar.fraction(shown).coerceAtLeast(0.001f)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .width(12.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Amber),
                )
            }
        }
    }
}

@Composable
private fun TransportStopButton(
    stop: TransportStop,
    label: String,
    enabledStops: Set<TransportStop>,
    entryTab: OverlayTab,
    tv: TvPlayerFocus?,
    color: Color = Paper,
    onClick: () -> Unit,
) {
    val enabled = stop in enabledStops
    PlaybackTextButton(
        label = label,
        enabled = enabled,
        onClick = onClick,
        color = color,
        television = tv != null,
        modifier = if (tv != null && enabled) {
            tv.transportModifier(stop, enabledStops, entryTab)
        } else {
            Modifier
        },
    )
}

@Composable
private fun PlaybackTextButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    color: Color = Paper,
    modifier: Modifier = Modifier,
    television: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val highlight = television && focused
    val shape = RoundedCornerShape(8.dp)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.tvFocusBorder(highlight),
        interactionSource = interaction,
        shape = shape,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (highlight) Color.White.copy(alpha = 0.22f) else Color(0x8012181D),
            contentColor = color,
            disabledContentColor = Muted,
        ),
    ) {
        Text(label, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ChannelStepButton(
    label: String,
    television: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val highlight = television && focused
    val shape = RoundedCornerShape(8.dp)
    TextButton(
        onClick = onClick,
        modifier = Modifier.tvFocusBorder(highlight),
        interactionSource = if (television) interaction else null,
        shape = shape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (highlight) Color.White.copy(alpha = 0.22f) else Color(0x8012181D),
        ),
    ) {
        Text(label)
    }
}

/** White outline so the direction pad's landing spot is visible from across the room. */
private fun Modifier.tvFocusBorder(highlight: Boolean): Modifier {
    if (!highlight) return this
    return border(3.dp, Color.White, RoundedCornerShape(8.dp))
}

@Composable
private fun Modifier.holdClicks(focusable: Boolean = true): Modifier {
    val source = remember { MutableInteractionSource() }
    val click = clickable(interactionSource = source, indication = null, onClick = {})
    return if (focusable) this.then(click) else this.focusProperties { canFocus = false }.then(click)
}

@Composable
private fun Modifier.noRippleClick(onClick: () -> Unit, focusable: Boolean = true): Modifier {
    val source = remember { MutableInteractionSource() }
    val click = clickable(interactionSource = source, indication = null, onClick = onClick)
    return if (focusable) this.then(click) else this.focusProperties { canFocus = false }.then(click)
}

private fun readingText(percent: Int?, detail: String?): String {
    val value = percent?.let { "$it%" } ?: "—"
    return if (detail.isNullOrBlank()) value else "$value  $detail"
}
