package net.etekweb.hdhrmeter.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ThreadLocalRandom

/** A byte past any live buffer. The record engine clamps a seek here back to the live edge. */
internal const val TIMESHIFT_LIVE_BYTE = 1_000_000_000_000_000L

/**
 * Origin of a record engine, without the `recorded_files.json` path.
 * Live pause and seek use `http://<origin>/auto/v<channel>`.
 */
internal fun recordEngineOrigin(storageUrl: String): String? {
    return try {
        val uri = URI(storageUrl)
        val scheme = uri.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        "$scheme://$host$port"
    } catch (_: Exception) {
        null
    }
}

/** 32-bit hex session id. A new one marks a channel change; a seek keeps the current one. */
internal fun newTimeshiftSessionId(): String {
    val value = ThreadLocalRandom.current().nextInt().toLong() and 0xffffffffL
    return value.toString(16).uppercase().padStart(8, '0')
}

/**
 * Record-engine live URL. [clientId] stays for the life of the player.
 * [sessionId] stays across seeks and changes when the channel changes.
 */
internal fun timeshiftStreamUrl(
    origin: String,
    guideNumber: String,
    clientId: String,
    sessionId: String,
): String? {
    if (!guideNumber.matches(CHANNEL_NUMBER)) return null
    if (clientId.isBlank() || sessionId.isBlank()) return null
    val base = origin.trimEnd('/')
    if (!base.startsWith("http://") && !base.startsWith("https://")) return null
    return "$base/auto/v$guideNumber?ClientID=$clientId&SessionID=$sessionId"
}

internal fun contentRangeStart(header: String?): Long? {
    if (header.isNullOrBlank()) return null
    val match = CONTENT_RANGE.find(header) ?: return null
    return match.groupValues[1].toLongOrNull()
}

/** Total length from `bytes START-END/TOTAL`. A star total means the file is still growing. */
internal fun contentRangeTotal(header: String?): Long? {
    if (header.isNullOrBlank()) return null
    val match = CONTENT_RANGE_TOTAL.find(header.trim()) ?: return null
    return match.groupValues[1].toLongOrNull()?.takeIf { it > 0L }
}

/**
 * File size from a playback response.
 * A ranged reply that does not start at byte 0 has a Content-Length for that slice only.
 */
internal fun recordingFileBytes(headers: Map<String, String>): Long {
    contentRangeTotal(headers["content-range"])?.let { return it }
    val start = contentRangeStart(headers["content-range"])
    if (start != null && start > 0L) return 0L
    return headers["content-length"]?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
}

/** Size of one recording, preferring a Range reply so later seeks can use byte offsets. */
internal data class RecordingProbe(
    val failure: StreamFailure? = null,
    val totalBytes: Long = 0,
    /** Range header the player should send. Null when the engine rejected Range. */
    val range: String? = null,
    val sampledAtMs: Long = 0L,
)

internal fun probeRecording(url: String, growing: Boolean = false): RecordingProbe {
    val ranged = openRecordingProbe(url, "bytes=0-")
    if (ranged.failure == null) {
        if (!growing) return ranged.copy(range = "bytes=0-")
        if (ranged.totalBytes > 1L && ranged.range != null) return ranged
        // A growing stream can omit a total. Ask for the edge as with DVR timeshift.
        val edge = openRecordingProbe(url, "bytes=$TIMESHIFT_LIVE_BYTE-", edge = true)
        return if (edge.totalBytes > 1L) edge.copy(range = "bytes=0-") else ranged
    }
    if (ranged.failure.hdhrCode != null) return ranged
    return openRecordingProbe(url, null)
}

private fun openRecordingProbe(url: String, range: String?, edge: Boolean = false): RecordingProbe {
    val opened = try {
        openTimeshift(url, range, stream = false)
    } catch (_: Exception) {
        return RecordingProbe(failure = StreamFailure("Could not open the recording.", null))
    }
    opened.use { head ->
        val now = System.currentTimeMillis()
        val contentRange = head.headers["content-range"]
        if (edge && head.status == 416) {
            return RecordingProbe(totalBytes = contentRangeTotal(contentRange) ?: 0L, sampledAtMs = now)
        }
        if (head.status !in 200..299) {
            val code = hdhrErrorCode(head.headers.mapValues { listOf(it.value) }, head.errorBody)
            val message = hdhrErrorMessage(code)
                ?: "Could not open the recording (HTTP ${head.status})."
            return RecordingProbe(failure = StreamFailure(message, code))
        }
        val start = contentRangeStart(contentRange)
        val total = if (edge) {
            start?.takeIf { it in 1L until TIMESHIFT_LIVE_BYTE } ?: 0L
        } else {
            recordingFileBytes(head.headers)
                .takeIf { it > 0L }
                ?: CONTENT_RANGE_END.find(contentRange.orEmpty())?.groupValues?.get(1)
                    ?.toLongOrNull()?.takeIf { it < Long.MAX_VALUE }?.plus(1L)
                ?: 0L
        }
        return RecordingProbe(
            totalBytes = total,
            range = range?.takeIf {
                head.status == 206 || start != null || head.headers["accept-ranges"]?.equals("bytes", true) == true
            },
            sampledAtMs = now,
        )
    }
}

