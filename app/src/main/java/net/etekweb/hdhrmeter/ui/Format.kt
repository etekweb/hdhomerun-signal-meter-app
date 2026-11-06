package net.etekweb.hdhrmeter.ui

import androidx.compose.ui.graphics.Color
import net.etekweb.hdhrmeter.data.GuideProgram
import net.etekweb.hdhrmeter.data.SignalReading
import net.etekweb.hdhrmeter.data.rfChannelFromFrequencyHz
import net.etekweb.hdhrmeter.player.AudioFamily
import net.etekweb.hdhrmeter.player.AudioReport
import net.etekweb.hdhrmeter.player.audioFamilyOf
import net.etekweb.hdhrmeter.player.audioIsAtmos
import net.etekweb.hdhrmeter.player.heardAudio
import java.time.Instant
import java.time.LocalDate
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

fun formatClockTime(
    epochMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    return clock(epochMs, zone, formatter)
}

fun formatProgramDetail(subtitle: String?, episode: String?): String? {
    return listOfNotNull(subtitle?.trim()?.ifBlank { null }, episode?.trim()?.ifBlank { null })
        .joinToString(" · ")
        .ifBlank { null }
}

/** Episode number, then episode name, when the guide has them. */
fun formatEpisodeSummary(episode: String?, subtitle: String?): String? {
    return listOfNotNull(episode?.trim()?.ifBlank { null }, subtitle?.trim()?.ifBlank { null })
        .joinToString(" · ")
        .ifBlank { null }
}

fun formatGuideDay(
    epochMs: Long,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> DateTimeFormatter.ofPattern("EEE, MMM d", locale).format(date)
    }
}

fun formatGuideWhen(
    startMs: Long,
    stopMs: Long,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    return "${formatGuideDay(startMs, nowMs, zone, locale)} · ${formatAirTime(startMs, stopMs, zone, locale)}"
}

sealed interface ChannelGuideEntry {
    data class Day(val label: String, val dateKey: String) : ChannelGuideEntry
    data class Airing(val program: GuideProgram, val onNow: Boolean) : ChannelGuideEntry
}

