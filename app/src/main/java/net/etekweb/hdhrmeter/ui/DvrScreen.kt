package net.etekweb.hdhrmeter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.etekweb.hdhrmeter.data.Recording
import net.etekweb.hdhrmeter.data.ScheduledRecording
import net.etekweb.hdhrmeter.data.formatStorageCapacity
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
    onPlay: () -> Unit,
    onSection: (DvrSection) -> Unit,
    onEnsureScheduled: () -> Unit,
    onRefreshScheduled: () -> Unit,
    onOpenScheduled: (String) -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onEdit: () -> Unit,
    onDismissEdit: () -> Unit,
    onSavePadding: (Int, Int) -> Unit,
) {
    LaunchedEffect(Unit) { onEnsure() }
    LaunchedEffect(state.dvr.section) {
        if (state.dvr.section == DvrSection.Scheduled) onEnsureScheduled()
    }
    val dvr = state.dvr
    val recording = dvr.recordings.firstOrNull { it.id == dvr.selectedId }
    val scheduled = dvr.scheduled.firstOrNull { it.id == dvr.selectedScheduledId }
    when {
        dvr.section == DvrSection.Recordings && recording != null -> {
            RecordingDetail(recording, dvr.acting, dvr.actionError, onClose, onPlay, onDelete)
        }
        dvr.section == DvrSection.Scheduled && scheduled != null -> {
            ScheduledDetail(
                scheduled,
                dvr.acting,
                dvr.actionError,
                dvr.notice,
                onClose,
                onDelete,
                onEdit,
            )
        }
        else -> {
            DvrBrowser(dvr, onSection, onRefresh, onRefreshScheduled, onOpen, onOpenScheduled)
        }
    }
    if (dvr.confirmDelete) {
        deleteCopy(dvr)?.let { copy ->
            DeleteConfirmDialog(
                title = copy.title,
                body = copy.body,
                acting = dvr.acting,
                error = dvr.actionError,
                onDismiss = onDismissDelete,
                onConfirm = onConfirmDelete,
            )
        }
    }
    if (dvr.editingPadding && scheduled != null) {
        ScheduledPaddingDialog(
            startPaddingSec = scheduled.startPaddingSec,
            endPaddingSec = scheduled.endPaddingSec,
            seriesWide = !scheduled.oneOff,
            saving = dvr.acting,
            error = dvr.actionError,
            onDismiss = onDismissEdit,
            onSave = onSavePadding,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DvrBrowser(
    dvr: DvrState,
    onSection: (DvrSection) -> Unit,
    onRefresh: () -> Unit,
    onRefreshScheduled: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenScheduled: (String) -> Unit,
) {
    val scheduled = dvr.section == DvrSection.Scheduled
    val loading = if (scheduled) dvr.scheduledLoading else dvr.loading
    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Text("DVR") },
                actions = {
                    val capacity = dvr.storage?.let { formatStorageCapacity(it) }
                    if (capacity != null) {
                        Text(
                            capacity,
                            color = Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    IconButton(
                        onClick = if (scheduled) onRefreshScheduled else onRefresh,
                        enabled = !loading,
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = if (scheduled) "Refresh scheduled recordings" else "Refresh recordings",
                            tint = Amber,
                        )
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
            DvrSectionTabs(dvr.section, onSection)
            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Amber,
                    trackColor = InkRaised,
                )
            }
            if (scheduled) {
                ScheduledList(dvr, onOpenScheduled, Modifier.weight(1f))
            } else {
                RecordingList(dvr, onOpen, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DvrSectionTabs(section: DvrSection, onSection: (DvrSection) -> Unit) {
    PrimaryTabRow(
        selectedTabIndex = if (section == DvrSection.Recordings) 0 else 1,
        containerColor = Ink,
        contentColor = Amber,
    ) {
        DvrTab("Recordings", section == DvrSection.Recordings) { onSection(DvrSection.Recordings) }
        DvrTab("Scheduled", section == DvrSection.Scheduled) { onSection(DvrSection.Scheduled) }
    }
}

@Composable
private fun DvrTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        selectedContentColor = Amber,
        unselectedContentColor = Muted,
        text = { Text(label) },
    )
}

@Composable
private fun RecordingList(
    dvr: DvrState,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowMs = System.currentTimeMillis()
    when {
        dvr.recordings.isNotEmpty() -> {
            LazyColumn(
                modifier = modifier,
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
        dvr.loading || !dvr.loaded -> DvrMessage("Looking for recordings…", modifier)
        !dvr.error.isNullOrBlank() -> DvrMessage(dvr.error, modifier, Bad)
        dvr.loaded && dvr.enginesFound == 0 -> {
            DvrMessage("No HDHomeRun DVR storage answered on the network.", modifier)
        }
        dvr.loaded -> DvrMessage("No recordings.", modifier)
    }
}

@Composable
private fun ScheduledList(
    dvr: DvrState,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowMs = System.currentTimeMillis()
    val banner = dvr.scheduledError ?: dvr.scheduledWarning
    when {
        dvr.scheduled.isNotEmpty() -> {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!banner.isNullOrBlank() || !dvr.notice.isNullOrBlank()) {
                    item {
                        DvrBanner(banner, dvr.scheduledError != null, dvr.notice)
                    }
                }
                items(dvr.scheduled, key = { it.id }) { item ->
                    ScheduledRow(item, nowMs, onClick = { onOpen(item.id) })
                }
            }
        }
        dvr.scheduledLoading || !dvr.scheduledLoaded -> DvrMessage("Looking for scheduled recordings…", modifier)
        !dvr.scheduledError.isNullOrBlank() -> DvrMessage(dvr.scheduledError, modifier, Bad)
        dvr.scheduledLoaded -> {
            Column(modifier) {
                if (!dvr.scheduledWarning.isNullOrBlank() || !dvr.notice.isNullOrBlank()) {
                    DvrBanner(
                        dvr.scheduledWarning,
                        error = false,
                        notice = dvr.notice,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                DvrMessage("No scheduled recordings.", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DvrBanner(
    banner: String?,
    error: Boolean,
    notice: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!banner.isNullOrBlank()) {
            Text(
                banner,
                color = if (error) Bad else Amber,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!notice.isNullOrBlank() && notice != banner) {
            Text(notice, color = Amber, style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun ScheduledRow(item: ScheduledRecording, nowMs: Long, onClick: () -> Unit) {
    val episode = formatEpisodeSummary(item.episodeNumber, item.episodeTitle)
    val schedule = windowText(item.startEpochMs, item.endEpochMs, nowMs)
    val channel = channelLabel(item.channelNumber, item.channelName)
    Surface(
        onClick = onClick,
        color = InkRaised,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                item.title,
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
                    listOfNotNull(schedule, channel).joinToString(" · "),
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                if (scheduledOnAir(item, nowMs)) {
                    Text(
                        "Recording",
                        color = Amber,
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
private fun RecordingDetail(
    recording: Recording,
    acting: Boolean,
    actionError: String?,
    onClose: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler(enabled = !acting, onBack = onClose)
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
                    IconButton(onClick = onClose, enabled = !acting) {
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
            if (!recording.playUrl.isNullOrBlank()) {
                Button(
                    onClick = onPlay,
                    enabled = !acting,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Play")
                }
            }
            if (!actionError.isNullOrBlank()) {
                Text(actionError, color = Bad, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = onDelete,
                enabled = !acting,
                modifier = Modifier.padding(top = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = Ink),
            ) {
                Text("Delete")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledDetail(
    item: ScheduledRecording,
    acting: Boolean,
    actionError: String?,
    notice: String?,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    BackHandler(enabled = !acting, onBack = onClose)
    val nowMs = System.currentTimeMillis()
    val episode = item.episodeNumber?.trim()?.ifBlank { null }
    val episodeTitle = item.episodeTitle?.trim()?.ifBlank { null }
    val synopsis = item.synopsis?.trim()?.ifBlank { null }
    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !acting) {
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
            channelLabel(item.channelNumber, item.channelName)?.let { channel ->
                RecordingFact("Channel", channel)
            }
            RecordingFact("Aired", windowText(item.startEpochMs, item.endEpochMs, nowMs))
            item.originalAirdateEpochSec?.let { airdate ->
                RecordingFact("First aired", formatOriginalAirDate(airdate))
            }
            RecordingFact("Starts early", formatPadding(item.startPaddingSec))
            RecordingFact("Ends late", formatPadding(item.endPaddingSec))
            if (scheduledOnAir(item, nowMs)) {
                Text("Recording", color = Amber, style = MaterialTheme.typography.bodyMedium)
            }
            if (synopsis != null) {
                Text(
                    synopsis,
                    color = Paper,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (!notice.isNullOrBlank()) {
                Text(notice, color = Amber, style = MaterialTheme.typography.bodyMedium)
            }
            if (!actionError.isNullOrBlank()) {
                Text(actionError, color = Bad, style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onEdit, enabled = !acting) { Text("Edit") }
                Button(
                    onClick = onDelete,
                    enabled = !acting,
                    colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = Ink),
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    title: String,
    body: String,
    acting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!acting) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = !acting),
    ) {
        BackHandler(enabled = !acting, onBack = onDismiss)
        Surface(
            color = InkRaised,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, color = Paper, style = MaterialTheme.typography.titleLarge)
                Text(body, color = Paper, style = MaterialTheme.typography.bodyMedium)
                if (!error.isNullOrBlank()) {
                    Text(error, color = Bad, style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !acting) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        enabled = !acting,
                        colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = Ink),
                    ) {
                        if (acting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Ink,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Delete")
                        }
                    }
                }
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

private data class DeleteCopy(val title: String, val body: String)

private fun deleteCopy(dvr: DvrState): DeleteCopy? {
    return when (dvr.section) {
        DvrSection.Recordings -> {
            if (dvr.recordings.none { it.id == dvr.selectedId }) return null
            DeleteCopy(
                "Delete this recording?",
                "The file is removed and this episode will not be recorded again.",
            )
        }
        DvrSection.Scheduled -> {
            val item = dvr.scheduled.firstOrNull { it.id == dvr.selectedScheduledId } ?: return null
            if (item.oneOff) {
                DeleteCopy(
                    "Cancel this recording?",
                    "This program is taken off the schedule.",
                )
            } else {
                DeleteCopy(
                    "Cancel this airing?",
                    "Other episodes of this series still record.",
                )
            }
        }
    }
}

private fun recordingChannel(recording: Recording): String? {
    return channelLabel(recording.channelNumber, recording.channelName)
}

private fun channelLabel(number: String?, name: String?): String? {
    return listOfNotNull(
        number?.trim()?.ifBlank { null },
        name?.trim()?.ifBlank { null },
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

private fun scheduledOnAir(item: ScheduledRecording, nowMs: Long): Boolean {
    val end = item.endEpochMs ?: return false
    return nowMs >= item.startEpochMs && nowMs < end
}

private fun formatPadding(totalSec: Int): String {
    if (totalSec <= 0) return "None"
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return when {
        minutes == 0 -> "$seconds sec"
        seconds == 0 -> "$minutes min"
        else -> "$minutes min $seconds sec"
    }
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
