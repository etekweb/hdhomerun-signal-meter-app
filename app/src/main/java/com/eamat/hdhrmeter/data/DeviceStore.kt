package net.etekweb.hdhrmeter.data

import android.content.Context

class DeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadHost(): String = prefs.getString(KEY_HOST, "").orEmpty()

    fun saveHost(host: String) {
        prefs.edit().putString(KEY_HOST, host).apply()
    }

    fun loadGuideHost(): String = prefs.getString(KEY_GUIDE_HOST, "").orEmpty()

    fun saveGuideHost(host: String) {
        prefs.edit().putString(KEY_GUIDE_HOST, host.trim()).apply()
    }

    fun loadRecent(): HdhrDevice? {
        val base = prefs.getString(KEY_BASE, null) ?: return null
        val host = prefs.getString(KEY_DEVICE_HOST, null) ?: return null
        val id = prefs.getString(KEY_ID, null) ?: return null
        return HdhrDevice(
            deviceId = id,
            friendlyName = prefs.getString(KEY_NAME, null) ?: "HDHomeRun $id",
            model = prefs.getString(KEY_MODEL, "").orEmpty(),
            firmwareVersion = prefs.getString(KEY_FIRMWARE, "").orEmpty(),
            firmwareName = "",
            baseUrl = base,
            host = host,
            tunerCount = prefs.getInt(KEY_TUNERS, 0),
        )
    }

    fun saveRecent(device: HdhrDevice) {
        prefs.edit()
            .putString(KEY_ID, device.deviceId)
            .putString(KEY_NAME, device.friendlyName)
            .putString(KEY_MODEL, device.model)
            .putString(KEY_FIRMWARE, device.firmwareVersion)
            .putString(KEY_BASE, device.baseUrl)
            .putString(KEY_DEVICE_HOST, device.host)
            .putInt(KEY_TUNERS, device.tunerCount)
            .apply()
    }

    /** Guide numbers newest-first. One list per tuner, because lineups differ. */
    fun loadRecentChannels(deviceId: String): List<String> {
        val raw = prefs.getString(recentChannelsKey(deviceId), null) ?: return emptyList()
        return raw.lineSequence().filter { it.isNotEmpty() }.toList()
    }

    fun saveRecentChannels(deviceId: String, guideNumbers: List<String>) {
        prefs.edit()
            .putString(recentChannelsKey(deviceId), guideNumbers.joinToString("\n"))
            .apply()
    }

    private fun recentChannelsKey(deviceId: String) = "$KEY_RECENT_CHANNELS$deviceId"

    private companion object {
        const val PREFS = "hdhr_meter"
        const val KEY_HOST = "manual_host"
        const val KEY_GUIDE_HOST = "guide_host"
        const val KEY_RECENT_CHANNELS = "recent_channels_"
        const val KEY_ID = "device_id"
        const val KEY_NAME = "device_name"
        const val KEY_MODEL = "device_model"
        const val KEY_FIRMWARE = "device_firmware"
        const val KEY_BASE = "device_base"
        const val KEY_DEVICE_HOST = "device_host"
        const val KEY_TUNERS = "device_tuners"
    }
}
