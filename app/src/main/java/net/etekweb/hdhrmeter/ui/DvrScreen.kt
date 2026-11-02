package net.etekweb.hdhrmeter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.etekweb.hdhrmeter.data.Recording
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DvrScreen(
    state: UiState,
    onEnsure: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    LaunchedEffect(Unit) { onEnsure() }
    val selected = state.dvr.recordings.firstOrNull { it.id == state.dvr.selectedId }
    if (selected != null) {
        RecordingDetail(selected, onClose)
    } else {
        RecordingList(state.dvr, onRefresh, onOpen)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingList(
    dvr: DvrState,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val nowMs = System.currentTimeMillis()
    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Text("DVR") },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !dvr.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh recordings", tint = Amber)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Ink,
                    titleContentColor = Paper,
                    actionIconContentColor = Amber,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (dvr.loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Amber,
                    trackColor = InkRaised,
                )
            }
            when {
                dvr.recordings.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!dvr.warning.isNullOrBlank() || !dvr.error.isNullOrBlank()) {
                            item {
                                Text(
                                    dvr.error ?: dvr.warning.orEmpty(),
                                    color = if (dvr.error != null) Bad else Amber,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        items(dvr.recordings, key = { it.id }) { recording ->
                            RecordingRow(recording, nowMs, onClick = { onOpen(recording.id) })
                        }
                    }
                }
                dvr.loading || !dvr.loaded -> DvrMessage("Looking for recordings…", Modifier.weight(1f))
                !dvr.error.isNullOrBlank() -> DvrMessage(dvr.error, Modifier.weight(1f), Bad)
                dvr.loaded && dvr.enginesFound == 0 -> {
                    DvrMessage(
                        "No HDHomeRun DVR storage answered on the network.",
                        Modifier.weight(1f),
                    )
                }
                dvr.loaded -> DvrMessage("No recordings.", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DvrMessage(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Muted,
) {
    Box(modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecordingRow(recording: Recording, nowMs: Long, onClick: () -> Unit) {
    val episode = formatEpisodeSummary(recording.episodeNumber, recording.episodeTitle)
    val schedule = recordingSchedule(recording, nowMs)
    val channel = recordingChannel(recording)
    Surface(
        onClick = onClick,
        color = InkRaised,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                recording.title,
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
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    listOfNotNull(schedule, channel).joinToString(" · ").ifBlank { "Recorded" },
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                when {
                    recording.stillRecording -> Text(
                        "Recording",
                        color = Amber,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    recording.problem != null -> Text(
                        "Did not finish",
                        color = Bad,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingDetail(recording: Recording, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val nowMs = System.currentTimeMillis()
    val episode = recording.episodeNumber?.trim()?.ifBlank { null }
    val episodeTitle = recording.episodeTitle?.trim()?.ifBlank { null }
    val synopsis = recording.synopsis?.trim()?.ifBlank { null }
    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Text(recording.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Paper)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Ink,
                    titleContentColor = Paper,
                    navigationIconContentColor = Paper,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (episode != null) {
                Text(
                    episode,
                    color = Amber,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (episodeTitle != null) {
                Text(episodeTitle, color = Paper, style = MaterialTheme.typography.titleMedium)
            }
            recordingChannel(recording)?.let { channel ->
                RecordingFact("Channel", channel)
            }
            programWindow(recording, nowMs)?.let { aired ->
                RecordingFact("Aired", aired)
            }
            recordedWindow(recording, nowMs)?.let { recorded ->
                RecordingFact("Recorded", recorded)
            }
            recording.originalAirdateEpochSec?.let { airdate ->
                RecordingFact("First aired", formatOriginalAirDate(airdate))
            }
            recording.sourceName?.let { source ->
                RecordingFact("Stored on", source)
            }
            if (recording.stillRecording) {
                Text("Recording", color = Amber, style = MaterialTheme.typography.bodyMedium)
            }
            recording.problem?.let { problem ->
                Text(problem, color = Bad, style = MaterialTheme.typography.bodyMedium)
            }
            if (synopsis != null) {
                Text(
                    synopsis,
                    color = Paper,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RecordingFact(label: String, value: String) {
    Column {
        Text(label, color = Muted, style = MaterialTheme.typography.labelMedium)
        Text(value, color = Paper, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun recordingChannel(recording: Recording): String? {
    return listOfNotNull(
        recording.channelNumber?.trim()?.ifBlank { null },
        recording.channelName?.trim()?.ifBlank { null },
    ).joinToString(" ").ifBlank { null }
}

private fun recordingSchedule(recording: Recording, nowMs: Long): String? {
    return programWindow(recording, nowMs) ?: recordedWindow(recording, nowMs)
}

private fun programWindow(recording: Recording, nowMs: Long): String? {
    val start = recording.startEpochMs ?: return null
    val end = recording.endEpochMs
    return windowText(start, end, nowMs)
}

private fun recordedWindow(recording: Recording, nowMs: Long): String? {
    val start = recording.recordStartEpochMs ?: return null
    return windowText(start, recording.recordEndEpochMs, nowMs)
}

private fun windowText(startMs: Long, endMs: Long?, nowMs: Long): String {
    val day = formatGuideDay(startMs, nowMs)
    val clock = if (endMs != null && endMs > startMs) {
        formatAirTime(startMs, endMs)
    } else {
        formatClockTime(startMs)
    }
    return "$day · $clock"
}

private fun formatOriginalAirDate(epochSec: Long, locale: Locale = Locale.getDefault()): String {
    val date = Instant.ofEpochSecond(epochSec).atZone(ZoneOffset.UTC).toLocalDate()
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
}
