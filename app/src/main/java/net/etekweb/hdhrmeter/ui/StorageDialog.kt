package net.etekweb.hdhrmeter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.etekweb.hdhrmeter.data.AtscRecordingRate
import net.etekweb.hdhrmeter.data.StorageSpace
import net.etekweb.hdhrmeter.data.formatStorageAmount
import net.etekweb.hdhrmeter.data.recordingSpaceForecast

private val StorageUsedColor = Color(0xFF2196F3)
private val StorageScheduledColor = Color(0xFF90CAF9)

private const val ATSC_ESTIMATE_NOTE =
    "ATSC 1.0 estimates: 480i at 3 Mbps, 720p at 12 Mbps, 1080i at 16 Mbps. " +
        "Actual broadcast sizes vary."

internal const val RECORDING_ESTIMATE_NOTE = ATSC_ESTIMATE_NOTE +
    " Forecast uses 480i for SD and 1080i for HD or unknown channels, including padding."

@Composable
internal fun StorageDialog(
    dvr: DvrState,
    channels: List<net.etekweb.hdhrmeter.data.Channel>,
    onForecastChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val space = dvr.storage ?: return
    val forecastEnabled = dvr.storageForecastEnabled
    val forecast = recordingSpaceForecast(dvr.scheduled, dvr.recordings, channels, System.currentTimeMillis())
    val scheduledBytes = if (forecastEnabled) forecast.bytes else 0L
    val remaining = (space.freeBytes - scheduledBytes).coerceAtLeast(0)
    val freeLabel = if (forecastEnabled) "Free after schedule" else "Free now"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkRaised,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Storage", color = Paper, modifier = Modifier.weight(1f))
                Row(
                    Modifier.toggleable(
                        value = forecastEnabled,
                        role = Role.Switch,
                        onValueChange = onForecastChange,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Forecast", color = Paper, style = MaterialTheme.typography.labelLarge)
                    Switch(checked = forecastEnabled, onCheckedChange = null)
                }
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StorageMeter(space, scheduledBytes, forecastEnabled)
                StorageKey("Used", formatStorageAmount(space.totalBytes - space.freeBytes), StorageUsedColor)
                if (forecastEnabled) {
                    StorageKey("Scheduled / still recording", "≈ ${formatStorageAmount(forecast.bytes)}", StorageScheduledColor)
                }
                StorageKey(freeLabel, formatStorageAmount(remaining), Muted.copy(alpha = 0.25f))
                if (forecastEnabled && forecast.bytes > space.freeBytes) {
                    Text("Scheduled recordings may exceed storage by ${formatStorageAmount(forecast.bytes - space.freeBytes)}.", color = Bad)
                }
                if (forecastEnabled) ForecastAvailability(dvr, forecast.unknownDurations)
                HorizontalDivider(color = Muted.copy(alpha = 0.25f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Format", color = Muted)
                    Text(freeLabel, color = Muted)
                }
                AtscRecordingRate.entries.reversed().forEach { rate ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(rate.label, color = Paper)
                        Text(rate.capacity(remaining), color = Paper)
                    }
                }
                Text(if (forecastEnabled) RECORDING_ESTIMATE_NOTE else ATSC_ESTIMATE_NOTE,
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                if (forecastEnabled) {
                    Text("Forecast covers currently listed airings; future series episodes and automatic deletions are not included.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
internal fun ForecastAvailability(dvr: DvrState, unknownDurations: Int) {
    val updatingMessage = "Updating storage forecast…"
    val message = when {
        dvr.scheduledLoading || dvr.loading -> updatingMessage
        !dvr.scheduledLoaded || dvr.scheduledError != null -> "Schedule unavailable; forecast is incomplete."
        dvr.error != null -> "Storage could not be refreshed; estimates may be out of date."
        else -> dvr.scheduledWarning
    }
    // Keep the loading line's measured space after it disappears, including at larger font sizes.
    // This is shared by the storage and recording dialogs so neither jumps on refresh completion.
    Text(
        message ?: updatingMessage,
        color = if (message != null) Amber else Color.Transparent,
        style = MaterialTheme.typography.bodySmall,
        modifier = if (message == null) Modifier.clearAndSetSemantics { } else Modifier,
    )
    if (unknownDurations > 0) Text("$unknownDurations recording(s) have unknown duration and are excluded from the estimate.",
        color = Amber, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun StorageKey(label: String, value: String, color: Color) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Text(label, color = Paper, modifier = Modifier.weight(1f))
        Text(value, color = Paper, maxLines = 1)
    }
}

@Composable
private fun StorageMeter(space: StorageSpace, scheduledBytes: Long, forecastEnabled: Boolean) {
    val used = (space.totalBytes - space.freeBytes).toDouble() / space.totalBytes
    val scheduled = minOf(scheduledBytes, space.freeBytes).toDouble() / space.totalBytes
    Canvas(Modifier.fillMaxWidth().height(16.dp).semantics {
        contentDescription = "Storage: ${formatStorageAmount(space.totalBytes - space.freeBytes)} used, " +
            (if (forecastEnabled) "${formatStorageAmount(scheduledBytes)} forecast, " else "") +
            "${formatStorageAmount((space.freeBytes - scheduledBytes).coerceAtLeast(0))} " +
            (if (forecastEnabled) "free after schedule" else "free now")
    }) {
        drawRect(Muted.copy(alpha = 0.25f))
        drawRect(StorageUsedColor, size = Size((size.width * used).toFloat(), size.height))
        drawRect(StorageScheduledColor, topLeft = Offset((size.width * used).toFloat(), 0f),
            size = Size((size.width * scheduled).toFloat(), size.height))
    }
}
