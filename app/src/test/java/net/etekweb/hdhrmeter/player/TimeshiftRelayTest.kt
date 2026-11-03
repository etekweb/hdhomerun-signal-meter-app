package net.etekweb.hdhrmeter.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

class TimeshiftRelayTest {
    @Test(timeout = 15_000)
    fun relayForwardsTheArmedRange() {
        val upstream = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        upstream.soTimeout = 4_000
        val seen = StringBuilder()
        val server = Thread {
            try {
                val client = upstream.accept()
                client.soTimeout = 3_000
                client.use {
                    val request = readUntilHeaders(it)
                    synchronized(seen) { seen.append(request) }
                    val head = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Range: bytes 9000-9004/9005\r\n" +
                        "Content-Type: video/mpeg\r\n" +
                        "Connection: close\r\n\r\n"
                    val output = it.getOutputStream()
                    output.write(head.toByteArray(Charsets.ISO_8859_1))
                    output.write("HELLO".toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
            } catch (_: Exception) {
                // The test closes the listening socket on the way out.
            }
        }
        server.isDaemon = true
        server.start()
        val relay = TimeshiftRelay()
        try {
            val url = "http://127.0.0.1:${upstream.localPort}/auto/v2.1?ClientID=abc&SessionID=DEADBEEF"
            relay.arm(url, "bytes=9000-", joinLive = false, requestedWallMs = 50_000L)
            val payload = httpGet(relay.localUrl)
            assertTrue(payload.contains("HELLO"))
            assertTrue(relay.snapshot.value.opened)
            assertEquals(9000L, relay.snapshot.value.startByte)
            assertTrue(seen.toString().contains("Range: bytes=9000-"))
        } finally {
            relay.close()
            upstream.close()
            server.join(2_000)
        }
    }

    private fun httpGet(url: String): String {
        val uri = URI(url)
        val socket = Socket()
        socket.soTimeout = 4_000
        try {
            socket.connect(InetSocketAddress(uri.host, uri.port), 4_000)
            val path = uri.rawPath?.ifBlank { "/" } ?: "/"
            val request = "GET $path HTTP/1.0\r\nHost: ${uri.host}:${uri.port}\r\nConnection: close\r\n\r\n"
            val output = socket.getOutputStream()
            val bytes = request.toByteArray(Charsets.ISO_8859_1)
            output.write(bytes)
            output.flush()
            return socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readUntilHeaders(socket: Socket): String {
        val input = socket.getInputStream()
        val buffer = ByteArray(4096)
        var size = 0
        while (size < buffer.size) {
            val read = input.read(buffer, size, buffer.size - size)
            if (read < 0) break
            size += read
            val text = buffer.copyOfRange(0, size).toString(Charsets.ISO_8859_1)
            if (text.contains("\r\n\r\n")) return text
        }
        return buffer.copyOfRange(0, size).toString(Charsets.ISO_8859_1)
    }
}
