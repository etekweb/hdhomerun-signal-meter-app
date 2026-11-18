package net.etekweb.hdhrmeter.player

import net.etekweb.hdhrmeter.data.TIMESHIFT_LIVE_BYTE
import net.etekweb.hdhrmeter.data.StreamFailure
import net.etekweb.hdhrmeter.data.consumeHttpHeaders
import net.etekweb.hdhrmeter.data.contentRangeStart
import net.etekweb.hdhrmeter.data.liveStreamFailure
import net.etekweb.hdhrmeter.data.openTimeshift
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** What the local player connection has opened on the record engine. */
data class RelaySnapshot(
    val generation: Int = 0,
    val opened: Boolean = false,
    val startByte: Long = 0,
    val bytesSent: Long = 0,
    val joinLive: Boolean = false,
    val requestedWallMs: Long = 0,
    val openedAtMs: Long = 0,
    val failure: StreamFailure? = null,
)

/**
 * Local HTTP server for direct tuner streams and record-engine timeshift.
 *
 * LibVLC treats a live response with no length as not seekable. The record
 * engine seeks by closing the stream and opening it again with the same
 * ClientID and SessionID and a Range byte offset. This relay is that reopen:
 * the player always reads `localUrl`, and [arm] chooses the upstream range.
 */
class TimeshiftRelay : Closeable {
    private val server = ServerSocket()
    private val thread: Thread
    private val lock = Any()
    private val generation = AtomicInteger()
    @Volatile private var running = true
    private var armed: Armed? = null
    private var clientSocket: Socket? = null
    private var upstreamSocket: Socket? = null
    internal var interlaceToken: (() -> Int)? = null
    internal var interlaceListener: ((Int, Boolean) -> Unit)? = null

    private val _snapshot = MutableStateFlow(RelaySnapshot())
    val snapshot: StateFlow<RelaySnapshot> = _snapshot.asStateFlow()

    val localUrl: String

    init {
        server.reuseAddress = true
        server.bind(InetSocketAddress("127.0.0.1", 0))
        localUrl = "http://127.0.0.1:${server.localPort}/live"
        thread = Thread(::acceptLoop, "hdhr-shift").apply {
            isDaemon = true
            start()
        }
    }

    fun arm(url: String, range: String, joinLive: Boolean, requestedWallMs: Long): Int =
        armStream(url, range, joinLive, requestedWallMs)

    fun armDirect(url: String): String {
        val gen = armStream(url, range = null, joinLive = false, requestedWallMs = 0L)
        return "$localUrl/$gen"
    }

    private fun armStream(url: String, range: String?, joinLive: Boolean, requestedWallMs: Long): Int =
        synchronized(lock) {
            val gen = generation.incrementAndGet()
            armed = Armed(url, range, joinLive, requestedWallMs, gen)
            _snapshot.value = RelaySnapshot(generation = gen)
            closeActive()
            gen
        }

    fun stopForwarding() {
        synchronized(lock) {
            armed = null
            generation.incrementAndGet()
            _snapshot.value = RelaySnapshot()
            closeActive()
        }
    }

    override fun close() {
        running = false
        stopForwarding()
        runCatching { server.close() }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                if (!running) break
                continue
            }
            try {
                serve(socket)
            } catch (_: Exception) {
                // A seek closes the socket while it is being copied.
            } finally {
                runCatching { socket.close() }
                synchronized(lock) {
                    if (clientSocket === socket) clientSocket = null
                }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 8_000
        val request = synchronized(lock) {
            val current = armed ?: return
            clientSocket = client
            current
        }
        val direct = request.range == null
        val expectedPath = if (direct) "/live/${request.generation}" else "/live"
        if (!consumeHttpHeaders(client.getInputStream(), expectedPath = expectedPath)) return
        val token = interlaceToken?.invoke()
        val socket = synchronized(lock) {
            if (generation.get() != request.generation) return
            Socket().also { upstreamSocket = it }
        }
        try {
            val upstream = try {
                openTimeshift(request.url, request.range, stream = true, socket = socket, direct = direct)
            } catch (_: IOException) {
                publishFailure(request, StreamFailure("Could not connect to the HDHomeRun live stream.", null))
                writeEmpty(client, 502)
                return
            }
            if (generation.get() != request.generation) return
            val failure = liveStreamFailure(upstream.status, upstream.headers, upstream.errorBody)
            if (failure != null) {
                publishFailure(request, failure)
                writeEmpty(client, 502)
                return
            }
            val start = contentRangeStart(upstream.headers["content-range"])
                ?: contentRangeStart(request.range?.replace("=", " "))?.takeIf { it < TIMESHIFT_LIVE_BYTE }
                ?: 0L
            val opened = RelaySnapshot(
                generation = request.generation,
                opened = true,
                startByte = start,
                bytesSent = upstream.extra.size.toLong(),
                joinLive = request.joinLive,
                requestedWallMs = request.requestedWallMs,
                openedAtMs = System.currentTimeMillis(),
            )
            _snapshot.update { current ->
                if (current.generation == request.generation) opened else current
            }
            client.soTimeout = 0
            val output = client.getOutputStream()
            output.write(LOCAL_OK)
            if (upstream.extra.isNotEmpty()) output.write(upstream.extra)
            output.flush()
            // Sniff the bytes already on their way to the player. A second
            // connection to this relay would steal the only client.
            val listener = interlaceListener
            val sniff = if (token != null && listener != null) InterlaceSniff() else null
            val report: (Boolean) -> Unit = { interlaced ->
                if (token != null && generation.get() == request.generation) {
                    runCatching { listener?.invoke(token, interlaced) }
                }
            }
            if (upstream.extra.isNotEmpty()) sniff?.add(upstream.extra, upstream.extra.size, report)
            copy(upstream.socket, output, request, upstream.extra.size.toLong(), sniff, report)
            sniff?.finish(report)
        } finally {
            runCatching { socket.close() }
            synchronized(lock) {
                if (upstreamSocket === socket) upstreamSocket = null
            }
        }
    }

