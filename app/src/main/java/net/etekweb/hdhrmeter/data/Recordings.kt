package net.etekweb.hdhrmeter.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * One finished or in-progress file on an HDHomeRun record engine.
 * Times are epoch milliseconds except [originalAirdateEpochSec], which is a UTC date.
 */
data class Recording(
    val id: String,
    val title: String,
    val episodeTitle: String?,
    val episodeNumber: String?,
    val synopsis: String?,
    val channelNumber: String?,
    val channelName: String?,
    val startEpochMs: Long?,
    val endEpochMs: Long?,
    val recordStartEpochMs: Long?,
    val recordEndEpochMs: Long?,
    val originalAirdateEpochSec: Long?,
    val stillRecording: Boolean,
    val problem: String?,
    val sourceName: String?,
)

/** Record engines found on the LAN, and the programs they are holding. */
data class RecordingLibrary(
    val recordings: List<Recording>,
    val enginesFound: Int,
    val warning: String?,
)

internal data class RecordEngine(
    val deviceId: String,
    val name: String,
    val host: String,
    val storageUrl: String,
)

internal data class RecordEngineDiscover(
    val name: String,
    val storageUrl: String?,
)

internal data class SeriesRequest(
    val url: String,
    val title: String,
    val seriesId: String?,
)

internal data class RecordingIndex(
    val pending: List<SeriesRequest>,
    val recordings: List<Recording>,
)

internal fun parseRecordEngineDiscover(body: String, sourceIp: String): RecordEngineDiscover? {
    val json = runCatching { JSONObject(stripRecordingBom(body)) }.getOrNull() ?: return null
    val name = json.optText("FriendlyName") ?: "HDHomeRun DVR"
    val storage = json.optText("StorageURL")?.let { rewriteLanUrl(it, sourceIp) }
    return RecordEngineDiscover(name, storage)
}

/**
 * `recorded_files.json` is a list of series. Each series names an [SeriesRequest.url]
 * unless its episodes are already inline.
 */
internal fun parseRecordingIndex(body: String, nowEpochMs: Long, sourceName: String?): RecordingIndex {
    return readRecordingDocument(body, fallbackTitle = null, seriesId = null, nowEpochMs, sourceName)
}

/** The document at a series EpisodesURL. */
internal fun parseEpisodeDocument(
    body: String,
    fallbackTitle: String?,
    seriesId: String?,
    nowEpochMs: Long,
    sourceName: String?,
): List<Recording> {
    return readRecordingDocument(body, fallbackTitle, seriesId, nowEpochMs, sourceName).recordings
}

internal fun sortRecordings(recordings: List<Recording>): List<Recording> {
    val seen = HashMap<String, Int>()
    return recordings
        .map { recording ->
            val count = (seen[recording.id] ?: 0) + 1
            seen[recording.id] = count
            if (count == 1) recording else recording.copy(id = "${recording.id}#$count")
        }
        .sortedByDescending { it.recordStartEpochMs ?: it.startEpochMs ?: 0L }
}

/** Point a record-engine URL at the host that answered discovery. Path and query stay. */
internal fun rewriteLanUrl(url: String, sourceHost: String): String {
    return try {
        val uri = URI(url)
        val host = uri.host ?: return url
        if (host == sourceHost) return url
        val literalIp = host.none { it.isLetter() }
        if (literalIp) return url
        val scheme = uri.scheme ?: "http"
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        "$scheme://$sourceHost$port$path$query"
    } catch (_: Exception) {
        url
    }
}

private fun readRecordingDocument(
    body: String,
    fallbackTitle: String?,
    seriesId: String?,
    nowEpochMs: Long,
    sourceName: String?,
): RecordingIndex {
    val trimmed = stripRecordingBom(body)
    if (trimmed.isEmpty()) return RecordingIndex(emptyList(), emptyList())
    return try {
        when {
            trimmed.startsWith("[") -> readArray(JSONArray(trimmed), fallbackTitle, seriesId, nowEpochMs, sourceName)
            trimmed.startsWith("{") -> readNode(JSONObject(trimmed), fallbackTitle, seriesId, nowEpochMs, sourceName)
            else -> throw HdhrException("The DVR returned a recording list that could not be read.")
        }
    } catch (error: HdhrException) {
        throw error
    } catch (error: Exception) {
        throw HdhrException("The DVR returned a recording list that could not be read.", error)
    }
}

