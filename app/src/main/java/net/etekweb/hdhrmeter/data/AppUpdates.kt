package net.etekweb.hdhrmeter.data

import android.content.Context
import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val repository = "etekweb/hdhomerun-signal-meter-app"
private const val apkName = "app-release.apk"
private val releaseTag = Regex("v?(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,2})(?:\\.(0|[1-9][0-9]{0,2}))?")
private val sha256Digest = Regex("sha256:[0-9a-f]{64}")

internal data class AppUpdate(
    val tag: String,
    val size: Long,
    val digest: String,
) {
    val version: String get() = tag.removePrefix("v")
    val downloadUrl: String get() = "https://github.com/$repository/releases/download/$tag/$apkName"
}

// Use the same numeric ordering as the tagged Gradle builds. A local 1.0 build has code 1.
internal fun taggedVersionCode(tag: String): Long? {
    val match = releaseTag.matchEntire(tag) ?: return null
    val (major, minor, patch) = match.destructured
    val code = major.toLong() * 1_000_000 + minor.toInt() * 1_000 + (patch.toIntOrNull() ?: 0) + 1
    return code.takeIf { it in 1_000_001L..2_100_000_000L }
}

internal fun parseAppUpdate(release: JSONObject, installedCode: Long): AppUpdate? {
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    val tag = release.getString("tag_name")
    val code = taggedVersionCode(tag) ?: throw IOException("GitHub returned an unsupported release version.")
    if (code <= installedCode) return null

    val assets = release.getJSONArray("assets")
    for (index in 0 until assets.length()) {
        val asset = assets.getJSONObject(index)
        if (asset.optString("name") != apkName || asset.optString("state") != "uploaded") continue
        val size = asset.optLong("size")
        val digest = asset.optString("digest")
        if (size > 0 && sha256Digest.matches(digest)) return AppUpdate(tag, size, digest)
    }
    throw IOException("The latest GitHub release has no verified APK to download.")
}

@Suppress("DEPRECATION")
internal fun installedApp(context: Context): PackageInfo =
    context.packageManager.getPackageInfo(context.packageName, 0)

internal suspend fun checkAppUpdate(context: Context): AppUpdate? = withContext(Dispatchers.IO) {
    val connection = updateConnection("https://api.github.com/repos/$repository/releases/latest")
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    try {
        when (val status = connection.responseCode) {
            404 -> null // GitHub returns 404 when the public repository has no published release.
            200 -> {
                val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                parseAppUpdate(release, PackageInfoCompat.getLongVersionCode(installedApp(context)))
            }
            else -> throw IOException("GitHub returned HTTP $status. Try again later.")
        }
    } finally {
        connection.disconnect()
    }
}

internal suspend fun downloadAppUpdate(
    context: Context,
    update: AppUpdate,
    onProgress: (Int) -> Unit,
): File = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "updates")
    if (!directory.isDirectory && !directory.mkdirs()) {
        throw IOException("Could not create storage for the update.")
    }
    val apk = File(directory, apkName)
    val temporary = File.createTempFile("download-", ".apk", directory)
    try {
        val connection = updateConnection(update.downloadUrl)
        try {
            val status = connection.responseCode
            if (status != 200) throw IOException("The APK download returned HTTP $status.")
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var percent = 0
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > update.size) throw IOException("The APK exceeded its expected size.")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        val nextPercent = (downloaded * 100 / update.size).toInt()
                        if (nextPercent != percent) {
                            percent = nextPercent
                            withContext(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                }
            }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            if (downloaded != update.size || update.digest != "sha256:$checksum") {
                throw IOException("The APK failed its download integrity check.")
            }
        } finally {
            connection.disconnect()
        }

        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(temporary.absolutePath, 0)
        if (archive == null || archive.packageName != context.packageName ||
            archive.versionName != update.version ||
            PackageInfoCompat.getLongVersionCode(archive) != taggedVersionCode(update.tag)
        ) {
            throw IOException("The APK does not match the release version or this app.")
        }
        ensureActive()
        Files.move(temporary.toPath(), apk.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        apk
    } finally {
        temporary.delete()
    }
}

private fun updateConnection(url: String): HttpsURLConnection =
    (URL(url).openConnection() as HttpsURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "HDHomeRun-Signal-Meter")
    }
