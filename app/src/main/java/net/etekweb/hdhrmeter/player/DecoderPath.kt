package net.etekweb.hdhrmeter.player

/**
 * Which decoder this playback is using.
 * Software is requested by leaving hardware off, so a visible picture is enough to say so.
 * Hardware is reported only when it was requested and this device has a decoder for the stream.
 */
internal fun usingHardwareDecoder(requested: Boolean, videoPresent: Boolean, available: Boolean): Boolean? {
    if (!videoPresent) return null
    if (!requested) return false
    return available
}

/** LibVLC often puts a description in the codec field and the real code in the fourcc. */
internal fun videoMimeOf(codec: String?, original: String?, fourcc: Int, description: String?): String? {
    for (raw in listOfNotNull(fourccText(fourcc), codec, original, description)) {
        looseVideoMime(raw)?.let { return it }
    }
    return null
}

private fun looseVideoMime(raw: String): String? {
    val letters = raw.lowercase().filter { it.isLetterOrDigit() }
    if (letters.isEmpty()) return null
    return when {
        "hevc" in letters || "h265" in letters || "hvc1" in letters || "hev1" in letters -> "video/hevc"
        "h264" in letters || "avc1" in letters || "avc" in letters -> "video/avc"
        "mpeg2" in letters || "mpeg12" in letters || "mpgv" in letters || "mp2v" in letters || "mp1v" in letters ->
            "video/mpeg2"
        "vp9" in letters || "vp09" in letters -> "video/x-vnd.on2.vp9"
        "av01" in letters || "av1" in letters -> "video/av01"
        else -> null
    }
}
