package net.etekweb.hdhrmeter.player

import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * Interlacing comes from the bitstream, not the picture size.
 * 1080p and 1080i share a resolution, and so do 480p and 480i.
 * Unknown stays null so a progressive picture is never labeled (i).
 */
internal fun interlacedTransport(bytes: ByteArray, length: Int = bytes.size): Boolean? {
    val end = length.coerceIn(0, bytes.size)
    if (end < 188 * 3) return null
    val origin = syncOffset(bytes, end) ?: return null
    val pmtPid = patPmtPid(bytes, end, origin) ?: return null
    val video = pmtVideo(bytes, end, origin, pmtPid) ?: return null
    val elementary = assembleElementary(bytes, end, origin, video.pid)
    if (elementary.isEmpty()) return null
    return when (video.streamType) {
        MPEG1_VIDEO, MPEG2_VIDEO -> mpeg2Interlaced(elementary)
        H264_VIDEO -> h264Interlaced(elementary)
        else -> null
    }
}

internal fun isLoopbackPlayUrl(url: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull() ?: return false
    val bare = host.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
    return bare == "::1" || bare.equals("localhost", ignoreCase = true) || bare.startsWith("127.")
}

/** MPEG-2 sequence extension: progressive_sequence clear means the broadcast is interlaced. */
internal fun mpeg2Interlaced(data: ByteArray, length: Int = data.size): Boolean? {
    val end = length.coerceIn(0, data.size)
    var sawSequence = false
    var index = 0
    while (index + 4 <= end) {
        if (data[index] != 0.toByte() || data[index + 1] != 0.toByte() || data[index + 2] != 1.toByte()) {
            index++
            continue
        }
        val code = data[index + 3].toInt() and 0xFF
        if (code == 0xB3) sawSequence = true
        if (code == 0xB5 && sawSequence && index + 6 <= end) {
            val extensionId = (data[index + 4].toInt() and 0xFF) ushr 4
            if (extensionId == SEQUENCE_EXTENSION) {
                val progressive = data[index + 5].toInt() and PROGRESSIVE_SEQUENCE != 0
                return !progressive
            }
        }
        index += 3
    }
    return null
}

/**
 * H.264 SPS frame_mbs_only_flag.
 * Clear means the stream is coded as fields, which is how 1080i is signaled.
 */
internal fun h264Interlaced(data: ByteArray, length: Int = data.size): Boolean? {
    val end = length.coerceIn(0, data.size)
    var index = 0
    while (index + 4 < end) {
        val start = startCodeLength(data, end, index)
        if (start == 0) {
            index++
            continue
        }
        val nal = index + start
        if (nal >= end) return null
        val next = nextStartCode(data, end, nal + 1)
        if ((data[nal].toInt() and 0x1F) == SPS_NAL) {
            val frameOnly = frameMbsOnly(rbsp(data, nal, next))
            if (frameOnly != null) return !frameOnly
        }
        index = next
    }
    return null
}

private data class VideoPid(val pid: Int, val streamType: Int)

private fun syncOffset(bytes: ByteArray, end: Int): Int? {
    val last = minOf(end - 188 * 3, 188)
    if (last < 0) return null
    for (index in 0..last) {
        if (bytes[index] == SYNC &&
            bytes[index + 188] == SYNC &&
            bytes[index + 376] == SYNC
        ) {
            return index
        }
    }
    return null
}

private fun patPmtPid(bytes: ByteArray, end: Int, origin: Int): Int? {
    val section = psiSection(bytes, end, origin, PAT_PID) ?: return null
    if (section.isEmpty() || section[0] != 0.toByte() || section.size < 12) return null
    val limit = sectionLimit(section)
    var index = 8
    while (index + 4 <= limit) {
        val program = ((section[index].toInt() and 0xFF) shl 8) or (section[index + 1].toInt() and 0xFF)
        val pid = ((section[index + 2].toInt() and 0x1F) shl 8) or (section[index + 3].toInt() and 0xFF)
        if (program != 0) return pid
        index += 4
    }
    return null
}

