package net.etekweb.hdhrmeter.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.CRC32

/**
 * HDHomeRun discovery packets and the HTTP JSON/text the tuner already publishes.
 *
 * Discovery uses the documented type-length-value frame on UDP port 65001:
 * big-endian type and length, payload, then a little-endian Ethernet CRC-32.
 * Device type 1 is a tuner. Device id 0xFFFFFFFF matches every unit.
 *
 * Control get/set uses the same frame on TCP port 65001. A set carries the
 * variable name and the new value, each as a null-terminated string.
 */
internal object HdhrPackets {
    const val DISCOVER_PORT = 65001
    const val CONTROL_PORT = 65001
    const val TYPE_DISCOVER_REQ = 0x0002
    const val TYPE_DISCOVER_RPY = 0x0003
    const val TYPE_GETSET_REQ = 0x0004
    const val TYPE_GETSET_RPY = 0x0005
    const val TAG_DEVICE_TYPE = 0x01
    const val TAG_DEVICE_ID = 0x02
    const val TAG_GETSET_NAME = 0x03
    const val TAG_GETSET_VALUE = 0x04
    const val TAG_ERROR_MESSAGE = 0x05
    const val TAG_TUNER_COUNT = 0x10
    const val TAG_LINEUP_URL = 0x27
    const val TAG_BASE_URL = 0x2A
    const val TAG_MULTI_TYPE = 0x2D
    const val DEVICE_TYPE_TUNER = 0x00000001L

    /** Classic request: tuner type plus a wildcard id. Older firmware expects both tags. */
    fun discoverRequest(): ByteArray {
        val payload = ByteArrayOutputStream()
        writeTlvU32(payload, TAG_DEVICE_TYPE, DEVICE_TYPE_TUNER)
        writeTlvU32(payload, TAG_DEVICE_ID, 0xFFFFFFFFL)
        return seal(TYPE_DISCOVER_REQ, payload.toByteArray())
    }

    /** Newer request that omits the wildcard id, matching current libhdhomerun. */
    fun discoverRequestTypeOnly(): ByteArray {
        val payload = ByteArrayOutputStream()
        writeTlvU32(payload, TAG_DEVICE_TYPE, DEVICE_TYPE_TUNER)
        return seal(TYPE_DISCOVER_REQ, payload.toByteArray())
    }

    /** A get leaves [value] null. A set sends both tags. */
    fun getSetRequest(name: String, value: String?): ByteArray {
        return controlFrame(TYPE_GETSET_REQ, name, value, error = null)
    }

    fun controlFrame(type: Int, name: String?, value: String?, error: String?): ByteArray {
        val payload = ByteArrayOutputStream()
        if (name != null) writeTlvText(payload, TAG_GETSET_NAME, name)
        if (value != null) writeTlvText(payload, TAG_GETSET_VALUE, value)
        if (error != null) writeTlvText(payload, TAG_ERROR_MESSAGE, error)
        return seal(type, payload.toByteArray())
    }

    fun parseGetSetReply(frame: ByteArray): GetSetReply? {
        if (frame.size < 8) return null
        val type = readBeU16(frame, 0)
        val length = readBeU16(frame, 2)
        if (type != TYPE_GETSET_RPY) return null
        if (frame.size < 4 + length + 4) return null
        val expected = crc32(frame, 0, 4 + length)
        val actual = readLeU32(frame, 4 + length)
        if (expected != actual) return null

        var name: String? = null
        var value: String? = null
        var error: String? = null
        var index = 4
        val end = 4 + length
        while (index < end) {
            val tlv = readTlv(frame, index, end) ?: return null
            when (tlv.tag) {
                TAG_GETSET_NAME -> name = tlv.text()
                TAG_GETSET_VALUE -> value = tlv.text()
                TAG_ERROR_MESSAGE -> error = tlv.text()
            }
            index = tlv.next
        }
        return GetSetReply(name, value, error)
    }

    fun seal(type: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(4 + payload.size + 4)
        frame[0] = (type shr 8).toByte()
        frame[1] = type.toByte()
        frame[2] = (payload.size shr 8).toByte()
        frame[3] = payload.size.toByte()
        payload.copyInto(frame, destinationOffset = 4)
        val crc = crc32(frame, 0, 4 + payload.size)
        frame[4 + payload.size] = crc.toByte()
        frame[5 + payload.size] = (crc shr 8).toByte()
        frame[6 + payload.size] = (crc shr 16).toByte()
        frame[7 + payload.size] = (crc shr 24).toByte()
        return frame
    }

