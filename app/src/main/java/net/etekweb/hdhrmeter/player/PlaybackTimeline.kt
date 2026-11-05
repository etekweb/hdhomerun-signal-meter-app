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

/** A restarted LibVLC clock is still near zero for about this long. */
internal const val FRESH_MEDIA_MS = 1_200L

/**
 * How long to keep the seek target on screen when LibVLC never restarts its clock.
 * The picture is already moving by then, so the playhead has to follow it.
 */
internal const val STREAM_HOLD_MS = 2_000L

private const val MIN_RATE_BYTES_PER_MS = 10.0
private const val MAX_RATE_BYTES_PER_MS = 8_000.0
private const val READ_AHEAD_MS = 1_200L
private const val MAX_STEP_BYTES = 2_000_000L

/**
 * Picture time since this stream opened.
 * LibVLC's media clock on a live HTTP response often stays at 0, sticks, or
 * never comes back through zero after a seek. While the picture is advancing,
 * a stuck media clock is replaced with time actually spent playing.
 */
internal class PictureClock {
    var elapsedMs: Long = 0L
        private set

    private var started = false
    private var followAbsolute = false
    private var originMedia = 0L
    private var lastWall = 0L
    private var lastMedia = 0L

    fun clear() {
        elapsedMs = 0L
        started = false
        followAbsolute = false
        originMedia = 0L
        lastWall = 0L
        lastMedia = 0L
    }

    /** [adoptMedia] is for a clock that restarted near zero. Otherwise follow steps. */
    fun start(nowMs: Long, mediaTimeMs: Long, adoptMedia: Boolean) {
        val media = mediaTimeMs.coerceAtLeast(0L)
        started = true
        followAbsolute = adoptMedia
        originMedia = if (adoptMedia) 0L else media
        lastWall = nowMs
        lastMedia = media
        elapsedMs = if (adoptMedia) media else 0L
    }

