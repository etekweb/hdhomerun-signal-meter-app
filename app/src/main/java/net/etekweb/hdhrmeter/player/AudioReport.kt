package net.etekweb.hdhrmeter.player

import java.util.Locale

/**
 * One audio elementary stream from the player.
 * [codec] and [originalCodec] are LibVLC's descriptions. [fourcc] is the raw code.
 */
data class AudioReport(
    val id: Int = -1,
    val codec: String? = null,
    val originalCodec: String? = null,
    val fourcc: String? = null,
    val channels: Int? = null,
    val sampleRateHz: Int? = null,
    val bitrateBps: Long? = null,
    val language: String? = null,
    val description: String? = null,
    val profile: Int? = null,
    val playing: Boolean = false,
)

enum class AudioFamily {
    Ac3,
    Eac3,
    Ac4,
    Aac,
    Mp2,
    Mp3,
    Dts,
    DtsHd,
    TrueHd,
}

/** FFmpeg's E-AC-3 profile for Dolby Atmos JOC, which LibVLC copies onto the track. */
const val EAC3_ATMOS_PROFILE = 30

/**
 * Dolby Digital Plus and Atmos are more specific than the AC-3 decoder name.
 * VLC often describes the decoder as "A52 Audio (aka AC3)" while the stream is E-AC-3,
 * and the HDHomeRun lineup says AC3 for both.
 */
fun audioFamilyOf(track: AudioReport?): AudioFamily? {
    if (track == null) return null
    return listOfNotNull(
        audioFamilyOf(track.fourcc),
        audioFamilyOf(track.originalCodec),
        audioFamilyOf(track.codec),
        audioFamilyOf(track.description),
    ).maxByOrNull { it.rank() }
}

fun audioFamilyOf(raw: String?): AudioFamily? {
    val text = raw?.trim()?.lowercase(Locale.US)?.ifBlank { null } ?: return null
    val compact = buildString(text.length) {
        text.forEach { char ->
            if (char.isLetterOrDigit()) append(char)
        }
    }
    return when {
        "truehd" in compact || "mlpa" in compact || compact == "mlp" -> AudioFamily.TrueHd
        "ac4" in compact -> AudioFamily.Ac4
        "eac3" in compact || compact == "ec3" || "a52b" in compact ||
            "dolbydigitalplus" in compact || "digitalplus" in compact ||
            "ddplus" in compact || "e-ac" in text || "dd+" in text -> AudioFamily.Eac3
        "dtshd" in compact || "dtsx" in compact || "dts-hd" in text -> AudioFamily.DtsHd
        "dts" in compact -> AudioFamily.Dts
        "a52" in compact || "ac3" in compact || "dolbydigital" in compact -> AudioFamily.Ac3
        "mp4a" in compact || "aac" in compact -> AudioFamily.Aac
        "mpegaudiolayer3" in compact || compact == "mp3" || compact.startsWith("mp3") -> AudioFamily.Mp3
        "mpegaudiolayer2" in compact || "mp2" in compact || "mpga" in compact -> AudioFamily.Mp2
        else -> null
    }
}

fun audioIsAtmos(track: AudioReport): Boolean {
    val blob = listOfNotNull(track.codec, track.originalCodec, track.description)
        .joinToString(" ")
        .lowercase(Locale.US)
    if ("atmos" in blob || "joc" in blob) return true
    val family = audioFamilyOf(track)
    return track.profile == EAC3_ATMOS_PROFILE &&
        (family == AudioFamily.Eac3 || family == AudioFamily.Ac4)
}

fun heardAudio(tracks: List<AudioReport>): AudioReport? {
    return tracks.firstOrNull { it.playing } ?: tracks.firstOrNull()
}

fun audioMimeOf(track: AudioReport?): String? = when (audioFamilyOf(track)) {
    AudioFamily.Ac3 -> "audio/ac3"
    AudioFamily.Eac3 -> "audio/eac3"
    AudioFamily.Ac4 -> "audio/ac4"
    AudioFamily.Aac -> "audio/mp4a-latm"
    AudioFamily.Mp2, AudioFamily.Mp3 -> "audio/mpeg"
    AudioFamily.Dts, AudioFamily.DtsHd, AudioFamily.TrueHd, null -> null
}

/** LibVLC fourccs are little-endian character codes, for example `eac3` or `a52 `. */
fun fourccText(value: Int): String? {
    if (value == 0) return null
    val chars = CharArray(4)
    for (index in 0 until 4) {
        val byte = (value ushr (8 * index)) and 0xFF
        if (byte !in 32..126) return null
        chars[index] = byte.toChar()
    }
    return String(chars).trim().ifBlank { null }
}

private fun AudioFamily.rank(): Int = when (this) {
    AudioFamily.Ac4 -> 80
    AudioFamily.TrueHd -> 70
    AudioFamily.Eac3 -> 60
    AudioFamily.DtsHd -> 50
    AudioFamily.Dts -> 40
    AudioFamily.Ac3 -> 30
    AudioFamily.Aac -> 20
    AudioFamily.Mp3 -> 10
    AudioFamily.Mp2 -> 10
}
