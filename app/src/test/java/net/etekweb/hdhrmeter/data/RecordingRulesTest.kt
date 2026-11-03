package net.etekweb.hdhrmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class RecordingRulesTest {
    private val startMs = 1_700_000_000_000L
    private val stopMs = startMs + 30 * 60_000L

    @Test
    fun rulesParseOneOffsSeriesAndNull() {
        assertEquals(emptyList<RecordingRule>(), parseRecordingRules("null"))
        assertNull(parseRecordingRules("not json"))
        val rules = parseRecordingRules(
            """
            [
              {
                "RecordingRuleID": "403922",
                "SeriesID": 11579711,
                "Title": "Skyfall",
                "ChannelOnly": "2.1|702",
                "DateTimeOnly": "1455212160",
                "StartPadding": 0,
                "EndPadding": 90
              },
              {
                "RecordingRuleID": "10",
                "SeriesID": "SHOW",
                "Title": "News",
                "StartPadding": 45
              }
            ]
            """.trimIndent(),
        )
        val movie = rules!!.first()
        assertEquals("403922", movie.id)
        assertEquals("11579711", movie.seriesId)
        assertEquals(listOf("2.1", "702"), movie.channels)
        assertEquals(1_455_212_160L, movie.dateTimeOnlyEpochSec)
        assertEquals(0, movie.startPaddingSec)
        assertEquals(90, movie.endPaddingSec)
        val series = rules[1]
        assertNull(series.dateTimeOnlyEpochSec)
        assertEquals(emptyList<String>(), series.channels)
        assertEquals(45, series.startPaddingSec)
        assertEquals(DEFAULT_RECORD_PADDING_SEC, series.endPaddingSec)
    }

    @Test
    fun urlsEncodeTheAuthAndTheCommand() {
        assertEquals(
            "https://api.hdhomerun.com/api/recording_rules?DeviceAuth=a%2Bb%2Fc",
            recordingRulesUrl("a+b/c"),
        )
        assertNull(recordingRulesUrl("a b"))
        assertEquals(
            "https://api.hdhomerun.com/api/recording_rules?DeviceAuth=abc&Cmd=add&SeriesID=99&DateTimeOnly=1700000000&ChannelOnly=8.1&StartPadding=30&EndPadding=90",
            recordingRulesUrl(
                "abc",
                linkedMapOf(
                    "Cmd" to "add",
                    "SeriesID" to "99",
                    "DateTimeOnly" to "1700000000",
                    "ChannelOnly" to "8.1",
                    "StartPadding" to "30",
                    "EndPadding" to "90",
                ),
            ),
        )
        assertEquals(
            "https://api.hdhomerun.com/api/guide?DeviceAuth=abc&Start=1700000000",
            hdhrJsonGuideUrl("abc", 1_700_000_000L),
        )
        assertEquals(
            "https://api.hdhomerun.com/api/guide?DeviceAuth=abc",
            hdhrJsonGuideUrl("abc", null),
        )
        assertEquals(
            "http://10.0.0.20:59090/recording_events.post?sync",
            recordingSyncUrl("http://10.0.0.20:59090/"),
        )
        assertNull(recordingSyncUrl("10.0.0.20"))
    }

    @Test
    fun paddingRejectsWordsAndTheHourLimits() {
        val ready = recordingPadding("0", "30", "", "0")
        assertEquals(PaddingInput.Ready(30, 0), ready)
        assertEquals(
            "Seconds have to be under 60.",
            (recordingPadding("0", "60", "0", "0") as PaddingInput.Rejected).message,
        )
        assertEquals(
            "Start early can be up to 1 hour.",
            (recordingPadding("61", "0", "0", "0") as PaddingInput.Rejected).message,
        )
        assertEquals(
            "End late can be up to 3 hours.",
            (recordingPadding("0", "0", "181", "0") as PaddingInput.Rejected).message,
        )
        assertEquals(
            "Enter the early and late times as minutes and seconds.",
            (recordingPadding("no", "0", "0", "0") as PaddingInput.Rejected).message,
        )
        assertEquals("1" to "30", splitPadding(90))
    }

    @Test
    fun oneOffQueryAddsOrChanges() {
        assertEquals(
            linkedMapOf(
                "Cmd" to "add",
                "SeriesID" to "99",
                "DateTimeOnly" to "1700000000",
                "ChannelOnly" to "8.1",
                "StartPadding" to "30",
                "EndPadding" to "90",
            ),
            oneOffScheduleQuery("99", null, "08.1", 1_700_000_000L, 30, 90),
        )
        assertEquals(
            linkedMapOf(
                "Cmd" to "change",
                "RecordingRuleID" to "7",
                "StartPadding" to "0",
                "EndPadding" to "0",
            ),
            oneOffScheduleQuery(null, "7", "8.1", 1_700_000_000L, 0, 0),
        )
        assertNull(oneOffScheduleQuery("99", null, "8.1", 1_700_000_000L, 3601, 0))
        assertNull(oneOffScheduleQuery(" ", null, "8.1", 1_700_000_000L, 0, 0))
    }

    @Test
    fun statusFollowsTheRuleTheFileAndTheSeries() {
        val oneOff = rule(id = "1", channels = listOf("8.1"), at = startMs / 1000L, startPad = 30, endPad = 30)
        val program = program()
        assertEquals(
            AiringRecordStatus.Scheduled,
            airingRecordStatus(listOf(oneOff), emptyList(), "8.1", program, startMs - 60_000L),
        )
        assertEquals(
            AiringRecordStatus.Recording,
            airingRecordStatus(listOf(oneOff), emptyList(), "8.1", program, startMs),
        )
        assertEquals(
            AiringRecordStatus.None,
            airingRecordStatus(listOf(oneOff), emptyList(), "8.2", program, startMs),
        )
        val growing = file(still = true)
        assertEquals(
            AiringRecordStatus.Recording,
            airingRecordStatus(emptyList(), listOf(growing), "08.1", program, startMs),
        )
        val saved = file(still = false)
        assertEquals(
            AiringRecordStatus.Recorded,
            airingRecordStatus(emptyList(), listOf(saved), "8.1", program, stopMs + 120_000L),
        )
        val series = rule(id = "2", title = "news", channels = listOf("8.1"), at = null, startPad = 0, endPad = 0)
        assertEquals(
            AiringRecordStatus.Scheduled,
            airingRecordStatus(listOf(series), emptyList(), "8.1", program, startMs - 60_000L),
        )
        val otherChannel = series.copy(channels = listOf("9.1"))
        assertEquals(
            AiringRecordStatus.None,
            airingRecordStatus(listOf(otherChannel), emptyList(), "8.1", program, startMs),
        )
    }

    @Test
    fun guidePagePicksTheSeriesIdForThatAiring() {
        val body = """
            [
              {
                "GuideNumber": "8.1",
                "Guide": [
                  {"StartTime": 1700000000, "EndTime": 1700001800, "Title": "News", "SeriesID": "998877"},
                  {"StartTime": 1700001800, "EndTime": 1700003600, "Title": "Other", "SeriesID": 12345}
                ]
              },
              {"GuideNumber": "9.1", "Guide": [
                {"StartTime": 1700000000, "EndTime": 1700001800, "Title": "News", "SeriesID": "NO"}
              ]}
            ]
        """.trimIndent()
        val news = readGuideLookup(body, "08.1", 1_700_000_000L, "News")
        assertEquals("998877", news!!.seriesId)
        assertEquals(1_700_000_000L, news.airingStartEpochSec)
        assertTrue(news.channelSeen)
        assertTrue(news.coversAiring)
        assertEquals(1_700_003_600L, news.latestEndEpochSec)
        val drifted = readGuideLookup(body, "8.1", 1_700_000_040L, "News")
        assertEquals("998877", drifted!!.seriesId)
        assertEquals(1_700_000_000L, drifted.airingStartEpochSec)
        val other = readGuideLookup(body, "8.1", 1_700_001_800L, "Other")
        assertEquals("12345", other!!.seriesId)
        assertEquals(1_700_001_800L, other.airingStartEpochSec)
        assertNull(readGuideLookup(body, "4.1", 1_700_000_000L, "News")!!.seriesId)
        assertEquals(false, readGuideLookup(body, "4.1", 1_700_000_000L, "News")!!.channelSeen)
    }

    @Test
    fun gzipBodyDecodes() {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write("null".toByteArray()) }
        assertEquals("null", decodeHttpBytes(out.toByteArray()))
        assertEquals("[]", decodeHttpBytes("[]".toByteArray()))
    }

    private fun program() = GuideProgram(
        title = "News",
        subtitle = null,
        description = null,
        episode = null,
        startEpochMs = startMs,
        stopEpochMs = stopMs,
    )

    private fun rule(
        id: String,
        title: String = "News",
        channels: List<String>,
        at: Long?,
        startPad: Int,
        endPad: Int,
    ) = RecordingRule(
        id = id,
        seriesId = "S",
        title = title,
        channels = channels,
        dateTimeOnlyEpochSec = at,
        startPaddingSec = startPad,
        endPaddingSec = endPad,
    )

    private fun file(still: Boolean) = Recording(
        id = "file",
        title = "News",
        episodeTitle = null,
        episodeNumber = null,
        synopsis = null,
        channelNumber = "8.1",
        channelName = "WJBK",
        startEpochMs = startMs,
        endEpochMs = stopMs,
        recordStartEpochMs = startMs,
        recordEndEpochMs = if (still) null else stopMs,
        originalAirdateEpochSec = null,
        stillRecording = still,
        problem = null,
        sourceName = null,
    )
}