    fun parseDiscoverReply(datagram: ByteArray): UdpDevice? {
        if (datagram.size < 8) return null
        val type = readBeU16(datagram, 0)
        val length = readBeU16(datagram, 2)
        if (type != TYPE_DISCOVER_RPY) return null
        if (datagram.size < 4 + length + 4) return null
        val expected = crc32(datagram, 0, 4 + length)
        val actual = readLeU32(datagram, 4 + length)
        if (expected != actual) return null

        var deviceId = 0L
        var tunerCount = 0
        var baseUrl: String? = null
        var lineupUrl: String? = null
        val types = mutableListOf<Long>()
        var index = 4
        val end = 4 + length
        while (index < end) {
            val tlv = readTlv(datagram, index, end) ?: return null
            when (tlv.tag) {
                TAG_DEVICE_TYPE -> tlv.u32()?.let { if (it != 0L && it != 0xFFFFFFFFL) types += it }
                TAG_MULTI_TYPE -> {
                    var offset = 0
                    while (offset + 4 <= tlv.value.size) {
                        val value = readBeU32(tlv.value, offset)
                        if (value != 0L && value != 0xFFFFFFFFL) types += value
                        offset += 4
                    }
                }
                TAG_DEVICE_ID -> tlv.u32()?.let { deviceId = it }
                TAG_TUNER_COUNT -> if (tlv.value.isNotEmpty()) tunerCount = tlv.value[0].toInt() and 0xFF
                TAG_BASE_URL -> baseUrl = tlv.text()
                TAG_LINEUP_URL -> lineupUrl = tlv.text()
            }
            index = tlv.next
        }
        if (deviceId == 0L) return null
        if (types.isNotEmpty() && DEVICE_TYPE_TUNER !in types) return null
        return UdpDevice(deviceId, tunerCount, baseUrl, lineupUrl, types)
    }

    private fun writeTlvU32(out: ByteArrayOutputStream, tag: Int, value: Long) {
        out.write(tag)
        out.write(4)
        out.write((value shr 24).toInt())
        out.write((value shr 16).toInt())
        out.write((value shr 8).toInt())
        out.write(value.toInt())
    }

    private fun writeTlvText(out: ByteArrayOutputStream, tag: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        out.write(tag)
        writeVarLength(out, bytes.size + 1)
        out.write(bytes)
        out.write(0)
    }

    private fun writeVarLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 128) {
            out.write(length)
        } else {
            out.write((length and 0x7F) or 0x80)
            out.write(length shr 7)
        }
    }

    private class Tlv(val tag: Int, val value: ByteArray, val next: Int) {
        fun u32(): Long? = if (value.size == 4) readBeU32(value, 0) else null
        fun text(): String? {
            val raw = String(value, StandardCharsets.UTF_8).trim('\u0000', ' ', '\n', '\r', '\t')
            return raw.ifBlank { null }
        }
    }

    private fun readTlv(data: ByteArray, start: Int, end: Int): Tlv? {
        if (start >= end) return null
        val tag = data[start].toInt() and 0xFF
        var cursor = start + 1
        if (cursor >= end) return null
        var length = data[cursor].toInt() and 0xFF
        cursor++
        if (length and 0x80 != 0) {
            if (cursor >= end) return null
            length = (length and 0x7F) or ((data[cursor].toInt() and 0xFF) shl 7)
            cursor++
        }
        if (cursor + length > end) return null
        return Tlv(tag, data.copyOfRange(cursor, cursor + length), cursor + length)
    }
}

internal data class GetSetReply(val name: String?, val value: String?, val error: String?)

/** Device errors arrive as `ERROR: ...`, sometimes in the value instead of the error tag. */
internal fun controlProblem(reply: GetSetReply): String? {
    controlErrorMessage(reply.error)?.let { return it }
    val value = reply.value?.trim().orEmpty()
    if (value.startsWith("ERROR:", ignoreCase = true)) return controlErrorMessage(value)
    return null
}

