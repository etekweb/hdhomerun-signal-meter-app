package net.etekweb.hdhrmeter.player

import net.etekweb.hdhrmeter.data.RecordingSpan
import java.io.Closeable

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

    fun begin(
        url: String,
        window: RecordingSpan,
        range: String?,
        startWallMs: Long,
        growing: Boolean,
        recordEndMs: Long?,
    ): Long {
        upstream = url
        span = window
        this.growing = growing
        this.recordEndMs = recordEndMs
        finishedEdgeMs = null
        hasPlayed = false
        val wall = if (window.seekable) clampSeek(startWallMs) else window.startMs
        return open(url, wall, if (wall > window.startMs) "bytes=${window.byteAt(wall)}-" else range.orEmpty())
    }

    private fun open(url: String, wall: Long, range: String): Long {
        val generation = relay.arm(
            url,
            range,
            joinLive = false,
            requestedWallMs = wall,
            followRecording = growing && finishedEdgeMs == null && range.isNotEmpty(),
            recordEndMs = recordEndMs,
        )
        hold = true
        holdGeneration = generation
        holdWall = wall
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
            val origin = if (snap.opened) snap.requestedWallMs else holdWall
            return placed(origin + picture.elapsedMs, nowMs)
        }
        val elapsed = picture.advance(nowMs, media, advancing)
        val origin = if (snap.opened) snap.requestedWallMs else span.startMs
        return placed(origin + elapsed, nowMs)
    }

    /** Lands on [wallMs]. Returns the clamped clock time, or null when this file cannot be seeked. */
    fun seek(wallMs: Long, refreshedSpan: RecordingSpan? = null): Long? {
        val url = upstream ?: return null
        if (refreshedSpan?.seekable == true) {
            span = finishedEdgeMs?.let { refreshedSpan.copy(endMs = it) } ?: refreshedSpan
        }
        if (!span.seekable) return null
        val wall = clampSeek(wallMs)
        return open(url, wall, "bytes=${span.byteAt(wall)}-")
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
