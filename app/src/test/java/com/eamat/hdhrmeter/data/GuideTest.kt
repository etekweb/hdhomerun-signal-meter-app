package com.eamat.hdhrmeter.data

import com.eamat.hdhrmeter.ui.formatAirTime
import com.eamat.hdhrmeter.ui.formatProgramDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.GZIPOutputStream

class GuideTest {
    @Test
    fun addressBecomesXmlGuideUrl() {
        assertEquals("http://10.0.0.241:8080/xmlguide.xmltv", guideUrlForInput("10.0.0.241"))
        assertEquals(
            "http://10.0.0.241:8080/xmlguide.xmltv",
            guideUrlForInput("  http://10.0.0.241:8080/xmlguide.xmltv "),
        )
        assertEquals("http://guide.local:8080/xmlguide.xmltv", guideUrlForInput("guide.local"))
        assertNull(guideUrlForInput("   "))
        assertNull(guideUrlForInput("not a host"))
    }

    @Test
    fun xmltvTimeHonorsOffset() {
        val expected = LocalDateTime.of(2026, 10, 31, 1, 0)
            .toInstant(ZoneOffset.ofHours(-4))
            .toEpochMilli()
        assertEquals(expected, parseXmltvTime("20261031010000 -0400"))
        assertEquals(expected, parseXmltvTime("20261031050000 +0000"))
        assertEquals(expected, parseXmltvTime("20261031050000"))
        assertNull(parseXmltvTime("tomorrow"))
    }

    @Test
    fun currentProgramMatchesGuideNumberAndNotALongerNumber() {
        val index = parseXmltv(fixture().byteInputStream())
        val during = parseXmltvTime("20261031013000 +0000")!!
        val onTheHour = parseXmltvTime("20261031020000 +0000")!!

        val fox = index.airing("2.1", "WJBK", during)
        assertEquals("Law & Order", fox?.title)
        assertEquals("Hare Today", fox?.subtitle)
        assertEquals("A long description.", fox?.description)
        assertEquals("S02E05", fox?.episode)
        assertEquals("Other Ten", index.airing("2.10", "", during)?.title)
        assertEquals("Subchannel", index.airing("2.2", "", during)?.title)
        assertEquals("Later Show", index.airing("2.1", "WJBK", onTheHour)?.title)
        assertEquals("Law & Order", index.airing("2.1", "WJBK", parseXmltvTime("20261031010000 +0000")!!)?.title)

        assertEquals("Law & Order", index.airing("9.9", "WJBKDT", during)?.title)
        assertEquals("Subchannel", index.airing("9.9", "WJBK-DT2", during)?.title)
        assertNull(index.airing("9.9", "WJBK", during))
        assertEquals("Law & Order", index.airing("9.9", "Fox Entertainment", during)?.title)
        assertEquals("Movie Night", index.airing("9.9", "MOVIES!", during)?.title)
        val lengthOnly = index.airing("9.9", "MOVIES!", parseXmltvTime("20261031041500 +0000")!!)
        assertEquals("Length Only", lengthOnly?.title)
        assertEquals(parseXmltvTime("20261031043000 +0000"), lengthOnly?.stopEpochMs)
        assertNull(index.airing("999.9", "NOTACHANNEL", during))
        assertEquals(onTheHour, index.nextBoundary(during))
    }

    @Test
    fun endedProgramsAreDroppedAndGzipDecodes() {
        val later = parseXmltvTime("20261031023000 +0000")!!
        val index = parseXmltv(fixture().byteInputStream(), nowEpochMs = later)
        assertEquals("Later Show", index.airing("2.1", "", later)?.title)
        assertNull(index.byNumber["2.1"]?.firstOrNull { it.title == "Law & Order" })

        val gzipped = ByteArrayOutputStream().also { buffer ->
            GZIPOutputStream(buffer).use { gzip -> gzip.write(fixture().toByteArray()) }
        }.toByteArray()
        val fromGzip = parseXmltv(prepareGuideStream(ByteArrayInputStream(gzipped)))
        assertEquals("Law & Order", fromGzip.airing("2.1", "", parseXmltvTime("20261031013000 +0000")!!)?.title)
    }

