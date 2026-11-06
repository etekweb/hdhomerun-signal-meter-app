package net.etekweb.hdhrmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTest {
    private val nowMs = 1_700_000_100_000L

    @Test
    fun episodesKeepFutureAiringAndAttachTheMatchingRule() {
        val episodes = parseScheduledEpisodes(catalog(), "SHOW")!!
        val oneOff = rule(
            id = "7",
            at = 1_700_000_000L,
            startPad = 0,
            endPad = 15,
            channels = listOf("8.1"),
        )
        val series = rule(id = "10", at = null, startPad = 45, endPad = 60, channels = emptyList())
        val other = rule(id = "3", at = 1_700_009_000L, startPad = 5, endPad = 5, channels = listOf("8.1"))
        val scheduled = scheduledRecordings(
            listOf(series, oneOff, other),
            episodes,
            recordedProgramIds = setOf("REC"),
            nowMs = nowMs,
        )
        assertEquals(listOf("SHOW|EP1|1700000000", "SHOW|LATER|1700003600"), scheduled.map { it.id })
        assertTrue(scheduled.none { it.programId == "ONCE" || it.programId == "EP2" || it.programId == "OLD" || it.programId == "DUP" || it.programId == "REC" })
        val airing = scheduled[0]
        assertEquals("News", airing.title)
        assertEquals("Morning", airing.episodeTitle)
        assertEquals("S1E1", airing.episodeNumber)
        assertEquals("Headlines.", airing.synopsis)
        assertEquals("8.1", airing.channelNumber)
        assertEquals("WJBK", airing.channelName)
        assertEquals(1_700_000_000_000L, airing.startEpochMs)
        assertEquals(1_700_003_600_000L, airing.endEpochMs)
        assertEquals(1_690_000_000L, airing.originalAirdateEpochSec)
        assertEquals("EP1", airing.programId)
        assertEquals("7", airing.ruleId)
        assertTrue(airing.oneOff)
        assertEquals(0, airing.startPaddingSec)
        assertEquals(15, airing.endPaddingSec)
        val later = scheduled[1]
        assertEquals("10", later.ruleId)
        assertFalse(later.oneOff)
        assertEquals(45, later.startPaddingSec)
        assertEquals(60, later.endPaddingSec)
        assertEquals("SHOW", later.seriesId)
    }

    @Test
    fun nestedEpisodesAndASingleObjectParse() {
        val nested = parseScheduledEpisodes(
            """{"SeriesID":"SHOW","Episodes":[{"ProgramID":"EP1","Title":"News","StartTime":1700003600,"EndTime":1700007200,"RecordingRule":true,"ChannelNumber":8.1}]}""",
            "OTHER",
        )
        assertEquals("SHOW", nested!!.single().seriesId)
        assertEquals(1, nested.single().recordingRule)
        assertEquals("8.1", nested.single().channelNumber)
        val one = parseScheduledEpisodes(
            """{"ProgramID":"EP9","Title":"Film","StartTime":1700003600,"RecordingRule":"1","RecordingRuleExt":"RecordDuplicate"}""",
            "SHOW",
        )
        assertEquals("RecordDuplicate", one!!.single().recordingRuleExt)
        assertEquals(emptyList<ScheduledEpisode>(), parseScheduledEpisodes("null", "SHOW"))
        assertNull(parseScheduledEpisodes("not json", "SHOW"))
    }

    @Test
    fun recordedCopyAndMissingRuleAreLeftOut() {
        val episodes = parseScheduledEpisodes(catalog(), "SHOW")!!
        val series = rule(id = "10", at = null, startPad = 30, endPad = 30, channels = listOf("8.1"))
        val open = scheduledRecordings(listOf(series), episodes, emptySet(), nowMs)
        assertTrue(open.any { it.programId == "REC" })
        val saved = scheduledRecordings(listOf(series), episodes, setOf("REC"), nowMs)
        assertTrue(saved.none { it.programId == "REC" })
        val unmatched = scheduledRecordings(emptyList(), episodes, emptySet(), nowMs)
        assertTrue(unmatched.isEmpty())
    }

    @Test
    fun titleAndChannelFallBackToTheSeriesRule() {
        val episodes = parseScheduledEpisodes(
            """[{"ProgramID":"EP1","StartTime":1700003600,"EndTime":1700007200,"RecordingRule":1,"SeriesID":"SHOW"}]""",
            "SHOW",
        )!!
        val series = rule(id = "10", at = null, startPad = 30, endPad = 30, channels = listOf("4.1"))
        val item = scheduledRecordings(listOf(series), episodes, emptySet(), nowMs).single()
        assertEquals("News", item.title)
        assertEquals("4.1", item.channelNumber)
        assertFalse(item.oneOff)
    }

    @Test
    fun urlsAndDeleteCommands() {
        assertEquals(
            "https://api.hdhomerun.com/api/episodes?DeviceAuth=a%2Bb&SeriesID=SHOW",
            episodesUrl("a+b", linkedMapOf("SeriesID" to "SHOW")),
        )
        assertNull(episodesUrl("a b"))
        assertEquals(
            linkedMapOf("Cmd" to "delete", "RecordingRuleID" to "7"),
            deleteRuleQuery("7"),
        )
        assertNull(deleteRuleQuery(" "))
        assertEquals(
            linkedMapOf(
                "Cmd" to "delete",
                "SeriesID" to "SHOW",
                "ProgramID" to "EP1",
                "StartTime" to "1700000000",
            ),
            skipEpisodeQuery("SHOW", "EP1", 1_700_000_000L),
        )
        assertEquals(
            linkedMapOf("Cmd" to "delete", "SeriesID" to "SHOW", "ProgramID" to "EP1"),
            skipEpisodeQuery("SHOW", "EP1", 0L),
        )
        assertNull(skipEpisodeQuery("SHOW", " ", 1_700_000_000L))
        assertEquals(
            "http://10.0.0.9/a?FileID=1&cmd=delete",
            recordingDeleteUrl("http://10.0.0.9/a?FileID=1"),
        )
        assertEquals("http://10.0.0.9/a?cmd=delete", recordingDeleteUrl("http://10.0.0.9/a"))
        assertEquals(
            "http://10.0.0.9/a?cmd=delete&x=1",
            recordingDeleteUrl("http://10.0.0.9/a?cmd=delete&x=1"),
        )
        assertNull(recordingDeleteUrl("ftp://10.0.0.9/a"))
        assertNull(recordingDeleteUrl("http://10.0.0.9/a b"))
        assertEquals(
            "http://10.0.0.9/a?FileID=1&cmd=set&Resume=682",
            recordingResumeUrl("http://10.0.0.9/a?FileID=1", 682L),
        )
        assertEquals(
            "http://10.0.0.9/a?cmd=set&Resume=4294967295",
            recordingResumeUrl("http://10.0.0.9/a", RECORDING_RESUME_WATCHED),
        )
        assertNull(recordingResumeUrl("ftp://10.0.0.9/a", 1L))
        assertNull(recordingResumeUrl("http://10.0.0.9/a b", 1L))
        assertNull(recordingResumeUrl("http://10.0.0.9/a", -1L))
    }

    private fun catalog(): String {
        return """
            [
              {"ProgramID":"EP1","Title":"News","EpisodeTitle":"Morning","EpisodeNumber":"S1E1",
               "Synopsis":"Headlines.","OriginalAirdate":1690000000,"StartTime":1700000000,"EndTime":1700003600,
               "ChannelNumber":"8.1","ChannelName":"WJBK","SeriesID":"SHOW","RecordingRule":1},
              {"ProgramID":"EP2","Title":"News","StartTime":1700007200,"EndTime":1700010800,
               "ChannelNumber":"8.1","SeriesID":"SHOW","RecordingRule":1,"RecordingRuleExt":"DeletedDontRerecord"},
              {"ProgramID":"OLD","Title":"News","StartTime":1600000000,"EndTime":1600003600,
               "ChannelNumber":"8.1","SeriesID":"SHOW","RecordingRule":1},
              {"ProgramID":"DUP","Title":"News","StartTime":1700014400,"EndTime":1700018000,
               "ChannelNumber":"8.1","SeriesID":"SHOW","RecordingRule":0},
              {"ProgramID":"REC","Title":"News","StartTime":1700020000,"EndTime":1700023600,
               "ChannelNumber":"8.1","SeriesID":"SHOW","RecordingRule":1,"RecordingRuleExt":"RecordIfNotRecorded"},
              {"ProgramID":"ONCE","Title":"News","StartTime":1700005000,"EndTime":1700008600,
               "ChannelNumber":"8.1","SeriesID":"SHOW","RecordingRule":1,"RecordingRuleExt":"RecordOnce"},
              {"ProgramID":"LATER","Title":"News","StartTime":1700003600,"EndTime":1700007200,
               "ChannelNumber":"8.1","SeriesID":"SHOW","RecordingRule":1}
            ]
        """.trimIndent()
    }

    private fun rule(
        id: String,
        at: Long?,
        startPad: Int,
        endPad: Int,
        channels: List<String>,
    ) = RecordingRule(
        id = id,
        seriesId = "SHOW",
        title = "News",
        channels = channels,
        dateTimeOnlyEpochSec = at,
        startPaddingSec = startPad,
        endPaddingSec = endPad,
    )
}
