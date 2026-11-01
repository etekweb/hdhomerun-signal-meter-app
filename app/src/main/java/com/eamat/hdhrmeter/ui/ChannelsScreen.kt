package net.etekweb.hdhrmeter.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.etekweb.hdhrmeter.data.Channel
import net.etekweb.hdhrmeter.data.GuideProgram
import net.etekweb.hdhrmeter.data.HdhrDevice
import net.etekweb.hdhrmeter.data.channelsInViewOrder

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
) {
    val query = state.query.trim()
    val matched = state.channels.filter { channel ->
        val programTitle = state.guide
            ?.airing(channel.guideNumber, channel.guideName, state.guideNowMs)
            ?.title
        val matchesQuery = channelMatchesQuery(channel, query, programTitle)
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
                    IconButton(onClick = onRefresh, enabled = !state.channelsLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh channels")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Ink,
                    titleContentColor = Paper,
                    navigationIconContentColor = Paper,
                    actionIconContentColor = Amber,
                ),
            )
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
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Search channels or programs") },
                trailingIcon = if (state.query.isEmpty()) {
                    null
                } else {
                    {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
            )
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
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
                                program = state.guide?.airing(channel.guideNumber, channel.guideName, state.guideNowMs),
                                onClick = { onWatch(channel) },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun channelMatchesQuery(channel: Channel, query: String, programTitle: String?): Boolean {
    val needle = query.trim()
    if (needle.isBlank()) return true
    return channel.guideNumber.contains(needle, ignoreCase = true) ||
        channel.guideName.contains(needle, ignoreCase = true) ||
        programTitle?.contains(needle, ignoreCase = true) == true
}

@Composable
private fun GuideStatus(state: UiState) {
    val guide = state.guide
    val message = when {
        state.guideLoading && guide == null -> "Loading the guide…"
        state.guideError != null -> state.guideError
        guide != null && state.channels.isNotEmpty() && state.channels.none { channel ->
            guide.airing(channel.guideNumber, channel.guideName, state.guideNowMs) != null
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
private fun ChannelRow(channel: Channel, program: GuideProgram?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = InkRaised,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (channel.drm) 0.55f else 1f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                channel.guideNumber,
                modifier = Modifier.width(72.dp),
                fontFamily = FontFamily.Monospace,
                color = Amber,
                style = MaterialTheme.typography.titleMedium,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    channel.guideName,
                    color = Paper,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        }
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