/** Oldest and live byte offsets for one record-engine session, sampled back to back. */
data class TimeshiftProbe(
    val failure: StreamFailure? = null,
    val oldestByte: Long = 0,
    val liveByte: Long = 0,
    val atMs: Long = 0,
)

internal fun probeTimeshift(url: String): TimeshiftProbe {
    // The record engine expects each range request to close before the session is reopened.
    fun readEdge(offset: Long): TimeshiftSocket = openTimeshift(url, "bytes=$offset-", stream = false)
    val noRange = StreamFailure("The DVR did not provide a seekable live buffer.", null)
    return try {
        val oldestByte = readEdge(0).use { head ->
            liveStreamFailure(head.status, head.headers, head.errorBody)?.let {
                return TimeshiftProbe(failure = it)
            }
            contentRangeStart(head.headers["content-range"]) ?: 0L
        }
        readEdge(TIMESHIFT_LIVE_BYTE).use { edge ->
            liveStreamFailure(edge.status, edge.headers, edge.errorBody)?.let {
                return TimeshiftProbe(failure = it)
            }
            val liveByte = contentRangeStart(edge.headers["content-range"]) ?: return TimeshiftProbe(failure = noRange)
            TimeshiftProbe(
                oldestByte = oldestByte,
                liveByte = maxOf(liveByte, oldestByte),
                atMs = System.currentTimeMillis(),
            )
        }
    } catch (_: java.io.IOException) {
        TimeshiftProbe(failure = StreamFailure("Could not open the stream.", null))
    }
}

internal fun liveStreamFailure(status: Int, headers: Map<String, String>, body: String): StreamFailure? {
    if (status in 200..299) return null
    val code = hdhrErrorCode(headers.mapValues { listOf(it.value) }, body)
    val message = hdhrErrorMessage(code) ?: when (status) {
        404 -> hdhrErrorMessage(801) ?: "The tuner does not know this channel."
        503 -> "The tuner could not start this stream."
        else -> "The tuner returned HTTP $status."
    }
    return StreamFailure(message, code)
}

internal class TimeshiftSocket(
    val socket: Socket,
    val status: Int,
    val headers: Map<String, String>,
    val extra: ByteArray,
    val errorBody: String,
) : java.io.Closeable {
    override fun close() {
        runCatching { socket.close() }
    }
}

internal fun openTimeshift(
    url: String,
    range: String?,
    stream: Boolean,
    socket: Socket = Socket(),
    direct: Boolean = false,
): TimeshiftSocket {
    try {
        val endpoint = httpEndpoint(url) ?: throw java.io.IOException("The stream address could not be read.")
        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 8_000)
        socket.soTimeout = if (direct) 12_000 else 8_000
        val output = socket.getOutputStream()
        output.write(requestBytes(endpoint, range, stream, direct))
        output.flush()
        val head = readHttpHead(socket.getInputStream())
            ?: throw java.io.IOException("The stream did not answer.")
        val errorBody = if (head.status !in 200..299) {
            socket.soTimeout = 500
            readErrorBody(socket.getInputStream(), head.extra)
        } else {
            if (stream && !direct) socket.soTimeout = 0
            ""
        }
        return TimeshiftSocket(
            socket = socket,
            status = head.status,
            headers = head.headers,
            extra = if (head.status in 200..299) head.extra else ByteArray(0),
            errorBody = errorBody,
        )
    } catch (error: Exception) {
        runCatching { socket.close() }
        throw error
    }
}

internal data class HttpEndpoint(val host: String, val port: Int, val path: String)

internal fun httpEndpoint(url: String): HttpEndpoint? {
    return try {
        val uri = URI(url)
        val host = uri.host ?: return null
        val port = when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotBlank() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        HttpEndpoint(host, port, path)
    } catch (_: Exception) {
        null
    }
}

