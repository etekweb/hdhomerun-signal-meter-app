package net.etekweb.hdhrmeter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.etekweb.hdhrmeter.data.AiringRecordStatus
import net.etekweb.hdhrmeter.data.DEFAULT_RECORD_PADDING_SEC
import net.etekweb.hdhrmeter.data.PaddingInput
import net.etekweb.hdhrmeter.data.Recording
import net.etekweb.hdhrmeter.data.RecordingRule
import net.etekweb.hdhrmeter.data.airingRecordStatus
import net.etekweb.hdhrmeter.data.oneOffRuleFor
import net.etekweb.hdhrmeter.data.recordingPadding
import net.etekweb.hdhrmeter.data.splitPadding

@Composable
fun RecordProgramButton(
    status: AiringRecordStatus,
    notice: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onClick, enabled = enabled) { Text("Record") }
            status.label()?.let { label ->
                Text(label, color = status.color(), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (!notice.isNullOrBlank()) {
            Text(notice, color = Amber, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun RecordConfirmDialog(
    pending: PendingRecording,
    rules: List<RecordingRule>,
    recordings: List<Recording>,
    nowMs: Long,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val program = pending.program
    val existing = oneOffRuleFor(rules, pending.channelNumber, program.startEpochMs)
    val status = airingRecordStatus(rules, recordings, pending.channelNumber, program, nowMs)
    val defaults = splitPadding(DEFAULT_RECORD_PADDING_SEC)
    var earlyMin by remember { mutableStateOf("0") }
    var earlySec by remember { mutableStateOf(defaults.second) }
    var lateMin by remember { mutableStateOf("0") }
    var lateSec by remember { mutableStateOf(defaults.second) }
    var localError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(existing?.id, existing?.startPaddingSec, existing?.endPaddingSec) {
        val rule = existing ?: return@LaunchedEffect
        val untouched = earlyMin == "0" && earlySec == defaults.second &&
            lateMin == "0" && lateSec == defaults.second
        if (!untouched) return@LaunchedEffect
        val start = splitPadding(rule.startPaddingSec)
        val end = splitPadding(rule.endPaddingSec)
        earlyMin = start.first
        earlySec = start.second
        lateMin = end.first
        lateSec = end.second
    }
    val note = when {
        existing != null -> "This airing is already set to record. You can change how early or late it records."
        status == AiringRecordStatus.Recording -> "This program is already recording."
        status == AiringRecordStatus.Scheduled -> "This program is already scheduled."
        status == AiringRecordStatus.Recorded -> "A recording of this airing is already saved."
        else -> null
    }
    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = !saving),
    ) {
        BackHandler(enabled = !saving, onBack = onDismiss)
        Surface(
            color = InkRaised,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Record this program?",
                    color = Paper,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(program.title, color = Paper, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOf(pending.channelNumber, pending.channelName).filter { it.isNotBlank() }
                        .joinToString(" "),
                    color = Amber,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    formatGuideWhen(program.startEpochMs, program.stopEpochMs, nowMs),
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (note != null) {
                    Text(note, color = Paper, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Extra time before the program starts and after it ends.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                PaddingFields(
                    label = "Start early",
                    minutes = earlyMin,
                    seconds = earlySec,
                    enabled = !saving,
                    onMinutes = {
                        earlyMin = digits(it)
                        localError = null
                    },
                    onSeconds = {
                        earlySec = digits(it)
                        localError = null
                    },
                )
                PaddingFields(
                    label = "End late",
                    minutes = lateMin,
                    seconds = lateSec,
                    enabled = !saving,
                    onMinutes = {
                        lateMin = digits(it)
                        localError = null
                    },
                    onSeconds = {
                        lateSec = digits(it)
                        localError = null
                    },
                )
                val message = localError ?: error
                if (!message.isNullOrBlank()) {
                    Text(message, color = Bad, style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            when (
                                val padding = recordingPadding(earlyMin, earlySec, lateMin, lateSec)
                            ) {
                                is PaddingInput.Rejected -> localError = padding.message
                                is PaddingInput.Ready -> onConfirm(padding.startSec, padding.endSec)
                            }
                        },
                        enabled = !saving,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Ink,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(if (existing != null) "Update" else "Record")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduledPaddingDialog(
    startPaddingSec: Int,
    endPaddingSec: Int,
    seriesWide: Boolean,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    val start = splitPadding(startPaddingSec)
    val end = splitPadding(endPaddingSec)
    var earlyMin by remember(startPaddingSec, endPaddingSec) { mutableStateOf(start.first) }
    var earlySec by remember(startPaddingSec, endPaddingSec) { mutableStateOf(start.second) }
    var lateMin by remember(startPaddingSec, endPaddingSec) { mutableStateOf(end.first) }
    var lateSec by remember(startPaddingSec, endPaddingSec) { mutableStateOf(end.second) }
    var localError by remember(startPaddingSec, endPaddingSec) { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = !saving),
    ) {
        BackHandler(enabled = !saving, onBack = onDismiss)
        Surface(
            color = InkRaised,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Record times", color = Paper, style = MaterialTheme.typography.titleLarge)
                if (seriesWide) {
                    Text(
                        "These times apply to every scheduled episode of this series.",
                        color = Paper,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Extra time before the program starts and after it ends.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                PaddingFields(
                    label = "Start early",
                    minutes = earlyMin,
                    seconds = earlySec,
                    enabled = !saving,
                    onMinutes = {
                        earlyMin = digits(it)
                        localError = null
                    },
                    onSeconds = {
                        earlySec = digits(it)
                        localError = null
                    },
                )
                PaddingFields(
                    label = "End late",
                    minutes = lateMin,
                    seconds = lateSec,
                    enabled = !saving,
                    onMinutes = {
                        lateMin = digits(it)
                        localError = null
                    },
                    onSeconds = {
                        lateSec = digits(it)
                        localError = null
                    },
                )
                val message = localError ?: error
                if (!message.isNullOrBlank()) {
                    Text(message, color = Bad, style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            when (val padding = recordingPadding(earlyMin, earlySec, lateMin, lateSec)) {
                                is PaddingInput.Rejected -> localError = padding.message
                                is PaddingInput.Ready -> onSave(padding.startSec, padding.endSec)
                            }
                        },
                        enabled = !saving,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Ink,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaddingFields(
    label: String,
    minutes: String,
    seconds: String,
    enabled: Boolean,
    onMinutes: (String) -> Unit,
    onSeconds: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Paper, style = MaterialTheme.typography.labelLarge)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaddingField(minutes, enabled, onMinutes)
            Text("min", color = Muted, style = MaterialTheme.typography.bodyMedium)
            PaddingField(seconds, enabled, onSeconds)
            Text("sec", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PaddingField(value: String, enabled: Boolean, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.width(76.dp).heightIn(min = 52.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Paper),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Paper,
            unfocusedTextColor = Paper,
            disabledTextColor = Muted,
            focusedBorderColor = Amber,
            unfocusedBorderColor = Muted,
            cursorColor = Amber,
        ),
    )
}

private fun digits(raw: String): String = raw.filter { it.isDigit() }.take(4)

private fun AiringRecordStatus.label(): String? = when (this) {
    AiringRecordStatus.None -> null
    AiringRecordStatus.Scheduled -> "Scheduled"
    AiringRecordStatus.Recording -> "Recording"
    AiringRecordStatus.Recorded -> "Recorded"
}

private fun AiringRecordStatus.color(): Color = when (this) {
    AiringRecordStatus.Recording -> Amber
    AiringRecordStatus.Scheduled -> Good
    AiringRecordStatus.Recorded -> Muted
    AiringRecordStatus.None -> Muted
}
