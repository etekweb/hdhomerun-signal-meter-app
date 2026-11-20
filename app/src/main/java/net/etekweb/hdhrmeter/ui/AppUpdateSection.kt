package net.etekweb.hdhrmeter.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.etekweb.hdhrmeter.data.AppUpdate
import net.etekweb.hdhrmeter.data.checkAppUpdate
import net.etekweb.hdhrmeter.data.downloadAppUpdate
import net.etekweb.hdhrmeter.data.installedApp

@Composable
internal fun AppUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installedVersion = remember(context) { installedApp(context).versionName.orEmpty() }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var readyApk by remember { mutableStateOf<File?>(null) }
    var request by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    fun openInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    fun downloadAndInstall(release: AppUpdate) {
        message = null
        progress = 0
        val download = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val apk = readyApk?.takeIf { it.isFile } ?: downloadAppUpdate(context, release) { progress = it }
                readyApk = apk
                openInstaller(apk)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = if (readyApk?.isFile == true) {
                    "Android could not open the package installer."
                } else {
                    "Could not download the update. Check your connection and available storage, then try again."
                }
            } finally {
                request = null
            }
        }
        request = download
        download.start()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val release = update ?: return@rememberLauncherForActivityResult
        if (context.packageManager.canRequestPackageInstalls()) {
            downloadAndInstall(release)
        } else {
            message = "Allow installs from this app in Android Settings to continue."
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("App updates", style = MaterialTheme.typography.titleMedium, color = Paper)
        Text("Installed version $installedVersion", style = MaterialTheme.typography.bodyMedium, color = Muted)
        Button(
            onClick = {
                message = null
                readyApk = null
                request = scope.launch {
                    try {
                        update = checkAppUpdate(context)
                        if (update == null) message = "No newer GitHub release is available."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        message = "Could not check GitHub for an update. Try again later."
                    } finally {
                        request = null
                    }
                }
            },
            enabled = request == null && update == null,
            modifier = Modifier.tvFocusBorder(),
        ) {
            Text(if (request != null && update == null) "Checking…" else "Check for updates")
        }
        if (update == null) {
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Paper) }
        }
    }

    update?.let { release ->
        val downloading = request != null
        val size = Formatter.formatShortFileSize(context, release.size)
        val dismiss: () -> Unit = {
            if (downloading) {
                request?.cancel()
            } else {
                update = null
                message = null
            }
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Update to ${release.version}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (downloading) {
                        Text("Downloading… $progress%")
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text("Download $size from GitHub and install with Android’s package installer.")
                    }
                    message?.let { Text(it) }
                }
            },
            confirmButton = {
                if (!downloading) {
                    Button(
                        onClick = {
                            if (context.packageManager.canRequestPackageInstalls()) {
                                downloadAndInstall(release)
                            } else {
                                try {
                                    permissionLauncher.launch(
                                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                                    )
                                } catch (_: Exception) {
                                    message = "Android could not open the settings for installing apps."
                                }
                            }
                        },
                        modifier = Modifier.tvFocusBorder(),
                    ) {
                        Text(if (readyApk != null) "Install update" else "Download and install")
                    }
                }
            },
            dismissButton = {
                Button(onClick = dismiss, modifier = Modifier.tvFocusBorder()) {
                    Text(if (downloading) "Cancel download" else "Close")
                }
            },
        )
    }
}