private fun pmtVideo(bytes: ByteArray, end: Int, origin: Int, pmtPid: Int): VideoPid? {
    val section = psiSection(bytes, end, origin, pmtPid) ?: return null
    if (section.size < 12 || (section[0].toInt() and 0xFF) != PMT_TABLE) return null
    val limit = sectionLimit(section)
    val infoLength = ((section[10].toInt() and 0x0F) shl 8) or (section[11].toInt() and 0xFF)
    var index = 12 + infoLength
    while (index + 5 <= limit) {
        val streamType = section[index].toInt() and 0xFF
        val pid = ((section[index + 1].toInt() and 0x1F) shl 8) or (section[index + 2].toInt() and 0xFF)
        val esInfo = ((section[index + 3].toInt() and 0x0F) shl 8) or (section[index + 4].toInt() and 0xFF)
        if (streamType == MPEG1_VIDEO || streamType == MPEG2_VIDEO ||
            streamType == H264_VIDEO || streamType == HEVC_VIDEO
        ) {
            return VideoPid(pid, streamType)
        }
        index += 5 + esInfo
    }
    return null
}

private fun sectionLimit(section: ByteArray): Int {
    val sectionLength = ((section[1].toInt() and 0x0F) shl 8) or (section[2].toInt() and 0xFF)
    return minOf(section.size, 3 + sectionLength - 4).coerceAtLeast(0)
}

private fun psiSection(bytes: ByteArray, end: Int, origin: Int, pid: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    var collecting = false
    var at = origin
    while (at + 188 <= end) {
        if (bytes[at] != SYNC) {
            at++
            continue
        }
        val packetPid = packetPid(bytes, at)
        val packetEnd = at + 188
        if (packetPid == pid) {
            val payload = rawPayload(bytes, at, packetEnd)
            if (payload != null && unitStart(bytes, at)) {
                val pointer = payload[0].toInt() and 0xFF
                val start = 1 + pointer
                if (start <= payload.size) {
                    out.reset()
                    out.write(payload, start, payload.size - start)
                    collecting = true
                }
            } else if (payload != null && collecting) {
                out.write(payload)
            }
            if (collecting) {
                val buf = out.toByteArray()
                if (buf.size >= 3) {
                    val sectionLength = ((buf[1].toInt() and 0x0F) shl 8) or (buf[2].toInt() and 0xFF)
                    val total = 3 + sectionLength
                    if (sectionLength in 5..1021 && buf.size >= total) return buf.copyOf(total)
                }
            }
        }
        at = packetEnd
    }
    return null
}

private fun assembleElementary(bytes: ByteArray, end: Int, origin: Int, pid: Int): ByteArray {
    val out = ByteArrayOutputStream()
    var started = false
    var at = origin
    while (at + 188 <= end) {
        if (bytes[at] != SYNC) {
            at++
            continue
        }
        val packetEnd = at + 188
        if (packetPid(bytes, at) == pid) {
            val payload = rawPayload(bytes, at, packetEnd)
            if (payload != null && unitStart(bytes, at)) {
                val body = pesBody(payload)
                if (body != null) {
                    started = true
                    out.write(body)
                }
            } else if (payload != null && started) {
                out.write(payload)
            }
        }
        at = packetEnd
    }
    return out.toByteArray()
}

private fun pesBody(payload: ByteArray): ByteArray? {
    if (payload.size < 9) return null
    if (payload[0] != 0.toByte() || payload[1] != 0.toByte() || payload[2] != 1.toByte()) return null
    val headerLength = payload[8].toInt() and 0xFF
    val start = 9 + headerLength
    if (start > payload.size) return null
    return payload.copyOfRange(start, payload.size)
}

private fun rawPayload(bytes: ByteArray, at: Int, packetEnd: Int): ByteArray? {
    val adapt = bytes[at + 3].toInt() and 0x30
    var start = at + 4
    if (adapt and 0x20 != 0) {
        if (start >= packetEnd) return null
        val adaptation = bytes[start].toInt() and 0xFF
        start += 1 + adaptation
    }
    if (adapt and 0x10 == 0 || start >= packetEnd) return null
    return bytes.copyOfRange(start, packetEnd)
}

private fun packetPid(bytes: ByteArray, at: Int): Int {
    return ((bytes[at + 1].toInt() and 0x1F) shl 8) or (bytes[at + 2].toInt() and 0xFF)
}

private fun unitStart(bytes: ByteArray, at: Int): Boolean = bytes[at + 1].toInt() and 0x40 != 0

