package net.etekweb.hdhrmeter.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream

/** The record engine's own default when a rule omits padding. */
internal const val DEFAULT_RECORD_PADDING_SEC = 30

/** HDHomeRun accepts at most an hour early and three hours late. */
internal const val MAX_START_PADDING_SEC = 60 * 60
internal const val MAX_END_PADDING_SEC = 3 * 60 * 60

/** Guide clocks and the DVR can disagree by a little and still be the same airing. */
internal const val AIRING_MATCH_SEC = 90L
internal const val AIRING_MATCH_MS = AIRING_MATCH_SEC * 1000L

/**
 * One HDHomeRun DVR rule.
 * [dateTimeOnlyEpochSec] records that single airing. Without it, the rule records the series.
 * [channels] empty means every channel. Padding is seconds before the start and after the end.
 */
data class RecordingRule(
    val id: String,
    val seriesId: String,
    val title: String?,
    val channels: List<String>,
    val dateTimeOnlyEpochSec: Long?,
    val startPaddingSec: Int,
    val endPaddingSec: Int,
)

/** What the lineup and the player should say about one airing. */
enum class AiringRecordStatus { None, Scheduled, Recording, Recorded }

data class ScheduleOutcome(
    val rules: List<RecordingRule>,
    val warning: String?,
    /** False when the change was saved but the rule list could not be read again. */
    val replaceRules: Boolean = true,
)

/** A guide page, reduced to the series id for one airing and how far the page reaches. */
internal data class GuideLookup(
    val seriesId: String?,
    /** Start time from the HDHomeRun guide, which is what a recording rule has to name. */
    val airingStartEpochSec: Long?,
    val latestEndEpochSec: Long,
    val channelSeen: Boolean,
    /** The page includes this airing's time on its channel, so paging further will not find it. */
    val coversAiring: Boolean,
)

internal sealed interface PaddingInput {
    data class Ready(val startSec: Int, val endSec: Int) : PaddingInput
    data class Rejected(val message: String) : PaddingInput
}

internal fun decodeHttpBytes(bytes: ByteArray): String {
    if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
        return try {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            bytes.toString(Charsets.UTF_8)
        }
    }
    return bytes.toString(Charsets.UTF_8)
}

/**
 * `null` is an empty schedule. Anything that is not a JSON array could not be read.
 */
internal fun parseRecordingRules(body: String): List<RecordingRule>? {
    val trimmed = body.trim().removePrefix("\uFEFF")
    if (trimmed == "null") return emptyList()
    if (!trimmed.startsWith("[")) return null
    val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return null
    val rules = ArrayList<RecordingRule>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        ruleFrom(item)?.let { rules += it }
    }
    return rules
}

internal fun recordingRulesUrl(deviceAuth: String, query: Map<String, String> = emptyMap()): String? {
    val auth = deviceAuth.trim()
    if (!usableAuth(auth)) return null
    val pairs = ArrayList<Pair<String, String>>(query.size + 1)
    pairs += "DeviceAuth" to auth
    for ((key, value) in query) {
        if (key == "DeviceAuth") continue
        pairs += key to value
    }
    val encoded = pairs.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
    }
    return "https://api.hdhomerun.com/api/recording_rules?$encoded"
}

/** JSON guide that carries SeriesID. XMLTV does not. [startEpochSec] pages forward from that time. */
internal fun hdhrJsonGuideUrl(deviceAuth: String, startEpochSec: Long?): String? {
    val auth = deviceAuth.trim()
    if (!usableAuth(auth)) return null
    val base = "https://api.hdhomerun.com/api/guide?DeviceAuth=${urlEncode(auth)}"
    if (startEpochSec == null || startEpochSec <= 0L) return base
    return "$base&Start=$startEpochSec"
}

internal fun recordingSyncUrl(engineOrigin: String): String? {
    val base = engineOrigin.trim().trimEnd('/')
    if (!base.startsWith("http://") && !base.startsWith("https://")) return null
    if (base.any { it.isISOControl() || it.isWhitespace() }) return null
    return "$base/recording_events.post?sync"
}

