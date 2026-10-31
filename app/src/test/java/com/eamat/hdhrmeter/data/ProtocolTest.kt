package com.eamat.hdhrmeter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun classicDiscoverRequestMatchesKnownPacket() {
        assertEquals(
            "0002000c0104000000010204ffffffff4e507f35",
            HdhrPackets.discoverRequest().toHex(),
        )
    }

    @Test
    fun wildcardDiscoverCrcMatchesCapture() {
        val payload = tlvU32(0x01, 0xFFFFFFFFL) + tlvU32(0x02, 0xFFFFFFFFL)
        assertEquals(
            "0002000c0104ffffffff0204ffffffff73cc7d8f",
            HdhrPackets.seal(HdhrPackets.TYPE_DISCOVER_REQ, payload).toHex(),
        )
    }

    @Test
    fun replyRoundTripAndBadCrc() {
        val payload = tlvU32(HdhrPackets.TAG_DEVICE_TYPE, HdhrPackets.DEVICE_TYPE_TUNER) +
            tlvU32(HdhrPackets.TAG_DEVICE_ID, 0x1234ABCDL) +
            tlvU8(HdhrPackets.TAG_TUNER_COUNT, 2) +
            tlvText(HdhrPackets.TAG_BASE_URL, "http://hdhr.local:80")
        val packet = HdhrPackets.seal(HdhrPackets.TYPE_DISCOVER_RPY, payload)
        val device = HdhrPackets.parseDiscoverReply(packet)
        assertEquals(0x1234ABCDL, device?.deviceId)
        assertEquals(2, device?.tunerCount)
        assertEquals("http://hdhr.local:80", device?.baseUrl)
        assertTrue(HdhrPackets.DEVICE_TYPE_TUNER in device!!.deviceTypes)

        val broken = packet.copyOf()
        broken[broken.lastIndex] = (broken[broken.lastIndex].toInt() xor 0xFF).toByte()
        assertNull(HdhrPackets.parseDiscoverReply(broken))
    }

    @Test
    fun storageOnlyReplyIsIgnoredAndTypelessReplyIsKept() {
        val storage = tlvU32(HdhrPackets.TAG_DEVICE_TYPE, 5) + tlvU32(HdhrPackets.TAG_DEVICE_ID, 0x10)
        assertNull(HdhrPackets.parseDiscoverReply(HdhrPackets.seal(HdhrPackets.TYPE_DISCOVER_RPY, storage)))

        val legacy = tlvU32(HdhrPackets.TAG_DEVICE_ID, 0x99)
        val device = HdhrPackets.parseDiscoverReply(HdhrPackets.seal(HdhrPackets.TYPE_DISCOVER_RPY, legacy))
        assertEquals(0x99L, device?.deviceId)
        assertTrue(device!!.deviceTypes.isEmpty())
    }

    @Test
    fun discoverJsonRewritesLocalHostname() {
        val body = """
            {"FriendlyName":"Living Room","ModelNumber":"HDHR5-4US","FirmwareName":"hdhomerun5_atsc",
            "FirmwareVersion":"20251001","DeviceID":"1234ABCD","TunerCount":4,
            "BaseURL":"http://hdhr-1234ABCD.local"}
        """.trimIndent()
        val device = parseDiscoverJson(body, "192.168.1.50")
        assertEquals("Living Room", device.friendlyName)
        assertEquals("HDHR5-4US", device.model)
        assertEquals("192.168.1.50", device.host)
        assertEquals("http://192.168.1.50", device.baseUrl)
        assertEquals(4, device.tunerCount)
    }

    @Test
    fun lineupSortsAndKeepsDrmAndFavorites() {
        val body = """
            [
              {"GuideNumber":"10.1","GuideName":"Ten","URL":"http://x/auto/v10.1"},
              {"GuideNumber":"2.10","GuideName":"Two ten","URL":"http://x/auto/v2.10","HD":1,"ATSC3":1},
              {"GuideNumber":"2.2","GuideName":"Two two","URL":"http://x/auto/v2.2","Tags":"favorite"},
              {"GuideNumber":"2.1","GuideName":"Two one","URL":"http://x/auto/v2.1","DRM":1,"VideoCodec":"MPEG2","AudioCodec":"AC3","Frequency":57000000},
              {"GuideNumber":"","GuideName":"Blank","URL":"http://x/auto/v0"},
              {"GuideNumber":"9.1","GuideName":"Off","URL":"http://x/auto/v9.1","Enabled":0},
              {"GuideNumber":"4.1","GuideName":"Tagged","URL":"http://x/auto/v4.1","Tags":"drm"}
            ]
        """.trimIndent()
        val channels = parseLineup(body)
        assertEquals(listOf("2.1", "2.2", "2.10", "4.1", "10.1"), channels.map { it.guideNumber })
        assertTrue(channels.first { it.guideNumber == "2.1" }.drm)
        assertEquals("MPEG2", channels.first { it.guideNumber == "2.1" }.videoCodec)
        assertEquals(57_000_000L, channels.first { it.guideNumber == "2.1" }.frequencyHz)
        assertTrue(channels.first { it.guideNumber == "2.2" }.favorite)
        assertFalse(channels.first { it.guideNumber == "2.2" }.drm)
        assertTrue(channels.first { it.guideNumber == "2.10" }.hd)
        assertTrue(channels.first { it.guideNumber == "2.10" }.atsc3)
        assertTrue(channels.first { it.guideNumber == "4.1" }.drm)
    }

    @Test
    fun statusJsonReadsTunerRow() {
        val body = """
            [{"Resource":"tuner0","VctNumber":"7.1","VctName":"ABC","Frequency":177000000,
            "SignalStrengthPercent":83,"SignalQualityPercent":90,"SymbolQualityPercent":100,
            "NetworkRate":19392658,"TargetIP":"192.168.1.20"}]
        """.trimIndent()
        val tuner = parseStatusJson(body).single()
        assertEquals(0, tuner.index)
        assertEquals("7.1", tuner.vctNumber)
        assertEquals(83, tuner.strengthPercent)
        assertEquals(90, tuner.qualityPercent)
        assertEquals(100, tuner.symbolPercent)
        assertEquals(19_392_658L, tuner.networkRateBps)
        assertEquals("192.168.1.20", targetHost(tuner.targetIp))
        assertFalse(tuner.isIdle())
    }

    @Test
    fun tunerTextParsesRfMetersAndRate() {
        val text = parseTunerStatusText(
            "ch=qam:33 lock=qam256 ss=83(-10dBmV) snq=90(29dB) seq=100 bps=38807712 pps=0",
        )
        assertEquals("qam", text.requestedModulation)
        assertEquals(33, text.rfChannel)
        assertEquals("qam256", text.lock)
        assertEquals(83, text.strengthPercent)
        assertEquals("-10dBmV", text.strengthDetail)
        assertEquals(90, text.qualityPercent)
        assertEquals("29dB", text.qualityDetail)
        assertEquals(100, text.symbolPercent)
        assertEquals(38_807_712L, text.channelRateBps)

        val frequency = parseTunerStatusText("ch=8vsb:177000000 lock=8vsb ss=70 snq=80 seq=100")
        assertEquals(177_000_000L, frequency.tunedFrequencyHz)
        assertEquals(7, frequency.rfChannel)

        val unlocked = parseTunerStatusText("ch=none lock=none ss=0 snq=0 seq=0")
        assertNull(unlocked.lock)
        assertNull(unlocked.rfChannel)
    }

    @Test
    fun broadcastFrequencyMap() {
        assertEquals(2, rfChannelFromFrequencyHz(57_000_000L))
        assertEquals(5, rfChannelFromFrequencyHz(79_000_000L))
        assertEquals(7, rfChannelFromFrequencyHz(177_000_000L))
        assertEquals(14, rfChannelFromFrequencyHz(473_000_000L))
        assertNull(rfChannelFromFrequencyHz(121_000_000L))
    }

    @Test
    fun streamUrlRewriteAndTranscode() {
        assertEquals(
            "http://192.168.1.20:5004/tuner1/v7.1",
            rewriteStreamUrl("http://hdhr.local:5004/auto/v7.1", "192.168.1.20", 1),
        )
        assertEquals(
            "http://192.168.1.20:5004/auto/v7.1",
            rewriteStreamUrl("http://hdhr.local:5004/auto/v7.1", "192.168.1.20", null),
        )
        val transcoded = withMobileTranscode("http://192.168.1.20:5004/auto/v7.1")
        assertEquals("http://192.168.1.20:5004/auto/v7.1?transcode=mobile", transcoded)
        assertEquals(transcoded, withMobileTranscode(transcoded))
    }

    @Test
    fun error811IsCopyProtection() {
        assertEquals(
            "This channel is copy protected and cannot be played here.",
            hdhrErrorMessage(811),
        )
        assertEquals(811, hdhrErrorCode(mapOf("x-hdhomerun-error" to listOf("811")), ""))
        assertEquals(804, hdhrErrorCode(emptyMap(), "resource busy 804"))
    }

    @Test
    fun selectTunerIgnoresAnotherViewer() {
        val other = tuner(0, "7.1", "10.0.0.5")
        val ours = tuner(1, "7.1", "192.168.1.20:5000")
        assertEquals(1, selectTuner(listOf(other, ours), "7.1", "192.168.1.20", preferredIndex = 0)?.index)

        val strangers = listOf(tuner(0, "5.1", "10.0.0.9"), tuner(1, "8.1", "10.0.0.8"))
        assertNull(selectTuner(strangers, "7.1", "192.168.1.20", preferredIndex = 0))

        assertEquals(0, selectTuner(listOf(tuner(0, null, null)), "7.1", "192.168.1.20", 0)?.index)
    }

    @Test
    fun hostNormalization() {
        assertEquals("192.168.1.20", normalizeHostInput("  http://192.168.1.20/lineup.json "))
        assertEquals(
            listOf("http://192.168.1.20", "http://192.168.1.20:5004", "http://192.168.1.20:80"),
            candidateBases("192.168.1.20"),
        )
        assertEquals("http://10.0.0.4", usableBase("http://hdhr.local", "10.0.0.4"))
        assertEquals(-1, compareGuideNumbers("2.2", "2.10"))
        assertTrue(compareGuideNumbers("2.1", "2.2") < 0)
        assertTrue(compareGuideNumbers("2.10", "10.1") < 0)
    }

    private fun tuner(index: Int, vct: String?, target: String?) = TunerJson(
        resource = "tuner$index",
        index = index,
        vctNumber = vct,
        vctName = null,
        frequencyHz = null,
        strengthPercent = null,
        qualityPercent = null,
        symbolPercent = null,
        networkRateBps = null,
        targetIp = target,
    )

    private fun tlvU32(tag: Int, value: Long): ByteArray = byteArrayOf(
        tag.toByte(),
        4,
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )

    private fun tlvU8(tag: Int, value: Int): ByteArray = byteArrayOf(tag.toByte(), 1, value.toByte())

    private fun tlvText(tag: Int, text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return byteArrayOf(tag.toByte(), bytes.size.toByte()) + bytes
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
