package net.etekweb.hdhrmeter.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/**
 * One airing from an XMLTV guide. Times are absolute instants.
 * [stopEpochMs] is exclusive.
 */
data class GuideProgram(
    val title: String,
    val subtitle: String?,
    val description: String?,
    val episode: String?,
    val startEpochMs: Long,
    val stopEpochMs: Long,
)

/**
 * Programs indexed for the lineup. [byNumber] keys are canonical guide numbers
 * such as `2.1`. [byName] holds callsigns that belong to only one channel.
 *
 * This is a plain class so a clock tick can replace the surrounding UI state
 * without walking every program to test equality.
 */
class GuideIndex(
    val byNumber: Map<String, List<GuideProgram>>,
    val byName: Map<String, List<GuideProgram>>,
    val channelCount: Int,
) {
    fun airing(guideNumber: String, guideName: String, nowMs: Long): GuideProgram? {
        val programs = byNumber[canonicalGuideNumber(guideNumber)]
            ?: guideNameKeys(guideName).firstNotNullOfOrNull { byName[it] }
            ?: return null
        return programs.airingAt(nowMs)
    }

    /** The next start or stop at or after [nowMs], so the UI can roll to the next program. */
    fun nextBoundary(nowMs: Long): Long? {
        var soonest = Long.MAX_VALUE
        for (programs in byNumber.values) soonest = sooner(soonest, programs, nowMs)
        for (programs in byName.values) soonest = sooner(soonest, programs, nowMs)
        return soonest.takeIf { it != Long.MAX_VALUE }
    }

    private fun sooner(soonest: Long, programs: List<GuideProgram>, nowMs: Long): Long {
        var edge = soonest
        for (program in programs) {
            if (program.stopEpochMs <= nowMs) continue
            val mark = if (program.startEpochMs > nowMs) program.startEpochMs else program.stopEpochMs
            if (mark < edge) edge = mark
        }
        return edge
    }
}

internal fun List<GuideProgram>.airingAt(nowMs: Long): GuideProgram? {
    var chosen: GuideProgram? = null
    for (program in this) {
        if (program.startEpochMs > nowMs) break
        if (nowMs < program.stopEpochMs) chosen = program
    }
    return chosen
}

/** `10.0.0.241` becomes `http://10.0.0.241:8080/xmlguide.xmltv`. */
internal fun guideUrlForInput(raw: String): String? {
    val host = normalizeHostInput(raw) ?: return null
    val bare = if (host.startsWith("[")) {
        val end = host.indexOf(']')
        if (end < 0) return null
        host.substring(0, end + 1)
    } else {
        host.substringBefore(':')
    }
    if (bare.isBlank()) return null
    return "http://$bare:8080/xmlguide.xmltv"
}