/** Day headings plus each airing, in guide order. */
fun channelGuideEntries(
    programs: List<GuideProgram>,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<ChannelGuideEntry> {
    if (programs.isEmpty()) return emptyList()
    val rows = ArrayList<ChannelGuideEntry>(programs.size + 4)
    var lastDate: LocalDate? = null
    for (program in programs) {
        val date = Instant.ofEpochMilli(program.startEpochMs).atZone(zone).toLocalDate()
        if (date != lastDate) {
            rows += ChannelGuideEntry.Day(
                label = formatGuideDay(program.startEpochMs, nowMs, zone, locale),
                dateKey = date.toString(),
            )
            lastDate = date
        }
        val onNow = program.startEpochMs <= nowMs && nowMs < program.stopEpochMs
        rows += ChannelGuideEntry.Airing(program, onNow)
    }
    return rows
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

/** (i) is only added when the stream itself is interlaced. Unknown stays unmarked. */
fun formatPictureSize(width: Int, height: Int, interlaced: Boolean?): String {
    val scan = if (interlaced == true) "(i)" else ""
    return "$width×$height$scan"
}

/** Hardware or software decoding on this device. Unknown until the video stream is known. */
fun formatDecoder(hardware: Boolean?): String = when (hardware) {
    true -> "Hardware"
    false -> "Software"
    null -> "—"
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

fun formatSampleRate(hz: Int?): String? {
    if (hz == null || hz <= 0) return null
    val text = when {
        hz % 1000 == 0 -> (hz / 1000).toString()
        hz % 100 == 0 -> String.format(Locale.US, "%.1f", hz / 1000.0)
        else -> String.format(Locale.US, "%.2f", hz / 1000.0)
    }
    return "$text kHz"
}

/** Signal-tab rows for every audio stream. An empty player list falls back to the lineup codec. */
fun signalAudioRows(tracks: List<AudioReport>, lineup: String?): List<Pair<String, String>> {
    if (tracks.isEmpty()) {
        val label = audioCodecLabel(null, lineup) ?: return emptyList()
        return listOf("Audio" to label)
    }
    val many = tracks.size > 1
    val rows = mutableListOf<Pair<String, String>>()
    tracks.forEachIndexed { index, track ->
        val number = index + 1
        val audioLabel = if (many) "Audio $number" else "Audio"
        rows += audioLabel to audioSummary(track, lineup, markPlaying = many)
        audioCodecDetail(track)?.let { detail ->
            rows += (if (many) "Codec $number" else "Codec") to detail
        }
    }
    lineupRow(lineup, tracks)?.let { rows += it }
    return rows
}

fun audioDisplayName(track: AudioReport): String? {
    val family = audioFamilyOf(track) ?: return null
    val atmos = audioIsAtmos(track)
    return when (family) {
        AudioFamily.Ac3 -> "AC-3"
        AudioFamily.Eac3 -> if (atmos) "Dolby Atmos (E-AC-3)" else "Dolby Digital Plus (E-AC-3)"
        AudioFamily.Ac4 -> if (atmos) "Dolby Atmos (AC-4)" else "Dolby AC-4"
        AudioFamily.TrueHd -> "Dolby TrueHD"
        AudioFamily.DtsHd -> "DTS-HD"
        AudioFamily.Dts -> "DTS"
        AudioFamily.Aac -> "AAC"
        AudioFamily.Mp2 -> "MP2"
        AudioFamily.Mp3 -> "MP3"
    }
}

fun audioOutputNote(passthrough: Boolean, codec: String): String {
    return if (passthrough) {
        "Passthrough. This device can send $codec to the TV."
    } else {
        "Decoded here. This device decodes $codec before it reaches the TV."
    }
}

fun audioOutputNoteFor(
    tracks: List<AudioReport>,
    lineup: String?,
    passthrough: Boolean?,
): String? {
    if (passthrough == null) return null
    val track = heardAudio(tracks) ?: return null
    val codec = audioDisplayName(track)
        ?: audioCodecLabel(null, lineup)
        ?: track.codec?.trim()?.ifBlank { null }
        ?: return null
    return audioOutputNote(passthrough, codec)
}

private fun audioSummary(track: AudioReport, lineup: String?, markPlaying: Boolean): String {
    val name = audioDisplayName(track)
        ?: audioCodecLabel(null, lineup)
        ?: track.codec?.trim()?.ifBlank { null }
        ?: track.originalCodec?.trim()?.ifBlank { null }
        ?: "Audio"
    val parts = mutableListOf(name)
    audioLayout(track.channels)?.let { parts += it }
    formatSampleRate(track.sampleRateHz)?.let { parts += it }
    track.bitrateBps?.takeIf { it > 0 }?.let { parts += formatBitrate(it) }
    audioLanguage(track.language)?.let { parts += it }
    val note = track.description?.trim()?.ifBlank { null }
    if (note != null && audioFamilyOf(note) == null && !sameLanguage(note, track.language)) {
        parts += note
    }
    if (markPlaying && track.playing) parts += "playing"
    return parts.joinToString(" · ")
}

private fun audioCodecDetail(track: AudioReport): String? {
    val parts = mutableListOf<String>()
    fun add(text: String) {
        if (parts.none { it.equals(text, ignoreCase = true) }) parts += text
    }
    track.fourcc?.trim()?.ifBlank { null }?.let { add(it) }
    track.codec?.trim()?.ifBlank { null }?.let { add(it) }
    val original = track.originalCodec?.trim()?.ifBlank { null }
    if (original != null && !original.equals(track.codec?.trim(), ignoreCase = true)) {
        add("original $original")
    }
    val note = track.description?.trim()?.ifBlank { null }
    if (note != null && audioFamilyOf(note) != null) add(note)
    track.profile?.takeIf { it > 0 }?.let { parts += "profile $it" }
    return parts.joinToString(" · ").ifBlank { null }
}

private fun lineupRow(lineup: String?, tracks: List<AudioReport>): Pair<String, String>? {
    val pretty = prettyCodec(lineup) ?: return null
    val families = tracks.mapNotNull { audioFamilyOf(it) }
    if (families.isEmpty() || families.any { lineupMatches(it, pretty) }) return null
    return "Lineup" to pretty
}

private fun lineupMatches(family: AudioFamily, lineupLabel: String): Boolean {
    return when (family) {
        AudioFamily.Ac3 -> lineupLabel == "AC-3"
        AudioFamily.Eac3 -> lineupLabel == "E-AC-3"
        AudioFamily.Ac4 -> lineupLabel == "AC-4"
        AudioFamily.Aac -> lineupLabel == "AAC"
        AudioFamily.Mp2 -> lineupLabel == "MP2"
        AudioFamily.Mp3 -> lineupLabel.equals("MP3", ignoreCase = true)
        AudioFamily.Dts -> lineupLabel.equals("DTS", ignoreCase = true)
        AudioFamily.DtsHd -> lineupLabel.equals("DTS-HD", ignoreCase = true)
        AudioFamily.TrueHd -> lineupLabel.equals("TrueHD", ignoreCase = true)
    }
}

private fun audioLanguage(code: String?): String? {
    val key = code?.trim()?.lowercase(Locale.US)?.ifBlank { null } ?: return null
    if (key == "und" || key == "??" || key == "unknown" || key == "none") return null
    return when (key) {
        "en", "eng" -> "English"
        "es", "spa" -> "Spanish"
        "fr", "fre", "fra" -> "French"
        "de", "ger", "deu" -> "German"
        "it", "ita" -> "Italian"
        "pt", "por" -> "Portuguese"
        "ja", "jpn" -> "Japanese"
        "ko", "kor" -> "Korean"
        "zh", "zho", "chi" -> "Chinese"
        "ru", "rus" -> "Russian"
        "hi", "hin" -> "Hindi"
        "ar", "ara" -> "Arabic"
        "pl", "pol" -> "Polish"
        "nl", "nld", "dut" -> "Dutch"
        "sv", "swe" -> "Swedish"
        "no", "nor" -> "Norwegian"
        "da", "dan" -> "Danish"
        "fi", "fin" -> "Finnish"
        "tr", "tur" -> "Turkish"
        "vi", "vie" -> "Vietnamese"
        "th", "tha" -> "Thai"
        else -> code.trim()
    }
}

private fun sameLanguage(note: String, code: String?): Boolean {
    val language = audioLanguage(code) ?: return false
    return note.equals(language, ignoreCase = true) || note.equals(code?.trim(), ignoreCase = true)
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

/**
 * RF channel for the signal tab, without an "RF" prefix.
 * The tuner `ch=` value wins. Otherwise a US broadcast center frequency is mapped
 * to a channel, and anything else is shown as MHz. Modulation is appended when known.
 */
fun rfChannelText(reading: SignalReading?, lineupFrequencyHz: Long?): String? {
    val frequency = reading?.frequencyHz ?: lineupFrequencyHz
    val rf = reading?.rfChannel ?: frequency?.let(::rfChannelFromFrequencyHz)
    val parts = mutableListOf<String>()
    when {
        rf != null -> parts += rf.toString()
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