internal fun controlErrorMessage(raw: String?): String? {
    val text = raw?.trim()?.removePrefix("ERROR:")?.trim().orEmpty()
    return text.ifBlank { null }
}

/**
 * One row per real tuner. [tunerCount] is the device's tuner count, so a live-relay
 * row cannot add a tuner the hardware does not have.
 * A tuner with no virtual channel is idle, even if a target address is still draining.
 */
internal fun tunerStations(
    status: List<TunerJson>,
    tunerCount: Int,
    viewers: List<ChannelViewer> = emptyList(),
): List<TunerStation> {
    val byIndex = linkedMapOf<Int, TunerJson>()
    for (row in status) {
        val index = row.index ?: continue
        if (tunerCount > 0 && index >= tunerCount) continue
        byIndex[index] = row
    }
    val count = if (tunerCount > 0) {
        tunerCount
    } else {
        (byIndex.keys.maxOrNull() ?: -1) + 1
    }
    return (0 until count).map { index -> stationRow(index, byIndex[index], viewers) }
}

private fun stationRow(index: Int, row: TunerJson?, viewers: List<ChannelViewer>): TunerStation {
    val number = row?.vctNumber?.trim().orEmpty()
    val name = row?.vctName?.trim().orEmpty()
    if (number.isEmpty() && name.isEmpty()) {
        return TunerStation(index = index, label = "Idle", target = null, inUse = false)
    }
    val label = when {
        number.isNotEmpty() && name.isNotEmpty() -> "$number $name"
        number.isNotEmpty() -> number
        else -> name
    }
    val own = targetHost(row?.targetIp)
    val relay = if (own == null && number.isNotEmpty()) {
        viewers.filter { it.channel == number }.map { it.target }.distinct()
    } else {
        emptyList()
    }
    return TunerStation(
        index = index,
        label = label,
        target = own ?: relay.takeIf { it.isNotEmpty() }?.joinToString(", "),
        inUse = true,
    )
}

/** Channel number from a DVR live row, for example `Live channel 4.1`. */
internal fun liveChannelNumber(name: String?): String? {
    if (name.isNullOrBlank()) return null
    return LIVE_CHANNEL.find(name.trim())?.groupValues?.get(1)?.ifBlank { null }
}

internal fun crc32(data: ByteArray, offset: Int, length: Int): Long {
    val crc = CRC32()
    crc.update(data, offset, length)
    return crc.value
}

private fun readBeU16(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}

private fun readBeU32(data: ByteArray, offset: Int): Long {
    return ((data[offset].toLong() and 0xFF) shl 24) or
        ((data[offset + 1].toLong() and 0xFF) shl 16) or
        ((data[offset + 2].toLong() and 0xFF) shl 8) or
        (data[offset + 3].toLong() and 0xFF)
}

private fun readLeU32(data: ByteArray, offset: Int): Long {
    return (data[offset].toLong() and 0xFF) or
        ((data[offset + 1].toLong() and 0xFF) shl 8) or
        ((data[offset + 2].toLong() and 0xFF) shl 16) or
        ((data[offset + 3].toLong() and 0xFF) shl 24)
}

internal fun deviceIdHex(id: Long): String = String.format(Locale.US, "%08X", id and 0xFFFFFFFFL)

internal fun parseDiscoverJson(body: String, fallbackHost: String): HdhrDevice {
    val json = JSONObject(stripBom(body))
    val base = usableBase(json.optStringOrNull("BaseURL"), fallbackHost)
    val host = hostOf(base) ?: fallbackHost
    val id = json.optStringOrNull("DeviceID") ?: "unknown"
    val name = json.optStringOrNull("FriendlyName") ?: "HDHomeRun $id"
    return HdhrDevice(
        deviceId = id,
        friendlyName = name,
        model = json.optStringOrNull("ModelNumber").orEmpty(),
        firmwareVersion = json.optStringOrNull("FirmwareVersion").orEmpty(),
        firmwareName = json.optStringOrNull("FirmwareName").orEmpty(),
        baseUrl = base.trimEnd('/'),
        host = host,
        tunerCount = json.optInt("TunerCount", 0).coerceAtLeast(0),
    )
}

