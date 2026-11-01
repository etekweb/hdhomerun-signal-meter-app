package net.etekweb.hdhrmeter.ui

import android.graphics.Rect
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import net.etekweb.hdhrmeter.data.Channel
import net.etekweb.hdhrmeter.data.GuideProgram
import net.etekweb.hdhrmeter.data.SignalReading
import net.etekweb.hdhrmeter.player.VideoStats
import kotlin.math.roundToInt
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
fun PlayerScreen(
    state: UiState,
    onAttach: (VLCVideoLayout) -> Unit,
    onDetach: (VLCVideoLayout) -> Unit,
    onToggleOverlay: () -> Unit,
    onSelectOverlayTab: (OverlayTab) -> Unit,
    onBack: () -> Unit,
    onStep: (Int) -> Unit,
    onTranscode: () -> Unit,
    onResume: () -> Unit,
    showChrome: Boolean,
    onVideoBounds: (Rect) -> Unit,
) {
    val watch = state.screen as Screen.Watch
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { context ->
                val video = VLCVideoLayout(context)
                FrameLayout(context).apply {
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
            modifier = Modifier
                .fillMaxSize()
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
                channel = watch.channel,
                state = state,
                onToggleOverlay = onToggleOverlay,
                onSelectTab = onSelectOverlayTab,
                onBack = onBack,
                onStep = onStep,
                onTranscode = onTranscode,
                onResume = onResume,
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
                    watch.channel.guideNumber,
                    color = Color.White.copy(alpha = 0.86f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .safeDrawingPadding()
                        .padding(16.dp),
                )
            }
        }
        if (showChrome) {
            state.pendingChannel?.let { pending ->
                ChannelHopCard(
                    channel = pending,
                    program = state.guide?.airing(pending.guideNumber, pending.guideName, state.guideNowMs),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun SignalOverlay(
    channel: Channel,
    state: UiState,
    onToggleOverlay: () -> Unit,
    onSelectTab: (OverlayTab) -> Unit,
    onBack: () -> Unit,
    onStep: (Int) -> Unit,
    onTranscode: () -> Unit,
    onResume: () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        val sidePanel = maxWidth > maxHeight && maxWidth > 700.dp
        val program = state.guide?.airing(channel.guideNumber, channel.guideName, state.guideNowMs)
        val upcoming = state.guide?.upcoming(channel.guideNumber, channel.guideName, state.guideNowMs)
        val overlayHeight = maxHeight
        Box(Modifier.fillMaxSize().noRippleClick { onToggleOverlay() })
        if (sidePanel) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    ChannelHeader(channel, onBack)
                    Spacer(Modifier.weight(1f))
                    NoticeBlock(state, onTranscode, onResume, Modifier.padding(horizontal = 12.dp))
                    Transport(state, onStep)
                }
                OverlayPanel(
                    channel = channel,
                    state = state,
                    program = program,
                    upcoming = upcoming,
                    onSelectTab = onSelectTab,
                    modifier = Modifier
                        .width(340.dp)
                        .heightIn(max = overlayHeight)
                        .padding(12.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ChannelHeader(channel, onBack)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Keep the channel buttons and any playback notice below a long description.
                    val panelMax = (maxHeight - 240.dp).coerceAtLeast(140.dp)
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                        OverlayPanel(
                            channel = channel,
                            state = state,
                            program = program,
                            upcoming = upcoming,
                            onSelectTab = onSelectTab,
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
                        Transport(state, onStep)
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
private fun ChannelHeader(channel: Channel, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Paper)
        }
        Column(Modifier.weight(1f)) {
            Text(
                channel.guideNumber,
                color = Amber,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                channel.guideName,
                color = Paper,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OverlayPanel(
    channel: Channel,
    state: UiState,
    program: GuideProgram?,
    upcoming: GuideProgram?,
    onSelectTab: (OverlayTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tab = state.overlayTab
    val showNext = tab == OverlayTab.Info && upcoming != null
    val infoScroll = rememberScrollState()
    val signalScroll = rememberScrollState()
    Surface(
        color = Color(0xE612181D),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .holdClicks(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            OverlayTabs(selected = tab, onSelect = onSelectTab)
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(if (tab == OverlayTab.Info) infoScroll else signalScroll)
                    .padding(horizontal = 14.dp)
                    .padding(top = 12.dp, bottom = if (showNext) 8.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (tab == OverlayTab.Info) {
                    ProgramInfo(program, state.guideLoading, state.guideError)
                } else {
                    SignalInfo(channel, state)
                }
            }
            if (tab == OverlayTab.Info && upcoming != null) UpNext(upcoming)
        }
    }
}

@Composable
private fun OverlayTabs(selected: OverlayTab, onSelect: (OverlayTab) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        OverlayTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics { this.selected = active }
                    .clickable(role = Role.Tab) { onSelect(tab) }
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

@Composable
private fun ProgramInfo(program: GuideProgram?, guideLoading: Boolean, guideError: String?) {
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
        if (video.width != null && video.height != null) add("${video.width}×${video.height}")
        codec?.let { add(it) }
        formatFps(video.frameRate)?.let { add("$it fps") }
    }
    val audio = listOfNotNull(
        audioCodecLabel(video.audioMime, channel.audioCodec),
        audioLayout(video.audioChannels),
    ).joinToString(" ")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Fact("Video", picture.joinToString(" · ").ifBlank { "Waiting for the stream" })
        if (audio.isNotBlank()) Fact("Audio", audio)
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            color = Paper,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
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
                "The tuner was released so another device can use it.",
                color = Paper,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onResume) { Text("Resume") }
        }
    }
}

@Composable
private fun Transport(state: UiState, onStep: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelStepButton("CH −") { onStep(-1) }
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
                state.phase == PlaybackPhase.Ready -> Text("Live", color = Good)
            }
            state.signal?.tunerIndex?.let { index ->
                Text("Tuner $index", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        ChannelStepButton("CH +") { onStep(1) }
    }
}

@Composable
private fun ChannelStepButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = Color(0x8012181D)),
    ) {
        Text(label)
    }
}

@Composable
private fun Modifier.holdClicks(): Modifier {
    val source = remember { MutableInteractionSource() }
    return clickable(interactionSource = source, indication = null, onClick = {})
}

@Composable
private fun Modifier.noRippleClick(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return clickable(interactionSource = source, indication = null, onClick = onClick)
}

private fun readingText(percent: Int?, detail: String?): String {
    val value = percent?.let { "$it%" } ?: "—"
    return if (detail.isNullOrBlank()) value else "$value  $detail"
}
