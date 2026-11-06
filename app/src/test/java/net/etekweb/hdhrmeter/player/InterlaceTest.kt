package net.etekweb.hdhrmeter.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

class InterlaceTest {
    @Test
    fun mpeg2SequenceExtensionBitIsTheScanType() {
        assertEquals(true, mpeg2Interlaced(mpeg2(progressive = false)))
        assertEquals(false, mpeg2Interlaced(mpeg2(progressive = true)))
        assertNull(mpeg2Interlaced(byteArrayOf(0, 0, 1, 0xB5.toByte(), 0x20, 0)))
        assertNull(mpeg2Interlaced(ByteArray(32)))
    }

    @Test
    fun transportMarksAnInterlacedMpeg2Broadcast() {
        assertEquals(true, interlacedTransport(transport(0x02, mpeg2(progressive = false))))
        assertEquals(true, interlacedTransport(transport(0x02, mpeg2(progressive = false), prefix = 5)))
    }

    @Test
    fun transportLeavesProgressiveAndUnknownStreamsUnmarked() {
        assertEquals(false, interlacedTransport(transport(0x02, mpeg2(progressive = true))))
        assertNull(interlacedTransport(transport(0x24, mpeg2(progressive = false))))
        assertNull(interlacedTransport(transport(0x81, mpeg2(progressive = false))))
        assertNull(interlacedTransport(ByteArray(800)))
    }