internal fun fetchGuide(url: String, nowEpochMs: Long = System.currentTimeMillis()): GuideIndex {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/xml, text/xml, application/gzip, */*")
        setRequestProperty("User-Agent", "HDHomeRunSignalMeter")
    }
    try {
        val code = try {
            connection.responseCode
        } catch (error: Exception) {
            throw HdhrException("Could not reach the guide.", error)
        }
        if (code !in 200..299) {
            throw HdhrException("Could not read the guide (HTTP $code).")
        }
        val index = try {
            connection.inputStream.use { raw -> parseXmltv(prepareGuideStream(raw), nowEpochMs) }
        } catch (error: HdhrException) {
            throw error
        } catch (error: Exception) {
            throw HdhrException("The guide could not be read.", error)
        }
        if (index.channelCount == 0) {
            throw HdhrException("The guide did not list any channels.")
        }
        return index
    } finally {
        connection.disconnect()
    }
}

internal fun prepareGuideStream(raw: InputStream): InputStream {
    val push = PushbackInputStream(raw, 2)
    val first = push.read()
    if (first < 0) return push
    val second = push.read()
    val gzip = first == 0x1f && second == 0x8b
    if (second >= 0) push.unread(second)
    push.unread(first)
    val body = if (gzip) GZIPInputStream(push) else push
    return skipDoctype(body)
}

/**
 * Drops a leading XMLTV doctype so the parser does not try to download `xmltv.dtd`.
 */
internal fun skipDoctype(input: InputStream): InputStream {
    val buffered = BufferedInputStream(input, 8 * 1024)
    buffered.mark(64 * 1024)
    val sniff = ByteArray(64 * 1024)
    val count = buffered.read(sniff)
    buffered.reset()
    if (count <= 0) return buffered
    val text = String(sniff, 0, count, Charsets.ISO_8859_1)
    val start = text.indexOf("<!DOCTYPE")
    if (start < 0) return buffered
    var index = start + "<!DOCTYPE".length
    var quote: Char? = null
    var inSubset = false
    while (index < text.length) {
        val char = text[index]
        if (quote != null) {
            if (char == quote) quote = null
            index++
            continue
        }
        when {
            char == '"' || char == '\'' -> quote = char
            char == '[' -> inSubset = true
            inSubset && text.startsWith("]>", index) -> {
                index += 2
                break
            }
            !inSubset && char == '>' -> {
                index++
                break
            }
        }
        index++
    }
    buffered.skip(index.toLong())
    return buffered
}

internal fun parseXmltv(input: InputStream, nowEpochMs: Long = Long.MIN_VALUE): GuideIndex {
    val cutoff = if (nowEpochMs > 60_000L) nowEpochMs - 60_000L else Long.MIN_VALUE
    val handler = GuideHandler(cutoff)
    val factory = SAXParserFactory.newInstance()
    factory.isNamespaceAware = false
    factory.isValidating = false
    runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    val parser = factory.newSAXParser()
    runCatching { parser.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
    runCatching { parser.setProperty("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
    parser.parse(input, handler)
    return handler.build()
}

internal fun parseXmltvTime(raw: String): Long? {
    val match = XMLTV_TIME.matchEntire(raw.trim()) ?: return null
    return try {
        val local = LocalDateTime.of(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt(),
            match.groupValues[5].toInt(),
            match.groupValues[6].toInt(),
        )
        val offsetSeconds = if (match.groupValues[7].isEmpty()) {
            0
        } else {
            val sign = if (match.groupValues[7] == "-") -1 else 1
            sign * (match.groupValues[8].toInt() * 3600 + match.groupValues[9].toInt() * 60)
        }
        local.toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds)).toEpochMilli()
    } catch (_: RuntimeException) {
        null
    }
}

internal fun canonicalGuideNumber(raw: String): String {
    val parts = raw.trim().split('.')
    if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return raw.trim()
    return parts.joinToString(".") { it.toInt().toString() }
}

/** Compact callsign, plus the same key with a trailing `DT` / `DT2` removed. */
internal fun guideNameKeys(raw: String): Set<String> {
    val compact = raw.uppercase(Locale.US).replace(NON_ALNUM, "")
    if (compact.length < 2 || compact.all { it.isDigit() }) return emptySet()
    val keys = linkedSetOf(compact)
    val stripped = compact.replace(TRAILING_DT, "")
    if (stripped.length >= 2 && stripped != compact) keys += stripped
    return keys
}

private class GuideHandler(private val cutoffEpochMs: Long) : DefaultHandler() {
    private class ChannelAcc {
        val numbers = linkedSetOf<String>()
        val names = linkedSetOf<String>()
        val programs = mutableListOf<GuideProgram>()
        var declared = false
    }

    private val channels = LinkedHashMap<String, ChannelAcc>()
    private var channelCount = 0
    private var currentChannel: String? = null
    private var programChannel: String? = null
    private var programStart: Long? = null
    private var programStop: Long? = null
    private var title: String? = null
    private var subtitle: String? = null
    private var description: String? = null
    private var episode: String? = null
    private var lengthMs: Long? = null
    private var capturing: String? = null
    private var episodeSystem: String? = null
    private var lengthUnits: String? = null
    private val text = StringBuilder()

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        when (val name = elementName(localName, qName)) {
            "channel" -> {
                currentChannel = attributes.getValue("id").orEmpty()
                channel(currentChannel.orEmpty()).declared = true
                channelCount++
            }
            "programme" -> {
                programChannel = attributes.getValue("channel").orEmpty()
                programStart = attributes.getValue("start")?.let(::parseXmltvTime)
                programStop = attributes.getValue("stop")?.let(::parseXmltvTime)
                title = null
                subtitle = null
                description = null
                episode = null
                lengthMs = null
            }
            "display-name", "title", "sub-title", "desc", "episode-num", "length" -> {
                capturing = name
                text.clear()
                if (name == "episode-num") episodeSystem = attributes.getValue("system")
                if (name == "length") lengthUnits = attributes.getValue("units")
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (capturing != null) text.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        when (val name = elementName(localName, qName)) {
            "display-name" -> {
                rememberName(channel(currentChannel.orEmpty()), clean(text))
                capturing = null
            }
            "title" -> {
                if (title == null) title = clean(text).ifBlank { null }
                capturing = null
            }
            "sub-title" -> {
                if (subtitle == null) subtitle = clean(text).ifBlank { null }
                capturing = null
            }
            "desc" -> {
                if (description == null) description = clean(text).ifBlank { null }
                capturing = null
            }
            "episode-num" -> {
                if (episode == null && episodeSystem.equals("onscreen", ignoreCase = true)) {
                    episode = clean(text).ifBlank { null }
                }
                capturing = null
            }
            "length" -> {
                val value = clean(text).toLongOrNull()
                if (value != null && value > 0) {
                    lengthMs = when (lengthUnits?.lowercase(Locale.US)) {
                        "seconds" -> value * 1_000L
                        "hours" -> value * 3_600_000L
                        else -> value * 60_000L
                    }
                }
                capturing = null
            }
            "channel" -> currentChannel = null
            "programme" -> finishProgram()
            else -> if (capturing == name) capturing = null
        }
    }

    fun build(): GuideIndex {
        val byNumber = LinkedHashMap<String, MutableList<GuideProgram>>()
        val nameOwner = HashMap<String, String>()
        val ambiguous = HashSet<String>()
        for ((id, channel) in channels) {
            if (!channel.declared && channel.programs.isEmpty()) continue
            channel.programs.sortBy { it.startEpochMs }
            for (number in channel.numbers) {
                byNumber.getOrPut(number) { mutableListOf() }.addAll(channel.programs)
            }
            for (key in channel.names) {
                val previous = nameOwner.put(key, id)
                if (previous != null && previous != id) ambiguous += key
            }
        }
        for (programs in byNumber.values) programs.sortBy { it.startEpochMs }
        val byName = LinkedHashMap<String, List<GuideProgram>>()
        for (channel in channels.values) {
            val programs = channel.programs.toList()
            for (key in channel.names) {
                if (key in ambiguous || key in byName) continue
                byName[key] = programs
            }
        }
        return GuideIndex(
            byNumber = byNumber.mapValues { it.value.toList() },
            byName = byName,
            channelCount = channelCount,
        )
    }

    private fun finishProgram() {
        val start = programStart
        val titleText = title
        val channelId = programChannel.orEmpty()
        val stop = programStop ?: start?.let { begin -> lengthMs?.let { begin + it } }
        programChannel = null
        programStart = null
        programStop = null
        if (start == null || stop == null || stop <= start || titleText.isNullOrBlank()) return
        if (stop <= cutoffEpochMs) return
        channel(channelId).programs += GuideProgram(
            title = titleText,
            subtitle = subtitle,
            description = description,
            episode = episode,
            startEpochMs = start,
            stopEpochMs = stop,
        )
    }

    private fun channel(id: String): ChannelAcc = channels.getOrPut(id) { ChannelAcc() }

    private fun rememberName(channel: ChannelAcc, displayName: String) {
        if (displayName.isBlank()) return
        val match = DISPLAY_NUMBER.matchEntire(displayName)
        if (match != null) {
            channel.numbers += canonicalGuideNumber(match.groupValues[1])
            val token = match.groupValues[2]
            if (token.isNotBlank()) channel.names += guideNameKeys(token)
        } else {
            channel.names += guideNameKeys(displayName)
        }
    }

    private fun clean(value: StringBuilder): String = value.toString().replace(WHITESPACE, " ").trim()

    private fun elementName(localName: String?, qName: String?): String {
        if (!localName.isNullOrBlank()) return localName
        return qName.orEmpty()
    }
}

private val XMLTV_TIME = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?:\s*([+-])(\d{2})(\d{2}))?$""")
private val DISPLAY_NUMBER = Regex("""^(\d+(?:\.\d+)?)(?:\s+(\S+))?$""")
private val NON_ALNUM = Regex("[^A-Z0-9]")
private val TRAILING_DT = Regex("""DT\d*$""")
private val WHITESPACE = Regex("""\s+""")