internal fun checkedPadding(startSec: Int, endSec: Int): Pair<Int, Int>? {
    if (startSec !in 0..MAX_START_PADDING_SEC) return null
    if (endSec !in 0..MAX_END_PADDING_SEC) return null
    return startSec to endSec
}

/** Minutes and seconds fields from the confirm dialog. Blank fields mean zero. */
internal fun recordingPadding(
    earlyMinutes: String,
    earlySeconds: String,
    lateMinutes: String,
    lateSeconds: String,
): PaddingInput {
    val early = readPadding(earlyMinutes, earlySeconds)
    val late = readPadding(lateMinutes, lateSeconds)
    if (early is PaddingPart.Bad || late is PaddingPart.Bad) {
        val seconds = (early as? PaddingPart.Bad)?.secondsTooHigh == true ||
            (late as? PaddingPart.Bad)?.secondsTooHigh == true
        val message = if (seconds) {
            "Seconds have to be under 60."
        } else {
            "Enter the early and late times as minutes and seconds."
        }
        return PaddingInput.Rejected(message)
    }
    val earlySec = (early as PaddingPart.Ok).seconds
    val lateSec = (late as PaddingPart.Ok).seconds
    if (earlySec > MAX_START_PADDING_SEC) return PaddingInput.Rejected("Start early can be up to 1 hour.")
    if (lateSec > MAX_END_PADDING_SEC) return PaddingInput.Rejected("End late can be up to 3 hours.")
    return PaddingInput.Ready(earlySec, lateSec)
}

/** Whole minutes, then leftover seconds, for the confirm fields. */
internal fun splitPadding(totalSec: Int): Pair<String, String> {
    val safe = totalSec.coerceAtLeast(0)
    return (safe / 60).toString() to (safe % 60).toString()
}

/**
 * Query for one airing. An existing rule is changed in place. A new one is a
 * DateTimeOnly rule, which records that start time on that channel and then expires.
 */
internal fun oneOffScheduleQuery(
    seriesId: String?,
    existingRuleId: String?,
    channelNumber: String,
    startEpochSec: Long,
    startPaddingSec: Int,
    endPaddingSec: Int,
): Map<String, String>? {
    val padding = checkedPadding(startPaddingSec, endPaddingSec) ?: return null
    val ruleId = existingRuleId?.trim().orEmpty()
    if (ruleId.isNotEmpty()) {
        return linkedMapOf(
            "Cmd" to "change",
            "RecordingRuleID" to ruleId,
            "StartPadding" to padding.first.toString(),
            "EndPadding" to padding.second.toString(),
        )
    }
    val series = seriesId?.trim().orEmpty()
    val channel = canonicalGuideNumber(channelNumber)
    if (series.isEmpty() || channel.isEmpty() || startEpochSec <= 0L) return null
    return linkedMapOf(
        "Cmd" to "add",
        "SeriesID" to series,
        "DateTimeOnly" to startEpochSec.toString(),
        "ChannelOnly" to channel,
        "StartPadding" to padding.first.toString(),
        "EndPadding" to padding.second.toString(),
    )
}

internal fun oneOffRuleFor(
    rules: List<RecordingRule>,
    channelNumber: String,
    startEpochMs: Long,
): RecordingRule? {
    val wanted = canonicalGuideNumber(channelNumber)
    var best: RecordingRule? = null
    var bestDelta = Long.MAX_VALUE
    for (rule in rules) {
        val at = rule.dateTimeOnlyEpochSec ?: continue
        if (wanted !in rule.channels) continue
        val delta = kotlin.math.abs(at - startEpochMs / 1000L)
        if (delta > AIRING_MATCH_SEC) continue
        if (delta < bestDelta) {
            best = rule
            bestDelta = delta
        }
    }
    return best
}

