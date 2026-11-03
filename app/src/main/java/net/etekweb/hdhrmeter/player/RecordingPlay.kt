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

    val localUrl: String get() = relay.localUrl
    val active: Boolean get() = upstream != null
    val seekable: Boolean get() = active && span.seekable
    val startMs: Long get() = span.startMs

    fun begin(url: String, window: RecordingSpan, range: String?) {
        upstream = url
        span = window
        val generation = relay.arm(
            url,
            range?.takeIf { it.isNotBlank() }.orEmpty(),
            joinLive = false,
            requestedWallMs = window.startMs,
        )
        hold = true
        holdGeneration = generation
        holdWall = window.startMs
    }

    fun stop() {
        upstream = null
        hold = false
        relay.stopForwarding()
    }

    override fun close() {
        stop()
        relay.close()
    }

    fun sample(mediaTimeMs: Long): RecordingSample {
        val snap = relay.snapshot.value
        if (hold) {
            val restarted = snap.opened &&
                snap.generation == holdGeneration &&
                mediaTimeMs in 0L..1_200L
            if (!restarted) return placed(holdWall)
            hold = false
        }
        val origin = if (snap.opened) snap.requestedWallMs else span.startMs
        return placed(origin + mediaTimeMs.coerceAtLeast(0L))
    }

    /** Lands on [wallMs]. Returns the clamped clock time, or null when this file cannot be seeked. */
    fun seek(wallMs: Long): Long? {
        val url = upstream ?: return null
        if (!span.seekable) return null
        val wall = wallMs.coerceIn(span.startMs, span.endMs)
        val generation = relay.arm(url, "bytes=${span.byteAt(wall)}-", joinLive = false, requestedWallMs = wall)
        hold = true
        holdGeneration = generation
        holdWall = wall
        return wall
    }

    private fun placed(wallMs: Long): RecordingSample {
        val start = span.startMs
        val position = if (span.bounded) {
            wallMs.coerceIn(start, span.endMs)
        } else {
            wallMs.coerceAtLeast(start)
        }
        val end = if (span.bounded) span.endMs else maxOf(position, start + 1L)
        return RecordingSample(start, position, end)
    }
}
