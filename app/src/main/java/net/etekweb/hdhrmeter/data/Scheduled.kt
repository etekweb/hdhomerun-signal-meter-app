package net.etekweb.hdhrmeter.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * One airing the DVR is still going to record.
 * [oneOff] is a single DateTimeOnly rule. Otherwise the series rule records this episode,
 * and its padding applies to every episode of that series.
 */
data class ScheduledRecording(
    val id: String,
    val title: String,
    val episodeTitle: String?,
    val episodeNumber: String?,
    val synopsis: String?,
    val channelNumber: String?,
    val channelName: String?,
    val startEpochMs: Long,
    val endEpochMs: Long?,
    val originalAirdateEpochSec: Long?,
    val seriesId: String,
    val programId: String?,
    val ruleId: String,
    val oneOff: Boolean,
    val startPaddingSec: Int,
    val endPaddingSec: Int,
)

/** Upcoming airings, plus the rules they were matched to. */
data class ScheduledLibrary(
    val recordings: List<ScheduledRecording>,
    val rules: List<RecordingRule>,
    val warning: String?,
)

internal data class ScheduledEpisode(
    val seriesId: String,
    val programId: String?,
    val title: String?,
    val episodeTitle: String?,
    val episodeNumber: String?,
    val synopsis: String?,
    val channelNumber: String?,
    val channelName: String?,
    val startEpochSec: Long?,
    val endEpochSec: Long?,
    val originalAirdateEpochSec: Long?,
    val recordingRule: Int?,
    val recordingRuleExt: String?,
)

internal fun episodesUrl(deviceAuth: String, query: Map<String, String> = emptyMap()): String? {
    val auth = deviceAuth.trim()
    if (!usableEpisodeAuth(auth)) return null
    val pairs = ArrayList<Pair<String, String>>(query.size + 1)
    pairs += "DeviceAuth" to auth
    for ((key, value) in query) {
        if (key == "DeviceAuth") continue
        pairs += key to value
    }
    val encoded = pairs.joinToString("&") { (key, value) ->
        "${encodeEpisodeQuery(key)}=${encodeEpisodeQuery(value)}"
    }
    return "https://api.hdhomerun.com/api/episodes?$encoded"
}

/** Deletes one DateTimeOnly rule. A series rule is not deleted this way. */
internal fun deleteRuleQuery(ruleId: String): Map<String, String>? {
    val id = ruleId.trim()
    if (id.isEmpty()) return null
    return linkedMapOf(
        "Cmd" to "delete",
        "RecordingRuleID" to id,
    )
}

/**
 * Marks one episode DeletedDontRerecord so the series rule keeps recording the rest.
 * Null when the episode cannot be named.
 */
internal fun skipEpisodeQuery(seriesId: String, programId: String, startEpochSec: Long): Map<String, String>? {
    val series = seriesId.trim()
    val program = programId.trim()
    if (series.isEmpty() || program.isEmpty()) return null
    val query = linkedMapOf(
        "Cmd" to "delete",
        "SeriesID" to series,
        "ProgramID" to program,
    )
    if (startEpochSec > 0L) query["StartTime"] = startEpochSec.toString()
    return query
}

/** POST target for `cmd=delete`. An existing delete command is left as the engine wrote it. */
internal fun recordingDeleteUrl(cmdUrl: String): String? {
    val raw = cmdUrl.trim()
    if (!raw.startsWith("http://") && !raw.startsWith("https://")) return null
    if (raw.any { it.isISOControl() || it.isWhitespace() }) return null
    if (raw.contains("cmd=delete", ignoreCase = true)) return raw
    val join = if ('?' in raw) "&" else "?"
    return "$raw${join}cmd=delete"
}

/**
 * `null` is an empty episode list. A body that is not JSON could not be read.
 * The list may be an array, one episode, or series objects with nested episodes.
 */