/** A series rule has no single start time. The title and channel are what the guide can match. */
internal fun seriesRuleFor(
    rules: List<RecordingRule>,
    channelNumber: String,
    title: String,
): RecordingRule? {
    val wanted = canonicalGuideNumber(channelNumber)
    for (rule in rules) {
        if (rule.dateTimeOnlyEpochSec != null) continue
        if (!sameProgramTitle(rule.title, title)) continue
        if (rule.channels.isEmpty() || wanted in rule.channels) return rule
    }
    return null
}

internal fun recordingForAiring(
    recordings: List<Recording>,
    channelNumber: String,
    startEpochMs: Long,
): Recording? {
    var best: Recording? = null
    var bestDelta = Long.MAX_VALUE
    for (recording in recordings) {
        if (!sameGuideChannel(recording.channelNumber, channelNumber)) continue
        val start = recording.startEpochMs ?: continue
        val delta = kotlin.math.abs(start - startEpochMs)
        if (delta > AIRING_MATCH_MS) continue
        if (delta < bestDelta) {
            best = recording
            bestDelta = delta
        }
    }
    return best
}

internal fun airingRecordStatus(
    rules: List<RecordingRule>,
    recordings: List<Recording>,
    channelNumber: String,
    program: GuideProgram,
    nowMs: Long,
): AiringRecordStatus {
    val file = recordingForAiring(recordings, channelNumber, program.startEpochMs)
    if (file != null && file.stillRecording) return AiringRecordStatus.Recording
    val oneOff = oneOffRuleFor(rules, channelNumber, program.startEpochMs)
    if (oneOff != null) {
        val windowStart = program.startEpochMs - oneOff.startPaddingSec * 1000L
        val windowEnd = program.stopEpochMs + oneOff.endPaddingSec * 1000L
        if (nowMs < windowStart) return AiringRecordStatus.Scheduled
        if (nowMs < windowEnd) return AiringRecordStatus.Recording
    }
    if (file != null && file.problem == null) return AiringRecordStatus.Recorded
    val series = seriesRuleFor(rules, channelNumber, program.title)
    if (series != null) {
        val windowStart = program.startEpochMs - series.startPaddingSec * 1000L
        val windowEnd = program.stopEpochMs + series.endPaddingSec * 1000L
        return when {
            nowMs < windowStart -> AiringRecordStatus.Scheduled
            nowMs < windowEnd -> AiringRecordStatus.Recording
            else -> AiringRecordStatus.None
        }
    }
    return AiringRecordStatus.None
}

internal fun readGuideLookup(
    body: String,
    channelNumber: String,
    startEpochSec: Long,
    title: String,
): GuideLookup? {
    val trimmed = body.trim().removePrefix("\uFEFF")
    if (!trimmed.startsWith("[")) return null
    val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return null
    val wanted = canonicalGuideNumber(channelNumber)
    var latestEnd = 0L
    var channelSeen = false
    var channelEnd = 0L
    var bestId: String? = null
    var bestStart: Long? = null
    var bestScore = Int.MAX_VALUE
    var bestDelta = Long.MAX_VALUE
    var matched = false
    for (index in 0 until array.length()) {
        val channel = array.optJSONObject(index) ?: continue
        val number = jsonScalar(channel, "GuideNumber") ?: continue
        val programs = channel.optJSONArray("Guide") ?: continue
        val sameChannel = canonicalGuideNumber(number) == wanted
        if (sameChannel) channelSeen = true
        for (programIndex in 0 until programs.length()) {
            val program = programs.optJSONObject(programIndex) ?: continue
            val start = jsonLong(program, "StartTime") ?: continue
            val end = jsonLong(program, "EndTime") ?: start
            if (end > latestEnd) latestEnd = end
            if (!sameChannel) continue
            if (end > channelEnd) channelEnd = end
            val delta = kotlin.math.abs(start - startEpochSec)
            if (delta > AIRING_MATCH_SEC) continue
            matched = true
            val id = jsonScalar(program, "SeriesID")
            val score = (if (delta == 0L) 0 else 1) +
                (if (sameProgramTitle(jsonScalar(program, "Title"), title)) 0 else 2)
            if (score < bestScore || (score == bestScore && delta < bestDelta)) {
                bestId = id
                bestStart = start
                bestScore = score
                bestDelta = delta
            }
        }
    }
    return GuideLookup(
        seriesId = bestId?.ifBlank { null },
        airingStartEpochSec = bestStart,
        latestEndEpochSec = latestEnd,
        channelSeen = channelSeen,
        coversAiring = matched || (channelSeen && channelEnd >= startEpochSec),
    )
}

