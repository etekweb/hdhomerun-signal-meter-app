package net.etekweb.hdhrmeter.data

import android.content.Context

class DeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadHost(): String = prefs.getString(KEY_HOST, "").orEmpty()

    fun saveHost(host: String) {
        prefs.edit().putString(KEY_HOST, host).apply()
    }

    fun loadGuideLibrary(): GuideLibrary {
        if (prefs.contains(KEY_GUIDE_SOURCES)) {
            val mode = if (prefs.contains(KEY_GUIDE_MODE)) prefs.getString(KEY_GUIDE_MODE, null) else null
            return resolveGuideLibrary(
                mode,
                prefs.getString(KEY_GUIDE_SOURCES, "").orEmpty(),
                prefs.getString(KEY_GUIDE_ACTIVE, "").orEmpty(),
            )
        }
        val legacyHost = prefs.getString(KEY_GUIDE_HOST, null)
        // No guide keys at all: a new install starts on the HDHomeRun guide.
        if (legacyHost == null) {
            return resolveGuideLibrary(GUIDE_MODE_HDHOMERUN, "", "")
        }
        val migrated = migrateLegacyGuideHost(legacyHost)
        val library = resolveGuideLibrary(
            storedMode = if (migrated.isEmpty()) GUIDE_MODE_NONE else GUIDE_MODE_URL,
            storedSources = migrated.joinToString("\n"),
            storedActive = migrated.firstOrNull().orEmpty(),
        )
        saveGuideLibrary(library.sources, library.activeUrl, library.mode)
        return library
    }

    fun saveGuideLibrary(sources: List<String>, activeUrl: String, mode: GuideMode) {
        val clean = sources.mapNotNull { normalizeGuideUrl(it) }.distinct()
        val active = normalizeGuideUrl(activeUrl)?.takeIf { it in clean }.orEmpty()
        val resolved = when (mode) {
            GuideMode.Url -> if (active.isEmpty()) GuideMode.None else GuideMode.Url
            else -> mode
        }
        val storedActive = if (resolved == GuideMode.Url) active else ""
        val storedMode = when (resolved) {
            GuideMode.None -> GUIDE_MODE_NONE
            GuideMode.HdHomeRun -> GUIDE_MODE_HDHOMERUN
            GuideMode.Url -> GUIDE_MODE_URL
        }
        prefs.edit()
            .putString(KEY_GUIDE_SOURCES, clean.joinToString("\n"))
            .putString(KEY_GUIDE_ACTIVE, storedActive)
            .putString(KEY_GUIDE_MODE, storedMode)
            .apply()
    }

    fun loadSavedTuners(): List<HdhrDevice> {
        val raw = prefs.getString(KEY_SAVED_TUNERS, null)
        if (raw != null) return decodeTuners(raw)
        val seeded = listOfNotNull(loadRecent())
        saveSavedTuners(seeded)
        return seeded
    }

    fun saveSavedTuners(devices: List<HdhrDevice>) {
        prefs.edit().putString(KEY_SAVED_TUNERS, encodeTuners(devices)).apply()
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

    fun clearRecent() {
        prefs.edit()
            .remove(KEY_ID)
            .remove(KEY_NAME)
            .remove(KEY_MODEL)
            .remove(KEY_FIRMWARE)
            .remove(KEY_BASE)
            .remove(KEY_DEVICE_HOST)
            .remove(KEY_TUNERS)
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

    /** `Info`, `Signal`, or `Playback`. Missing and unknown values mean the Info tab. */
    fun loadOverlayTab(): String? = prefs.getString(KEY_OVERLAY_TAB, null)

    fun saveOverlayTab(tab: String) {
        prefs.edit().putString(KEY_OVERLAY_TAB, tab).apply()
    }

    /** Stretch sliders keyed by guide number. Empty when nothing has been set. */
    fun loadChannelStretches(): Map<String, ChannelStretch> {
        return decodeChannelStretches(prefs.getString(KEY_CHANNEL_STRETCH, null))
    }

    fun saveChannelStretches(stretches: Map<String, ChannelStretch>) {
        prefs.edit().putString(KEY_CHANNEL_STRETCH, encodeChannelStretches(stretches)).apply()
    }

    /** Off unless the viewer turns hardware decoding on in Settings. */
    fun loadHardwareDecode(): Boolean = prefs.getBoolean(KEY_HARDWARE_DECODE, false)

    fun saveHardwareDecode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HARDWARE_DECODE, enabled).apply()
    }

    private fun recentChannelsKey(deviceId: String) = "$KEY_RECENT_CHANNELS$deviceId"

    private companion object {
        const val PREFS = "hdhr_meter"
        const val KEY_HOST = "manual_host"
        const val KEY_GUIDE_HOST = "guide_host"
        const val KEY_GUIDE_SOURCES = "guide_sources"
        const val KEY_GUIDE_ACTIVE = "guide_active"
        const val KEY_GUIDE_MODE = "guide_mode"
        const val KEY_SAVED_TUNERS = "saved_tuners"
        const val KEY_RECENT_CHANNELS = "recent_channels_"
        const val KEY_OVERLAY_TAB = "overlay_tab"
        const val KEY_CHANNEL_STRETCH = "channel_stretch"
        const val KEY_HARDWARE_DECODE = "hardware_decode"
        const val KEY_ID = "device_id"
        const val KEY_NAME = "device_name"
        const val KEY_MODEL = "device_model"
        const val KEY_FIRMWARE = "device_firmware"
        const val KEY_BASE = "device_base"
        const val KEY_DEVICE_HOST = "device_host"
        const val KEY_TUNERS = "device_tuners"
    }
}
