package net.etekweb.hdhrmeter.player

import net.etekweb.hdhrmeter.data.TIMESHIFT_LIVE_BYTE

/**
 * Where "jump to the beginning" lands.
 *
 * The available recording is the shorter of the current program and the player
 * buffer. Both end at the live edge, so the shorter one starts later: the
 * program's start when the buffer already contains it, and the oldest buffered
 * moment when tuning in missed the opening.
 */
internal fun availableRecordingStartMs(programStartMs: Long?, bufferStartMs: Long): Long {
    if (programStartMs == null) return bufferStartMs
    return maxOf(programStartMs, bufferStartMs)
}

internal data class PlaybackBar(
    val startMs: Long,
    val endMs: Long,
    val positionMs: Long,
    val liveMs: Long,
    val seekStartMs: Long,
    val seekEndMs: Long,
    val seekable: Boolean,
) {
    fun fraction(ms: Long): Float {
        val span = (endMs - startMs).coerceAtLeast(1L)
        return ((ms - startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    /** A fraction along the bar, clamped to the span that can actually be played. */
    fun wallAt(fraction: Float): Long {
        val span = (endMs - startMs).coerceAtLeast(1L)
        val raw = startMs + (span * fraction.toDouble().coerceIn(0.0, 1.0)).toLong()
        return raw.coerceIn(seekStartMs, seekEndMs.coerceAtLeast(seekStartMs))
    }
}

internal fun playbackBar(
    bufferStartMs: Long,
    positionMs: Long,
    liveMs: Long,
    programStartMs: Long?,
    programStopMs: Long?,
    seekable: Boolean,
): PlaybackBar {
    val seekStart = availableRecordingStartMs(programStartMs, bufferStartMs)
    val start = programStartMs ?: minOf(bufferStartMs, positionMs)
    val end = when {
        programStopMs != null && programStopMs > start -> maxOf(programStopMs, liveMs, positionMs)
        else -> maxOf(liveMs, positionMs, start + 1_000L)
    }
    val barEnd = maxOf(end, start + 1L)
    return PlaybackBar(
        startMs = start,
        endMs = barEnd,
        positionMs = positionMs.coerceIn(start, barEnd),
        liveMs = liveMs.coerceIn(start, barEnd),
        seekStartMs = seekStart.coerceIn(start, barEnd),
        seekEndMs = liveMs.coerceIn(start, barEnd),
        seekable = seekable,
    )
}

internal data class ShiftTarget(
    val range: String,
    val joinLive: Boolean,
    val requestedWallMs: Long,
)

/**
 * Maps the record engine's byte buffer onto wall-clock time.
 * The first few seconds of playback measure bytes per millisecond.
 * Until that rate exists, only the buffer start and the live edge can be sought.
 */
internal class TimeshiftMap {
    var bufferStartByte: Long = 0L
        private set
    var bufferStartMs: Long = 0L
        private set
    var bytesPerMs: Double = 0.0
        private set

    private var anchorByte: Long = 0L
    private var anchorMs: Long = 0L
    private var initialLiveByte: Long = 0L
    private var initialLiveMs: Long = 0L
    private var rated = false
    private var opened = false
    private var hold = false
    private var heldMs = 0L
    private var holdGeneration = -1
    private var openedGeneration = -1
    private var slopeBytes = -1L
    private var slopeMedia = -1L
    private var slopeWall = -1L

    fun reset(nowMs: Long) {
        bufferStartByte = 0L
        bufferStartMs = nowMs
        bytesPerMs = 0.0
        anchorByte = 0L
        anchorMs = nowMs
        initialLiveByte = 0L
        initialLiveMs = nowMs
        rated = false
        opened = false
        hold = false
        heldMs = nowMs
        holdGeneration = -1
        openedGeneration = -1
        clearSlope()
    }

    /** Both edges were sampled at [nowMs], before the player connection. */
    fun noteEdges(oldestByte: Long, liveByte: Long, nowMs: Long) {
        bufferStartByte = oldestByte.coerceAtLeast(0L)
        initialLiveByte = liveByte.coerceAtLeast(bufferStartByte)
        initialLiveMs = nowMs
        anchorByte = initialLiveByte
        anchorMs = nowMs
        bufferStartMs = nowMs
        rated = false
        opened = false
        clearSlope()
    }

    /** Ignore player time until this seek's stream actually restarts at zero. */
    fun armHold(wallMs: Long, generation: Int) {
        hold = true
        heldMs = wallMs
        holdGeneration = generation
    }

    fun noteOpened(
        actualByte: Long,
        joinLive: Boolean,
        requestedWallMs: Long,
        openedAtMs: Long,
        generation: Int,
    ) {
        opened = true
        openedGeneration = generation
        if (!rated) clearSlope()
        if (joinLive) {
            anchorByte = actualByte
            anchorMs = openedAtMs
            if (actualByte > initialLiveByte) initialLiveByte = actualByte
            return
        }
        val trimmed = bytesPerMs > 0.0 &&
            actualByte > bufferStartByte &&
            requestedWallMs <= bufferStartMs + 1_500L
        if (trimmed) {
            val removed = actualByte - bufferStartByte
            bufferStartMs += (removed / bytesPerMs).toLong()
            bufferStartByte = actualByte
            anchorByte = actualByte
            anchorMs = bufferStartMs
            return
        }
        anchorByte = actualByte
        anchorMs = wallOf(actualByte, requestedWallMs)
    }

    /**
     * The first samples include LibVLC's cache burst, so the rate is the slope
     * after that burst. It is frozen with the buffer start. Updating only the
     * rate later maps every scrub past the live edge.
     */
    fun noteBytes(bytesSent: Long, mediaTimeMs: Long, nowMs: Long) {
        if (!opened || bytesSent <= 0L || rated) return
        if (slopeMedia >= 0L && mediaTimeMs + 500L < slopeMedia) clearSlope()
        if (slopeWall < 0L) {
            slopeWall = nowMs
            slopeMedia = mediaTimeMs.coerceAtLeast(0L)
            if (mediaTimeMs >= 1_500L) slopeBytes = bytesSent
            return
        }
        val warmed = mediaTimeMs >= 1_500L || nowMs - slopeWall >= 2_000L
        if (slopeBytes < 0L) {
            if (!warmed) return
            slopeBytes = bytesSent
            slopeMedia = mediaTimeMs.coerceAtLeast(0L)
            slopeWall = nowMs
            return
        }
        val dBytes = bytesSent - slopeBytes
        val dMedia = mediaTimeMs - slopeMedia
        val dWall = (nowMs - slopeWall).coerceAtLeast(0L)
        if (dBytes <= 0L) return
        val dTime = when {
            dMedia >= 2_000L -> dMedia
            dWall >= 2_000L && dMedia < 1_000L -> dWall
            else -> return
        }
        val rate = dBytes.toDouble() / dTime.toDouble()
        if (rate <= 0.0 || rate.isNaN()) return
        bytesPerMs = rate
        val span = (initialLiveByte - bufferStartByte).coerceAtLeast(0L)
        bufferStartMs = (initialLiveMs - (span / rate).toLong()).coerceAtMost(initialLiveMs)
        rated = true
    }

    fun positionMs(mediaTimeMs: Long, nowMs: Long): Long {
        if (hold) {
            val restarted = openedGeneration == holdGeneration && mediaTimeMs in 0L..1_200L
            if (!restarted) return heldMs
            hold = false
        }
        if (!opened) return nowMs
        return anchorMs + mediaTimeMs.coerceAtLeast(0L)
    }

    fun shiftTarget(
        wallMs: Long,
        joinLive: Boolean,
        liveMs: Long,
        observedLiveByte: Long? = null,
    ): ShiftTarget? {
        if (joinLive) {
            return ShiftTarget("bytes=$TIMESHIFT_LIVE_BYTE-", true, liveMs)
        }
        // Wall times in a historical buffer are unknown until the rate exists.
        // The oldest byte can belong to the previous program, so it is not a
        // stand-in for "somewhere before live".
        if (!rated && initialLiveByte > bufferStartByte) return null
        if (wallMs <= bufferStartMs + 750L) {
            return ShiftTarget("bytes=${bufferStartByte.coerceAtLeast(0L)}-", false, bufferStartMs)
        }
        val byte = byteFor(wallMs, liveMs, observedLiveByte) ?: return null
        return ShiftTarget("bytes=$byte-", false, wallMs)
    }

    /**
     * Beginning of the show, or the oldest buffered byte when that opening is
     * already gone. With no guide program the oldest byte is safe immediately.
     * A program inside a historical buffer waits for the rate, so the seek
     * cannot land before the show.
     */
    fun availableStartTarget(
        programStartMs: Long?,
        liveMs: Long,
        observedLiveByte: Long? = null,
    ): ShiftTarget? {
        val hasHistory = initialLiveByte > bufferStartByte
        if (!rated && hasHistory) {
            if (programStartMs != null) return null
            return ShiftTarget("bytes=${bufferStartByte.coerceAtLeast(0L)}-", false, bufferStartMs)
        }
        val wall = availableRecordingStartMs(programStartMs, bufferStartMs)
        return shiftTarget(wall, joinLive = false, liveMs, observedLiveByte)
    }

    /**
     * [observedLiveByte] is the end of the file while the picture is still live:
     * the byte the relay opened at, plus everything it has read since.
     * The frozen rate runs ahead of that file. A short step back is then placed
     * past the real end, and the record engine clamps the range to the live edge.
     */
    private fun byteFor(wallMs: Long, liveMs: Long, observedLiveByte: Long?): Long? {
        if (bytesPerMs <= 0.0) return null
        val estimatedLive = initialLiveByte +
            ((liveMs - initialLiveMs).coerceAtLeast(0L).toDouble() * bytesPerMs).toLong()
        val behind = (liveMs - wallMs).coerceAtLeast(0L)
        val distance = (behind.toDouble() * bytesPerMs).toLong()
        val estimated = estimatedLive - distance
        val liveCap = observedLiveByte?.coerceAtLeast(bufferStartByte)
        val byte = if (liveCap != null && estimated >= liveCap) liveCap - distance else estimated
        val ceiling = if (liveCap != null) minOf(estimatedLive, liveCap) else estimatedLive
        return byte.coerceIn(bufferStartByte, ceiling.coerceAtLeast(bufferStartByte))
    }

    private fun clearSlope() {
        slopeBytes = -1L
        slopeMedia = -1L
        slopeWall = -1L
    }

    private fun wallOf(byte: Long, requestedWallMs: Long): Long {
        if (bytesPerMs <= 0.0) return requestedWallMs.coerceAtLeast(bufferStartMs)
        val delta = (byte - bufferStartByte).coerceAtLeast(0L)
        return bufferStartMs + (delta / bytesPerMs).toLong()
    }
}