internal fun parseScheduledEpisodes(body: String, seriesId: String): List<ScheduledEpisode>? {
    val trimmed = body.trim().removePrefix("\uFEFF")
    if (trimmed == "null") return emptyList()
    return try {
        when {
            trimmed.startsWith("[") -> readScheduledArray(JSONArray(trimmed), seriesId)
            trimmed.startsWith("{") -> readScheduledObject(JSONObject(trimmed), seriesId)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Airings that will still record and have not ended, soonest first.
 * Padding comes from the DateTimeOnly rule for that airing, or from the series rule.
 * An episode with no rule is left out, because it cannot be edited or canceled.
 */
internal fun scheduledRecordings(
    rules: List<RecordingRule>,
    episodes: List<ScheduledEpisode>,
    recordedProgramIds: Set<String>,
    nowMs: Long,
): List<ScheduledRecording> {
    val nowSec = nowMs / 1000L
    val built = ArrayList<ScheduledRecording>()
    val seen = HashSet<String>()
    for (episode in episodes) {
        if (!episodeWillRecord(episode.recordingRule, episode.recordingRuleExt, episode.programId, recordedProgramIds)) {
            continue
        }
        if (!episodeStillAhead(episode.startEpochSec, episode.endEpochSec, nowSec)) continue
        val startSec = episode.startEpochSec ?: continue
        if (startSec <= 0L) continue
        val rule = ruleForEpisode(rules, episode) ?: continue
        val seriesId = episode.seriesId.ifBlank { rule.seriesId }
        if (seriesId.isBlank()) continue
        val identity = episode.programId?.ifBlank { null } ?: rule.id
        val id = "$seriesId|$identity|$startSec"
        if (!seen.add(id)) continue
        val title = episode.title?.ifBlank { null } ?: rule.title?.ifBlank { null } ?: continue
        val channelNumber = episode.channelNumber?.ifBlank { null } ?: rule.channels.singleOrNull()
        built += ScheduledRecording(
            id = id,
            title = title,
            episodeTitle = episode.episodeTitle?.ifBlank { null },
            episodeNumber = episode.episodeNumber?.ifBlank { null },
            synopsis = episode.synopsis?.ifBlank { null },
            channelNumber = channelNumber,
            channelName = episode.channelName?.ifBlank { null },
            startEpochMs = startSec * 1_000L,
            endEpochMs = episode.endEpochSec?.takeIf { it > 0L }?.times(1_000L),
            originalAirdateEpochSec = episode.originalAirdateEpochSec?.takeIf { it > 0L },
            seriesId = seriesId,
            programId = episode.programId?.ifBlank { null },
            ruleId = rule.id,
            oneOff = rule.dateTimeOnlyEpochSec != null,
            startPaddingSec = rule.startPaddingSec,
            endPaddingSec = rule.endPaddingSec,
        )
    }
    return built.sortedBy { it.startEpochMs }
}

/** RecordingRule 1 will record the episode. Other extension values skip it. */
internal fun episodeWillRecord(
    recordingRule: Int?,
    recordingRuleExt: String?,
    programId: String?,
    recordedProgramIds: Set<String>,
): Boolean {
    if (recordingRule != 1) return false
    val flag = recordingRuleExt?.trim().orEmpty()
    if (flag.equals("DeletedDontRerecord", ignoreCase = true)) return false
    if (flag.isEmpty() || flag.equals("RecordDuplicate", ignoreCase = true)) return true
    if (flag.equals("RecordIfNotRecorded", ignoreCase = true)) {
        val id = programId?.trim().orEmpty()
        return id.isEmpty() || id !in recordedProgramIds
    }
    return false
}

/** An airing that has not ended. A missing end counts only when the start is still ahead. */
internal fun episodeStillAhead(startEpochSec: Long?, endEpochSec: Long?, nowSec: Long): Boolean {
    val end = endEpochSec ?: 0L
    if (end > 0L) return end > nowSec
    val start = startEpochSec ?: return false
    return start > nowSec
}

/** The DateTimeOnly rule for this airing, otherwise the series rule. A different one-off is left alone. */
private fun ruleForEpisode(rules: List<RecordingRule>, episode: ScheduledEpisode): RecordingRule? {
    val seriesId = episode.seriesId
    val mine = if (seriesId.isBlank()) rules else rules.filter { it.seriesId == seriesId }
    if (mine.isEmpty()) return null
    val startMs = (episode.startEpochSec ?: return null) * 1_000L
    oneOffRuleFor(mine, episode.channelNumber.orEmpty(), startMs)?.let { return it }
    return mine.firstOrNull { it.dateTimeOnlyEpochSec == null }
}

private fun readScheduledObject(item: JSONObject, seriesId: String): List<ScheduledEpisode> {
    if (item.has("Episodes")) {
        val nested = item.optJSONArray("Episodes") ?: return emptyList()
        return readScheduledArray(nested, item.optScheduledText("SeriesID") ?: seriesId)
    }
    return listOfNotNull(episodeFrom(item, seriesId))
}

private fun readScheduledArray(array: JSONArray, seriesId: String): List<ScheduledEpisode> {
    val episodes = ArrayList<ScheduledEpisode>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        if (item.has("Episodes")) {
            episodes += readScheduledObject(item, item.optScheduledText("SeriesID") ?: seriesId)
        } else {
            episodeFrom(item, seriesId)?.let { episodes += it }
        }
    }
    return episodes
}

private fun episodeFrom(item: JSONObject, fallbackSeriesId: String): ScheduledEpisode? {
    val seriesId = item.optScheduledText("SeriesID") ?: fallbackSeriesId.trim()
    val start = scheduledLong(item, "StartTime")
    val end = scheduledLong(item, "EndTime")
    val programId = item.optScheduledText("ProgramID")
    if (seriesId.isBlank() && programId == null && start == null && end == null) return null
    return ScheduledEpisode(
        seriesId = seriesId,
        programId = programId,
        title = item.optScheduledText("Title"),
        episodeTitle = item.optScheduledText("EpisodeTitle"),
        episodeNumber = item.optScheduledText("EpisodeNumber"),
        synopsis = item.optScheduledText("Synopsis"),
        channelNumber = item.optScheduledText("ChannelNumber"),
        channelName = item.optScheduledText("ChannelName"),
        startEpochSec = start,
        endEpochSec = end,
        originalAirdateEpochSec = scheduledLong(item, "OriginalAirdate")?.takeIf { it > 0L },
        recordingRule = scheduledRuleFlag(item),
        recordingRuleExt = item.optScheduledText("RecordingRuleExt"),
    )
}

private fun scheduledRuleFlag(item: JSONObject): Int? {
    if (!item.has("RecordingRule") || item.isNull("RecordingRule")) return null
    return when (val value = item.opt("RecordingRule")) {
        null -> null
        is Boolean -> if (value) 1 else 0
        is Number -> value.toInt()
        is String -> {
            val text = value.trim()
            text.toIntOrNull() ?: if (text.equals("true", ignoreCase = true)) 1 else null
        }
        else -> null
    }
}

private fun scheduledLong(item: JSONObject, key: String): Long? {
    if (!item.has(key) || item.isNull(key)) return null
    return when (val value = item.opt(key)) {
        null -> null
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}

private fun JSONObject.optScheduledText(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        null -> null
        is String -> value.trim().ifBlank { null }
        is Number -> {
            val whole = value.toLong()
            if (value.toDouble() == whole.toDouble()) whole.toString() else value.toString()
        }
        else -> null
    }
}

private fun usableEpisodeAuth(auth: String): Boolean {
    return auth.isNotEmpty() && auth.none { it.isISOControl() || it.isWhitespace() }
}

private fun encodeEpisodeQuery(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
