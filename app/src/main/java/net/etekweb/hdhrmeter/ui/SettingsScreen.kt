package net.etekweb.hdhrmeter.ui

import android.view.KeyEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.etekweb.hdhrmeter.data.GuideMode
import net.etekweb.hdhrmeter.data.HardwareDecodeMode
import net.etekweb.hdhrmeter.data.HdhrDevice
import net.etekweb.hdhrmeter.data.mergeTuners
import net.etekweb.hdhrmeter.data.hdhrGuideKey
import net.etekweb.hdhrmeter.data.normalizeDvrAddress
import net.etekweb.hdhrmeter.data.sameTuner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onScan: () -> Unit,
    onHostChange: (String) -> Unit,
    onAddDevice: () -> Unit,
    onSelectDevice: (HdhrDevice) -> Unit,
    onRemoveDevice: (HdhrDevice) -> Unit,
    onDvrAddressChange: (String) -> Unit,
    onGuideDraft: (String) -> Unit,
    onAddGuide: () -> Unit,
    onSelectNone: () -> Unit,
    onSelectHdHomeRun: () -> Unit,
    onSelectGuide: (String) -> Unit,
    onRemoveGuide: (String) -> Unit,
    onHardwareDecode: (HardwareDecodeMode) -> Unit,
    onShowChannelNumber: (Boolean) -> Unit,
) {
    val tuners = mergeTuners(state.devices, state.savedTuners)
    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                actions = {
                    IconButton(onClick = onScan, enabled = !state.scanning) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Scan the network")
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
            if (state.scanning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Amber,
                    trackColor = InkRaised,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("Tuners", style = MaterialTheme.typography.titleMedium, color = Paper)
                    Text(
                        "Tuners on the network, plus any you add. Choose one for the lineup.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (state.scanError != null) {
                    item {
                        Text(state.scanError, color = Bad, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(tuners, key = { "${it.deviceId}|${it.host}" }) { device ->
                    val discovered = state.devices.any { sameTuner(it, device) }
                    val saved = state.savedTuners.any { sameTuner(it, device) }
                    val active = state.tunerReach == TunerReach.Ready &&
                        state.selectedDevice?.let { sameTuner(it, device) } == true
                    TunerChoiceRow(
                        device = device,
                        selected = state.selectedDevice?.let { sameTuner(it, device) } == true,
                        onNetwork = discovered || active,
                        onSelect = { onSelectDevice(device) },
                        onRemove = if (saved && !discovered && !active) {
                            { onRemoveDevice(device) }
                        } else {
                            null
                        },
                    )
                }
                item {
                    TunerDraftField(state, onHostChange, onAddDevice)
                }
                item {
                    HorizontalDivider(color = InkRaised, modifier = Modifier.padding(vertical = 6.dp))
                }
                item {
                    DvrAddressField(state.dvrAddress, onDvrAddressChange)
                }
                item {
                    HorizontalDivider(color = InkRaised, modifier = Modifier.padding(vertical = 6.dp))
                }
                item {
                    Text("Guide providers", style = MaterialTheme.typography.titleMedium, color = Paper)
                }
                item {
                    BuiltInGuideRow(
                        title = "None",
                        detail = "No guide data",
                        selected = state.guideMode == GuideMode.None,
                        status = null,
                        error = null,
                        onSelect = onSelectNone,
                    )
                }
                item {
                    val hdhrKey = state.selectedDevice?.deviceId?.let(::hdhrGuideKey).orEmpty()
                    BuiltInGuideRow(
                        title = "HDHomeRun",
                        detail = "Use guide data from the connected tuner",
                        selected = state.guideMode == GuideMode.HdHomeRun,
                        status = if (state.guideMode == GuideMode.HdHomeRun) {
                            selectedGuideStatus(state, hdhrKey)
                        } else {
                            null
                        },
                        error = state.guideError,
                        onSelect = onSelectHdHomeRun,
                    )
                }
                item {
                    Text(
                        "Full address of an XMLTV file. Add more than one and choose which to use.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
                items(state.guideSources, key = { it }) { url ->
                    GuideSourceRow(
                        url = url,
                        selected = state.guideMode == GuideMode.Url && url == state.activeGuideUrl,
                        state = state,
                        onSelect = { onSelectGuide(url) },
                        onRemove = { onRemoveGuide(url) },
                    )
                }
                item {
                    HorizontalDivider(color = InkRaised, modifier = Modifier.padding(vertical = 6.dp))
                }
                item {
                    GuideDraftField(state, onGuideDraft, onAddGuide)
                }
                item {
                    Text("Playback", style = MaterialTheme.typography.titleMedium, color = Paper)
                }
                item {
                    HardwareDecodeChoices(state.hardwareDecode, onHardwareDecode)
                }
                item {
                    PlaybackToggleRow(
                        title = "Always show channel number during playback",
                        checked = state.showChannelNumber,
                        onChecked = onShowChannelNumber,
                    )
                }
            }
        }
    }
}

@Composable
private fun DvrAddressField(address: String, onSave: (String) -> Unit) {
    var draft by rememberSaveable(address) { mutableStateOf(address) }
    val normalized = if (draft.isBlank()) "" else normalizeDvrAddress(draft)
    val canSave = normalized != null && normalized != address
    val save = { if (canSave) onSave(draft) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("DVR address", style = MaterialTheme.typography.titleMedium, color = Paper)
        Text(
            "Optional. Enter the DVR’s IP address or hostname, with its port if needed. Leave blank for automatic discovery.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        SettingsTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "192.168.1.20",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
        Button(onClick = save, enabled = canSave, modifier = Modifier.align(Alignment.End)) {
            Text("Save DVR address")
        }
    }
}

@Composable
private fun HardwareDecodeChoices(selectedMode: HardwareDecodeMode, onSelect: (HardwareDecodeMode) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Use hardware decoding when available", style = MaterialTheme.typography.titleMedium, color = Paper)
        Text(
            "Hardware decoding can improve performance but can cause issues with interlaced video.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        HardwareDecodeMode.entries.forEach { mode ->
            val label = when (mode) {
                HardwareDecodeMode.Always -> "Always"
                HardwareDecodeMode.HdOnly -> "HD Only"
                HardwareDecodeMode.Never -> "Never"
            }
            ChoiceRow(
                selected = mode == selectedMode,
                onSelect = { onSelect(mode) },
                onRemove = null,
                removeDescription = "",
                modifier = Modifier.semantics {
                    role = Role.RadioButton
                    selected = mode == selectedMode
                },
            ) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = Paper)
            }
        }
    }
}

@Composable
private fun PlaybackToggleRow(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Surface(
        color = InkRaised,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Paper)
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun BuiltInGuideRow(
    title: String,
    detail: String,
    selected: Boolean,
    status: String?,
    error: String?,
    onSelect: () -> Unit,
) {
    ChoiceRow(
        selected = selected,
        onSelect = onSelect,
        onRemove = null,
        removeDescription = "",
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Paper)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (status != null) {
            Text(
                status,
                color = if (status == error) Bad else Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun selectedGuideStatus(state: UiState, appliedKey: String): String? {
    return when {
        state.guideLoading -> "Loading the guide…"
        state.guideError != null -> state.guideError
        appliedKey.isNotEmpty() && state.appliedGuideUrl == appliedKey && state.guide != null -> "Guide loaded."
        else -> null
    }
}

@Composable
private fun GuideSourceRow(
    url: String,
    selected: Boolean,
    state: UiState,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val status = if (!selected) {
        null
    } else {
        when {
            state.guideLoading -> "Loading the guide…"
            state.guideError != null -> state.guideError
            state.appliedGuideUrl == url && state.guide != null -> "Guide loaded."
            else -> null
        }
    }
    ChoiceRow(
        selected = selected,
        onSelect = onSelect,
        onRemove = onRemove,
        removeDescription = "Remove guide",
    ) {
        Text(
            url,
            color = Paper,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (status != null) {
            Text(
                status,
                color = if (status == state.guideError) Bad else Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TunerChoiceRow(
    device: HdhrDevice,
    selected: Boolean,
    onNetwork: Boolean,
    onSelect: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    ChoiceRow(
        selected = selected,
        onSelect = onSelect,
        onRemove = onRemove,
        removeDescription = "Remove tuner",
    ) {
        Text(device.friendlyName, style = MaterialTheme.typography.titleMedium, color = Paper)
        val detail = listOf(device.model, device.firmwareVersion)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        val count = when {
            device.tunerCount <= 0 -> null
            device.tunerCount == 1 -> "1 tuner"
            else -> "${device.tunerCount} tuners"
        }
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(device.host, fontFamily = FontFamily.Monospace, color = Amber)
            if (count != null) Text(count, color = Muted)
            if (!onNetwork) Text("Not on the network", color = Muted)
        }
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    onSelect: () -> Unit,
    onRemove: (() -> Unit)?,
    removeDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onSelect,
        color = InkRaised,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) Amber else Color.Transparent, MaterialTheme.shapes.medium),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = Amber, unselectedColor = Muted),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            ) {
                content()
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = removeDescription)
                }
            }
        }
    }
}

/**
 * Tuner and guide addresses. On a television, moving onto the field with the direction pad
 * only focuses it. The keyboard stays closed until the field is selected.
 */
@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    modifier: Modifier = Modifier,
) {
    val television = isTelevision()
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(television, pressed) {
        if (television && pressed) editing = true
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .onFocusChanged { focus ->
                if (!focus.isFocused && editing) {
                    editing = false
                    keyboard?.hide()
                }
            }
            .onPreviewKeyEvent { event ->
                if (!television || editing) return@onPreviewKeyEvent false
                if (!settingsFieldAcceptsRemoteSelect(event.key.nativeKeyCode)) {
                    return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) editing = true
                true
            },
        readOnly = settingsFieldReadOnly(television, editing),
        interactionSource = interaction,
        singleLine = true,
        placeholder = { Text(placeholder) },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
    )
}

/** Direction-pad OK and the remote's select button. Arrows keep moving through the list. */
internal fun settingsFieldAcceptsRemoteSelect(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_SELECT,
    -> true
    else -> false
}

/**
 * A focused text field opens the soft keyboard. On a television, landing on the field while
 * scrolling should not — the keyboard waits until the field is selected.
 */
internal fun settingsFieldReadOnly(television: Boolean, editing: Boolean): Boolean =
    television && !editing

@Composable
private fun GuideDraftField(
    state: UiState,
    onDraft: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val draft = state.guideDraft.trim()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        SettingsTextField(
            value = state.guideDraft,
            onValueChange = onDraft,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "http://10.0.0.241:8080/xmlguide.xmltv",
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { if (draft.isNotEmpty()) onAdd() }),
        )
        if (state.guideDraftError != null || draft.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    state.guideDraftError?.let { message ->
                        Text(message, color = Bad, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (draft.isNotEmpty()) {
                    Button(onClick = onAdd) { Text("Add") }
                }
            }
        }
    }
}

@Composable
private fun TunerDraftField(
    state: UiState,
    onHostChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tuner address", style = MaterialTheme.typography.labelLarge, color = Muted)
        SettingsTextField(
            value = state.manualHost,
            onValueChange = onHostChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "192.168.1.20",
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onAdd() }),
        )
        state.tunerAddError?.let { message ->
            Text(message, color = Bad, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = onAdd,
            enabled = state.manualHost.isNotBlank() && !state.connecting,
            modifier = Modifier.align(Alignment.End),
        ) {
            if (state.connecting) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(16.dp),
                    strokeWidth = 2.dp,
                    color = Ink,
                )
            }
            Text(if (state.connecting) "Adding" else "Add")
        }
    }
}
