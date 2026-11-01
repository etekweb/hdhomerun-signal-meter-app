package net.etekweb.hdhrmeter.ui

import androidx.compose.ui.graphics.Color
import net.etekweb.hdhrmeter.data.SignalReading
import net.etekweb.hdhrmeter.data.rfChannelFromFrequencyHz
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class MeterKind { Strength, Quality, Symbol }

fun formatAirTime(
    startMs: Long,
    stopMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    return "${clock(startMs, zone, formatter)} – ${clock(stopMs, zone, formatter)}"
}

fun formatProgramDetail(subtitle: String?, episode: String?): String? {
    return listOfNotNull(subtitle?.trim()?.ifBlank { null }, episode?.trim()?.ifBlank { null })
        .joinToString(" · ")
        .ifBlank { null }
}

private fun clock(epochMs: Long, zone: ZoneId, formatter: DateTimeFormatter): String {
    return Instant.ofEpochMilli(epochMs)
        .atZone(zone)
        .format(formatter)
        .replace('\u202F', ' ')
        .replace('\u00A0', ' ')
}

fun formatBitrate(bps: Long?): String {
    if (bps == null || bps <= 0L) return "—"
    return if (bps >= 1_000_000L) {
        String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
    } else {
        String.format(Locale.US, "%.0f kbps", bps / 1_000.0)
    }
}

fun formatFps(fps: Float?): String? {
    if (fps == null || fps <= 1f) return null
    val hundredths = (fps * 100f).toInt()
    return if (hundredths % 100 == 0) {
        (hundredths / 100).toString()
    } else {
        String.format(Locale.US, "%.2f", hundredths / 100.0)
    }
}

fun videoCodecLabel(mime: String?, lineup: String?): String? {
    mimeLabel(mime)?.let { return it }
    return prettyCodec(lineup)
}

fun audioCodecLabel(mime: String?, lineup: String?): String? {
    mimeLabel(mime)?.let { return it }
    return prettyCodec(lineup)
}

fun audioLayout(channels: Int?): String? = when (channels) {
    null, 0 -> null
    1 -> "mono"
    2 -> "stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${channels}ch"
}

fun modulationLabel(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return when (raw.lowercase(Locale.US)) {
        "none" -> null
        "8vsb", "vsb8" -> "8VSB"
        "qam256" -> "QAM256"
        "qam64" -> "QAM64"
        "qam" -> "QAM"
        "dvbt" -> "DVB-T"
        "dvbt2", "t2" -> "DVB-T2"
        "dvbc" -> "DVB-C"
        "atsc3" -> "ATSC 3.0"
        else -> raw.uppercase(Locale.US)
    }
}

fun broadcastLabel(reading: SignalReading?, lineupFrequencyHz: Long?): String? {
    val frequency = reading?.frequencyHz ?: lineupFrequencyHz
    val rf = reading?.rfChannel ?: frequency?.let(::rfChannelFromFrequencyHz)
    val parts = mutableListOf<String>()
    when {
        rf != null -> parts += "RF $rf"
        frequency != null -> parts += "${frequency / 1_000_000} MHz"
    }
    modulationLabel(reading?.modulation)?.let { parts += it }
    return parts.joinToString(" · ").ifBlank { null }
}

fun meterColor(percent: Int?, kind: MeterKind): Color {
    val value = percent ?: return Color(0xFF3A4550)
    return when (kind) {
        MeterKind.Strength -> when {
            value >= 75 -> Color(0xFF7EC8E3)
            value >= 50 -> Color(0xFFC9A15B)
            else -> Color(0xFFD46A6A)
        }
        MeterKind.Quality -> when {
            value >= 70 -> Color(0xFF3DDC97)
            value >= 45 -> Color(0xFFF5A524)
            else -> Color(0xFFFF5D5D)
        }
        MeterKind.Symbol -> when {
            value >= 100 -> Color(0xFF3DDC97)
            value >= 90 -> Color(0xFFF5A524)
            else -> Color(0xFFFF5D5D)
        }
    }
}

private fun mimeLabel(mime: String?): String? = when (mime) {
    "video/mpeg2" -> "MPEG-2"
    "video/avc" -> "H.264"
    "video/hevc" -> "HEVC"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/av01" -> "AV1"
    "audio/ac3" -> "AC-3"
    "audio/eac3" -> "E-AC-3"
    "audio/ac4" -> "AC-4"
    "audio/mp4a-latm", "audio/aac" -> "AAC"
    "audio/mpeg", "audio/mpeg-L2" -> "MP2"
    else -> null
}

private fun prettyCodec(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return when (raw.uppercase(Locale.US)) {
        "MPEG2", "MPEG-2" -> "MPEG-2"
        "H264", "AVC", "H.264" -> "H.264"
        "H265", "HEVC", "H.265" -> "HEVC"
        "AC3", "AC-3" -> "AC-3"
        "EAC3", "E-AC-3", "EAC-3" -> "E-AC-3"
        "AC4", "AC-4" -> "AC-4"
        "AAC" -> "AAC"
        else -> raw
    }
}
