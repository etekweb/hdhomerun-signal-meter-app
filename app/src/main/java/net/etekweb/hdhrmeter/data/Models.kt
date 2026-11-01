package net.etekweb.hdhrmeter.data

data class HdhrDevice(
    val deviceId: String,
    val friendlyName: String,
    val model: String,
    val firmwareVersion: String,
    val firmwareName: String,
    val baseUrl: String,
    val host: String,
    val tunerCount: Int,
)

data class Channel(
    val guideNumber: String,
    val guideName: String,
    val streamUrl: String,
    val hd: Boolean,
    val drm: Boolean,
    val favorite: Boolean,
    val videoCodec: String?,
    val audioCodec: String?,
    val frequencyHz: Long?,
    val atsc3: Boolean,
)

/** One tuner on the guide's tuner list. [label] is the station or "Idle". */
data class TunerStation(
    val index: Int,
    val label: String,
    val target: String?,
    val inUse: Boolean,
)

/**
 * Someone watching through the DVR's live relay, which is not an extra tuner.
 * The native HDHomeRun app shows up here while a real tuner carries the channel.
 */
data class ChannelViewer(
    val channel: String,
    val target: String,
)

/** Tuners from `/status.json`, plus live-relay viewers that belong on those tuners. */
data class DeviceStatus(
    val tuners: List<TunerJson>,
    val viewers: List<ChannelViewer>,
)

/** One row from `/status.json`. Idle tuners often contain only [resource]. */
data class TunerJson(
    val resource: String?,
    val index: Int?,
    val vctNumber: String?,
    val vctName: String?,
    val frequencyHz: Long?,
    val strengthPercent: Int?,
    val qualityPercent: Int?,
    val symbolPercent: Int?,
    val networkRateBps: Long?,
    val targetIp: String?,
)

/** Parsed `/tunerN/status` text, for example `ch=8vsb:31 lock=8vsb ss=90(-5dBmV)`. */
data class TunerText(
    val requestedModulation: String?,
    val rfChannel: Int?,
    val tunedFrequencyHz: Long?,
    val lock: String?,
    val strengthPercent: Int?,
    val strengthDetail: String?,
    val qualityPercent: Int?,
    val qualityDetail: String?,
    val symbolPercent: Int?,
    val channelRateBps: Long?,
)

data class SignalReading(
    val tunerIndex: Int?,
    val virtualChannel: String?,
    val virtualName: String?,
    val strengthPercent: Int?,
    val qualityPercent: Int?,
    val symbolPercent: Int?,
    val strengthDetail: String?,
    val qualityDetail: String?,
    val rfChannel: Int?,
    val frequencyHz: Long?,
    val modulation: String?,
    val channelRateBps: Long?,
    val locked: Boolean,
)

data class UdpDevice(
    val deviceId: Long,
    val tunerCount: Int,
    val baseUrl: String?,
    val lineupUrl: String?,
    val deviceTypes: List<Long>,
)
