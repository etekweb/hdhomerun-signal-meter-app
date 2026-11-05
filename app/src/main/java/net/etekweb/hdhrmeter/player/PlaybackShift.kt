package net.etekweb.hdhrmeter.player

import net.etekweb.hdhrmeter.data.TIMESHIFT_LIVE_BYTE
import net.etekweb.hdhrmeter.data.TimeshiftProbe
import net.etekweb.hdhrmeter.data.newTimeshiftSessionId
import net.etekweb.hdhrmeter.data.timeshiftStreamUrl
import java.io.Closeable
import java.util.UUID

data class ShiftSample(
    val bufferStartMs: Long,
    val positionMs: Long,
    val liveMs: Long,
)

/**
 * One player's record-engine timeshift session.
 * [localUrl] is what LibVLC reads. Seeks reopen the engine with a new Range.
 */
class PlaybackShift : Closeable {
    private val relay = TimeshiftRelay()
    private val map = TimeshiftMap()
    private val clientId = UUID.randomUUID().toString()
    private var upstream: String? = null
    private var applied = -1

    val localUrl: String get() = relay.localUrl
    val active: Boolean get() = upstream != null
    val bufferStartMs: Long get() = map.bufferStartMs

    fun openSession(origin: String, guideNumber: String): String? {
        return timeshiftStreamUrl(origin, guideNumber, clientId, newTimeshiftSessionId())
    }

    fun begin(url: String, edges: TimeshiftProbe) {
        upstream = url
        applied = -1
        map.reset(edges.atMs)
        map.noteEdges(edges.oldestByte, edges.liveByte, edges.atMs)
        val range = if (edges.liveByte > 0L) "bytes=${edges.liveByte}-" else "bytes=$TIMESHIFT_LIVE_BYTE-"
        val generation = relay.arm(url, range, joinLive = true, requestedWallMs = edges.atMs)
        map.armHold(edges.atMs, generation)
    }

    fun stop() {
        upstream = null
        applied = -1
        relay.stopForwarding()
    }

    override fun close() {
        stop()
        relay.close()
    }

    fun sample(mediaTimeMs: Long, nowMs: Long, advancing: Boolean = true): ShiftSample {
        val snap = relay.snapshot.value
        if (snap.opened && snap.generation != applied) {
            applied = snap.generation
            map.noteOpened(
                actualByte = snap.startByte,
                joinLive = snap.joinLive,
                requestedWallMs = snap.requestedWallMs,
                openedAtMs = snap.openedAtMs,
                generation = snap.generation,
            )
        }
        if (snap.opened) map.noteBytes(snap.bytesSent, mediaTimeMs, nowMs, advancing)
        return ShiftSample(
            bufferStartMs = map.bufferStartMs,
            positionMs = map.positionMs(mediaTimeMs, nowMs, advancing),
            liveMs = nowMs,
        )
    }

    /** False when a middle seek still needs a measured rate. The picture keeps playing. */
    fun canSeek(wallMs: Long, joinLive: Boolean, liveMs: Long): Boolean {
        return plan(wallMs, joinLive, liveMs) != null
    }

    fun canSeekAvailableStart(programStartMs: Long?, liveMs: Long): Boolean {
        return planAvailableStart(programStartMs, liveMs) != null
    }

    fun seek(wallMs: Long, joinLive: Boolean, liveMs: Long): Boolean {
        return arm(plan(wallMs, joinLive, liveMs))
    }

    fun seekAvailableStart(programStartMs: Long?, liveMs: Long): Boolean {
        return arm(planAvailableStart(programStartMs, liveMs))
    }

    private fun plan(wallMs: Long, joinLive: Boolean, liveMs: Long): ShiftTarget? {
        if (upstream == null) return null
        return map.shiftTarget(wallMs, joinLive, liveMs, observedLiveByte())
    }

    private fun planAvailableStart(programStartMs: Long?, liveMs: Long): ShiftTarget? {
        if (upstream == null) return null
        return map.availableStartTarget(programStartMs, liveMs, observedLiveByte())
    }

    /** File end while this session is still reading the live edge. */
    private fun observedLiveByte(): Long? {
        val snap = relay.snapshot.value
        if (!snap.opened || !snap.joinLive) return null
        val byte = snap.startByte + snap.bytesSent.coerceAtLeast(0L)
        return byte.takeIf { it > 0L }
    }

    private fun arm(target: ShiftTarget?): Boolean {
        val url = upstream ?: return false
        if (target == null) return false
        val generation = relay.arm(url, target.range, target.joinLive, target.requestedWallMs)
        map.armHold(target.requestedWallMs, generation)
        return true
    }
}
