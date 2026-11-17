package net.etekweb.hdhrmeter.data

/** Planning assumptions for original ATSC 1.0 MPEG-2 streams, including audio/TS overhead.
 * Broadcasters use variable bitrates; these are estimates, not format requirements. */
internal enum class AtscRecordingRate(val label: String, val megabitsPerSecond: Int) {
    SD480i("480i", 3), HD720p("720p", 12), HD1080i("1080i", 16);

    fun bytesFor(durationMs: Long): Long =
        (durationMs.coerceAtLeast(0).toDouble() * megabitsPerSecond * 125.0)
            .coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()

    fun capacity(bytes: Long): String {
        val minutes = bytes.coerceAtLeast(0) / (megabitsPerSecond * 125_000L * 60)
        return "${minutes / 60} h ${minutes % 60} min"
    }
}

internal data class PlannedRecording(
    val channelNumber: String?,
    val startMs: Long,
    val endMs: Long?,
    val earlySec: Int,
    val lateSec: Int,
)

internal data class RecordingSpaceForecast(val bytes: Long, val unknownDurations: Int)

/** Only future bytes: saved files are already represented by the engine's used space.
 * A proposed airing replaces its scheduled/active entry when padding is edited. */
internal fun recordingSpaceForecast(
    scheduled: List<ScheduledRecording>,
    recordings: List<Recording>,
    channels: List<Channel>,
    nowMs: Long,
    proposed: PlannedRecording? = null,
): RecordingSpaceForecast {
    val plans = mutableListOf<PlannedRecording>()
    fun same(a: PlannedRecording, b: PlannedRecording): Boolean =
        a.channelNumber != null && a.channelNumber == b.channelNumber &&
            kotlin.math.abs(a.startMs - b.startMs) <= AIRING_MATCH_MS
    // Active files know their actual recording end, including padding.
    recordings.filter { it.stillRecording }.forEach {
        val start = it.startEpochMs ?: it.recordStartEpochMs ?: nowMs
        val end = it.recordEndEpochMs ?: it.endEpochMs?.plus(DEFAULT_RECORD_PADDING_SEC * 1000L)
        plans += PlannedRecording(it.channelNumber, start, end, 0, 0)
    }
    scheduled.forEach {
        val plan = PlannedRecording(it.channelNumber, it.startEpochMs, it.endEpochMs,
            it.startPaddingSec, it.endPaddingSec)
        if (plans.none { other -> same(plan, other) }) plans += plan
    }
    if (proposed != null) {
        plans.removeAll { same(it, proposed) }
        plans += proposed
    }
    var bytes = 0L
    var unknown = 0
    for (plan in plans) {
        val end = plan.endMs
        if (end == null || end <= plan.startMs) {
            unknown++
            continue
        }
        val remaining = (end + plan.lateSec.coerceAtLeast(0) * 1000L -
            maxOf(nowMs, plan.startMs - plan.earlySec.coerceAtLeast(0) * 1000L)).coerceAtLeast(0)
        val channel = channels.firstOrNull { it.guideNumber == plan.channelNumber }
        val rate = if (channel != null && !channel.hd && !channel.atsc3) AtscRecordingRate.SD480i
            else AtscRecordingRate.HD1080i
        val estimate = rate.bytesFor(remaining)
        bytes = if (Long.MAX_VALUE - bytes < estimate) Long.MAX_VALUE else bytes + estimate
    }
    return RecordingSpaceForecast(bytes, unknown)
}