internal fun deviceFromUdp(reply: UdpDevice, sourceIp: String): HdhrDevice {
    val base = usableBase(reply.baseUrl, sourceIp).trimEnd('/')
    val id = deviceIdHex(reply.deviceId)
    return HdhrDevice(
        deviceId = id,
        friendlyName = "HDHomeRun $id",
        model = "",
        firmwareVersion = "",
        firmwareName = "",
        baseUrl = base,
        host = hostOf(base) ?: sourceIp,
        tunerCount = reply.tunerCount,
    )
}

internal fun parseLineup(body: String): List<Channel> {
    val array = JSONArray(stripBom(body))
    val channels = ArrayList<Channel>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        if (item.has("Enabled") && item.optInt("Enabled", 1) == 0) continue
        val number = jsonText(item, "GuideNumber")
        val url = item.optStringOrNull("URL") ?: continue
        if (number.isBlank() || url.isBlank()) continue
        val tags = item.optString("Tags")
            .split(',')
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }
        channels += Channel(
            guideNumber = number,
            guideName = item.optStringOrNull("GuideName") ?: "Channel $number",
            streamUrl = url,
            hd = item.optInt("HD", 0) == 1,
            drm = item.optInt("DRM", 0) == 1 || "drm" in tags,
            favorite = item.optInt("Favorite", 0) == 1 || "favorite" in tags,
            videoCodec = item.optStringOrNull("VideoCodec"),
            audioCodec = item.optStringOrNull("AudioCodec"),
            frequencyHz = if (item.has("Frequency")) item.optLong("Frequency").takeIf { it > 0 } else null,
            atsc3 = item.optInt("ATSC3", 0) == 1 || "atsc3" in tags,
        )
    }
    channels.sortWith { a, b -> compareGuideNumbers(a.guideNumber, b.guideNumber) }
    return channels
}

internal fun parseStatusJson(body: String): List<TunerJson> = parseDeviceStatus(body).tuners

/**
 * A DVR adds a `live` row for the native app. That row is a viewer, not a tuner,
 * so a 2-tuner FLEX must not grow a Tuner 2.
 */
internal fun parseDeviceStatus(body: String): DeviceStatus {
    val trimmed = stripBom(body)
    val array = when {
        trimmed.startsWith("[") -> JSONArray(trimmed)
        trimmed.startsWith("{") -> JSONArray().put(JSONObject(trimmed))
        else -> return DeviceStatus(emptyList(), emptyList())
    }
    val tuners = ArrayList<TunerJson>(array.length())
    val viewers = ArrayList<ChannelViewer>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val resource = item.optStringOrNull("Resource")
        val index = tunerIndex(resource)
        val target = item.targetHosts().firstOrNull()
        if (index != null) {
            tuners += TunerJson(
                resource = resource,
                index = index,
                vctNumber = item.optStringOrNull("VctNumber"),
                vctName = item.optStringOrNull("VctName"),
                frequencyHz = if (item.has("Frequency")) item.optLong("Frequency").takeIf { it > 0 } else null,
                strengthPercent = item.optPercent("SignalStrengthPercent"),
                qualityPercent = item.optPercent("SignalQualityPercent"),
                symbolPercent = item.optPercent("SymbolQualityPercent"),
                networkRateBps = if (item.has("NetworkRate")) item.optLong("NetworkRate").takeIf { it > 0 } else null,
                targetIp = target,
            )
        } else {
            val channel = liveChannelNumber(item.optStringOrNull("Name")) ?: continue
            if (target != null) viewers += ChannelViewer(channel, target)
        }
    }
    return DeviceStatus(tuners, viewers)
}

internal fun parseTunerStatusText(body: String): TunerText {
    val fields = linkedMapOf<String, String>()
    for (match in STATUS_FIELD.findAll(body)) {
        fields[match.groupValues[1].lowercase(Locale.US)] = match.groupValues[2]
    }
    val channel = fields["ch"]?.let(::parseChannelField)
    val strength = fields["ss"]?.let(::parseMeterValue)
    val quality = fields["snq"]?.let(::parseMeterValue)
    val symbol = fields["seq"]?.let(::parseMeterValue)
    val bps = fields["bps"]?.substringBefore('(')?.toLongOrNull()?.takeIf { it > 0 }
    val lock = fields["lock"]?.takeUnless { it.equals("none", ignoreCase = true) }
    return TunerText(
        requestedModulation = channel?.modulation,
        rfChannel = channel?.rfChannel,
        tunedFrequencyHz = channel?.frequencyHz,
        lock = lock,
        strengthPercent = strength?.percent,
        strengthDetail = strength?.detail,
        qualityPercent = quality?.percent,
        qualityDetail = quality?.detail,
        symbolPercent = symbol?.percent,
        channelRateBps = bps,
    )
}

