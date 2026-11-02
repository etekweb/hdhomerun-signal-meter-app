package net.etekweb.hdhrmeter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.etekweb.hdhrmeter.data.Channel
import net.etekweb.hdhrmeter.data.GuideProgram
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
            IconButton(onClick = onClose) {
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
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (ChannelFilter) -> Unit,
    onWatch: (Channel) -> Unit,
    onReturnScrollHandled: () -> Unit,
    onOpenTuners: () -> Unit,
    onCloseTuners: () -> Unit,
    onRefreshTuners: () -> Unit,
    onClearTuner: (Int) -> Unit,
) {
    val query = state.query.trim()
    val guide = state.guide
    val nowMs = state.guideNowMs
    val matched = state.channels.filter { channel ->
        val programTitle = guide?.airing(channel.guideNumber, channel.guideName, nowMs)?.title
        val upcomingTitle = guide?.upcoming(channel.guideNumber, channel.guideName, nowMs)?.title
        val matchesQuery = channelMatchesQuery(channel, query, programTitle, upcomingTitle)
        val matchesFilter = when (state.filter) {
            ChannelFilter.All -> true
            ChannelFilter.Hd -> channel.hd || channel.atsc3
            ChannelFilter.Favorites -> channel.favorite
            ChannelFilter.Recents -> true
        }
        matchesQuery && matchesFilter
    }
    val shown = if (state.filter == ChannelFilter.Recents) {
        channelsInViewOrder(matched, state.recentChannels)
    } else {
        matched
    }
    var scheduleChannel by remember { mutableStateOf<Channel?>(null) }
    var scheduleProgram by remember { mutableStateOf<GuideProgram?>(null) }
    val returnTo = state.returnToChannel
    val returnIndex = if (returnTo == null) {
        -1
    } else {
        shown.indexOfFirst {
            it.guideNumber == returnTo.guideNumber && it.streamUrl == returnTo.streamUrl
        }
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = returnIndex.coerceAtLeast(0),
    )
    var searchOpen by remember { mutableStateOf(state.query.isNotEmpty()) }
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
    LaunchedEffect(state.filter) {
        // Coming back from the player reveals that channel instead of the top.
        if (returnTo != null) return@LaunchedEffect
        listState.scrollToItem(0)
    }
    LaunchedEffect(returnTo, shown) {
        val channel = returnTo ?: return@LaunchedEffect
        if (state.channelsLoading && shown.isEmpty()) return@LaunchedEffect
        val index = shown.indexOfFirst {
            it.guideNumber == channel.guideNumber && it.streamUrl == channel.streamUrl
        }
        if (index >= 0) listState.scrollToItem(index)
        onReturnScrollHandled()
    }
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
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        BarTooltip("Search") {
                            IconButton(
                                onClick = {
                                    grabFocus = true
                                    searchOpen = true
                                },
                            ) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                        }
                        BarTooltip("Tuners") {
                            IconButton(onClick = onOpenTuners) {
                                Icon(
                                    painterResource(R.drawable.ic_tuners),
                                    contentDescription = "Tuners",
                                )
                            }
                        }
                        BarTooltip("Refresh channels") {
                            IconButton(onClick = onRefresh, enabled = !state.channelsLoading) {
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
                        items(shown, key = { "${it.guideNumber}|${it.streamUrl}" }) { channel ->
                            ChannelRow(
                                channel,
                                program = guide?.airing(channel.guideNumber, channel.guideName, nowMs),
                                upcoming = guide?.upcoming(channel.guideNumber, channel.guideName, nowMs),
                                onWatch = { onWatch(channel) },
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
    scheduleChannel?.let { channel ->
        ChannelGuideDialog(
            channel = channel,
            programs = guide?.schedule(channel.guideNumber, channel.guideName, nowMs).orEmpty(),
            nowMs = nowMs,
            guideLoading = state.guideLoading,
            guideMissing = guide == null && state.guideError.isNullOrBlank() && !state.guideLoading,
            guideError = if (guide == null) state.guideError else null,
            onClose = {
                scheduleChannel = null
                scheduleProgram = null
            },
            onOpenProgram = { scheduleProgram = it },
        )
    }
    scheduleProgram?.let { program ->
        ProgramDetailDialog(
            program = program,
            nowMs = nowMs,
            onClose = { scheduleProgram = null },
        )
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
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
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
            TextButton(onClick = onClear, enabled = clearing == null) {
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
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ChannelRow(
    channel: Channel,
    program: GuideProgram?,
    upcoming: GuideProgram?,
    onWatch: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    Surface(
        color = InkRaised,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (channel.drm) 0.55f else 1f),
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
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
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Watch",
                        onClick = onWatch,
                    )
                    .padding(end = 12.dp, top = 12.dp, bottom = 12.dp),
            ) {
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
    onClose: () -> Unit,
    onOpenProgram: (GuideProgram) -> Unit,
) {
    val entries = channelGuideEntries(programs, nowMs)
    val dialogHeight = (LocalConfiguration.current.screenHeightDp - 48).dp
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
        ),
    ) {
        BackHandler(onBack = onClose)
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
                        Text(
                            channel.guideNumber,
                            color = Amber,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                        )
                        Text(
                            channel.guideName,
                            color = Paper,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close guide", tint = Paper)
                    }
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
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                entries,
                                key = { entry ->
                                    when (entry) {
                                        is ChannelGuideEntry.Day -> "day-${entry.dateKey}"
                                        is ChannelGuideEntry.Airing ->
                                            "${entry.program.startEpochMs}|${entry.program.stopEpochMs}|${entry.program.title}"
                                    }
                                },
                            ) { entry ->
                                when (entry) {
                                    is ChannelGuideEntry.Day -> GuideDayHeading(entry.label)
                                    is ChannelGuideEntry.Airing -> GuideProgramRow(
                                        entry.program,
                                        onNow = entry.onNow,
                                        onClick = { onOpenProgram(entry.program) },
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
) {
    val episode = formatEpisodeSummary(program.episode, program.subtitle)
    val blurb = program.description?.trim()?.ifBlank { null }
    Surface(
        onClick = onClick,
        color = InkRaised,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
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
                    IconButton(onClick = onClose) {
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