    @Test
    fun sequenceExtensionCanArriveInTheNextPacket() {
        val es = mpeg2(progressive = false)
        val head = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0, 0)
        val packets = ByteArray(0) +
            packet(0, true, byteArrayOf(0) + patSection(0x100)) +
            packet(0x100, true, byteArrayOf(0) + pmtSection(0x100, 0x02, 0x101)) +
            packet(0x101, true, head + es.copyOfRange(0, 8)) +
            packet(0x101, false, es.copyOfRange(8, es.size))
        assertEquals(true, interlacedTransport(packets))
    }

    @Test
    fun h264FrameMbsOnlyFlagIsTheScanType() {
        assertEquals(true, h264Interlaced(sps(frameMbsOnly = false, high = false)))
        assertEquals(false, h264Interlaced(sps(frameMbsOnly = true, high = false)))
        assertEquals(true, h264Interlaced(sps(frameMbsOnly = false, high = true)))
        assertEquals(false, h264Interlaced(sps(frameMbsOnly = true, high = true, scaling = true)))
        assertNull(h264Interlaced(byteArrayOf(0, 0, 1, 0x09, 0x10)))
    }

    @Test
    fun handPackedBaselineSpsAgreesWithTheBitPacker() {
        val packed = sps(frameMbsOnly = false, high = false)
        val hand = byteArrayOf(0, 0, 1, 0x67, 0x42, 0x00, 0x1E, 0xFB.toByte(), 0x00)
        assertEquals(hand.contentToString(), packed.contentToString())
    }

    @Test
    fun h264TransportUsesTheSps() {
        assertEquals(true, interlacedTransport(transport(0x1B, sps(frameMbsOnly = false, high = true))))
        assertEquals(false, interlacedTransport(transport(0x1B, sps(frameMbsOnly = true, high = false))))
    }

    @Test(timeout = 15_000)
    fun relayReportsInterlaceWithoutASecondConnection() {
        val upstream = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        upstream.soTimeout = 4_000
        val server = Thread {
            try {
                val client = upstream.accept()
                client.soTimeout = 3_000
                client.use {
                    readUntilHeaders(it)
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: video/mpeg\r\nConnection: close\r\n\r\n"
                    val output = it.getOutputStream()
                    output.write(head.toByteArray(Charsets.ISO_8859_1))
                    output.write(transport(0x02, mpeg2(progressive = false)))
                    output.flush()
                }
            } catch (_: Exception) {
                // The test closes the listening socket on the way out.
            }
        }
        server.isDaemon = true
        server.start()
        val relay = TimeshiftRelay()
        val seen = AtomicReference<Boolean?>()
        relay.interlaceToken = { 4 }
        relay.interlaceListener = { _, interlaced -> seen.set(interlaced) }
        try {
            val url = "http://127.0.0.1:${upstream.localPort}/auto/v2.1"
            relay.arm(url, "bytes=0-", joinLive = true, requestedWallMs = 1_000L)
            httpGet(relay.localUrl)
            assertEquals(true, seen.get())
        } finally {
            relay.close()
            upstream.close()
            server.join(2_000)
        }
    }

    @Test
    fun loopbackPlaybackIsNotProbed() {
        assertTrue(isLoopbackPlayUrl("http://127.0.0.1:34567/live"))
        assertTrue(isLoopbackPlayUrl("http://localhost/live"))
        assertTrue(isLoopbackPlayUrl("http://[::1]:9/live"))
        assertFalse(isLoopbackPlayUrl("http://192.168.1.50:5004/auto/v2.1"))
    }

    private fun httpGet(url: String) {
        val uri = URI(url)
        val socket = Socket()
        socket.soTimeout = 4_000
        try {
            socket.connect(InetSocketAddress(uri.host, uri.port), 4_000)
            val path = uri.rawPath?.ifBlank { "/" } ?: "/"
            val request = "GET $path HTTP/1.0\r\nHost: ${uri.host}:${uri.port}\r\nConnection: close\r\n\r\n"
            val output = socket.getOutputStream()
            output.write(request.toByteArray(Charsets.ISO_8859_1))
            output.flush()
            socket.getInputStream().readBytes()
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readUntilHeaders(socket: Socket) {
        val input = socket.getInputStream()
        val buffer = ByteArray(4096)
        var size = 0
        while (size + 3 < buffer.size) {
            val read = input.read(buffer, size, buffer.size - size)
            if (read < 0) return
            size += read
            for (index in 0..size - 4) {
                if (buffer[index] == '\r'.code.toByte() &&
                    buffer[index + 1] == '\n'.code.toByte() &&
                    buffer[index + 2] == '\r'.code.toByte() &&
                    buffer[index + 3] == '\n'.code.toByte()
                ) {
                    return
                }
            }
        }
    }

    private fun mpeg2(progressive: Boolean): ByteArray {
        val scan = if (progressive) 0x08 else 0x00
        return byteArrayOf(
            0, 0, 1, 0xB3.toByte(),
            0x2D, 0x01, 0xE0.toByte(), 0x24, 0, 0, 0, 0,
            0, 0, 1, 0xB5.toByte(),
            0x14, scan.toByte(),
        )
    }

    private fun transport(streamType: Int, elementary: ByteArray, prefix: Int = 0): ByteArray {
        val pes = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0, 0) + elementary
        val body = packet(0, true, byteArrayOf(0) + patSection(0x100)) +
            packet(0x100, true, byteArrayOf(0) + pmtSection(0x100, streamType, 0x101)) +
            packet(0x101, true, pes)
        if (prefix == 0) return body
        return ByteArray(prefix) { 0xFF.toByte() } + body
    }

    private fun patSection(pmtPid: Int): ByteArray {
        return byteArrayOf(
            0x00,
            0xB0.toByte(), 0x0D,
            0x00, 0x01,
            0xC1.toByte(),
            0x00, 0x00,
            0x00, 0x01,
            ((pmtPid shr 8) and 0x1F).toByte(),
            (pmtPid and 0xFF).toByte(),
            0, 0, 0, 0,
        )
    }

    private fun pmtSection(pcrPid: Int, streamType: Int, videoPid: Int): ByteArray {
        return byteArrayOf(
            0x02,
            0xB0.toByte(), 0x12,
            0x00, 0x01,
            0xC1.toByte(),
            0x00, 0x00,
            ((pcrPid shr 8) and 0x1F).toByte(),
            (pcrPid and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
            streamType.toByte(),
            ((videoPid shr 8) and 0x1F).toByte(),
            (videoPid and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
            0, 0, 0, 0,
        )
    }

    private fun packet(pid: Int, unitStart: Boolean, payload: ByteArray): ByteArray {
        val packet = ByteArray(188) { 0xFF.toByte() }
        packet[0] = 0x47
        var flags = (pid shr 8) and 0x1F
        if (unitStart) flags = flags or 0x40
        packet[1] = flags.toByte()
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = 0x10
        val count = minOf(payload.size, 184)
        payload.copyInto(packet, destinationOffset = 4, endIndex = count)
        return packet
    }

    private fun sps(frameMbsOnly: Boolean, high: Boolean, scaling: Boolean = false): ByteArray {
        val bits = BitPacker()
        if (high) {
            bits.u(100, 8)
            bits.u(0, 8)
            bits.u(40, 8)
            bits.ue(0)
            bits.ue(1)
            bits.ue(0)
            bits.ue(0)
            bits.u(0, 1)
            bits.u(if (scaling) 1 else 0, 1)
            if (scaling) repeat(8) { bits.u(0, 1) }
        } else {
            bits.u(66, 8)
            bits.u(0, 8)
            bits.u(30, 8)
            bits.ue(0)
        }
        bits.ue(0)
        bits.ue(0)
        bits.ue(0)
        bits.ue(0)
        bits.u(0, 1)
        bits.ue(0)
        bits.ue(0)
        bits.u(if (frameMbsOnly) 1 else 0, 1)
        return byteArrayOf(0, 0, 1, 0x67) + bits.bytes()
    }

    private class BitPacker {
        private val bits = ArrayList<Int>()

        fun u(value: Int, width: Int) {
            for (shift in width - 1 downTo 0) bits += (value shr shift) and 1
        }

        fun ue(value: Int) {
            val code = value + 1
            var width = 0
            var probe = code
            while (probe > 0) {
                width++
                probe = probe ushr 1
            }
            repeat(width - 1) { bits += 0 }
            u(code, width)
        }

        fun bytes(): ByteArray {
            val out = ByteArray((bits.size + 7) / 8)
            bits.forEachIndexed { index, bit ->
                if (bit != 0) {
                    out[index / 8] = (out[index / 8].toInt() or (1 shl (7 - index % 8))).toByte()
                }
            }
            return out
        }
    }
}