private fun readArray(
    array: JSONArray,
    fallbackTitle: String?,
    seriesId: String?,
    nowEpochMs: Long,
    sourceName: String?,
): RecordingIndex {
    val pending = ArrayList<SeriesRequest>()
    val recordings = ArrayList<Recording>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val node = readNode(item, fallbackTitle, seriesId, nowEpochMs, sourceName)
        pending += node.pending
        recordings += node.recordings
    }
    return RecordingIndex(pending, recordings)
}

private fun readNode(
    item: JSONObject,
    fallbackTitle: String?,
    fallbackSeriesId: String?,
    nowEpochMs: Long,
    sourceName: String?,
): RecordingIndex {
    val title = item.optText("Title") ?: fallbackTitle
    val seriesId = item.optText("SeriesID") ?: fallbackSeriesId
    val episodes = item.optJSONArray("Episodes")
    if (episodes != null) {
        return readArray(episodes, title, seriesId, nowEpochMs, sourceName)
    }
    val episodesUrl = item.optText("EpisodesURL")
    val playable = item.optText("PlayURL") != null || item.optText("CmdURL") != null
    if (!episodesUrl.isNullOrBlank() && !playable) {
        if (title.isNullOrBlank()) return RecordingIndex(emptyList(), emptyList())
        return RecordingIndex(listOf(SeriesRequest(episodesUrl, title, seriesId)), emptyList())
    }
    val recording = recordingFrom(item, title, seriesId, nowEpochMs, sourceName)
        ?: return RecordingIndex(emptyList(), emptyList())
    return RecordingIndex(emptyList(), listOf(recording))
}

private fun recordingFrom(
    item: JSONObject,
    title: String?,
    seriesId: String?,
    nowEpochMs: Long,
    sourceName: String?,
): Recording? {
    val name = title?.trim()?.ifBlank { null } ?: return null
    val programId = item.optText("ProgramID")
    val episodeNumber = item.optText("EpisodeNumber")
    val recordStart = positiveEpoch(item, "RecordStartTime")
    val recordEnd = item.optEpoch("RecordEndTime")
    val recordSuccess = when {
        !item.has("RecordSuccess") || item.isNull("RecordSuccess") -> null
        else -> item.optInt("RecordSuccess", 1) != 0
    }
    val recordError = item.optText("RecordError")
    val stillRecording = recordSuccess != false && recordEnd != null &&
        (recordEnd == 0L || recordEnd * 1_000 > nowEpochMs)
    val problem = when {
        recordSuccess == false -> recordError ?: "This recording did not finish."
        !recordError.isNullOrBlank() -> recordError
        else -> null
    }
    val id = listOfNotNull(
        seriesId?.ifBlank { null },
        programId?.ifBlank { null },
        recordStart?.toString(),
        name,
        episodeNumber?.ifBlank { null },
    ).joinToString("|")
    return Recording(
        id = id,
        title = name,
        episodeTitle = item.optText("EpisodeTitle"),
        episodeNumber = episodeNumber,
        synopsis = item.optText("Synopsis"),
        channelNumber = item.optText("ChannelNumber"),
        channelName = item.optText("ChannelName"),
        startEpochMs = positiveEpoch(item, "StartTime")?.times(1_000),
        endEpochMs = positiveEpoch(item, "EndTime")?.times(1_000),
        recordStartEpochMs = recordStart?.times(1_000),
        recordEndEpochMs = positiveEpoch(item, "RecordEndTime")?.times(1_000),
        originalAirdateEpochSec = positiveEpoch(item, "OriginalAirdate"),
        stillRecording = stillRecording,
        problem = problem,
        sourceName = sourceName,
    )
}

private fun positiveEpoch(item: JSONObject, key: String): Long? = item.optEpoch(key)?.takeIf { it > 0 }

private fun JSONObject.optEpoch(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        null -> null
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}

private fun JSONObject.optText(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        null -> null
        is String -> value.trim().ifBlank { null }
        is Number -> value.toString()
        else -> null
    }
}

private fun stripRecordingBom(text: String): String = text.trim().removePrefix("\uFEFF")