    fun advance(nowMs: Long, mediaTimeMs: Long, advancing: Boolean): Long {
        if (!started) start(nowMs, mediaTimeMs, adoptMedia = mediaTimeMs in 0L..FRESH_MEDIA_MS)
        val media = mediaTimeMs.coerceAtLeast(0L)
        val dWall = (nowMs - lastWall).coerceAtLeast(0L)
        val dMedia = media - lastMedia
        lastWall = nowMs
        lastMedia = media
        if (!advancing || dWall == 0L) return elapsedMs
        val stepSane = dMedia > 0L && dMedia <= dWall + 1_500L
        if (followAbsolute && stepSane) {
            elapsedMs = (media - originMedia).coerceAtLeast(0L)
        } else if (stepSane) {
            elapsedMs += dMedia
        } else if (dMedia <= -500L) {
            originMedia = media
            followAbsolute = media <= FRESH_MEDIA_MS
            if (followAbsolute) elapsedMs = media
        } else {
            elapsedMs += dWall
        }
        return elapsedMs
    }
}

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
    private var openedAtMs = 0L
    private var slopeBytes = -1L
    private var slopeMedia = -1L
    private var slopeWall = -1L
    private var accountedBytes = 0L
    private var burstBytes = 0L
    private val picture = PictureClock()

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
        openedAtMs = 0L
        clearRead()
        clearSlope()
        picture.clear()
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
        openedAtMs = 0L
        clearRead()
        clearSlope()
        picture.clear()
    }

    /** Ignore player time until this seek's stream actually restarts. */
    fun armHold(wallMs: Long, generation: Int) {
        hold = true
        heldMs = wallMs
        holdGeneration = generation
        // The previous open's timestamp must not release this hold early.
        openedAtMs = 0L
        picture.clear()
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
        this.openedAtMs = openedAtMs
        clearRead()
        if (!rated) clearSlope()
        if (joinLive) {
            anchorByte = actualByte
            anchorMs = openedAtMs
            // Keep the live byte paired with the time it was actually seen.
            // Leaving the probe time in place makes later seeks overshoot.
            if (actualByte > initialLiveByte) {
                initialLiveByte = actualByte
                initialLiveMs = openedAtMs
                if (rated && bytesPerMs > 0.0) adoptRate(bytesPerMs)
            }
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
     * after that burst. Later steady playback replaces a bad sample. A single
     * dumped burst does not, because that would place every scrub past the live edge.
     */
    fun noteBytes(
        bytesSent: Long,
        mediaTimeMs: Long,
        nowMs: Long,
        advancing: Boolean = false,
    ) {
        if (!opened || bytesSent <= 0L) return
        rememberRead(bytesSent)
        if (!rated) {
            measureInitial(bytesSent, mediaTimeMs, nowMs)
            return
        }
        if (advancing) adoptObservedRate()
    }

    fun positionMs(mediaTimeMs: Long, nowMs: Long, advancing: Boolean = true): Long {
        val media = mediaTimeMs.coerceAtLeast(0L)
        if (hold) {
            val openedThis = opened && openedGeneration == holdGeneration
            val fresh = media in 0L..FRESH_MEDIA_MS
            val timedOut = openedThis && openedAtMs > 0L && nowMs >= openedAtMs + STREAM_HOLD_MS
            if (!(openedThis && (fresh || timedOut))) return heldMs
            hold = false
            picture.start(nowMs, media, adoptMedia = fresh)
            return anchorMs + picture.elapsedMs
        }
        if (!opened) return nowMs
        picture.advance(nowMs, media, advancing)
        return anchorMs + picture.elapsedMs
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
        val rate = bytesPerMs
        val behind = (liveMs - wallMs).coerceAtLeast(0L)
        val distance = (behind.toDouble() * rate).toLong()
        val (estimated, estimatedLive) = locate(wallMs, liveMs, rate)
        val liveCap = observedLiveByte?.coerceAtLeast(bufferStartByte)
        val byte = if (liveCap != null && estimated >= liveCap) liveCap - distance else estimated
        val ceiling = if (liveCap != null) minOf(estimatedLive, liveCap) else estimatedLive
        return byte.coerceIn(bufferStartByte, ceiling.coerceAtLeast(bufferStartByte))
    }

    /**
     * After the picture has been playing, seek from the bytes actually read.
     * A rate frozen at startup is otherwise applied to the whole watch, so a
     * forward skip lands back at the byte where this segment opened.
     */
    private fun locate(wallMs: Long, liveMs: Long, rate: Double): Pair<Long, Long> {
        if (picture.elapsedMs > 0L) {
            val pictureWall = anchorMs + picture.elapsedMs
            val origin = pictureByte(rate)
            val estimated = origin + ((wallMs - pictureWall).toDouble() * rate).toLong()
            val estimatedLive = origin +
                ((liveMs - pictureWall).coerceAtLeast(0L).toDouble() * rate).toLong()
            return estimated to estimatedLive
        }
        val estimatedLive = initialLiveByte +
            ((liveMs - initialLiveMs).coerceAtLeast(0L).toDouble() * rate).toLong()
        val estimated = estimatedLive - ((liveMs - wallMs).coerceAtLeast(0L).toDouble() * rate).toLong()
        return estimated to estimatedLive
    }

    private fun pictureByte(rate: Double): Long {
        val elapsed = picture.elapsedMs
        val predicted = anchorByte + (elapsed.toDouble() * rate).toLong()
        val played = playedBytes()
        if (elapsed < 2_000L || played <= 0L) return predicted.coerceAtLeast(bufferStartByte)
        val read = anchorByte + played
        val observed = played.toDouble() / elapsed.toDouble()
        if (observed < MIN_RATE_BYTES_PER_MS || observed > MAX_RATE_BYTES_PER_MS) {
            return predicted.coerceAtLeast(bufferStartByte)
        }
        val cache = (observed * READ_AHEAD_MS).toLong()
        val gap = read - predicted
        if (gap > cache) return (read - cache).coerceAtLeast(bufferStartByte)
        return predicted.coerceIn(bufferStartByte, read.coerceAtLeast(bufferStartByte))
    }

    private fun measureInitial(bytesSent: Long, mediaTimeMs: Long, nowMs: Long) {
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
            // Media time stuck between those two windows used to block every seek.
            dWall >= 3_000L -> dWall
            else -> return
        }
        val rate = dBytes.toDouble() / dTime.toDouble()
        if (rate < MIN_RATE_BYTES_PER_MS || rate > MAX_RATE_BYTES_PER_MS || rate.isNaN()) {
            // A stall samples a few bytes over a long wall interval. Keep waiting.
            slopeBytes = bytesSent
            slopeMedia = mediaTimeMs.coerceAtLeast(0L)
            slopeWall = nowMs
            return
        }
        adoptRate(rate)
    }

    /** Replace a startup sample once real playback has shown the bitrate. */
    private fun adoptObservedRate() {
        val elapsed = picture.elapsedMs
        val played = playedBytes()
        if (elapsed < 8_000L || played <= 0L || bytesPerMs <= 0.0) return
        val raw = played.toDouble() / elapsed.toDouble()
        if (raw < MIN_RATE_BYTES_PER_MS || raw > MAX_RATE_BYTES_PER_MS) return
        val cacheBytes = (raw * READ_AHEAD_MS).toLong()
        val netBytes = (played - cacheBytes).coerceAtLeast(played / 2)
        val netElapsed = (elapsed - READ_AHEAD_MS).coerceAtLeast(elapsed / 2)
        val observed = netBytes.toDouble() / netElapsed.toDouble()
        if (observed < MIN_RATE_BYTES_PER_MS || observed > MAX_RATE_BYTES_PER_MS) return
        if (kotlin.math.abs(observed - bytesPerMs) < bytesPerMs * 0.08) return
        adoptRate(observed)
    }

    private fun adoptRate(rate: Double) {
        bytesPerMs = rate
        val span = (initialLiveByte - bufferStartByte).coerceAtLeast(0L)
        bufferStartMs = (initialLiveMs - (span / rate).toLong()).coerceAtMost(initialLiveMs)
        rated = true
    }

    private fun rememberRead(bytesSent: Long) {
        if (accountedBytes <= 0L || bytesSent < accountedBytes) {
            accountedBytes = bytesSent
            burstBytes = 0L
            return
        }
        val step = bytesSent - accountedBytes
        accountedBytes = bytesSent
        if (step > MAX_STEP_BYTES) burstBytes += step - MAX_STEP_BYTES
    }

    private fun playedBytes(): Long = (accountedBytes - burstBytes).coerceAtLeast(0L)

    private fun clearRead() {
        accountedBytes = 0L
        burstBytes = 0L
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