private fun startCodeLength(data: ByteArray, end: Int, index: Int): Int {
    if (index + 3 >= end || data[index] != 0.toByte() || data[index + 1] != 0.toByte()) return 0
    if (data[index + 2] == 1.toByte()) return 3
    if (data[index + 2] == 0.toByte() && index + 3 < end && data[index + 3] == 1.toByte()) return 4
    return 0
}

private fun nextStartCode(data: ByteArray, end: Int, from: Int): Int {
    var index = from
    while (index + 3 < end) {
        if (startCodeLength(data, end, index) != 0) return index
        index++
    }
    return end
}

/** Drop H.264 emulation-prevention bytes so the SPS can be read as RBSP. */
private fun rbsp(data: ByteArray, from: Int, to: Int): ByteArray {
    val out = ByteArrayOutputStream()
    var zeros = 0
    var index = from
    while (index < to) {
        val value = data[index].toInt() and 0xFF
        if (zeros >= 2 && value == 3) {
            zeros = 0
            index++
            continue
        }
        out.write(value)
        zeros = if (value == 0) zeros + 1 else 0
        index++
    }
    return out.toByteArray()
}

private fun frameMbsOnly(rbsp: ByteArray): Boolean? {
    if (rbsp.size < 4) return null
    val bits = BitReader(rbsp)
    bits.u(8)
    val profile = bits.u(8)
    bits.u(8)
    bits.u(8)
    bits.ue()
    if (!bits.ok) return null
    if (profile in HIGH_PROFILES) {
        val chroma = bits.ue()
        if (chroma == 3) bits.u(1)
        bits.ue()
        bits.ue()
        bits.u(1)
        if (bits.u(1) == 1) {
            val lists = if (chroma != 3) 8 else 12
            for (list in 0 until lists) {
                if (bits.u(1) == 1) skipScalingList(bits, if (list < 6) 16 else 64)
            }
        }
    }
    bits.ue()
    when (val order = bits.ue()) {
        0 -> bits.ue()
        1 -> {
            bits.u(1)
            bits.se()
            bits.se()
            val cycle = bits.ue()
            if (cycle > 64) return null
            repeat(cycle) { bits.se() }
        }
        else -> if (order != 2) return null
    }
    bits.ue()
    bits.u(1)
    bits.ue()
    bits.ue()
    val frameOnly = bits.u(1)
    if (!bits.ok) return null
    return frameOnly == 1
}

private fun skipScalingList(bits: BitReader, size: Int) {
    var last = 8
    var next = 8
    repeat(size) {
        if (!bits.ok) return
        if (next != 0) next = (last + bits.se() + 256) and 255
        last = if (next == 0) last else next
    }
}

private class BitReader(private val data: ByteArray) {
    private var index = 0
    var ok = true

    fun u(width: Int): Int {
        if (!ok || width !in 0..24) {
            ok = false
            return 0
        }
        var value = 0
        repeat(width) {
            val byteIndex = index ushr 3
            if (byteIndex >= data.size) {
                ok = false
                return 0
            }
            val shift = 7 - (index and 7)
            value = (value shl 1) or ((data[byteIndex].toInt() ushr shift) and 1)
            index++
        }
        return value
    }

    fun ue(): Int {
        var zeros = 0
        while (true) {
            val bit = u(1)
            if (!ok) return 0
            if (bit == 1) break
            zeros++
            if (zeros > 16) {
                ok = false
                return 0
            }
        }
        if (zeros == 0) return 0
        return ((1 shl zeros) - 1) + u(zeros)
    }

    fun se(): Int {
        val code = ue()
        val magnitude = (code + 1) ushr 1
        return if (code and 1 == 0) -magnitude else magnitude
    }
}

private const val SYNC = 0x47.toByte()
private const val PAT_PID = 0
private const val PMT_TABLE = 0x02
private const val MPEG1_VIDEO = 0x01
private const val MPEG2_VIDEO = 0x02
private const val H264_VIDEO = 0x1B
private const val HEVC_VIDEO = 0x24
private const val SPS_NAL = 7
private const val SEQUENCE_EXTENSION = 1
private const val PROGRESSIVE_SEQUENCE = 0x08

private val HIGH_PROFILES = intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
