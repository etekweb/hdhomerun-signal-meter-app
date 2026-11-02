package net.etekweb.hdhrmeter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import net.etekweb.hdhrmeter.data.HdhrDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    state: UiState,
    onScan: () -> Unit,
    onHostChange: (String) -> Unit,
    onConnect: () -> Unit,
    onOpen: (HdhrDevice) -> Unit,
    onGuideHostChange: (String) -> Unit,
    onSaveGuide: () -> Unit,
) {
    val recent = state.recent?.takeUnless { saved ->
        state.devices.any { it.deviceId == saved.deviceId }
    }
    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Text("HDHR Meter") },
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
                .fillMaxSize()
                .imePadding(),
        ) {
            if (state.scanning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Amber,
                    trackColor = InkRaised,
                )
            }
            state.scanError?.let { message ->
                Text(
                    message,
                    color = Bad,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    GuideProviderField(state, onGuideHostChange, onSaveGuide)
                }
                item {
                    HorizontalDivider(color = InkRaised)
                }
                item {
                    Text(
                        "Tuners on this network",
                        style = MaterialTheme.typography.titleMedium,
                        color = Paper,
                    )
                    Text(
                        "Scan finds HDHomeRun devices. You can also type an address.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }
                items(state.devices, key = { it.deviceId }) { device ->
                    DeviceCard(device, onClick = { onOpen(device) })
                }
                if (recent != null) {
                    item {
                        Text(
                            "Last used",
                            style = MaterialTheme.typography.titleMedium,
                            color = Paper,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    item(key = "recent-${recent.deviceId}") {
                        DeviceCard(recent, onClick = { onOpen(recent) })
                    }
                }
            }
            HorizontalDivider(color = InkRaised)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Tuner address", style = MaterialTheme.typography.labelLarge, color = Muted)
                OutlinedTextField(
                    value = state.manualHost,
                    onValueChange = onHostChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("192.168.1.20") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onConnect() }),
                )
                Button(
                    onClick = onConnect,
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
                    Text(if (state.connecting) "Connecting" else "Connect")
                }
            }
        }
    }
}

@Composable
private fun GuideProviderField(
    state: UiState,
    onGuideHostChange: (String) -> Unit,
    onSaveGuide: () -> Unit,
) {
    val draft = state.guideHost.trim()
    val edited = draft != state.savedGuideHost.trim()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text("Guide provider", style = MaterialTheme.typography.titleMedium, color = Paper)
        Text(
            "Optional. The app reads /xmlguide.xmltv from port 8080 at this address.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        OutlinedTextField(
            value = state.guideHost,
            onValueChange = onGuideHostChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("10.0.0.241") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = {
                if (edited && !state.guideLoading) onSaveGuide()
            }),
        )
        val guideError = state.guideError
        val showStatus = state.guideLoading || guideError != null || state.appliedGuideHost.isNotEmpty()
        if (showStatus || edited) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    when {
                        state.guideLoading -> Text(
                            "Loading the guide…",
                            color = Muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        guideError != null -> Text(
                            guideError,
                            color = Bad,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        state.appliedGuideHost.isNotEmpty() -> Text(
                            "Using ${state.appliedGuideHost}.",
                            color = Muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (edited) {
                    Button(
                        onClick = onSaveGuide,
                        enabled = !state.guideLoading,
                    ) {
                        if (state.guideLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(16.dp),
                                strokeWidth = 2.dp,
                                color = Ink,
                            )
                        }
                        Text(if (draft.isEmpty()) "Clear" else "Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: HdhrDevice, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = InkRaised,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(device.friendlyName, style = MaterialTheme.typography.titleMedium, color = Paper)
            val detail = listOf(device.model, device.firmwareVersion)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(device.host, fontFamily = FontFamily.Monospace, color = Amber)
                if (device.tunerCount > 0) {
                    Text(
                        if (device.tunerCount == 1) "1 tuner" else "${device.tunerCount} tuners",
                        color = Muted,
                    )
                }
            }
        }
    }
}
