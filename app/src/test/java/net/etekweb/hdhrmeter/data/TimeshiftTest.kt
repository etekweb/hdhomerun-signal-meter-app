package net.etekweb.hdhrmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class TimeshiftTest {
    @Test
    fun contentRangeAndEngineUrl() {
        assertEquals(1000L, contentRangeStart("bytes 1000-2000/9000"))
        assertEquals(9000L, contentRangeStart("BYTES 9000-"))
        assertNull(contentRangeStart("bytes *"))
        assertEquals("http://10.0.0.20:59090", recordEngineOrigin("http://10.0.0.20:59090/recorded_files.json"))
        assertNull(recordEngineOrigin("file:///tmp/recorded_files.json"))
        val url = timeshiftStreamUrl("http://10.0.0.20:59090/", "7.1", "client", "DEADBEEF")
        assertEquals(
            "http://10.0.0.20:59090/auto/v7.1?ClientID=client&SessionID=DEADBEEF",
            url,
        )
        assertNull(timeshiftStreamUrl("http://10.0.0.20:59090", "7 1", "client", "DEADBEEF"))
        assertEquals(8, newTimeshiftSessionId().length)
    }

    @Test(timeout = 15_000)
    fun probeReadsOldestThenLive() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 4_000
        val ranges = mutableListOf<String>()
        val thread = Thread {
            repeat(2) { index ->
                val client = server.accept()
                client.soTimeout = 3_000
                client.use {
                    val request = readUntilHeaders(it)
                    synchronized(ranges) { ranges += request }
                    val start = if (index == 0) 1000 else 9000
                    val response = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Range: bytes $start-${start + 10}/10000\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n"
                    it.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
                    it.getOutputStream().flush()
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        try {
            val probe = probeTimeshift("http://127.0.0.1:${server.localPort}/auto/v7.1?ClientID=a&SessionID=B")
            assertNull(probe.failure)
            assertEquals(1000L, probe.oldestByte)
            assertEquals(9000L, probe.liveByte)
            assertTrue(ranges[0].contains("Range: bytes=0-"))
            assertTrue(ranges[1].contains("Range: bytes=$TIMESHIFT_LIVE_BYTE-"))
        } finally {
            server.close()
            thread.join(2_000)
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
