package net.etekweb.hdhrmeter.player

import net.etekweb.hdhrmeter.data.RecordingSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Test(timeout = 15_000)
    fun recordingResumeUsesTheConfirmedOffsetAndRejectsALiveClamp() {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { upstream ->
            upstream.soTimeout = 4_000
            val worker = Executors.newSingleThreadExecutor()
            try {
                val requests = worker.submit<List<String>> {
                    listOf(
                        "206 Partial Content\r\nContent-Range: bytes 2900-2903/10000\r\nContent-Length: 4\r\n\r\nPAST",
                        "416 Range Not Satisfiable\r\nContent-Range: bytes */10000\r\nContent-Length: 0\r\n\r\n",
                        "206 Partial Content\r\nContent-Range: bytes 9996-9999/10000\r\nContent-Length: 4\r\n\r\nLIVE",
                    ).map { response ->
                        upstream.accept().use { client ->
                            client.soTimeout = 3_000
                            val request = readUntilHeaders(client)
                            client.getOutputStream().write("HTTP/1.1 $response".toByteArray(Charsets.US_ASCII))
                            request.lineSequence().first { it.startsWith("Range:") }
                        }
                    }
                }
                RecordingPlay().use { player ->
                    val url = "http://127.0.0.1:${upstream.localPort}/recorded/play"
                    val span = RecordingSpan(100_000L, 200_000L, 10_000L, bounded = true)
                    player.begin(url, span, "bytes=0-", 130_000L, growing = true, recordEndMs = 200_000L, resume = true)
                    assertTrue(httpGet(player.localUrl).endsWith("PAST"))
                    assertEquals(129_000L, player.sample(0L, 200_000L, advancing = true).positionMs)

                    player.begin(url, span, "bytes=0-", 130_000L, growing = true, recordEndMs = 200_000L, resume = true)
                    assertTrue(httpGet(player.localUrl).startsWith("HTTP/1.0 502"))
                    assertEquals("The DVR could not open the saved position.", player.failure?.message)
                    assertTrue(!player.hasPlayed)
                    assertEquals(
                        listOf("Range: bytes=3000-", "Range: bytes=2904-", "Range: bytes=3000-"),
                        requests.get(4, TimeUnit.SECONDS),
                    )
                }
            } finally {
                worker.shutdownNow()
            }
        }
    }

    @Test(timeout = 15_000)
    fun growingRecordingContinuesFromTheFirstUnreadByte() {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { upstream ->
            upstream.soTimeout = 4_000
            val worker = Executors.newSingleThreadExecutor()
            try {
                val requests = worker.submit<List<String>> {
                    listOf(
                        "206 Partial Content\r\nContent-Range: bytes 8000-8004/8005\r\nContent-Length: 5\r\n\r\nFIRST",
                        "206 Partial Content\r\nContent-Range: bytes 8005-8008/8009\r\nContent-Length: 4\r\n\r\nNEXT",
                        "416 Range Not Satisfiable\r\nContent-Range: bytes */8009\r\nContent-Length: 0\r\n\r\n",
                    ).map { response ->
                        upstream.accept().use { client ->
                            client.soTimeout = 3_000
                            val request = readUntilHeaders(client)
                            client.getOutputStream().write("HTTP/1.1 $response".toByteArray(Charsets.US_ASCII))
                            request.lineSequence().first { it.startsWith("Range:") }
                        }
                    }
                }
                TimeshiftRelay().use { relay ->
                    val url = "http://127.0.0.1:${upstream.localPort}/recorded/play"
                    relay.arm(url, "bytes=8000-", joinLive = false, requestedWallMs = 50_000L,
                        followRecording = true, recordEndMs = 1L)
                    assertTrue(httpGet(relay.localUrl).endsWith("FIRSTNEXT"))
                    assertEquals(9L, relay.snapshot.value.bytesSent)
                    assertEquals(
                        listOf("Range: bytes=8000-", "Range: bytes=8005-", "Range: bytes=8009-"),
                        requests.get(4, TimeUnit.SECONDS),
                    )
                }
            } finally {
                worker.shutdownNow()
            }
        }
    }

    @Test(timeout = 15_000)
    fun directTuneRejectsAnOldPlayerRequestAndReportsTheTunerError() {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { upstream ->
            upstream.soTimeout = 4_000
            val worker = Executors.newSingleThreadExecutor()
            try {
                val request = worker.submit<String> {
                    upstream.accept().use { client ->
                        client.soTimeout = 3_000
                        val headers = readUntilHeaders(client)
                        val response = "HTTP/1.0 503 Service Unavailable\r\n" +
                            "X-HDHomeRun-Error: 804\r\nContent-Length: 0\r\n\r\n"
                        client.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
                        headers
                    }
                }
                TimeshiftRelay().use { relay ->
                    val origin = "http://127.0.0.1:${upstream.localPort}"
                    val oldUrl = relay.armDirect("$origin/tuner0/v2.1")
                    val currentUrl = relay.armDirect("$origin/tuner0/v3.1")
                    assertEquals("", httpGet(oldUrl))
                    assertTrue(httpGet(currentUrl).startsWith("HTTP/1.0 502"))
                    assertEquals(804, relay.snapshot.value.failure?.hdhrCode)
                    assertTrue(request.get(4, TimeUnit.SECONDS).startsWith("GET /tuner0/v3.1 HTTP/1.0"))
                }
            } finally {
                worker.shutdownNow()
            }
        }
    }

    @Test(timeout = 15_000)
    fun changingChannelsClosesThePreviousTunerRequestEvenBeforeItAnswers() {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { upstream ->
            upstream.soTimeout = 4_000
            val worker = Executors.newFixedThreadPool(2)
            try {
                val firstRequest = CountDownLatch(1)
                val released = worker.submit<Int> {
                    val eof = upstream.accept().use { client ->
                        client.soTimeout = 3_000
                        readUntilHeaders(client)
                        firstRequest.countDown()
                        client.getInputStream().read()
                    }
                    upstream.accept().use { client ->
                        client.soTimeout = 3_000
                        readUntilHeaders(client)
                        val response = "HTTP/1.0 200 OK\r\nContent-Type: video/mpeg\r\n\r\nNEXT"
                        client.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
                    }
                    eof
                }
                TimeshiftRelay().use { relay ->
                    val origin = "http://127.0.0.1:${upstream.localPort}"
                    val oldUrl = relay.armDirect("$origin/tuner0/v2.1")
                    val oldPlayback = worker.submit<String> { httpGet(oldUrl) }
                    assertTrue(firstRequest.await(4, TimeUnit.SECONDS))
                    val currentUrl = relay.armDirect("$origin/tuner0/v3.1")
                    assertTrue(httpGet(currentUrl).endsWith("NEXT"))
                    assertEquals("", oldPlayback.get(4, TimeUnit.SECONDS))
                    assertEquals(-1, released.get(4, TimeUnit.SECONDS).toInt())
                }
            } finally {
                worker.shutdownNow()
            }
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
