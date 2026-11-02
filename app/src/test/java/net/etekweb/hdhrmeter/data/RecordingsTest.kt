package net.etekweb.hdhrmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingsTest {
    private val nowMs = 1_800_000_000_000L

    @Test
    fun indexKeepsInlineEpisodesAndSeriesLinks() {
        val index = parseRecordingIndex(
            """
            [
              {"SeriesID":"SHOW","Title":"Celebrity Jeopardy!","EpisodesURL":"http://dvr.local:59090/recorded_files.json?SeriesID=SHOW"},
              {"SeriesID":"MOV","Title":"Skyfall","Episodes":[
                {"ProgramID":"MV1","Title":"Skyfall","Synopsis":"Bond returns.","ChannelNumber":"702",
                 "StartTime":1455212160,"EndTime":1455220800,"RecordStartTime":1455212100,"RecordEndTime":1455220900}
              ]},
              {"Title":" "}
            ]
            """.trimIndent(),
            nowMs,
            "Living Room",
        )
        assertEquals("Celebrity Jeopardy!", index.pending.single().title)
        assertEquals("SHOW", index.pending.single().seriesId)
        assertEquals("http://dvr.local:59090/recorded_files.json?SeriesID=SHOW", index.pending.single().url)
        assertEquals("Skyfall", index.recordings.single().title)
        assertEquals("Bond returns.", index.recordings.single().synopsis)
        assertEquals("702", index.recordings.single().channelNumber)
        assertFalse(index.recordings.single().stillRecording)
    }

    @Test
    fun episodeDocumentFillsTheSeriesAndSortsNewestFirst() {
        val episodes = parseEpisodeDocument(
            """
            {"SeriesID":"SHOW","Title":"Celebrity Jeopardy!","Episodes":[
              {"ProgramID":"EP1","EpisodeNumber":"S03E07","EpisodeTitle":"Quarterfinal #7",
               "Synopsis":"The champions return.","ChannelNumber":"2.1","ChannelName":"WJBK",
               "StartTime":1735603200,"EndTime":1735606800,
               "RecordStartTime":1735603140,"RecordEndTime":1735606860,"OriginalAirdate":1735516800},
              {"ProgramID":"EP1","EpisodeNumber":"S03E07","EpisodeTitle":"Quarterfinal #7",
               "RecordStartTime":1735603140,"RecordEndTime":0,"RecordSuccess":0,"RecordError":"No video data"}
            ]}
            """.trimIndent(),
            "Celebrity Jeopardy!",
            "SHOW",
            nowMs,
            "Living Room",
        )
        val sorted = sortRecordings(episodes)
        assertEquals(listOf("SHOW|EP1|1735603140|Celebrity Jeopardy!|S03E07", "SHOW|EP1|1735603140|Celebrity Jeopardy!|S03E07#2"), sorted.map { it.id }.sorted())
        val finished = sorted.first { it.problem == null }
        assertEquals("Celebrity Jeopardy!", finished.title)
        assertEquals("S03E07", finished.episodeNumber)
        assertEquals("Quarterfinal #7", finished.episodeTitle)
        assertEquals("2.1", finished.channelNumber)
        assertEquals("WJBK", finished.channelName)
        assertEquals(1735603200_000L, finished.startEpochMs)
        assertEquals(1735516800L, finished.originalAirdateEpochSec)
        assertFalse(finished.stillRecording)
        val failed = sorted.first { it.problem != null }
        assertEquals("No video data", failed.problem)
        assertFalse(failed.stillRecording)
    }

    @Test
    fun openEndedRecordingIsStillInProgress() {
        val body = """
            [{"Title":"News","RecordStartTime":1799999900,"RecordEndTime":1800000100,"ChannelNumber":4.1}]
        """.trimIndent()
        val recording = parseRecordingIndex(body, nowMs, null).recordings.single()
        assertTrue(recording.stillRecording)
        assertEquals("4.1", recording.channelNumber)
        assertEquals("News", recording.title)
        assertNull(recording.problem)
    }

    @Test
    fun unreadableCatalogIsRejected() {
        val error = runCatching { parseRecordingIndex("not json", nowMs, null) }.exceptionOrNull()
        assertTrue(error is HdhrException)
    }

    @Test
    fun lanUrlKeepsThePathAndDropsALocalName() {
        assertEquals(
            "http://10.0.0.9:59090/recorded_files.json?SeriesID=SHOW",
            rewriteLanUrl("http://dvr.local:59090/recorded_files.json?SeriesID=SHOW", "10.0.0.9"),
        )
        assertEquals(
            "http://10.0.0.9:59090/recorded_files.json",
            rewriteLanUrl("http://10.0.0.9:59090/recorded_files.json", "10.0.0.8"),
        )
        assertEquals(
            "http://10.0.0.9:59090/recorded_files.json?SeriesID=A",
            rewriteLanUrl("http://nas.home:59090/recorded_files.json?SeriesID=A", "10.0.0.9"),
        )
    }

    @Test
    fun discoverJsonSuppliesTheStorageUrl() {
        val parsed = parseRecordEngineDiscover(
            """{"FriendlyName":"HDHomeRun RECORD","StorageURL":"http://dvr.local:59090/recorded_files.json"}""",
            "10.0.0.9",
        )
        assertEquals("HDHomeRun RECORD", parsed?.name)
        assertEquals("http://10.0.0.9:59090/recorded_files.json", parsed?.storageUrl)
        assertNull(parseRecordEngineDiscover("not json", "10.0.0.9"))
    }
}