private data class ChannelField(val modulation: String?, val rfChannel: Int?, val frequencyHz: Long?)

private fun parseChannelField(raw: String): ChannelField {
    if (raw.equals("none", ignoreCase = true)) return ChannelField(null, null, null)
    val modulation = raw.substringBefore(':', raw).takeIf { it.isNotBlank() && it != raw }
    val value = raw.substringAfter(':', "")
    val number = value.toLongOrNull()
    return if (number == null) {
        ChannelField(modulation, null, null)
    } else if (number >= 100_000L) {
        ChannelField(modulation, rfChannelFromFrequencyHz(number), number)
    } else {
        ChannelField(modulation, number.toInt(), null)
    }
}

private data class MeterValue(val percent: Int?, val detail: String?)

private fun parseMeterValue(raw: String): MeterValue {
    val percent = raw.substringBefore('(').toIntOrNull()
    val detail = DETAIL.find(raw)?.groupValues?.get(1)?.trim()?.ifBlank { null }
    return MeterValue(percent, detail)
}

internal fun mergeSignal(
    json: TunerJson?,
    text: TunerText?,
    lineupFrequencyHz: Long?,
): SignalReading {
    val frequency = json?.frequencyHz ?: text?.tunedFrequencyHz ?: lineupFrequencyHz
    val rf = text?.rfChannel ?: frequency?.let(::rfChannelFromFrequencyHz)
    val lock = text?.lock
    val locked = when {
        lock != null -> true
        json?.vctNumber != null && (json.strengthPercent ?: 0) > 0 -> true
        else -> false
    }
    return SignalReading(
        tunerIndex = json?.index,
        virtualChannel = json?.vctNumber,
        virtualName = json?.vctName,
        strengthPercent = json?.strengthPercent ?: text?.strengthPercent,
        qualityPercent = json?.qualityPercent ?: text?.qualityPercent,
        symbolPercent = json?.symbolPercent ?: text?.symbolPercent,
        strengthDetail = text?.strengthDetail,
        qualityDetail = text?.qualityDetail,
        rfChannel = rf,
        frequencyHz = frequency,
        modulation = lock ?: text?.requestedModulation,
        channelRateBps = json?.networkRateBps ?: text?.channelRateBps,
        locked = locked,
    )
}

/**
 * US broadcast (6 MHz) center-frequency map. Cable plans do not match this table,
 * so a frequency that is not within 2 MHz of a broadcast center returns null.
 * The tuner status `ch=` field is the authoritative RF number when it is present.
 */
internal fun rfChannelFromFrequencyHz(hz: Long): Int? {
    val mhz = ((hz + 500_000L) / 1_000_000L).toInt()
    LOW_VHF[mhz]?.let { return it }
    nearestChannel(mhz, startMhz = 177, endMhz = 213, firstChannel = 7)?.let { return it }
    nearestChannel(mhz, startMhz = 473, endMhz = 695, firstChannel = 14)?.let { return it }
    return null
}

private val LOW_VHF = mapOf(57 to 2, 63 to 3, 69 to 4, 79 to 5, 85 to 6)

private fun nearestChannel(mhz: Int, startMhz: Int, endMhz: Int, firstChannel: Int): Int? {
    if (mhz < startMhz - 2 || mhz > endMhz + 2) return null
    val steps = Math.round((mhz - startMhz) / 6.0).toInt()
    val center = startMhz + steps * 6
    if (kotlin.math.abs(mhz - center) > 2) return null
    val channel = firstChannel + steps
    val last = firstChannel + (endMhz - startMhz) / 6
    return channel.takeIf { it in firstChannel..last }
}

