package net.etekweb.hdhrmeter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.etekweb.hdhrmeter.R
import net.etekweb.hdhrmeter.data.HdhrDevice
import net.etekweb.hdhrmeter.data.tunerUnavailableMessage

@Composable
fun AppBottomBar(tab: AppTab, onTab: (AppTab) -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = Ink,
        selectedTextColor = Amber,
        indicatorColor = Amber,
        unselectedIconColor = Muted,
        unselectedTextColor = Muted,
    )
    NavigationBar(containerColor = InkRaised) {
        NavigationBarItem(
            selected = tab == AppTab.Lineup,
            onClick = { onTab(AppTab.Lineup) },
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text("Lineup") },
            colors = colors,
        )
        NavigationBarItem(
            selected = tab == AppTab.Dvr,
            onClick = { onTab(AppTab.Dvr) },
            icon = { Icon(painterResource(R.drawable.ic_nav_dvr), contentDescription = null) },
            label = { Text("DVR") },
            colors = colors,
        )
        NavigationBarItem(
            selected = tab == AppTab.Settings,
            onClick = { onTab(AppTab.Settings) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Settings") },
            colors = colors,
        )
    }
}

/**
 * Lineup and DVR need a tuner. Until the last one answers, both tabs explain
 * that the choice is in Settings.
 */
@Composable
fun TunerGate(
    title: String,
    state: UiState,
    ready: @Composable (HdhrDevice) -> Unit,
) {
    val device = state.selectedDevice
    if (state.tunerReach != TunerReach.Ready || device == null) {
        TunerUnavailablePage(
            title = title,
            checking = state.tunerReach == TunerReach.Checking && device != null,
            host = device?.host,
        )
    } else {
        ready(device)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunerUnavailablePage(title: String, checking: Boolean, host: String?) {
    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Ink,
                    titleContentColor = Paper,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (checking) {
                CircularProgressIndicator(color = Amber)
                Text(
                    if (host.isNullOrBlank()) "Looking for the tuner…" else "Looking for the tuner at $host…",
                    color = Muted,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                Text(
                    tunerUnavailableMessage(host),
                    color = Paper,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}