private fun ruleFrom(item: JSONObject): RecordingRule? {
    val id = jsonScalar(item, "RecordingRuleID") ?: return null
    val seriesId = jsonScalar(item, "SeriesID") ?: return null
    val channels = jsonScalar(item, "ChannelOnly")
        ?.split('|')
        .orEmpty()
        .map { canonicalGuideNumber(it) }
        .filter { it.isNotEmpty() }
    return RecordingRule(
        id = id,
        seriesId = seriesId,
        title = jsonScalar(item, "Title"),
        channels = channels,
        dateTimeOnlyEpochSec = jsonLong(item, "DateTimeOnly")?.takeIf { it > 0L },
        startPaddingSec = jsonPadding(item, "StartPadding"),
        endPaddingSec = jsonPadding(item, "EndPadding"),
    )
}

private sealed interface PaddingPart {
    data class Ok(val seconds: Int) : PaddingPart
    data class Bad(val secondsTooHigh: Boolean) : PaddingPart
}

/** Blank is zero. Seconds must be 0 through 59. */
private fun readPadding(minutes: String, seconds: String): PaddingPart {
    val minuteText = minutes.trim().ifEmpty { "0" }
    val secondText = seconds.trim().ifEmpty { "0" }
    val minute = minuteText.toIntOrNull()
    val second = secondText.toIntOrNull()
    if (minute == null || second == null || minute < 0 || second < 0) return PaddingPart.Bad(false)
    if (second > 59) return PaddingPart.Bad(true)
    val total = minute.toLong() * 60L + second
    if (total > Int.MAX_VALUE) return PaddingPart.Ok(Int.MAX_VALUE)
    return PaddingPart.Ok(total.toInt())
}

private fun sameGuideChannel(left: String?, right: String): Boolean {
    val raw = left?.trim()?.ifBlank { null } ?: return false
    return canonicalGuideNumber(raw) == canonicalGuideNumber(right)
}

private fun sameProgramTitle(left: String?, right: String?): Boolean {
    val a = normalizeTitle(left) ?: return false
    val b = normalizeTitle(right) ?: return false
    return a == b
}

private fun normalizeTitle(raw: String?): String? {
    val text = raw?.trim()?.replace(WHITESPACE, " ")?.lowercase(Locale.US)
    return text?.ifBlank { null }
}

private fun usableAuth(auth: String): Boolean {
    return auth.isNotEmpty() && auth.none { it.isISOControl() || it.isWhitespace() }
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun jsonPadding(item: JSONObject, key: String): Int {
    val value = jsonLong(item, key) ?: return DEFAULT_RECORD_PADDING_SEC
    if (value < 0L) return DEFAULT_RECORD_PADDING_SEC
    return value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private fun jsonLong(item: JSONObject, key: String): Long? {
    if (!item.has(key) || item.isNull(key)) return null
    return when (val value = item.opt(key)) {
        null -> null
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}

private fun jsonScalar(item: JSONObject, key: String): String? {
    if (!item.has(key) || item.isNull(key)) return null
    return when (val value = item.opt(key)) {
        null -> null
        is String -> value.trim().ifBlank { null }
        is Number -> {
            val whole = value.toLong()
            if (value.toDouble() == whole.toDouble()) whole.toString() else value.toString()
        }
        else -> null
    }
}

private val WHITESPACE = Regex("\\s+")