internal fun compareGuideNumbers(a: String, b: String): Int {
    val ap = a.split('.').map { it.toIntOrNull() }
    val bp = b.split('.').map { it.toIntOrNull() }
    val count = maxOf(ap.size, bp.size)
    for (i in 0 until count) {
        val av = ap.getOrNull(i)
        val bv = bp.getOrNull(i)
        if (av == null && bv == null) continue
        if (av == null) return -1
        if (bv == null) return 1
        if (av != bv) return av.compareTo(bv)
    }
    return a.compareTo(b)
}

internal fun rewriteStreamUrl(channelUrl: String, deviceHost: String, tunerIndex: Int?): String {
    val uri = URI(channelUrl)
    var path = uri.rawPath ?: ""
    if (tunerIndex != null && path.contains("/auto/")) {
        path = path.replaceFirst("/auto/", "/tuner$tunerIndex/")
    }
    val port = when {
        uri.port != -1 -> uri.port
        path.contains("/auto/") || path.contains("/tuner") -> 5004
        else -> -1
    }
    val authority = if (port == -1) deviceHost else "$deviceHost:$port"
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "http://$authority$path$query"
}

internal fun withMobileTranscode(url: String): String {
    if (url.contains("transcode=")) return url
    return if (url.contains("?")) "$url&transcode=mobile" else "$url?transcode=mobile"
}

internal fun hdhrErrorMessage(code: Int?): String? = when (code) {
    801 -> "The tuner does not know this channel."
    802 -> "This tuner cannot transcode. It is not an EXTEND."
    803 -> "The tuner is busy. A channel scan may be running."
    804 -> "That tuner is already in use."
    805 -> "All tuners are in use."
    806 -> "The tuner could not tune this channel."
    807 -> "No video data. The station may be off air, or the signal is too weak to decode."
    808 -> "The HDHomeRun DVR could not write to its recording location."
    809 -> "The DVR has reached its playback connection limit."
    810 -> "The DVR storage is full."
    811 -> "This channel is copy protected and cannot be played here."
    else -> null
}

