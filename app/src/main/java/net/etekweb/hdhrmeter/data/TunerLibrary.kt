package net.etekweb.hdhrmeter.data

import org.json.JSONArray
import org.json.JSONObject

/** Shown on Lineup and DVR when the last tuner cannot be opened. */
internal fun tunerUnavailableMessage(host: String?): String {
    return if (host.isNullOrBlank()) {
        "No tuner selected. Choose a tuner in the Settings tab."
    } else {
        "No tuner found at $host. Choose a tuner in the Settings tab."
    }
}

internal fun sameTuner(left: HdhrDevice, right: HdhrDevice): Boolean {
    if (left.deviceId.isNotBlank() && left.deviceId == right.deviceId) return true
    return left.host.isNotBlank() && left.host.equals(right.host, ignoreCase = true)
}

/** Discovered devices first, then saved devices that are not already listed. */
internal fun mergeTuners(discovered: List<HdhrDevice>, saved: List<HdhrDevice>): List<HdhrDevice> {
    val merged = ArrayList<HdhrDevice>(discovered.size + saved.size)
    for (device in discovered) {
        if (merged.none { sameTuner(it, device) }) merged += device
    }
    for (device in saved) {
        if (merged.none { sameTuner(it, device) }) merged += device
    }
    return merged
}

/** Inserts [device], or replaces the saved row for the same tuner. */
internal fun rememberTuner(saved: List<HdhrDevice>, device: HdhrDevice): List<HdhrDevice> {
    val index = saved.indexOfFirst { sameTuner(it, device) }
    if (index < 0) return saved + device
    return saved.toMutableList().also { it[index] = device }
}

internal fun encodeTuners(devices: List<HdhrDevice>): String {
    val array = JSONArray()
    for (device in devices) {
        array.put(
            JSONObject()
                .put("id", device.deviceId)
                .put("name", device.friendlyName)
                .put("model", device.model)
                .put("firmware", device.firmwareVersion)
                .put("base", device.baseUrl)
                .put("host", device.host)
                .put("tuners", device.tunerCount),
        )
    }
    return array.toString()
}

internal fun decodeTuners(raw: String): List<HdhrDevice> {
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    val devices = ArrayList<HdhrDevice>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val id = item.optString("id")
        val host = item.optString("host")
        val base = item.optString("base")
        if (id.isBlank() || host.isBlank() || base.isBlank()) continue
        devices += HdhrDevice(
            deviceId = id,
            friendlyName = item.optString("name").ifBlank { "HDHomeRun $id" },
            model = item.optString("model"),
            firmwareVersion = item.optString("firmware"),
            firmwareName = "",
            baseUrl = base,
            host = host,
            tunerCount = item.optInt("tuners", 0),
        )
    }
    return devices
}
