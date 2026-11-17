package net.etekweb.hdhrmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSpaceTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val hd = AtscRecordingRate.HD1080i

    private fun airing(start: Long = now, end: Long? = now + hour) = ScheduledRecording(
        id = "airing", title = "Show", episodeTitle = null, episodeNumber = null, synopsis = null,
        channelNumber = "7.1", channelName = "Station", startEpochMs = start, endEpochMs = end,
        originalAirdateEpochSec = null, seriesId = "series", programId = "program", ruleId = "rule",
        oneOff = true, startPaddingSec = 30, endPaddingSec = 30,
    )

    private fun forecast(items: List<ScheduledRecording>, proposal: PlannedRecording? = null) =
        recordingSpaceForecast(items, emptyList(), emptyList(), now, proposal)

    @Test fun estimatesUseDecimalBytesAndWholeMinutes() {
        assertEquals(1_350_000_000L, AtscRecordingRate.SD480i.bytesFor(hour))
        assertEquals(5_400_000_000L, AtscRecordingRate.HD720p.bytesFor(hour))
        assertEquals(7_200_000_000L, hd.bytesFor(hour))
        assertEquals("1 h 1 min", hd.capacity(hd.bytesFor(hour + 60_000L)))
        assertEquals("0 h 0 min", hd.capacity(0))
    }

    @Test fun futureScheduleIncludesBothPaddingPeriods() {
        assertEquals(hd.bytesFor(hour + 60_000), forecast(listOf(airing(now + hour, now + 2 * hour))).bytes)
    }

    @Test fun updatingAnAiringReplacesItsOriginalForecast() {
        val item = airing(now + hour, now + 2 * hour)
        val proposal = PlannedRecording("7.1", item.startEpochMs, item.endEpochMs, 120, 180)
        assertEquals(hd.bytesFor(hour + 300_000), forecast(listOf(item), proposal).bytes)
    }

    @Test fun newAiringAddsToExistingScheduleAndCanExceedFreeSpace() {
        val proposal = PlannedRecording("9.1", now, now + hour, 0, 0)
        val bytes = forecast(listOf(airing()), proposal).bytes
        assertEquals(hd.bytesFor(2 * hour + 30_000), bytes)
        assertTrue(bytes > StorageSpace(10_000_000_000L, 100_000_000_000L).freeBytes)
    }

    @Test fun activeFileCountsOnlyRemainingBytesAndIsNotCountedAgainInSchedule() {
        val recording = parseRecordingIndex(
            """[{"Title":"Show","ChannelNumber":"7.1","StartTime":${(now - hour) / 1000},
                "EndTime":${(now + hour) / 1000},"RecordStartTime":${(now - hour - 30_000) / 1000},
                "RecordEndTime":${(now + hour + 30_000) / 1000}}]""",
            now, "DVR",
        ).recordings.single()
        assertTrue(recording.stillRecording)
        val result = recordingSpaceForecast(listOf(airing(now - hour, now + hour)),
            listOf(recording), emptyList(), now)
        assertEquals(hd.bytesFor(hour + 30_000), result.bytes)
    }

    @Test fun sdUsesSdRateWhileUnknownChannelsUseHdRate() {
        val channel = Channel("7.1", "Station", "http://tuner", false, false, false, null, null, null, false)
        val result = recordingSpaceForecast(listOf(airing()), emptyList(), listOf(channel), now)
        assertEquals(AtscRecordingRate.SD480i.bytesFor(hour + 30_000), result.bytes)
        assertEquals(hd.bytesFor(hour + 30_000), forecast(listOf(airing())).bytes)
    }

    @Test fun missingDurationIsReportedAndPastAiringsConsumeNothing() {
        val result = forecast(listOf(airing(end = null), airing(now - 2 * hour, now - hour)))
        assertEquals(0L, result.bytes)
        assertEquals(1, result.unknownDurations)
    }
}
