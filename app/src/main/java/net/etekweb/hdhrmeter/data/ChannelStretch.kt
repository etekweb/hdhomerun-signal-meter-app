package net.etekweb.hdhrmeter.data

import kotlin.math.roundToInt

/**
 * Picture stretch for one channel.
 *
 * [widthPercent] and [heightPercent] are each a percent of the broadcast size.
 * 100 leaves that side unchanged. [enabled] false keeps those percents stored
 * and leaves the picture at the broadcast shape.
 */
data class ChannelStretch(
    val enabled: Boolean = false,
    val widthPercent: Int = 100,
    val heightPercent: Int = 100,
)

/** Smallest and largest slider position, as a percent of the broadcast size. */
internal const val STRETCH_PERCENT_MIN = 50
internal const val STRETCH_PERCENT_MAX = 200

/** Guide number shared by live TV and recordings of the same channel. */
internal fun channelStretchKey(guideNumber: String?): String? {
    val trimmed = guideNumber?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.any { it == '\t' || it == '\n' || it == '\r' }) return null
    return canonicalGuideNumber(trimmed).ifEmpty { null }
}

internal fun formatStretchPercent(percent: Int): String {
    val shown = percent.coerceIn(STRETCH_PERCENT_MIN, STRETCH_PERCENT_MAX)
    return "$shown%"
}

internal fun ChannelStretch.normalized(): ChannelStretch = ChannelStretch(
    enabled = enabled,
    widthPercent = widthPercent.coerceIn(STRETCH_PERCENT_MIN, STRETCH_PERCENT_MAX),
    heightPercent = heightPercent.coerceIn(STRETCH_PERCENT_MIN, STRETCH_PERCENT_MAX),
)

/**
 * Scales applied to the picture. A disabled stretch keeps its percents and
 * reports 1, so the broadcast shape is unchanged.
 */
internal fun ChannelStretch.pictureScale(): Pair<Float, Float> {
    val normal = normalized()
    return if (normal.enabled) {
        normal.widthPercent / 100f to normal.heightPercent / 100f
    } else {
        1f to 1f
    }
}

internal fun stretchFor(guideNumber: String?, stored: Map<String, ChannelStretch>): ChannelStretch {
    val key = channelStretchKey(guideNumber) ?: return ChannelStretch()
    return stored[key]?.normalized() ?: ChannelStretch()
}

internal fun encodeChannelStretches(stretches: Map<String, ChannelStretch>): String {
    return stretches.entries
        .mapNotNull { (raw, stretch) ->
            val key = channelStretchKey(raw) ?: return@mapNotNull null
            val normal = stretch.normalized()
            val flag = if (normal.enabled) "1" else "0"
            "$key\t$flag\t${normal.widthPercent}\t${normal.heightPercent}"
        }
        .sorted()
        .joinToString("\n")
}

internal fun decodeChannelStretches(raw: String?): Map<String, ChannelStretch> {
    if (raw.isNullOrBlank()) return emptyMap()
    val out = linkedMapOf<String, ChannelStretch>()
    for (line in raw.lineSequence()) {
        if (line.isBlank()) continue
        val parts = line.split('\t')
        if (parts.size != 4) continue
        val key = channelStretchKey(parts[0]) ?: continue
        val width = parts[2].toIntOrNull() ?: continue
        val height = parts[3].toIntOrNull() ?: continue
        out[key] = ChannelStretch(
            enabled = parts[1] == "1",
            widthPercent = width,
            heightPercent = height,
        ).normalized()
    }
    return out
}

/** Nearest stored percent for a slider position. */
internal fun stretchPercent(position: Float): Int {
    if (!position.isFinite()) return 100
    return position.roundToInt().coerceIn(STRETCH_PERCENT_MIN, STRETCH_PERCENT_MAX)
}
