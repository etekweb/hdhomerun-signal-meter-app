package net.etekweb.hdhrmeter.data

import android.content.Context
import android.net.wifi.WifiManager
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL

class HdhrException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class StreamFailure(val message: String, val hdhrCode: Int?)

class HdhrClient(context: Context) {
    private val appContext = context.applicationContext

    fun discover(): List<HdhrDevice> {
        val lock = multicastLock()
        try {
            lock?.acquire()
            val hits = listenForDevices(
                listOf(HdhrPackets.discoverRequest(), HdhrPackets.discoverRequestTypeOnly()),
                HdhrPackets::parseDiscoverReply,
            )
            val found = ArrayList<HdhrDevice>(hits.size)
            for (hit in hits) {
                val preliminary = deviceFromUdp(hit.device, hit.sourceIp)
                val enriched = runCatching { fetchDiscover(preliminary.baseUrl, preliminary.host) }.getOrNull()
                    ?: runCatching { fetchDiscover("http://${hit.sourceIp}", hit.sourceIp) }.getOrNull()
                found += enriched ?: preliminary
            }
            return found
                .groupBy { it.deviceId }
                .map { (_, group) ->
                    group.minWith(
                        compareBy<HdhrDevice> { ipRank(it.host) }
                            .thenBy { if (it.model.isBlank()) 1 else 0 },
                    )
                }
                .sortedBy { it.friendlyName.lowercase() }
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }

    /**
     * Recordings on every HDHomeRun record engine that answers discovery.
     * An empty library with [RecordingLibrary.enginesFound] of 0 means no DVR was found.
     */
    /**
     * Origin of one record engine on the LAN, such as `http://10.0.0.20:59090`.
     * Null when no DVR storage answered. Live pause and seek stream from here.
     */
    fun recordEngineOrigin(): String? {
        val hits = discoverStorage()
        val engines = hits.mapNotNull { recordEngine(it) }
        val engine = engines.minWithOrNull(compareBy<RecordEngine> { ipRank(it.host) }) ?: return null
        return recordEngineOrigin(engine.storageUrl)
    }

    fun loadRecordings(nowEpochMs: Long = System.currentTimeMillis()): RecordingLibrary {
        val hits = discoverStorage()
        val engines = hits.mapNotNull { recordEngine(it) }
            .groupBy { it.deviceId }
            .map { (_, group) -> group.minWith(compareBy<RecordEngine> { ipRank(it.host) }) }
        if (engines.isEmpty()) return RecordingLibrary(emptyList(), 0, null)
        val recordings = ArrayList<Recording>()
        var failedEngines = 0
        var incomplete = false
        var lastError: Exception? = null
        for (engine in engines) {
            try {
                val loaded = recordingsOn(engine, nowEpochMs)
                recordings += loaded.recordings
                if (loaded.incomplete) incomplete = true
            } catch (error: Exception) {
                failedEngines++
                lastError = error
            }
        }
        if (recordings.isEmpty() && failedEngines == engines.size) {
            val message = (lastError as? HdhrException)?.message ?: "Could not read recordings."
            throw HdhrException(message, lastError)
        }
        val warning = when {
            failedEngines > 0 || incomplete -> "Some recordings could not be read."
            else -> null
        }
        val named = if (engines.size > 1) recordings else recordings.map { it.copy(sourceName = null) }
        return RecordingLibrary(sortRecordings(named), engines.size, warning)
    }

    fun deviceForHost(raw: String): HdhrDevice {
        val host = normalizeHostInput(raw) ?: throw HdhrException("Enter an IP address or hostname.")
        var lastError: Exception? = null
        for (base in candidateBases(host)) {
            try {
                val fallback = hostOf(base) ?: host.substringBefore(':')
                return fetchDiscover(base, fallback)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw HdhrException("No HDHomeRun answered at $host.", lastError)
    }

    /**
     * Stores the favorite on the tuner, so every device on this lineup shares it.
     * [favorite] true posts `+`, false posts `-`. This does not hide the channel.
     */
    fun setChannelFavorite(device: HdhrDevice, guideNumber: String, favorite: Boolean) {
        val url = favoritePostUrl(device.baseUrl, guideNumber, favorite)
            ?: throw HdhrException("That channel cannot be marked as a favorite.")
        try {
            val response = http(url, method = "POST")
            if (response.code !in 200..299) {
                throw HdhrException("Could not update favorites (HTTP ${response.code}).")
            }
        } catch (error: HdhrException) {
            throw error
        } catch (_: Exception) {
            throw HdhrException("Could not update favorites.")
        }
    }

    fun lineup(device: HdhrDevice): List<Channel> {
        val tuned = http("${device.baseUrl}/lineup.json?tuning")
        val response = if (tuned.code in 200..299 && tuned.body.trimStart().startsWith("[")) {
            tuned
        } else {
            http("${device.baseUrl}/lineup.json")
        }
        if (response.code !in 200..299) {
            throw HdhrException("Could not read the channel lineup (HTTP ${response.code}).")
        }
        return try {
            parseLineup(response.body)
        } catch (error: Exception) {
            throw HdhrException("The tuner returned a channel list that could not be read.", error)
        }
    }

    fun loadGuide(url: String, nowEpochMs: Long = System.currentTimeMillis()): GuideIndex {
        return fetchGuide(url, nowEpochMs)
    }

    /**
     * Guide associated with this tuner. DeviceAuth is read from the tuner's
     * discover.json and sent to SiliconDust's XMLTV service. The token rotates,
     * so it is not saved.
     */
    fun loadHdhrGuide(device: HdhrDevice, nowEpochMs: Long = System.currentTimeMillis()): GuideIndex {
        val discover = http("${device.baseUrl.trimEnd('/')}/discover.json", readMs = 8_000)
        if (discover.code !in 200..299) {
            throw HdhrException("Could not read the tuner (HTTP ${discover.code}).")
        }
        val auth = parseDeviceAuth(discover.body)
            ?: throw HdhrException("The tuner did not provide guide access.")
        val url = hdhrGuideUrl(auth)
            ?: throw HdhrException("The tuner did not provide guide access.")
        return fetchGuide(url, nowEpochMs)
    }

    fun status(device: HdhrDevice): List<TunerJson> {
        val response = http("${device.baseUrl}/status.json", readMs = 2500)
        if (response.code !in 200..299) return emptyList()
        return try {
            parseStatusJson(response.body)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Tuner rows for the guide list. Unlike [status], a failed read is reported. */
    fun tunerStatus(device: HdhrDevice): DeviceStatus {
        val response = http("${device.baseUrl}/status.json", readMs = 2500)
        if (response.code !in 200..299) {
            throw HdhrException("Could not read tuner status (HTTP ${response.code}).")
        }
        return try {
            parseDeviceStatus(response.body)
        } catch (error: Exception) {
            throw HdhrException("The tuner status could not be read.", error)
        }
    }

    /**
     * Drops another host's lock, then stops the tuner.
     * The tuner list button is the confirmation SiliconDust requires before `lockkey force`.
     */
    fun forceClearTuner(device: HdhrDevice, index: Int) {
        if (index < 0) throw HdhrException("That tuner does not exist.")
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(device.host, HdhrPackets.CONTROL_PORT), 4_000)
                socket.soTimeout = 4_000
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                exchange(output, input, "/tuner$index/lockkey", "force")
                val stopped = exchange(output, input, "/tuner$index/channel", "none")
                val problem = controlProblem(stopped)
                if (problem != null) throw HdhrException("Could not clear tuner $index. $problem")
                val value = stopped.value
                if (value != null && !value.equals("none", ignoreCase = true)) {
                    throw HdhrException("Could not clear tuner $index. It stayed on $value.")
                }
            }
        } catch (error: HdhrException) {
            throw error
        } catch (_: Exception) {
            throw HdhrException("Could not clear tuner $index.")
        }
    }

    fun tunerText(device: HdhrDevice, index: Int): TunerText? {
        val response = http("${device.baseUrl}/tuner$index/status", readMs = 2500)
        if (response.code !in 200..299 || response.body.isBlank()) return null
        return parseTunerStatusText(response.body)
    }

    /**
     * Opens the stream only long enough to read an error status.
     * A successful response is closed without reading the body, because an MPEG-TS stream does not end.
     */
    fun probeStream(url: String): StreamFailure? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "HDHomeRunSignalMeter")
        }
        try {
            val code = connection.responseCode
            if (code in 200..299) return null
            val body = connection.errorStream?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }.orEmpty()
            val headers = buildMap {
                for ((key, value) in connection.headerFields) {
                    if (key != null && value != null) put(key, value)
                }
            }
            val hdhrCode = hdhrErrorCode(headers, body)
            val message = hdhrErrorMessage(hdhrCode) ?: when (code) {
                404 -> hdhrErrorMessage(801) ?: "The tuner does not know this channel."
                503 -> "The tuner could not start this stream."
                else -> "The tuner returned HTTP $code."
            }
            return StreamFailure(message, hdhrCode)
        } catch (_: Exception) {
            return StreamFailure("Could not open the stream.", null)
        } finally {
            connection.disconnect()
        }
    }

    fun localAddressToward(host: String): String? {
        return try {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName(host), 9)
                socket.localAddress?.hostAddress
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchDiscover(base: String, fallbackHost: String): HdhrDevice {
        val response = http("${base.trimEnd('/')}/discover.json")
        if (response.code !in 200..299) {
            throw HdhrException("Tuner returned HTTP ${response.code}.")
        }
        return try {
            parseDiscoverJson(response.body, fallbackHost)
        } catch (error: Exception) {
            throw HdhrException("That address did not return HDHomeRun device info.", error)
        }
    }

    private data class UdpHit(val device: UdpDevice, val sourceIp: String)

    private fun discoverStorage(): List<UdpHit> {
        val lock = multicastLock()
        try {
            lock?.acquire()
            return listenForDevices(
                listOf(HdhrPackets.discoverStorageRequest(), HdhrPackets.discoverStorageRequestTypeOnly()),
                HdhrPackets::parseStorageDiscoverReply,
            )
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }

    private fun recordEngine(hit: UdpHit): RecordEngine? {
        val sourceIp = hit.sourceIp
        val base = hit.device.baseUrl?.let { usableBase(it, sourceIp) }?.trimEnd('/')
        var name = "HDHomeRun DVR"
        var storage = hit.device.storageUrl?.let { rewriteLanUrl(it, sourceIp) }
        if (base != null) {
            val response = runCatching { http("$base/discover.json", readMs = 4_000) }.getOrNull()
            if (response != null && response.code in 200..299) {
                parseRecordEngineDiscover(response.body, sourceIp)?.let { parsed ->
                    name = parsed.name
                    if (!parsed.storageUrl.isNullOrBlank()) storage = parsed.storageUrl
                }
            }
        }
        if (storage == null && base != null) storage = "$base/recorded_files.json"
        val url = storage?.let { httpRecordingUrl(it) } ?: return null
        return RecordEngine(
            deviceId = deviceIdHex(hit.device.deviceId),
            name = name,
            host = hostOf(url) ?: sourceIp,
            storageUrl = url,
        )
    }

    private data class EngineRecordings(val recordings: List<Recording>, val incomplete: Boolean)

    private fun recordingsOn(engine: RecordEngine, nowEpochMs: Long): EngineRecordings {
        val response = http(engine.storageUrl, readMs = 20_000)
        if (response.code !in 200..299) {
            throw HdhrException("Could not read recordings (HTTP ${response.code}).")
        }
        val index = try {
            parseRecordingIndex(response.body, nowEpochMs, engine.name)
        } catch (error: HdhrException) {
            throw error
        } catch (error: Exception) {
            throw HdhrException("The DVR returned a recording list that could not be read.", error)
        }
        val recordings = ArrayList(index.recordings)
        var incomplete = false
        for (series in index.pending) {
            val url = httpRecordingUrl(rewriteLanUrl(series.url, engine.host))
            if (url == null) {
                incomplete = true
                continue
            }
            try {
                val episodes = http(url, readMs = 15_000)
                if (episodes.code !in 200..299) {
                    incomplete = true
                    continue
                }
                recordings += parseEpisodeDocument(
                    episodes.body,
                    series.title,
                    series.seriesId,
                    nowEpochMs,
                    engine.name,
                )
            } catch (_: Exception) {
                incomplete = true
            }
        }
        if (recordings.isEmpty() && incomplete) {
            throw HdhrException("Could not read recordings.")
        }
        return EngineRecordings(recordings, incomplete)
    }

    private fun listenForDevices(
        payloads: List<ByteArray>,
        parse: (ByteArray) -> UdpDevice?,
    ): List<UdpHit> {
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(0))
            val targets = broadcastTargets()
            val found = linkedMapOf<String, UdpHit>()
            repeat(2) { pass ->
                sendDiscover(socket, targets, payloads)
                collectReplies(socket, found, if (pass == 0) 900 else 700, parse)
            }
            return found.values.toList()
        } finally {
            socket.close()
        }
    }

    private fun sendDiscover(
        socket: DatagramSocket,
        targets: List<InetAddress>,
        payloads: List<ByteArray>,
    ) {
        for (target in targets) {
            for (payload in payloads) {
                try {
                    socket.send(DatagramPacket(payload, payload.size, target, HdhrPackets.DISCOVER_PORT))
                } catch (_: Exception) {
                    // One interface can reject a broadcast without invalidating the others.
                }
            }
        }
    }

    private fun collectReplies(
        socket: DatagramSocket,
        found: MutableMap<String, UdpHit>,
        windowMs: Int,
        parse: (ByteArray) -> UdpDevice?,
    ) {
        val deadline = System.nanoTime() + windowMs * 1_000_000L
        val buffer = ByteArray(2048)
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt().coerceIn(1, windowMs)
            socket.soTimeout = remaining
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val parsed = parse(bytes) ?: continue
                val sourceIp = (packet.address as? Inet4Address)?.hostAddress ?: continue
                val key = deviceIdHex(parsed.deviceId)
                val hit = UdpHit(parsed, sourceIp)
                val previous = found[key]
                if (previous == null || ipRank(sourceIp) < ipRank(previous.sourceIp)) {
                    found[key] = hit
                }
            } catch (_: SocketTimeoutException) {
                // The window elapsed with no further replies.
            }
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        runCatching { targets += InetAddress.getByName("255.255.255.255") }
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
        for (network in interfaces) {
            val usable = runCatching { network.isUp && !network.isLoopback }.getOrDefault(false)
            if (!usable) continue
            val name = network.name.lowercase()
            if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("ipsec")) continue
            for (address in network.interfaceAddresses) {
                address.broadcast?.let { targets += it }
            }
        }
        return targets.toList()
    }

    private fun multicastLock(): WifiManager.MulticastLock? {
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return null
        return try {
            wifi.createMulticastLock("hdhr-discover").apply { setReferenceCounted(false) }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun exchange(output: OutputStream, input: InputStream, name: String, value: String): GetSetReply {
        output.write(HdhrPackets.getSetRequest(name, value))
        output.flush()
        val frame = readControlFrame(input)
        return HdhrPackets.parseGetSetReply(frame)
            ?: throw HdhrException("The tuner returned a control reply that could not be read.")
    }

    private fun readControlFrame(input: InputStream): ByteArray {
        val header = readExact(input, 4)
        val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        if (length > 1452) throw HdhrException("The tuner returned a control reply that could not be read.")
        return header + readExact(input, length + 4)
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) throw HdhrException("The tuner closed the control connection.")
            offset += read
        }
        return buffer
    }

    private fun httpRecordingUrl(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        return trimmed
    }

    private data class HttpResponse(val code: Int, val body: String)

    private fun http(
        url: String,
        method: String = "GET",
        connectMs: Int = 4000,
        readMs: Int = 4000,
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectMs
            readTimeout = readMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", "HDHomeRunSignalMeter")
            if (method == "POST") {
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
        }
        try {
            if (method == "POST") connection.outputStream.use { }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return HttpResponse(code, body)
        } finally {
            connection.disconnect()
        }
    }
}

private fun ipRank(host: String): Int = when {
    host.startsWith("169.254.") -> 2
    host.startsWith("127.") -> 3
    else -> 0
}
