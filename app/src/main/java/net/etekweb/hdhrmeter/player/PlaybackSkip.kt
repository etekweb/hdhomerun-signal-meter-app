package net.etekweb.hdhrmeter.player

import java.util.Locale

/** One press of the player −10s or +10s control. */
const val SKIP_STEP_MS = 10_000L

/**
 * A burst of skip taps that has not been applied yet.
 * [anchorMs] is the picture position when the burst started, so later taps
 * add onto the same spot while playback continues.
 */
data class PendingSkip(
    val anchorMs: Long,
    val deltaMs: Long,
) {
    val targetMs: Long get() = anchorMs + deltaMs
}

/**
 * Adds [deltaMs] onto a skip burst.
 * The anchor stays on the first tap. The landing spot is kept inside the
 * seekable window, and a burst that lands back on the anchor is cleared.
 */
fun accumulateSkip(
    pending: PendingSkip?,
    positionMs: Long,
    deltaMs: Long,
    seekStartMs: Long,
    seekEndMs: Long,
): PendingSkip? {
    val end = seekEndMs.coerceAtLeast(seekStartMs)
    val start = seekStartMs.coerceAtMost(end)
    val anchor = pending?.anchorMs ?: positionMs
    val requested = (pending?.deltaMs ?: 0L) + deltaMs
    val target = (anchor + requested).coerceIn(start, end)
    val shown = target - anchor
    if (shown == 0L) return null
    return PendingSkip(anchor, shown)
}

/** Signed skip amount, such as "−30s" or "+1:30". */
fun formatSkipDelta(deltaMs: Long): String {
    val sign = if (deltaMs < 0) "\u2212" else "+"
    val magnitude = kotlin.math.abs(deltaMs)
    val totalSeconds = when {
        magnitude == 0L -> 0L
        magnitude < 1_000L -> 1L
        else -> (magnitude + 500L) / 1_000L
    }
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes == 0L) {
        "$sign${seconds}s"
    } else {
        String.format(Locale.US, "%s%d:%02d", sign, minutes, seconds)
    }
}