internal fun hdhrErrorCode(headers: Map<String, List<String>>, body: String): Int? {
    val header = headers.entries.firstOrNull { it.key.equals("X-HDHomeRun-Error", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.trim()
        ?.toIntOrNull()
    if (header != null) return header
    return HDHR_ERROR.find(body)?.value?.toIntOrNull()
}

internal fun normalizeHostInput(raw: String): String? {
    var text = raw.trim()
    if (text.isEmpty()) return null
    text = text.removePrefix("http://").removePrefix("https://")
    text = text.substringBefore('/').substringBefore('?')
    if (text.isBlank() || text.any { it.isWhitespace() }) return null
    return text
}

internal fun candidateBases(hostOrAuthority: String): List<String> {
    val hasPort = hostOrAuthority.startsWith("[") && hostOrAuthority.contains("]:") ||
        (!hostOrAuthority.startsWith("[") && hostOrAuthority.contains(':'))
    val bases = mutableListOf("http://$hostOrAuthority")
    if (!hasPort) {
        bases += "http://$hostOrAuthority:5004"
        bases += "http://$hostOrAuthority:80"
    }
    return bases.distinct()
}

internal fun usableBase(advertised: String?, sourceIp: String): String {
    if (advertised.isNullOrBlank()) return "http://$sourceIp"
    return try {
        val uri = URI(advertised)
        val host = uri.host ?: return "http://$sourceIp"
        val usable = if (host.endsWith(".local") || host == "localhost" || host == "127.0.0.1") {
            sourceIp
        } else {
            host
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        "http://$usable$port"
    } catch (_: Exception) {
        "http://$sourceIp"
    }
}

internal fun hostOf(url: String): String? = try {
    URI(url).host
} catch (_: Exception) {
    null
}

/**
 * Host of a tuner target. Loopback (`[::1]`, `127.0.0.1`) is the DVR pulling its own
 * tuner for a live relay, so it is not a viewer address.
 */
internal fun targetHost(targetIp: String?): String? {
    if (targetIp.isNullOrBlank()) return null
    var value = targetIp.trim()
    if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
        value = value.substring(1, value.length - 1).trim()
    }
    val bracket = BRACKET_HOST.matchEntire(value)
    if (bracket != null) return publicHost(bracket.groupValues[1])
    IPV4_WITH_OPTIONAL_PORT.matchEntire(value)?.let { return publicHost(it.groupValues[1]) }
    if (':' in value && '.' !in value) return publicHost(value.substringBefore('%'))
    return null
}

private fun publicHost(host: String): String? {
    var trimmed = host.trim().trim('"')
    IPV4_WITH_OPTIONAL_PORT.matchEntire(trimmed)?.let { trimmed = it.groupValues[1] }
    if (trimmed.isEmpty() || isLoopback(trimmed)) return null
    return trimmed
}

private fun isLoopback(host: String): Boolean {
    return host == "::1" || host.equals("localhost", ignoreCase = true) || host.startsWith("127.")
}

private fun JSONObject.targetHosts(): List<String> {
    if (!has("TargetIP") || isNull("TargetIP")) return emptyList()
    val raw = when (val value = opt("TargetIP")) {
        is JSONArray -> (0 until value.length()).map { value.optString(it) }
        is String -> listOf(value)
        null -> emptyList()
        else -> listOf(value.toString())
    }
    return raw.mapNotNull { targetHost(it) }.distinct()
}

internal fun tunerIndex(resource: String?): Int? {
    if (resource == null) return null
    return TUNER_INDEX.find(resource)?.groupValues?.get(1)?.toIntOrNull()
}

internal fun TunerJson.isIdle(): Boolean = vctNumber.isNullOrBlank() && targetIp.isNullOrBlank()

internal fun sameChannel(guideNumber: String, vctNumber: String?): Boolean {
    if (vctNumber.isNullOrBlank()) return false
    return guideNumber.trim() == vctNumber.trim()
}

/**
 * Picks the tuner row that belongs to this playback. A preferred index is only
 * trusted while it is still locking, already on this channel, or aimed at us.
 */
internal fun selectTuner(
    tuners: List<TunerJson>,
    guideNumber: String,
    localIp: String?,
    preferredIndex: Int?,
): TunerJson? {
    if (tuners.isEmpty()) return null
    val ours = if (localIp.isNullOrBlank()) {
        emptyList()
    } else {
        tuners.filter { targetHost(it.targetIp) == localIp }
    }
    ours.firstOrNull { sameChannel(guideNumber, it.vctNumber) }?.let { return it }
    if (preferredIndex != null) {
        val preferred = tuners.firstOrNull { it.index == preferredIndex }
        if (preferred != null && tunerBelongsToPlayback(preferred, guideNumber, localIp)) {
            return preferred
        }
    }
    val matched = tuners.filter { sameChannel(guideNumber, it.vctNumber) }
    if (matched.size == 1) return matched.first()
    return ours.firstOrNull()
}

private fun tunerBelongsToPlayback(tuner: TunerJson, guideNumber: String, localIp: String?): Boolean {
    val target = targetHost(tuner.targetIp)
    if (!localIp.isNullOrBlank() && target == localIp) return true
    if (sameChannel(guideNumber, tuner.vctNumber) && (target == null || target == localIp)) return true
    return tuner.vctNumber.isNullOrBlank() && target == null
}

private fun stripBom(text: String): String = text.trim().removePrefix("\uFEFF")

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().ifBlank { null }
}

private fun JSONObject.optPercent(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key, -1).takeIf { it >= 0 }
}

private fun jsonText(item: JSONObject, key: String): String {
    if (!item.has(key) || item.isNull(key)) return ""
    return when (val raw = item.get(key)) {
        is String -> raw.trim()
        is Number -> raw.toString()
        else -> raw.toString().trim()
    }
}

private val STATUS_FIELD = Regex("""([A-Za-z]+)=([^\s]+)""")
private val DETAIL = Regex("""\(([^)]*)\)""")
private val HDHR_ERROR = Regex("""\b(80[1-9]|81[01])\b""")
private val TUNER_INDEX = Regex("""tuner(\d+)""", RegexOption.IGNORE_CASE)
private val IPV4_WITH_OPTIONAL_PORT = Regex("""(\d{1,3}(?:\.\d{1,3}){3})(?::\d+)?""")
private val BRACKET_HOST = Regex("""\[([^\]]+)](?::\d+)?""")
private val LIVE_CHANNEL = Regex("""live channel\s+(\S+)""", RegexOption.IGNORE_CASE)