    @Test
    fun airTimeUsesTheDeviceZone() {
        val start = LocalDateTime.of(2026, 10, 31, 20, 0)
            .toInstant(ZoneOffset.ofHours(-4))
            .toEpochMilli()
        val stop = LocalDateTime.of(2026, 10, 31, 21, 30)
            .toInstant(ZoneOffset.ofHours(-4))
            .toEpochMilli()
        assertEquals(
            "8:00 PM – 9:30 PM",
            formatAirTime(start, stop, ZoneId.of("America/New_York"), Locale.US),
        )
        assertEquals("Hare Today · S02E05", formatProgramDetail("Hare Today", "S02E05"))
        assertNull(formatProgramDetail("  ", null))
    }

    @Test
    fun liveGuideHasAProgramOnNow() {
        assumeTrue(System.getenv("HDHR_GUIDE_LIVE") == "1")
        val index = fetchGuide("http://10.0.0.241:8080/xmlguide.xmltv")
        val now = System.currentTimeMillis()
        assertTrue(index.channelCount > 10)
        assertTrue(index.byNumber.containsKey("2.1"))
        assertTrue(index.byNumber.containsKey("4.1"))
        assertTrue(index.byNumber.containsKey("7.1"))
        for (number in listOf("2.1", "4.1", "7.1")) {
            val program = index.airing(number, "", now)
            assertNotNull(number, program)
            assertTrue(program!!.title.isNotBlank())
            assertTrue(program.startEpochMs <= now && now < program.stopEpochMs)
        }
        assertNull(index.airing("999.9", "NOTACHANNEL", now))
    }

    private fun fixture(): String = """
        <?xml version="1.0" ?>
        <!DOCTYPE tv SYSTEM 'xmltv.dtd'>
        <tv source-info-name="GraceNote">
          <channel id="19600">
            <display-name>2.1 WJBKDT</display-name>
            <display-name>2.1</display-name>
            <display-name>WJBKDT</display-name>
            <display-name>FOX ENTERTAINMENT</display-name>
            <icon src="https://example.test/fox.png" />
          </channel>
          <channel id="35451">
            <display-name>2.2 WJBKDT2</display-name>
            <display-name>2.2</display-name>
            <display-name>WJBKDT2</display-name>
          </channel>
          <channel id="999">
            <display-name>2.10 OTHER</display-name>
            <display-name>2.10</display-name>
          </channel>
          <channel id="50">
            <display-name>MOVIES!</display-name>
          </channel>
          <programme start="20261031010000 +0000" stop="20261031020000 +0000" channel="19600">
            <title lang="en-us">Law &amp; Order</title>
            <sub-title lang="en-us">Hare Today</sub-title>
            <desc lang="en-us">A long description.</desc>
            <length units="minutes">60</length>
            <episode-num system="xmltv_ns">1.4</episode-num>
            <episode-num system="onscreen">S02E05</episode-num>
            <previously-shown />
            <rating system="USA Parental Rating"><value>TV-14</value></rating>
          </programme>
          <programme start="20261031020000 +0000" stop="20261031030000 +0000" channel="19600">
            <title>Later Show</title>
          </programme>
          <programme start="20261031010000 +0000" stop="20261031020000 +0000" channel="35451">
            <title>Subchannel</title>
          </programme>
          <programme start="20261031010000 +0000" stop="20261031020000 +0000" channel="999">
            <title>Other Ten</title>
          </programme>
          <programme start="20261031010000 +0000" stop="20261031020000 +0000" channel="50">
            <title>Movie Night</title>
          </programme>
          <programme start="20261031040000 +0000" channel="50">
            <title>Length Only</title>
            <length units="minutes">30</length>
          </programme>
        </tv>
    """.trimIndent()
}
