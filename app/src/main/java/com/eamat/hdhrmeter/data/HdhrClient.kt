package com.eamat.hdhrmeter.data

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
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
            val hits = listenForDevices()
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

    fun status(device: HdhrDevice): List<TunerJson> {
        val response = http("${device.baseUrl}/status.json", readMs = 2500)
        if (response.code !in 200..299) return emptyList()
        return try {
            parseStatusJson(response.body)
        } catch (_: Exception) {
            emptyList()
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

    private fun listenForDevices(): List<UdpHit> {
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(0))
            val payloads = listOf(HdhrPackets.discoverRequest(), HdhrPackets.discoverRequestTypeOnly())
            val targets = broadcastTargets()
            val found = linkedMapOf<String, UdpHit>()
            repeat(2) { pass ->
                sendDiscover(socket, targets, payloads)
                collectReplies(socket, found, if (pass == 0) 900 else 700)
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

    private fun collectReplies(socket: DatagramSocket, found: MutableMap<String, UdpHit>, windowMs: Int) {
        val deadline = System.nanoTime() + windowMs * 1_000_000L
        val buffer = ByteArray(2048)
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt().coerceIn(1, windowMs)
            socket.soTimeout = remaining
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val parsed = HdhrPackets.parseDiscoverReply(bytes) ?: continue
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

    private data class HttpResponse(val code: Int, val body: String)

    private fun http(url: String, connectMs: Int = 4000, readMs: Int = 4000): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", "HDHomeRunSignalMeter")
        }
        try {
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
