package net.etekweb.hdhrmeter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.etekweb.hdhrmeter.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel as PageChannel
import kotlinx.coroutines.flow.first
import net.etekweb.hdhrmeter.data.Channel
import net.etekweb.hdhrmeter.data.AiringRecordStatus
import net.etekweb.hdhrmeter.data.GuideProgram
import net.etekweb.hdhrmeter.data.airingRecordStatus
import net.etekweb.hdhrmeter.data.HdhrDevice
import net.etekweb.hdhrmeter.data.TunerStation
import net.etekweb.hdhrmeter.data.channelsInViewOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarTooltip(
    label: String,
    content: @Composable () -> Unit,
) {
    val state = rememberTooltipState()
    val haptic = LocalHapticFeedback.current
    val visible = state.isVisible
    LaunchedEffect(visible) {
        if (visible) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Below,
        ),
        tooltip = { PlainTooltip { Text(label) } },
        state = state,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelSearchTopBar(
    query: String,
    grabFocus: Boolean,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    onFocusRequested: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val textStyle = MaterialTheme.typography.titleLarge.copy(color = Paper)
    LaunchedEffect(Unit) {
        if (grabFocus) {
            focusRequester.requestFocus()
            keyboard?.show()
            onFocusRequested()
        }
    }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose, modifier = Modifier.tvFocusBorder()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
            }
        },
        title = {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = if (query.isEmpty()) 16.dp else 0.dp)
                    .heightIn(min = 48.dp)
                    .tvFocusBorder()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics {
                        if (query.isEmpty()) {
                            contentDescription = "Search channels or programs"
                        }
                    },
                textStyle = textStyle,
                singleLine = true,
                cursorBrush = SolidColor(Amber),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                decorationBox = { inner ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    "Search channels or programs",
                                    modifier = Modifier.semantics { hideFromAccessibility() },
                                    style = textStyle.copy(color = Muted),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                        Box(
                            Modifier
                                .padding(top = 4.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(if (focused) Amber else MaterialTheme.colorScheme.outline),
                        )
                    }
                },
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(
                    modifier = Modifier.tvFocusBorder(),
                    onClick = {
                        onQuery("")
                        focusRequester.requestFocus()
                        keyboard?.show()
                    },
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Ink,
            titleContentColor = Paper,
            navigationIconContentColor = Paper,
            actionIconContentColor = Amber,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    state: UiState,
    device: HdhrDevice,
    onPageHandlerChanged: (((Int) -> Unit)?) -> Unit,
    onRefresh: () -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (ChannelFilter) -> Unit,
    onWatch: (Channel, Long?) -> Unit,
    onLineupReturnHandled: () -> Unit,
    onOpenTuners: () -> Unit,
    onCloseTuners: () -> Unit,
    onRefreshTuners: () -> Unit,
    onClearTuner: (Int) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onDismissFavoriteError: () -> Unit,
    onEnsureSchedule: () -> Unit,
    onRecord: (String, String, GuideProgram) -> Unit,
) {
    LaunchedEffect(Unit) { onEnsureSchedule() }
    val query = state.query.trim()
    val guide = state.guide
    val nowMs = state.guideNowMs
    val lineupReturn = state.lineupReturn
    val scrollToChannel = lineupReturn?.scrollToChannel == true
    val matched = state.channels.filter { channel ->
        val programTitle = guide?.airing(channel.guideNumber, channel.guideName, nowMs)?.title
        val upcomingTitle = guide?.upcoming(channel.guideNumber, channel.guideName, nowMs)?.title
        val matchesQuery = channelMatchesQuery(channel, query, programTitle, upcomingTitle)
        matchesQuery && state.filter.includes(channel)
    }
    val shown = if (state.filter == ChannelFilter.Recents) {
        // Keep the previous ordering when restoring a physical scroll offset.
        // A surf return uses the updated Recents so the new channel is in the list.
        var recentChannels by rememberSaveable { mutableStateOf(ArrayList(state.recentChannels)) }
        if (scrollToChannel) {
            SideEffect { recentChannels = ArrayList(state.recentChannels) }
        }
        channelsInViewOrder(matched, if (scrollToChannel) state.recentChannels else recentChannels)
    } else {
        matched
    }
    var scheduleChannel by remember { mutableStateOf<Channel?>(null) }
    var scheduleProgram by remember { mutableStateOf<GuideProgram?>(null) }
    val listState = key(state.filter) { rememberLazyListState() }
    val television = isTelevision()
    val lineupFocus = if (television) remember { LineupFocus() } else null
    if (lineupFocus != null) {
        val pages = remember(listState) { PageChannel<Int>(PageChannel.UNLIMITED) }
        DisposableEffect(pages) {
            onPageHandlerChanged { direction -> pages.trySend(direction) }
            onDispose {
                onPageHandlerChanged(null)
                pages.close()
            }
        }
        LaunchedEffect(pages, lineupFocus) {
            for (direction in pages) lineupFocus.page(listState, direction)
        }
    }
    val returnChannelKey = lineupReturn?.channel?.let(::lineupChannelKey)
    val returnIndex = if ((television || scrollToChannel) && returnChannelKey != null) {
        shown.indexOfFirst { lineupChannelKey(it) == returnChannelKey }
    } else {
        -1
    }
    val waitingForChannels = state.channelsLoading && shown.isEmpty()
    LaunchedEffect(lineupReturn, waitingForChannels, lineupFocus, returnIndex, listState) {
        if (lineupReturn == null || waitingForChannels) return@LaunchedEffect
        if (returnIndex >= 0) {
            if (scrollToChannel) {
                listState.scrollToItem(returnIndex)
            } else {
                // Let the restored scroll position lay out before deciding whether to scroll.
                val layout = snapshotFlow { listState.layoutInfo }.first { it.totalItemsCount > returnIndex }
                if (layout.visibleItemsInfo.none { it.key == returnChannelKey }) listState.scrollToItem(returnIndex)
            }
            lineupFocus?.requestWatchFocus(returnChannelKey, keepScrollPosition = scrollToChannel)
        }
        if (!television) delay(150)
        onLineupReturnHandled()
    }
    var searchOpen by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    var grabFocus by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch = {
        keyboard?.hide()
        grabFocus = false
        searchOpen = false
        if (state.query.isNotEmpty()) onQuery("")
    }
    // An open search consumes back so the query is cleared before leaving the lineup.
    BackHandler(enabled = searchOpen, onBack = closeSearch)
    Scaffold(
        containerColor = Ink,
        topBar = {
            if (searchOpen) {
                ChannelSearchTopBar(
                    query = state.query,
                    grabFocus = grabFocus,
                    onQuery = onQuery,
                    onClose = closeSearch,
                    onFocusRequested = { grabFocus = false },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(device.friendlyName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                device.host,
                                style = MaterialTheme.typography.labelMedium,
                                color = Muted,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    },
                    actions = {
                        BarTooltip("Search") {
                            IconButton(
                                modifier = Modifier.tvFocusBorder(),
                                onClick = {
                                    grabFocus = true
                                    searchOpen = true
                                },
                            ) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                        }
                        BarTooltip("Tuners") {
                            IconButton(onClick = onOpenTuners, modifier = Modifier.tvFocusBorder()) {
                                Icon(
                                    painterResource(R.drawable.ic_tuners),
                                    contentDescription = "Tuners",
                                )
                            }
                        }
                        BarTooltip("Refresh channels") {
                            IconButton(
                                onClick = onRefresh,
                                enabled = !state.channelsLoading,
                                modifier = Modifier.tvFocusBorder(),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh channels")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Ink,
                        titleContentColor = Paper,
                        navigationIconContentColor = Paper,
                        actionIconContentColor = Amber,
                    ),
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            if (state.channelsLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Amber,
                    trackColor = InkRaised,
                )
            }
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChoice("All", state.filter == ChannelFilter.All) { onFilter(ChannelFilter.All) }
                FilterChoice("HD", state.filter == ChannelFilter.Hd) { onFilter(ChannelFilter.Hd) }
                FilterChoice("Favorites", state.filter == ChannelFilter.Favorites) {
                    onFilter(ChannelFilter.Favorites)
                }
                FilterChoice("Recents", state.filter == ChannelFilter.Recents) {
                    onFilter(ChannelFilter.Recents)
                }
            }
            Text(
                if (state.channels.isEmpty()) " " else "${shown.size} of ${state.channels.size}",
                color = Muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            GuideStatus(state)
            state.channelsError?.let { message ->
                Text(
                    message,
                    color = Bad,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                shown.isEmpty() && !state.channelsLoading && state.channelsError == null -> {
                    val emptyLabel = if (state.filter == ChannelFilter.Recents && query.isBlank()) {
                        "No recently viewed channels."
                    } else {
                        "No channels match."
                    }
                    Text(
                        emptyLabel,
                        color = Muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(shown, key = ::lineupChannelKey) { channel ->
                            val channelKey = lineupChannelKey(channel)
                            val program = guide?.airing(channel.guideNumber, channel.guideName, nowMs)
                            val watchModifier = lineupFocus?.watchModifier(channelKey) ?: Modifier
                            ChannelRow(
                                channel,
                                program = program,
                                upcoming = guide?.upcoming(channel.guideNumber, channel.guideName, nowMs),
                                nowMs = nowMs,
                                returnHighlighted = !television && channelKey == returnChannelKey &&
                                    lineupReturn.programStartMs == program?.startEpochMs,
                                watchModifier = watchModifier,
                                onWatch = { onWatch(channel, program?.startEpochMs) },
                                onOpenGuide = {
                                    scheduleProgram = null
                                    scheduleChannel = channel
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    if (state.tuners.open) {
        TunersDialog(
            state.tuners,
            onClose = onCloseTuners,
            onRefresh = onRefreshTuners,
            onClear = onClearTuner,
        )
    }
    scheduleChannel?.let { selected ->
        val channel = state.channels.firstOrNull {
            it.guideNumber == selected.guideNumber && it.streamUrl == selected.streamUrl
        } ?: selected
        ChannelGuideDialog(
            channel = channel,
            programs = guide?.schedule(channel.guideNumber, channel.guideName, nowMs).orEmpty(),
            nowMs = nowMs,
            guideLoading = state.guideLoading,
            guideMissing = guide == null && state.guideError.isNullOrBlank() && !state.guideLoading,
            guideError = if (guide == null) state.guideError else null,
            favoriteSaving = channel.guideNumber in state.favoritePending,
            favoriteError = state.favoriteError?.takeIf { state.favoriteErrorGuide == channel.guideNumber },
            onToggleFavorite = { onToggleFavorite(channel) },
            onClose = {
                scheduleChannel = null
                scheduleProgram = null
                onDismissFavoriteError()
            },
            onOpenProgram = { scheduleProgram = it },
        )
    }
    scheduleProgram?.let { program ->
        val channel = scheduleChannel
        if (channel != null) {
            ProgramDetailDialog(
                program = program,
                nowMs = nowMs,
                recordStatus = airingRecordStatus(
                    state.schedule.rules,
                    state.dvr.recordings,
                    channel.guideNumber,
                    program,
                    nowMs,
                ),
                recordNotice = state.schedule.noticeFor(channel.guideNumber, program.startEpochMs),
                recordBusy = state.schedule.saving,
                onRecord = { onRecord(channel.guideNumber, channel.guideName, program) },
                onClose = { scheduleProgram = null },
            )
        }
    }
}

internal fun channelMatchesQuery(
    channel: Channel,
    query: String,
    programTitle: String?,
    upcomingTitle: String? = null,
): Boolean {
    val needle = query.trim()
    if (needle.isBlank()) return true
    return channel.guideNumber.contains(needle, ignoreCase = true) ||
        channel.guideName.contains(needle, ignoreCase = true) ||
        programTitle?.contains(needle, ignoreCase = true) == true ||
        upcomingTitle?.contains(needle, ignoreCase = true) == true
}

@Composable
private fun TunersDialog(
    panel: TunerPanel,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onClear: (Int) -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(
            color = InkRaised,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Tuners",
                        style = MaterialTheme.typography.titleLarge,
                        color = Paper,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onRefresh,
                        enabled = !panel.loading && panel.clearing == null,
                        modifier = Modifier.tvFocusBorder(),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh tuners", tint = Amber)
                    }
                }
                if (panel.rows.any { it.inUse }) {
                    Text(
                        "Force clear stops whoever is using that tuner.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                }
                if (panel.loading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        color = Amber,
                        trackColor = Ink,
                    )
                }
                if (panel.loading && panel.rows.isEmpty()) {
                    Text(
                        "Reading tuners…",
                        color = Muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                panel.rows.forEachIndexed { position, row ->
                    if (position > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    TunerStatusRow(row, clearing = panel.clearing, onClear = { onClear(row.index) })
                }
                if (!panel.loading && panel.rows.isEmpty() && panel.error == null) {
                    Text("This device did not report any tuners.", color = Muted)
                }
                panel.error?.let { message ->
                    Text(
                        message,
                        color = Bad,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).tvFocusBorder()) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun TunerStatusRow(
    row: TunerStation,
    clearing: Int?,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Tuner ${row.index}",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                row.label,
                color = if (row.inUse) Paper else Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.target?.let { target ->
                Text(
                    target,
                    color = Muted,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (row.inUse) {
            TextButton(onClick = onClear, enabled = clearing == null, modifier = Modifier.tvFocusBorder()) {
                if (clearing == row.index) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Amber,
                    )
                } else {
                    Text("Force clear", color = if (clearing == null) Bad else Muted)
                }
            }
        }
    }
}

@Composable
private fun GuideStatus(state: UiState) {
    val guide = state.guide
    val message = when {
        state.guideLoading && guide == null -> "Loading the guide…"
        state.guideError != null -> state.guideError
        guide != null && state.channels.isNotEmpty() && state.channels.none { channel ->
            val nowMs = state.guideNowMs
            guide.airing(channel.guideNumber, channel.guideName, nowMs) != null ||
                guide.upcoming(channel.guideNumber, channel.guideName, nowMs) != null
        } -> "The guide has no program for these channels."
        else -> null
    } ?: return
    val color = if (state.guideError != null && message == state.guideError) Bad else Muted
    Text(
        message,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun FilterChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.tvFocusBorder(),
    )
}

private fun lineupChannelKey(channel: Channel): String = "${channel.guideNumber}|${channel.streamUrl}"

private class LineupFocus {
    private val watchControls = mutableMapOf<Any, FocusRequester>()
    private var keepScrollPosition = false

    @Composable
    fun watchModifier(channelKey: String): Modifier {
        val requester = remember { FocusRequester() }
        DisposableEffect(this, channelKey, requester) {
            watchControls[channelKey] = requester
            onDispose { watchControls.remove(channelKey, requester) }
        }
        return Modifier.focusRequester(requester).then(WatchScrollElement(this))
    }

    suspend fun page(listState: LazyListState, direction: Int) {
        val viewportHeight = listState.layoutInfo.viewportSize.height
        if (viewportHeight == 0) return
        val previousTop = listState.firstVisibleItemIndex
        listState.scrollBy(direction * viewportHeight.toFloat())

        val nextTop = listState.firstVisibleItemIndex
        if (nextTop != previousTop && listState.firstVisibleItemScrollOffset != 0 && listState.canScrollForward) {
            // Show the whole program at the page boundary without skipping a partially visible row.
            listState.scrollToItem(nextTop)
        }
        val top = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }
        requestWatchFocus(top?.key, keepScrollPosition = true)
    }

    fun requestWatchFocus(channelKey: Any?, keepScrollPosition: Boolean) {
        val requester = watchControls[channelKey] ?: return
        this.keepScrollPosition = keepScrollPosition
        try {
            requester.requestFocus()
        } finally {
            this.keepScrollPosition = false
        }
    }

    private data class WatchScrollElement(val focus: LineupFocus) : ModifierNodeElement<WatchScrollNode>() {
        override fun create() = WatchScrollNode(focus)

        override fun update(node: WatchScrollNode) {
            node.focus = focus
        }
    }

    private class WatchScrollNode(var focus: LineupFocus) : Modifier.Node(), BringIntoViewModifierNode {
        override suspend fun bringIntoView(
            childCoordinates: LayoutCoordinates,
            boundsProvider: () -> Rect?,
        ) {
            // Paging already positioned the list. Skip the TV focus pivot for this request.
            if (!focus.keepScrollPosition) bringIntoView()
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    program: GuideProgram?,
    upcoming: GuideProgram?,
    nowMs: Long,
    returnHighlighted: Boolean,
    watchModifier: Modifier,
    onWatch: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val television = isTelevision()
    val watchStartPadding = if (television) 8.dp else 0.dp
    val cardColor by animateColorAsState(
        targetValue = if (returnHighlighted) {
            Color.White.copy(alpha = 0.16f).compositeOver(InkRaised)
        } else {
            InkRaised
        },
        animationSpec = tween(durationMillis = 350, easing = LinearEasing),
        label = "Return highlight",
    )
    Surface(
        color = cardColor,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (channel.drm) 0.55f else 1f),
    ) {
        Box {
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .tvFocusBorder(MaterialTheme.shapes.medium)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Open channel guide",
                            onClick = onOpenGuide,
                        )
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
                        .width(88.dp),
                ) {
                    Text(
                        channel.guideNumber,
                        fontFamily = FontFamily.Monospace,
                        color = Amber,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        channel.guideName,
                        color = Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ChannelMarks(channel)
                }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 10.dp, vertical = 12.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )
                Box(
                    watchModifier
                        .weight(1f)
                        .fillMaxHeight()
                        .tvFocusBorder(MaterialTheme.shapes.medium)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Watch",
                            onClick = onWatch,
                        ),
                ) {
                    Column(Modifier.padding(start = watchStartPadding, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                        if (program != null) {
                            Text(
                                program.title,
                                color = Paper,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                formatAirTime(program.startEpochMs, program.stopEpochMs),
                                color = Muted,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (upcoming != null) UpcomingLine(upcoming)
                    }
                    if (program != null && program.stopEpochMs > program.startEpochMs) {
                        val progress = ((nowMs - program.startEpochMs).toDouble() /
                            (program.stopEpochMs - program.startEpochMs)).toFloat().coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(
                                    start = watchStartPadding,
                                    end = 12.dp,
                                    top = if (television) 4.dp else 0.dp,
                                )
                                .fillMaxWidth()
                                .height(2.dp)
                                .semantics { contentDescription = "Current program progress" },
                            color = Amber,
                            trackColor = MaterialTheme.colorScheme.outline,
                            strokeCap = StrokeCap.Butt,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelGuideDialog(
    channel: Channel,
    programs: List<GuideProgram>,
    nowMs: Long,
    guideLoading: Boolean,
    guideMissing: Boolean,
    guideError: String?,
    favoriteSaving: Boolean,
    favoriteError: String?,
    onToggleFavorite: () -> Unit,
    onClose: () -> Unit,
    onOpenProgram: (GuideProgram) -> Unit,
) {
    val entries = channelGuideEntries(programs, nowMs)
    val firstProgramIndex = entries.indexOfFirst { it is ChannelGuideEntry.Airing }
    val television = isTelevision()
    val dialogHeight = (LocalConfiguration.current.screenHeightDp - 48).dp
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
        ),
    ) {
        BackHandler(onBack = onClose)
        val listState = rememberLazyListState()
        val firstProgramFocus = remember { FocusRequester() }
        val favoriteFocus = remember { FocusRequester() }
        if (television) {
            val window = LocalWindowInfo.current
            val initialIndex by rememberUpdatedState(firstProgramIndex)
            LaunchedEffect(window) {
                // The guide can finish loading after the dialog opens.
                snapshotFlow {
                    window.isWindowFocused && listState.layoutInfo.visibleItemsInfo.any { it.index == initialIndex }
                }.first { it }
                firstProgramFocus.requestFocus()
            }
        }
        Surface(
            color = Ink,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .height(dialogHeight),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                channel.guideNumber,
                                color = Amber,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                            )
                            IconButton(
                                onClick = onToggleFavorite,
                                enabled = !favoriteSaving,
                                modifier = Modifier
                                    .alpha(if (favoriteSaving) 0.45f else 1f)
                                    .then(if (television) Modifier.focusRequester(favoriteFocus) else Modifier)
                                    .tvFocusBorder(),
                            ) {
                                if (channel.favorite) {
                                    Icon(
                                        Icons.Filled.Star,
                                        contentDescription = "Remove from favorites",
                                        tint = Amber,
                                    )
                                } else {
                                    Icon(
                                        painterResource(R.drawable.ic_star_border),
                                        contentDescription = "Add to favorites",
                                        tint = Paper,
                                    )
                                }
                            }
                        }
                        Text(
                            channel.guideName,
                            color = Paper,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onClose, modifier = Modifier.tvFocusBorder()) {
                        Icon(Icons.Filled.Close, contentDescription = "Close guide", tint = Paper)
                    }
                }
                if (!favoriteError.isNullOrBlank()) {
                    Text(
                        favoriteError,
                        color = Bad,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                if (guideLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        color = Amber,
                        trackColor = InkRaised,
                    )
                }
                when {
                    entries.isNotEmpty() -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(
                                entries,
                                key = { _, entry ->
                                    when (entry) {
                                        is ChannelGuideEntry.Day -> "day-${entry.dateKey}"
                                        is ChannelGuideEntry.Airing ->
                                            "${entry.program.startEpochMs}|${entry.program.stopEpochMs}|${entry.program.title}"
                                    }
                                },
                            ) { index, entry ->
                                when (entry) {
                                    is ChannelGuideEntry.Day -> GuideDayHeading(entry.label)
                                    is ChannelGuideEntry.Airing -> GuideProgramRow(
                                        entry.program,
                                        onNow = entry.onNow,
                                        onClick = { onOpenProgram(entry.program) },
                                        modifier = if (television && index == firstProgramIndex) {
                                            Modifier.focusRequester(firstProgramFocus).focusProperties {
                                                up = if (favoriteSaving) FocusRequester.Default else favoriteFocus
                                            }
                                        } else {
                                            Modifier
                                        },
                                    )
                                }
                            }
                        }
                    }
                    guideLoading -> GuideMessage("Loading the guide…")
                    !guideError.isNullOrBlank() -> GuideMessage(guideError, color = Bad)
                    guideMissing -> GuideMessage("No guide is loaded.")
                    else -> GuideMessage("No upcoming programs for this channel.")
                }
            }
        }
    }
}

@Composable
private fun GuideDayHeading(label: String) {
    Text(
        label,
        color = Amber,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

@Composable
private fun GuideMessage(text: String, color: Color = Muted) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

@Composable
private fun GuideProgramRow(
    program: GuideProgram,
    onNow: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val episode = formatEpisodeSummary(program.episode, program.subtitle)
    val blurb = program.description?.trim()?.ifBlank { null }
    Surface(
        onClick = onClick,
        color = InkRaised,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().tvFocusBorder(MaterialTheme.shapes.medium),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onNow) {
                    Text(
                        "On now",
                        color = Amber,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    formatAirTime(program.startEpochMs, program.stopEpochMs),
                    color = Muted,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                program.title,
                color = Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (episode != null) {
                Text(
                    episode,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (blurb != null) {
                Text(
                    blurb,
                    color = Paper,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProgramDetailDialog(
    program: GuideProgram,
    nowMs: Long,
    recordStatus: AiringRecordStatus,
    recordNotice: String?,
    recordBusy: Boolean,
    onRecord: () -> Unit,
    onClose: () -> Unit,
) {
    val episode = program.episode?.trim()?.ifBlank { null }
    val subtitle = program.subtitle?.trim()?.ifBlank { null }
    val description = program.description?.trim()?.ifBlank { null }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnBackPress = false),
    ) {
        BackHandler(onBack = onClose)
        Surface(
            color = InkRaised,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        program.title,
                        color = Paper,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 10.dp),
                    )
                    IconButton(onClick = onClose, modifier = Modifier.tvFocusBorder()) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Paper)
                    }
                }
                if (episode != null) {
                    Text(
                        episode,
                        color = Amber,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = Paper,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 2.dp, end = 12.dp),
                    )
                }
                Text(
                    formatGuideWhen(program.startEpochMs, program.stopEpochMs, nowMs),
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp, end = 12.dp),
                )
                RecordProgramButton(
                    status = recordStatus,
                    notice = recordNotice,
                    enabled = !recordBusy,
                    onClick = onRecord,
                    modifier = Modifier.padding(top = 12.dp, end = 12.dp),
                )
                if (description != null) {
                    Text(
                        description,
                        color = Paper,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp, end = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelMarks(channel: Channel) {
    if (!channel.hd && !channel.atsc3 && !channel.favorite && !channel.drm) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (channel.hd) Badge("HD")
        if (channel.atsc3) Badge("ATSC 3.0")
        if (channel.favorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Favorite",
                tint = Amber,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(14.dp),
            )
        }
        if (channel.drm) Badge("Protected")
    }
}

@Composable
private fun UpcomingLine(program: GuideProgram) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Up next",
            color = Muted,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            program.title,
            color = Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .weight(1f),
        )
        Text(
            formatClockTime(program.startEpochMs),
            color = Muted,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        color = Muted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 2.dp),
    )
}
