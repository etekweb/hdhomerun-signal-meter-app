package net.etekweb.hdhrmeter.player

import net.etekweb.hdhrmeter.data.RecordingSpan
import java.io.Closeable
import kotlin.math.roundToLong

internal data class RecordingSample(
    val startMs: Long,
    val positionMs: Long,
    val endMs: Long,
)

/**
 * Plays one recorded file through the same local relay as live timeshift.
 * LibVLC reads [localUrl]. A seek closes that stream and opens the file again
 * at the byte that matches the clock.
 */
internal class RecordingPlay : Closeable {
    private val relay = TimeshiftRelay()
    private var upstream: String? = null
    private var span = RecordingSpan(0L, 1L, 0L, bounded = false)
    private var hold = false
    private var holdGeneration = -1
    private var holdWall = 0L
    private var holdByte = 0L
    private var holdRate = 0.0
    private var openedWall = 0L
    private var openedByte = 0L
    private var openedRate = 0.0
    private val picture = PictureClock(monotonic = true)
    private var growing = false
    private var recordEndMs: Long? = null
    private var finishedEdgeMs: Long? = null
    var hasPlayed: Boolean = false
        private set

    val localUrl: String get() = relay.localUrl
    val active: Boolean get() = upstream != null
    val seekable: Boolean get() = active && span.seekable
    val startMs: Long get() = span.startMs
    val failure get() = relay.snapshot.value.failure

    fun begin(
        url: String,
        window: RecordingSpan,
        range: String?,
        startWallMs: Long,
        growing: Boolean,
        recordEndMs: Long?,
        resume: Boolean = false,
    ): Long {
        upstream = url
        span = window
        this.growing = growing
        this.recordEndMs = recordEndMs
        finishedEdgeMs = null
        hasPlayed = false
        val wall = if (window.seekable) clampSeek(startWallMs) else window.startMs
        val byte = window.byteAt(wall)
        val initialRange = if (byte > 0L) "bytes=$byte-" else range.orEmpty()
        return open(url, wall, byte, initialRange, resume = resume && window.seekable)
    }

    private fun open(
        url: String,
        wall: Long,
        byte: Long,
        range: String = "bytes=$byte-",
        resume: Boolean = false,
        rate: Double = span.bytesPerMs,
    ): Long {
        // Allow packet/keyframe alignment while rejecting a jump to a different part of the file.
        val resumeBytes = if (resume) span.byteAt(wall - 3_000L)..span.byteAt(wall + 3_000L) else null
        val generation = relay.arm(
            url,
            range,
            joinLive = false,
            requestedWallMs = wall,
            followRecording = growing && finishedEdgeMs == null && range.isNotEmpty(),
            recordEndMs = recordEndMs,
            resumeBytes = resumeBytes,
        )
        hold = true
        holdGeneration = generation
        holdWall = wall
        holdByte = byte
        holdRate = rate
        return wall
    }

    fun stop() {
        upstream = null
        hold = false
        hasPlayed = false
        relay.stopForwarding()
    }

    override fun close() {
        stop()
        relay.close()
    }

    fun finish(nowMs: Long) {
        if (growing && finishedEdgeMs == null) finishedEdgeMs = edgeAt(nowMs)
    }

    fun sample(mediaTimeMs: Long, nowMs: Long, advancing: Boolean): RecordingSample {
        val snap = relay.snapshot.value
        val media = mediaTimeMs.coerceAtLeast(0L)
        if (hold) {
            val openedThis = snap.opened && snap.generation == holdGeneration
            val fresh = media in 0L..FRESH_MEDIA_MS
            val timedOut = openedThis && snap.openedAtMs > 0L && nowMs >= snap.openedAtMs + STREAM_HOLD_MS
            if (!(openedThis && advancing && (fresh || timedOut))) return placed(holdWall, nowMs)
            hold = false
            hasPlayed = true
            picture.start(nowMs, media, adoptMedia = fresh)
            openedByte = snap.startByte
            openedRate = holdRate
            val alignmentMs = if (openedRate > 0.0) ((openedByte - holdByte) / openedRate).roundToLong() else 0L
            openedWall = holdWall + alignmentMs
            return placed(openedWall + picture.elapsedMs, nowMs)
        }
        val elapsed = picture.advance(nowMs, media, advancing)
        return placed(openedWall + elapsed, nowMs)
    }

    /** Lands on [wallMs]. Returns the clamped clock time, or null when this file cannot be seeked. */
    fun seek(wallMs: Long, refreshedSpan: RecordingSpan? = null, skip: PendingSkip? = null): Long? {
        val url = upstream ?: return null
        if (refreshedSpan?.seekable == true && refreshedSpan.totalBytes >= span.totalBytes) {
            // A file that gained no bytes still describes the same duration.
            val refreshedEnd = finishedEdgeMs ?: if (refreshedSpan.totalBytes == span.totalBytes) {
                minOf(span.endMs, refreshedSpan.endMs)
            } else {
                refreshedSpan.endMs
            }
            span = refreshedSpan.copy(endMs = refreshedEnd)
        }
        if (!span.seekable) return null
        if (skip == null) {
            val wall = clampSeek(wallMs)
            return open(url, wall, span.byteAt(wall))
        }

        // Updating the file size can change its time-to-byte scale. Keep the
        // current picture anchored to the connection that is playing it.
        val rate = if (hold) holdRate else openedRate
        if (rate <= 0.0) return null
        val anchorByte = if (hold) holdByte else openedByte + ((skip.anchorMs - openedWall) * rate).roundToLong()
        val step = (skip.deltaMs * rate).roundToLong()
        val maxByte = span.byteAt(clampSeek(Long.MAX_VALUE))
        val byte = (anchorByte + step).coerceIn(0L, maxByte)
        if ((skip.deltaMs > 0L && byte <= anchorByte) || (skip.deltaMs < 0L && byte >= anchorByte)) return null
        val landed = if (byte == 0L) span.startMs else skip.anchorMs + ((byte - anchorByte) / rate).roundToLong()
        return open(url, landed, byte, rate = rate)
    }

    private fun clampSeek(wallMs: Long): Long {
        // The newest bytes may be an incomplete MPEG-TS packet or lack a keyframe.
        val end = if (growing && finishedEdgeMs == null) maxOf(span.startMs, span.endMs - 2_000L) else span.endMs
        return wallMs.coerceIn(span.startMs, end)
    }

    private fun placed(wallMs: Long, nowMs: Long): RecordingSample {
        val start = span.startMs
        val edge = edgeAt(nowMs)
        val position = if (span.bounded) {
            wallMs.coerceIn(start, edge)
        } else {
            wallMs.coerceAtLeast(start)
        }
        val end = if (span.bounded) edge else maxOf(position, start + 1L)
        return RecordingSample(start, position, end)
    }

    private fun edgeAt(nowMs: Long): Long = finishedEdgeMs ?: if (growing) {
        maxOf(span.endMs, minOf(nowMs, recordEndMs ?: nowMs))
    } else {
        span.endMs
    }
}
