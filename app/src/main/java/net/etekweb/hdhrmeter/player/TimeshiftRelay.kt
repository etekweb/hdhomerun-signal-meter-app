package net.etekweb.hdhrmeter.player

import net.etekweb.hdhrmeter.data.TIMESHIFT_LIVE_BYTE
import net.etekweb.hdhrmeter.data.consumeHttpHeaders
import net.etekweb.hdhrmeter.data.contentRangeStart
import net.etekweb.hdhrmeter.data.openTimeshift
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
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
)

/**
 * Local HTTP server in front of a record-engine timeshift URL.
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
    private var upstreamUrl: String? = null
    private var rangeHeader: String? = null
    private var joinLive = false
    private var requestedWallMs = 0L
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

    fun arm(url: String, range: String, joinLive: Boolean, requestedWallMs: Long): Int {
        val gen = generation.incrementAndGet()
        synchronized(lock) {
            upstreamUrl = url
            rangeHeader = range
            this.joinLive = joinLive
            this.requestedWallMs = requestedWallMs
        }
        _snapshot.value = RelaySnapshot(generation = gen)
        closeActive()
        return gen
    }

    fun stopForwarding() {
        synchronized(lock) { upstreamUrl = null }
        generation.incrementAndGet()
        _snapshot.value = RelaySnapshot()
        closeActive()
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
        if (!consumeHttpHeaders(client.getInputStream())) return
        val request = synchronized(lock) {
            val url = upstreamUrl ?: return
            Armed(url, rangeHeader, joinLive, requestedWallMs, generation.get())
        }
        synchronized(lock) { clientSocket = client }
        if (generation.get() != request.generation) return
        val upstream = try {
            openTimeshift(request.url, request.range, stream = true)
        } catch (_: Exception) {
            writeEmpty(client, 502)
            return
        }
        synchronized(lock) { upstreamSocket = upstream.socket }
        try {
            if (generation.get() != request.generation) return
            if (upstream.status !in 200..299) {
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
            if (generation.get() != request.generation) return
            _snapshot.value = opened
            client.soTimeout = 0
            val output = client.getOutputStream()
            output.write(LOCAL_OK)
            if (upstream.extra.isNotEmpty()) output.write(upstream.extra)
            output.flush()
            // Sniff the bytes already on their way to the player. A second
            // connection to this relay would steal the only client.
            val token = interlaceToken?.invoke()
            val listener = interlaceListener
            val sniff = if (token != null && listener != null) InterlaceSniff() else null
            val report: (Boolean) -> Unit = { interlaced ->
                if (token != null && generation.get() == request.generation) {
                    runCatching { listener?.invoke(token, interlaced) }
                }
            }
            if (upstream.extra.isNotEmpty()) sniff?.add(upstream.extra, upstream.extra.size, report)
            copy(upstream.socket, output, request.generation, upstream.extra.size.toLong(), sniff, report)
            sniff?.finish(report)
        } finally {
            upstream.close()
            synchronized(lock) {
                if (upstreamSocket === upstream.socket) upstreamSocket = null
            }
        }
    }

    private fun copy(
        upstream: Socket,
        output: java.io.OutputStream,
        gen: Int,
        already: Long,
        sniff: InterlaceSniff?,
        report: (Boolean) -> Unit,
    ) {
        val input = upstream.getInputStream()
        val buffer = ByteArray(16 * 1024)
        var sent = already
        var published = already
        try {
            while (running && generation.get() == gen) {
                val read = input.read(buffer)
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

    private fun publishBytes(gen: Int, sent: Long) {
        _snapshot.update { current ->
            if (current.generation != gen || !current.opened) current
            else current.copy(bytesSent = sent)
        }
    }

    private fun closeActive() {
        val sockets = synchronized(lock) {
            val open = listOfNotNull(clientSocket, upstreamSocket)
            clientSocket = null
            upstreamSocket = null
            open
        }
        sockets.forEach { runCatching { it.close() } }
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