private fun requestBytes(endpoint: HttpEndpoint, range: String?, stream: Boolean, direct: Boolean): ByteArray {
    val bareHost = endpoint.host.removeSurrounding("[", "]")
    val host = if (bareHost.contains(':')) "[$bareHost]" else bareHost
    // A tuner request uses a close-delimited MPEG-TS body that the relay can forward directly.
    val version = if (direct) "1.0" else "1.1"
    val text = buildString {
        append("GET ${endpoint.path} HTTP/$version\r\n")
        append("Host: $host:${endpoint.port}\r\n")
        append("User-Agent: HDHomeRunSignalMeter\r\n")
        append("Accept: */*\r\n")
        append(if (stream && !direct) "Connection: keep-alive\r\n" else "Connection: close\r\n")
        if (!range.isNullOrBlank()) append("Range: $range\r\n")
        append("\r\n")
    }
    return text.toByteArray(Charsets.ISO_8859_1)
}

internal data class HttpHeadBytes(
    val status: Int,
    val headers: Map<String, String>,
    val extra: ByteArray,
)

/** Read and ignore a player request. The bytes after the header stay on [input]. */
internal fun consumeHttpHeaders(input: InputStream, max: Int = 8192, expectedPath: String? = null): Boolean {
    val buffer = ByteArray(max)
    var size = 0
    while (size < max) {
        val read = try {
            input.read(buffer, size, max - size)
        } catch (_: SocketTimeoutException) {
            return false
        }
        if (read < 0) return false
        size += read
        val split = indexOfHeaderEnd(buffer, size)
        if (split >= 0) {
            if (expectedPath == null) return true
            val requestLine = buffer.decodeToString(0, split).substringBefore('\n')
            return requestLine.split(' ').getOrNull(1) == expectedPath
        }
    }
    return false
}

internal fun readHttpHead(input: InputStream, max: Int = 8192): HttpHeadBytes? {
    val buffer = ByteArray(max)
    var size = 0
    while (size < max) {
        val read = try {
            input.read(buffer, size, max - size)
        } catch (_: SocketTimeoutException) {
            return null
        }
        if (read < 0) break
        size += read
        val split = indexOfHeaderEnd(buffer, size)
        if (split >= 0) {
            val text = buffer.copyOfRange(0, split).toString(Charsets.ISO_8859_1)
            val extra = if (split < size) buffer.copyOfRange(split, size) else ByteArray(0)
            return parseHttpHead(text, extra)
        }
    }
    return null
}

internal fun parseHttpHead(text: String, extra: ByteArray): HttpHeadBytes? {
    val lines = text.split("\r\n", "\n")
    val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return null
    val headers = LinkedHashMap<String, String>()
    for (line in lines.drop(1)) {
        if (line.isBlank()) continue
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }
    return HttpHeadBytes(status, headers, extra)
}

private fun indexOfHeaderEnd(buffer: ByteArray, size: Int): Int {
    for (index in 0 until size - 3) {
        if (buffer[index] == CR && buffer[index + 1] == LF &&
            buffer[index + 2] == CR && buffer[index + 3] == LF
        ) {
            return index + 4
        }
    }
    for (index in 0 until size - 1) {
        if (buffer[index] == LF && buffer[index + 1] == LF) return index + 2
    }
    return -1
}

private fun readErrorBody(input: InputStream, extra: ByteArray): String {
    val out = ByteArrayOutputStream()
    if (extra.isNotEmpty()) out.write(extra)
    val buffer = ByteArray(1024)
    var remaining = 2048 - extra.size
    while (remaining > 0) {
        val read = try {
            input.read(buffer, 0, minOf(buffer.size, remaining))
        } catch (_: SocketTimeoutException) {
            break
        } catch (_: Exception) {
            break
        }
        if (read < 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }
    return out.toByteArray().toString(Charsets.UTF_8)
}

private val CHANNEL_NUMBER = Regex("[0-9A-Za-z._-]+")
private val CONTENT_RANGE = Regex("""bytes\s+(\d+)\s*-""", RegexOption.IGNORE_CASE)
private val CONTENT_RANGE_TOTAL = Regex("""/\s*(\d+)\s*$""")
private val CONTENT_RANGE_END = Regex("""bytes\s+0\s*-\s*(\d+)\s*/\s*\*\s*$""", RegexOption.IGNORE_CASE)
private const val CR = '\r'.code.toByte()
private const val LF = '\n'.code.toByte()