    private fun copy(
        upstream: Socket,
        output: java.io.OutputStream,
        request: Armed,
        already: Long,
        sniff: InterlaceSniff?,
        report: (Boolean) -> Unit,
    ) {
        val input = upstream.getInputStream()
        val buffer = ByteArray(16 * 1024)
        var sent = already
        var published = already
        val gen = request.generation
        try {
            while (running && generation.get() == gen) {
                val read = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    publishFailure(request, StreamFailure("The live stream connection was interrupted.", null))
                    break
                }
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
                sniff?.add(buffer, read, report)
                sent += read
                if (sent - published >= 32 * 1024) {
                    publishBytes(gen, sent)
                    published = sent
                }
            }
        } catch (_: Exception) {
            // The player paused long enough to be seeked, or it left the channel.
        } finally {
            publishBytes(gen, sent)
        }
    }

    private fun publishFailure(request: Armed, failure: StreamFailure) {
        if (request.range != null) return
        _snapshot.update { current ->
            if (current.generation == request.generation) current.copy(failure = failure) else current
        }
    }

    private fun publishBytes(gen: Int, sent: Long) {
        _snapshot.update { current ->
            if (current.generation != gen || !current.opened) current
            else current.copy(bytesSent = sent)
        }
    }

    private fun closeActive() {
        runCatching { clientSocket?.close() }
        runCatching { upstreamSocket?.close() }
        clientSocket = null
        upstreamSocket = null
    }

    private fun writeEmpty(socket: Socket, code: Int) {
        val text = "HTTP/1.0 $code\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        runCatching { socket.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1)) }
    }

    private class InterlaceSniff {
        private val bytes = ByteArray(SCAN_BYTES)
        private var filled = 0
        private var mark = 0
        private var done = false

        fun add(src: ByteArray, length: Int, onFound: (Boolean) -> Unit) {
            if (done || length <= 0) return
            val take = minOf(length, bytes.size - filled)
            if (take > 0) {
                System.arraycopy(src, 0, bytes, filled, take)
                filled += take
            }
            scan(onFound)
            if (filled >= bytes.size) done = true
        }

        fun finish(onFound: (Boolean) -> Unit) {
            if (done || filled < 188 * 3) return
            done = true
            val found = runCatching { interlacedTransport(bytes, filled) }.getOrNull() ?: return
            onFound(found)
        }

        private fun scan(onFound: (Boolean) -> Unit) {
            while (!done && mark < MARKS.size && filled >= MARKS[mark]) {
                mark++
                val found = runCatching { interlacedTransport(bytes, filled) }.getOrNull() ?: continue
                done = true
                onFound(found)
            }
        }
    }

    private data class Armed(
        val url: String,
        val range: String?,
        val joinLive: Boolean,
        val requestedWallMs: Long,
        val generation: Int,
    )

    private companion object {
        val LOCAL_OK = "HTTP/1.0 200 OK\r\nContent-Type: video/mpeg\r\n\r\n"
            .toByteArray(Charsets.ISO_8859_1)
        const val SCAN_BYTES = 2 * 1024 * 1024
        val MARKS = intArrayOf(128 * 1024, 512 * 1024, SCAN_BYTES)
    }
}
