package net.etekweb.hdhrmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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
        assertNull(index.recordings.single().playUrl)
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
    fun playUrlIsKeptAndPointedAtTheEngine() {
        val body = """
            [{"Title":"News","ChannelNumber":"4.1","RecordStartTime":1700000000,"RecordEndTime":1700001800,
              "PlayURL":"http://dvr.local:59090/rec/news"}]
        """.trimIndent()
        val recording = parseRecordingIndex(body, nowMs, null).recordings.single()
        assertEquals("http://dvr.local:59090/rec/news", recording.playUrl)
        assertEquals("http://10.0.0.9:59090/rec/news", recordingPlayUrl(recording.playUrl, "10.0.0.9"))
        assertNull(recordingPlayUrl("ftp://dvr.local/rec/news", "10.0.0.9"))
    }

    @Test
    fun cmdUrlIsKeptAndPointedAtTheEngine() {
        val body = """
            [{"Title":"News","ProgramID":"N1","RecordStartTime":1700000000,"RecordEndTime":1700001800,
              "PlayURL":"http://dvr.local:59090/rec/news",
              "CmdURL":"http://dvr.local:59090/rec/news?FileID=1"}]
        """.trimIndent()
        val recording = parseRecordingIndex(body, nowMs, null).recordings.single()
        assertEquals("N1", recording.programId)
        assertEquals("http://dvr.local:59090/rec/news?FileID=1", recording.cmdUrl)
        val rewritten = recordingAtEngine(recording, "10.0.0.9")
        assertEquals("http://10.0.0.9:59090/rec/news", rewritten.playUrl)
        assertEquals("http://10.0.0.9:59090/rec/news?FileID=1", rewritten.cmdUrl)
    }

    @Test
    fun spanUsesTheFinishedFileAndRefusesAGrowingOne() {
        val finished = Recording(
            id = "1",
            title = "News",
            episodeTitle = null,
            episodeNumber = null,
            synopsis = null,
            channelNumber = "4.1",
            channelName = null,
            startEpochMs = 1_700_000_000_000L,
            endEpochMs = 1_700_003_600_000L,
            recordStartEpochMs = 1_700_000_000_000L,
            recordEndEpochMs = 1_700_003_600_000L,
            originalAirdateEpochSec = null,
            stillRecording = false,
            problem = null,
            sourceName = null,
            playUrl = "http://10.0.0.9/rec",
        )
        val span = recordingSpan(finished, nowMs, 3_600_000L)
        assertTrue(span.seekable)
        assertEquals(0L, span.byteAt(span.startMs))
        assertEquals(1_800_000L, span.byteAt(span.startMs + 1_800_000L))
        assertEquals(3_599_999L, span.byteAt(span.endMs))
        val growing = finished.copy(stillRecording = true, recordEndEpochMs = null)
        assertFalse(recordingSpan(growing, nowMs, 3_600_000L).seekable)
        assertFalse(recordingSpan(finished, nowMs, 0L).seekable)
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
        assertNull(parsed?.space)
        assertNull(parseRecordEngineDiscover("not json", "10.0.0.9"))
    }

    @Test
    fun discoverJsonSuppliesCapacity() {
        val parsed = parseRecordEngineDiscover(
            """{"FriendlyName":"HDHomeRun RECORD","StorageURL":"http://dvr.local:59090/recorded_files.json","FreeSpace":1363153027072,"TotalSpace":4000000000000}""",
            "10.0.0.9",
        )
        assertEquals(1_363_153_027_072L, parsed?.space?.freeBytes)
        assertEquals(4_000_000_000_000L, parsed?.space?.totalBytes)
        assertEquals(
            StorageSpace(100L, 1_000L),
            parseRecordEngineDiscover("""{"FreeSpace":"100","TotalSpace":"1000"}""", "10.0.0.9")?.space,
        )
    }

    @Test
    fun discoverJsonWithoutAUsableTotalLeavesCapacityUnknown() {
        assertNull(parseRecordEngineDiscover("""{"FreeSpace":1000}""", "10.0.0.9")?.space)
        assertNull(parseRecordEngineDiscover("""{"FreeSpace":5000,"TotalSpace":1000}""", "10.0.0.9")?.space)
        assertNull(parseRecordEngineDiscover("""{"FreeSpace":-1,"TotalSpace":1000}""", "10.0.0.9")?.space)
        assertNull(parseRecordEngineDiscover("""{"FreeSpace":0,"TotalSpace":0}""", "10.0.0.9")?.space)
        assertNull(parseRecordEngineDiscover("""{"FreeSpace":1.5,"TotalSpace":10}""", "10.0.0.9")?.space)
    }

    @Test
    fun capacityReadsAsAvailableOverTotal() {
        val locale = Locale.US
        assertEquals("0 B / 4 TB", formatStorageCapacity(StorageSpace(0, 4_000_000_000_000L), locale))
        assertEquals(
            "1.36 TB / 4 TB",
            formatStorageCapacity(StorageSpace(1_360_000_000_000L, 4_000_000_000_000L), locale),
        )
        assertEquals(
            "1.36 TB / 4 TB",
            formatStorageCapacity(StorageSpace(1_363_153_027_072L, 4_000_000_000_000L), locale),
        )
        assertEquals(
            "842 GB / 1 TB",
            formatStorageCapacity(StorageSpace(842_000_000_000L, 1_000_000_000_000L), locale),
        )
        assertEquals(
            "99.5 GB / 100 GB",
            formatStorageCapacity(StorageSpace(99_500_000_000L, 100_000_000_000L), locale),
        )
        assertEquals("1.5 KB", formatStorageAmount(1_500L, locale))
        assertEquals("50 GB", formatStorageAmount(50_000_000_000L, locale))
        assertEquals("1 TB", formatStorageAmount(999_710_000_000L, locale))
        assertNull(formatStorageCapacity(StorageSpace(5, 4), locale))
        assertNull(formatStorageCapacity(StorageSpace(-1, 100), locale))
        assertEquals(
            "1,36 TB / 4 TB",
            formatStorageCapacity(StorageSpace(1_360_000_000_000L, 4_000_000_000_000L), Locale.GERMANY),
        )
    }

    @Test
    fun capacitySumsEnginesThatReportedATotal() {
        val combined = combineStorage(
            listOf(
                StorageSpace(1_000_000_000_000L, 2_000_000_000_000L),
                null,
                StorageSpace(5, 1),
                StorageSpace(500_000_000_000L, 2_000_000_000_000L),
            ),
        )
        assertEquals(StorageSpace(1_500_000_000_000L, 4_000_000_000_000L), combined)
        assertNull(combineStorage(emptyList()))
        assertNull(combineStorage(listOf(null, StorageSpace(5, 1))))
        assertNull(
            combineStorage(
                listOf(
                    StorageSpace(Long.MAX_VALUE - 5, Long.MAX_VALUE - 5),
                    StorageSpace(10, 10),
                ),
            ),
        )
    }
}
