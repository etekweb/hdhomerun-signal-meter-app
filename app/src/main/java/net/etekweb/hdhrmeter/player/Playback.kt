package net.etekweb.hdhrmeter.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import net.etekweb.hdhrmeter.data.StreamFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

data class VideoStats(
    val videoMime: String? = null,
    val audioMime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val bitrateBps: Long? = null,
    val audioChannels: Int? = null,
    val audioTracks: List<AudioReport> = emptyList(),
    /** True when this device can passthrough the playing codec. False when it decodes to PCM. */
    val audioPassthrough: Boolean? = null,
    /** True when the broadcast is interlaced. Null until the stream has been read. */
    val interlaced: Boolean? = null,
    /** True when this playback is using a hardware decoder. Null until the video stream is known. */
    val hardwareDecoded: Boolean? = null,
)

enum class EnginePhase {
    Idle,
    Buffering,
    Ready,
    Ended,
}

data class PlaybackFailure(
    val message: String,
    val hdhrCode: Int? = null,
    val offerTranscode: Boolean = false,
    /** The file or stream reached its end. A recording treats this as finished, not a failure. */
    val ended: Boolean = false,
)

/** LibVLC's clock for the connection that is open now. [playing] is false while paused. */
data class PlayerTransport(
    val mediaTimeMs: Long = 0,
    val playing: Boolean = false,
)

class PlaybackEngine(context: Context) {
    private val appContext = context.applicationContext
    private val _stats = MutableStateFlow(VideoStats())
    private val _phase = MutableStateFlow(EnginePhase.Idle)
    private val _error = MutableStateFlow<PlaybackFailure?>(null)
    private val _transport = MutableStateFlow(PlayerTransport())

    val stats: StateFlow<VideoStats> = _stats.asStateFlow()
    val phase: StateFlow<EnginePhase> = _phase.asStateFlow()
    val error: StateFlow<PlaybackFailure?> = _error.asStateFlow()
    val transport: StateFlow<PlayerTransport> = _transport.asStateFlow()

    private val lib: LibVLC?
    private val player: MediaPlayer?
    private val unavailable: String?
    private val commands = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hdhr-playback").apply { isDaemon = true }
    }
    private val scans = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hdhr-scan").apply { isDaemon = true }
    }
    private val commandGeneration = AtomicInteger(0)
    /** Each play() bumps this. An older queued start must not swallow the latest seek. */
    private val startSerial = AtomicInteger(0)

    @Volatile private var scanConnection: HttpURLConnection? = null
    @Volatile private var hardwareRequested = false
    /** The choice used for the media that is open now, not a checkbox flipped mid-stream. */
    @Volatile private var openedWithHardware = false
    private val hardwareCodecs = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var wantPlaying = true
    @Volatile private var pendingUrl: String? = null
    @Volatile private var ignoreErrorUntilElapsed = 0L
    @Volatile private var playingUrl: String? = null
    @Volatile private var attachedLayout: VLCVideoLayout? = null
    @Volatile private var ignoreNextStop = false
    private var passthroughKey: String? = null
    private var passthroughValue: Boolean? = null

    init {
        val created = runCatching { openLibVlc() }
        if (created.isSuccess) {
            val (library, mediaPlayer) = created.getOrThrow()
            lib = library
            player = mediaPlayer
            unavailable = null
            mediaPlayer.setEventListener { event -> onPlayerEvent(event) }
        } else {
            val error = created.exceptionOrNull()
            Log.e(TAG, "LibVLC failed to load", error)
            lib = null
            player = null
            unavailable = "Video playback could not start on this phone."
        }
    }

    fun play(url: String, keepPicture: Boolean = false) {
        val mediaPlayer = player
        if (mediaPlayer == null || lib == null) {
            _error.value = PlaybackFailure(unavailable ?: "Video playback could not start on this phone.")
            _phase.value = EnginePhase.Idle
            _transport.value = PlayerTransport()
            return
        }
        pendingUrl = url
        wantPlaying = true
        val serial = startSerial.incrementAndGet()
        // A seek closes the current HTTP socket. That looks like a playback failure
        // until the replacement stream is open. A fresh tune reports real failures.
        ignoreErrorUntilElapsed = if (keepPicture) SystemClock.elapsedRealtime() + 1_500 else 0L
        if (keepPicture) {
            _error.value = null
        } else {
            _error.value = null
            _stats.value = VideoStats()
            maybeScanInterlace(url, serial)
        }
        _phase.value = EnginePhase.Buffering
        _transport.update { it.copy(playing = true) }
        runCatching { mediaPlayer.volume = 100 }
        enqueueStart(serial, force = true)
    }

    /** The next socket close is a seek or a channel change, not a failure. */
    fun expectRestart() {
        ignoreErrorUntilElapsed = SystemClock.elapsedRealtime() + 3_000
    }

    fun pause() {
        wantPlaying = false
        _transport.update { it.copy(playing = false) }
        val generation = commandGeneration.get()
        val mediaPlayer = player ?: return
        commands.execute {
            if (generation != commandGeneration.get()) return@execute
            runCatching { mediaPlayer.pause() }
        }
    }

    fun resumePlayback() {
        wantPlaying = true
        _transport.update { it.copy(playing = true) }
        val generation = commandGeneration.get()
        val mediaPlayer = player ?: return
        commands.execute {
            if (generation != commandGeneration.get()) return@execute
            runCatching { mediaPlayer.volume = 100 }
            runCatching { mediaPlayer.play() }
        }
    }

    fun attach(layout: VLCVideoLayout) {
        val mediaPlayer = player ?: return
        if (layout === attachedLayout) return
        detachInternal()
        try {
            mediaPlayer.attachViews(layout, null, false, true)
            mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            attachedLayout = layout
            enqueueStart(startSerial.get(), force = false)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not attach the video surface", error)
            attachedLayout = null
            _error.value = PlaybackFailure("The video surface could not be attached.")
            _phase.value = EnginePhase.Idle
        }
    }

    fun detach(layout: VLCVideoLayout) {
        if (layout !== attachedLayout) return
        detachInternal()
    }

    fun stop() {
        pendingUrl = null
        playingUrl = null
        wantPlaying = false
        startSerial.incrementAndGet()
        scanConnection?.disconnect()
        // Drop the surface on this thread, before the slow input shutdown, so
        // disposing the picture does not wait on the tuner socket.
        detachInternal()
        val generation = commandGeneration.incrementAndGet()
        player?.let { mediaPlayer ->
            // Silence on this thread so the sound ends with the tap. Closing the
            // live input can block for seconds, so that work stays off the UI.
            runCatching { mediaPlayer.volume = 0 }
            commands.execute {
                if (generation != commandGeneration.get()) return@execute
                ignoreNextStop = false
                runCatching { mediaPlayer.stop() }
                if (generation != commandGeneration.get()) return@execute
                runCatching { mediaPlayer.setMedia(null) }
            }
        }
        _phase.value = EnginePhase.Idle
        _stats.value = VideoStats()
        _error.value = null
        _transport.value = PlayerTransport()
    }

    fun release() {
        pendingUrl = null
        playingUrl = null
        startSerial.incrementAndGet()
        scanConnection?.disconnect()
        scans.shutdown()
        commandGeneration.incrementAndGet()
        commands.execute {
            val mediaPlayer = player
            runCatching { mediaPlayer?.volume = 0 }
            runCatching { mediaPlayer?.stop() }
            runCatching { mediaPlayer?.setMedia(null) }
            runCatching { mediaPlayer?.setEventListener(null) }
            runCatching { mediaPlayer?.release() }
            runCatching { lib?.release() }
        }
    }

    private fun openLibVlc(): Pair<LibVLC, MediaPlayer> {
        val options = arrayListOf(
            "--network-caching=1200",
            "--live-caching=1200",
            "--http-reconnect",
            "--http-user-agent=HDHomeRunSignalMeter",
            // TextureView only displays RV32. The library default is RV16, which stays black.
            "--android-display-chroma=RV32",
        )
        // Deinterlace options stay on the media. An unknown library flag aborts
        // LibVLC here, and then every channel reports that playback could not start.
        val library = LibVLC(appContext, options)
        return library to MediaPlayer(library)
    }

    private fun enqueueStart(serial: Int, force: Boolean) {
        val generation = commandGeneration.get()
        val mediaPlayer = player ?: return
        commands.execute {
            if (generation != commandGeneration.get()) return@execute
            if (serial != startSerial.get()) return@execute
            startLocked(mediaPlayer, generation, serial, force)
        }
    }

    private fun startLocked(mediaPlayer: MediaPlayer, generation: Int, serial: Int, force: Boolean) {
        val library = lib ?: return
        val url = pendingUrl ?: return
        if (attachedLayout == null) return
        if (generation != commandGeneration.get() || serial != startSerial.get()) return
        if (!force && playingUrl == url && mediaPlayer.isPlaying && wantPlaying) return
        ignoreNextStop = true
        mediaPlayer.stop()
        if (generation != commandGeneration.get() || serial != startSerial.get()) return
        runCatching { mediaPlayer.volume = 100 }
        // 5.1 passthrough and 6-channel PCM stay silent on Android TV. Stereo PCM plays.
        runCatching { mediaPlayer.setAudioOutputDevice("stereo") }
        val media = Media(library, Uri.parse(url))
        media.addOption(":http-user-agent=HDHomeRunSignalMeter")
        media.addOption(":network-caching=1200")
        media.addOption(":live-caching=1200")
        openedWithHardware = hardwareRequested
        if (openedWithHardware) {
            // Hardware first, then VLC's own decoder. Phones often have no MPEG-2 decoder.
            media.setHWDecoderEnabled(true, false)
        } else {
            // Software first, so interlaced frames stay planar and Yadif can run.
            media.addOption(":codec=avcodec,all")
            media.addOption(":avcodec-hw=none")
        }
        media.addOption(":deinterlace=-1")
        media.addOption(":deinterlace-mode=yadif")
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
        playingUrl = url
        if (!wantPlaying) runCatching { mediaPlayer.pause() }
    }

    fun playSerial(): Int = startSerial.get()

    /** Applies on the next open. A stream that is already up restarts so the Signal tab matches it. */
    fun setHardwareDecode(enabled: Boolean) {
        hardwareRequested = enabled
        val url = pendingUrl ?: return
        play(url)
    }

    /** A scan from an older tune is ignored. The Signal tab reads [VideoStats.interlaced]. */
    fun noteInterlaced(serial: Int, interlaced: Boolean) {
        if (serial != startSerial.get() || pendingUrl == null) return
        _stats.update { current ->
            if (serial != startSerial.get() || pendingUrl == null) current
            else current.copy(interlaced = interlaced)
        }
    }

    private fun maybeScanInterlace(url: String, serial: Int) {
        // The timeshift relay accepts one client. A second connection to it steals playback.
        if (isLoopbackPlayUrl(url)) return
        scanConnection?.disconnect()
        try {
            scans.execute {
                if (serial != startSerial.get()) return@execute
                val found = runCatching { readInterlace(url, serial) }.getOrNull() ?: return@execute
                noteInterlaced(serial, found)
            }
        } catch (_: RejectedExecutionException) {
            // release() already shut the scanner down.
        }
    }

    private fun readInterlace(url: String, serial: Int): Boolean? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_500
            readTimeout = 2_500
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "HDHomeRunSignalMeter")
        }
        scanConnection = connection
        return try {
            if (serial != startSerial.get()) return null
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                val buffer = ByteArray(SCAN_BYTES)
                var filled = 0
                var next = 128 * 1024
                while (filled < buffer.size && serial == startSerial.get()) {
                    val read = input.read(buffer, filled, buffer.size - filled)
                    if (read < 0) break
                    filled += read
                    if (filled >= next || filled >= buffer.size) {
                        interlacedTransport(buffer, filled)?.let { return it }
                        next += 384 * 1024
                    }
                }
                if (filled < 188 * 3) null else interlacedTransport(buffer, filled)
            }
        } finally {
            connection.disconnect()
            if (scanConnection === connection) scanConnection = null
        }
    }

    private fun detachInternal() {
        if (attachedLayout == null) return
        runCatching { player?.detachViews() }
        attachedLayout = null
    }

    private fun onPlayerEvent(event: MediaPlayer.Event) {
        val mediaPlayer = player ?: return
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                _phase.value = EnginePhase.Buffering
                _transport.update { it.copy(mediaTimeMs = 0) }
            }
            MediaPlayer.Event.Buffering -> {
                if (event.buffering < 100f) {
                    _phase.value = EnginePhase.Buffering
                }
                publishTracks(mediaPlayer)
            }
            MediaPlayer.Event.Playing,
            MediaPlayer.Event.Vout,
            MediaPlayer.Event.ESSelected,
            -> {
                _phase.value = EnginePhase.Ready
                noteTransport(mediaPlayer, playing = true)
                publishTracks(mediaPlayer)
            }
            MediaPlayer.Event.Paused -> noteTransport(mediaPlayer, playing = false)
            MediaPlayer.Event.TimeChanged -> {
                _transport.update { it.copy(mediaTimeMs = mediaPlayer.time.coerceAtLeast(0)) }
                publishTracks(mediaPlayer)
            }
            MediaPlayer.Event.Stopped -> {
                if (ignoreNextStop) {
                    ignoreNextStop = false
                    return
                }
                if (pendingUrl == null) {
                    _phase.value = EnginePhase.Idle
                    _transport.value = PlayerTransport()
                }
            }
            MediaPlayer.Event.EndReached -> {
                ignoreNextStop = false
                playingUrl = null
                if (pendingUrl == null) {
                    _phase.value = EnginePhase.Idle
                    return
                }
                _phase.value = EnginePhase.Ended
                _transport.update { it.copy(playing = false) }
                _error.value = PlaybackFailure("The stream ended.", ended = true)
            }
            MediaPlayer.Event.EncounteredError -> {
                ignoreNextStop = false
                playingUrl = null
                if (pendingUrl == null || SystemClock.elapsedRealtime() < ignoreErrorUntilElapsed) {
                    if (pendingUrl == null) _phase.value = EnginePhase.Idle
                    return
                }
                _phase.value = EnginePhase.Idle
                _transport.update { it.copy(playing = false) }
                _error.value = PlaybackFailure("Playback failed. The stream could not be decoded.")
            }
        }
    }

    private fun noteTransport(mediaPlayer: MediaPlayer, playing: Boolean) {
        _transport.update {
            it.copy(mediaTimeMs = mediaPlayer.time.coerceAtLeast(0), playing = playing)
        }
    }

    private fun publishTracks(mediaPlayer: MediaPlayer) {
        val media = runCatching {
            if (!mediaPlayer.hasMedia()) null else mediaPlayer.media
        }.getOrNull() ?: return
        try {
            // LibVLC keeps the first track snapshot. On a live MPEG-TS stream that snapshot
            // is often taken before the sequence header, so the picture size stays unknown.
            if (_stats.value.width == null || _stats.value.height == null) {
                clearCachedTracks(media)
            }
            var sawVideo = false
            var videoMime: String? = null
            var width: Int? = null
            var height: Int? = null
            var frameRate: Float? = null
            var trackBits: Long? = null
            val audioTracks = mutableListOf<AudioReport>()
            val selectedAudioId = runCatching { mediaPlayer.audioTrack }.getOrNull()?.takeIf { it >= 0 }
            val audioNames = audioTrackNames(mediaPlayer)
            val count = media.trackCount
            for (index in 0 until count) {
                when (val track = media.getTrack(index)) {
                    is IMedia.VideoTrack -> if (!sawVideo) {
                        sawVideo = true
                        videoMime = videoMimeOf(track.codec, track.originalCodec, track.fourcc, track.description)
                        val size = pictureSize(track, mediaPlayer)
                        width = size?.first
                        height = size?.second
                        frameRate = frameRateOf(track.frameRateNum, track.frameRateDen)
                        trackBits = track.bitrate.takeIf { it > 0 }?.toLong()
                    }
                    is IMedia.AudioTrack -> audioTracks += audioReport(track, selectedAudioId, audioNames)
                }
            }
            val heard = heardAudio(audioTracks)
            val stats = media.stats
            // libvlc reports these rates in bytes per microsecond.
            val demuxBps = bitsPerSecond(stats?.demuxBitrate ?: 0f)
            val inputBps = bitsPerSecond(stats?.inputBitrate ?: 0f)
            _stats.update { current ->
                VideoStats(
                    videoMime = videoMime,
                    audioMime = audioMimeOf(heard) ?: heard?.let { mimeForCodec(it.codec, it.originalCodec) },
                    width = width,
                    height = height,
                    frameRate = frameRate,
                    bitrateBps = demuxBps ?: inputBps ?: trackBits,
                    audioChannels = heard?.channels,
                    audioTracks = audioTracks,
                    audioPassthrough = heard?.let { passthroughFor(it) },
                    interlaced = current.interlaced,
                    hardwareDecoded = usingHardwareDecoder(
                        requested = openedWithHardware,
                        videoPresent = sawVideo,
                        available = videoMime?.let { hardwareDecoderAvailable(it, hardwareCodecs) } == true,
                    ),
                )
            }
        } catch (_: RuntimeException) {
            // Tracks are not ready on the first buffering events.
        } finally {
            runCatching { media.release() }
        }
    }

    private fun audioReport(
        track: IMedia.AudioTrack,
        selectedId: Int?,
        names: Map<Int, String>,
    ): AudioReport {
        val described = track.description?.trim()?.ifBlank { null } ?: names[track.id]
        return AudioReport(
            id = track.id,
            codec = track.codec?.trim()?.ifBlank { null },
            originalCodec = track.originalCodec?.trim()?.ifBlank { null },
            fourcc = fourccText(track.fourcc),
            channels = track.channels.takeIf { it > 0 },
            sampleRateHz = track.rate.takeIf { it > 0 },
            bitrateBps = track.bitrate.takeIf { it > 0 }?.toLong(),
            language = track.language?.trim()?.ifBlank { null },
            description = described,
            profile = track.profile.takeIf { it >= 0 },
            playing = selectedId != null && track.id == selectedId,
        )
    }

    private fun audioTrackNames(mediaPlayer: MediaPlayer): Map<Int, String> {
        val tracks = runCatching { mediaPlayer.audioTracks }.getOrNull() ?: return emptyMap()
        return tracks.mapNotNull { described ->
            val name = usefulTrackName(described.name) ?: return@mapNotNull null
            described.id to name
        }.toMap()
    }

    private fun passthroughFor(track: AudioReport): Boolean? {
        val key = listOf(
            track.fourcc,
            track.codec,
            track.originalCodec,
            track.description,
            track.channels,
            track.sampleRateHz,
            track.profile,
        ).joinToString("|")
        if (key == passthroughKey) return passthroughValue
        val value = queryPassthrough(track)
        passthroughKey = key
        passthroughValue = value
        return value
    }

    /**
     * LibVLC passes Dolby through when the device claims support. AAC and MPEG audio
     * are always decoded. A null result means the device could not be asked.
     */
    private fun queryPassthrough(track: AudioReport): Boolean? {
        val family = audioFamilyOf(track) ?: return null
        if (family == AudioFamily.Aac || family == AudioFamily.Mp2 || family == AudioFamily.Mp3) {
            return false
        }
        if (Build.VERSION.SDK_INT < 29) return null
        val channels = track.channels ?: return null
        val mask = channelMask(channels) ?: return null
        val encoding = encodingFor(family, audioIsAtmos(track)) ?: return null
        val rate = track.sampleRateHz ?: 48_000
        return runCatching {
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setChannelMask(mask)
                .setSampleRate(rate)
                .build()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            directPlayback(format, attributes)
        }.getOrNull()
    }

    /**
     * Bitstream support means the compressed codec can be sent to the TV.
     * Offload still decodes on this device, so it counts as a local decode.
     */
    private fun directPlayback(format: AudioFormat, attributes: AudioAttributes): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val support = AudioManager.getDirectPlaybackSupport(format, attributes)
            return (support and AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED) != 0
        }
        return directPlaybackSupported(format, attributes)
    }

    @Suppress("DEPRECATION")
    private fun directPlaybackSupported(format: AudioFormat, attributes: AudioAttributes): Boolean {
        return android.media.AudioTrack.isDirectPlaybackSupported(format, attributes)
    }

    private fun clearCachedTracks(media: IMedia) {
        runCatching {
            val field = media.javaClass.getDeclaredField("mNativeTracks")
            field.isAccessible = true
            field.set(media, null)
        }
    }

    private fun pictureSize(track: IMedia.VideoTrack, mediaPlayer: MediaPlayer): Pair<Int, Int>? {
        val drawn = drawnPictureAccess?.read(mediaPlayer)
        // The vout visible size is the picture on screen. Prefer it when it is the
        // same frame as this track, so codec padding (1088, 2176, 768, 512) is not shown.
        if (drawn != null &&
            fitsCodingAlignment(track.width, drawn.width) &&
            fitsCodingAlignment(track.height, drawn.height)
        ) {
            val useDrawnSar = drawn.sarNum > 0 && drawn.sarDen > 0
            return displayResolution(
                drawn.width,
                drawn.height,
                if (useDrawnSar) drawn.sarNum else track.sarNum,
                if (useDrawnSar) drawn.sarDen else track.sarDen,
                track.orientation,
            )
        }
        return displayResolution(
            track.width,
            track.height,
            track.sarNum,
            track.sarDen,
            track.orientation,
        )
    }

    private val drawnPictureAccess: DrawnPictureAccess? by lazy {
        val access = DrawnPictureAccess.open()
        if (access == null) Log.w(TAG, "Could not read the drawn picture size")
        access
    }

    private companion object {
        const val TAG = "HdhrPlayback"
        const val SCAN_BYTES = 2 * 1024 * 1024
    }
}

private fun hardwareDecoderAvailable(mime: String, cache: MutableMap<String, Boolean>): Boolean {
    cache[mime]?.let { return it }
    val found = runCatching { deviceHasHardwareDecoder(mime) }.getOrDefault(false)
    cache[mime] = found
    return found
}

private fun deviceHasHardwareDecoder(mime: String): Boolean {
    val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    return codecs.codecInfos.any { info ->
        !info.isEncoder &&
            info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
            codecIsHardware(info)
    }
}

private fun codecIsHardware(info: MediaCodecInfo): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
    val name = info.name.lowercase(Locale.US)
    return !name.startsWith("omx.google.") &&
        !name.startsWith("c2.android.") &&
        !name.startsWith("omx.ffmpeg.")
}

fun playbackFailure(failure: StreamFailure): PlaybackFailure {
    return PlaybackFailure(message = failure.message, hdhrCode = failure.hdhrCode)
}

private fun usefulTrackName(name: String?): String? {
    val text = name?.trim()?.ifBlank { null } ?: return null
    val lower = text.lowercase(Locale.US)
    if (lower == "disable" || lower == "disabled") return null
    if (lower.matches(Regex("track\\s+\\d+"))) return null
    return text
}

private fun encodingFor(family: AudioFamily, atmos: Boolean): Int? = when (family) {
    AudioFamily.Ac3 -> AudioFormat.ENCODING_AC3
    AudioFamily.Eac3 -> if (atmos) AudioFormat.ENCODING_E_AC3_JOC else AudioFormat.ENCODING_E_AC3
    AudioFamily.Ac4 -> AudioFormat.ENCODING_AC4
    AudioFamily.Dts -> AudioFormat.ENCODING_DTS
    AudioFamily.DtsHd -> AudioFormat.ENCODING_DTS_HD
    AudioFamily.TrueHd -> AudioFormat.ENCODING_DOLBY_TRUEHD
    AudioFamily.Aac, AudioFamily.Mp2, AudioFamily.Mp3 -> null
}

private fun channelMask(channels: Int): Int? = when (channels) {
    1 -> AudioFormat.CHANNEL_OUT_MONO
    2 -> AudioFormat.CHANNEL_OUT_STEREO
    6 -> AudioFormat.CHANNEL_OUT_5POINT1
    8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
    else -> null
}

private fun bitsPerSecond(bytesPerMicrosecond: Float): Long? {
    if (bytesPerMicrosecond <= 0f) return null
    return (bytesPerMicrosecond * 8_000_000.0).toLong().takeIf { it > 0L }
}

private class DrawnPicture(
    val width: Int,
    val height: Int,
    val sarNum: Int,
    val sarDen: Int,
)

private class DrawnPictureAccess(
    private val helperField: java.lang.reflect.Field,
    private val widthField: java.lang.reflect.Field,
    private val heightField: java.lang.reflect.Field,
    private val sarNumField: java.lang.reflect.Field,
    private val sarDenField: java.lang.reflect.Field,
) {
    fun read(player: MediaPlayer): DrawnPicture? {
        return runCatching {
            val helper = helperField.get(player) ?: return null
            val width = widthField.getInt(helper)
            val height = heightField.getInt(helper)
            if (width <= 0 || height <= 0) return null
            DrawnPicture(
                width,
                height,
                sarNumField.getInt(helper),
                sarDenField.getInt(helper),
            )
        }.getOrNull()
    }

    companion object {
        fun open(): DrawnPictureAccess? {
            return runCatching {
                val helperField = MediaPlayer::class.java.getDeclaredField("mVideoHelper")
                helperField.isAccessible = true
                val type = Class.forName("org.videolan.libvlc.VideoHelper")
                fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
                DrawnPictureAccess(
                    helperField,
                    field("mVideoVisibleWidth"),
                    field("mVideoVisibleHeight"),
                    field("mVideoSarNum"),
                    field("mVideoSarDen"),
                )
            }.getOrNull()
        }
    }
}

private fun frameRateOf(numerator: Int, denominator: Int): Float? {
    if (numerator <= 0 || denominator <= 0) return null
    val rate = numerator.toFloat() / denominator.toFloat()
    return rate.takeIf { it > 1f }
}

private fun mimeForCodec(codec: String?, original: String?): String? {
    for (raw in listOfNotNull(codec, original)) {
        mimeOf(raw.trim().lowercase().replace(" ", ""))?.let { return it }
    }
    return null
}

private fun mimeOf(key: String): String? = when (key) {
    "mp2v", "mpgv", "mp1v", "mpeg2", "mpeg" -> "video/mpeg2"
    "h264", "avc1", "avc" -> "video/avc"
    "hevc", "hvc1", "hev1", "h265" -> "video/hevc"
    "vp09", "vp9" -> "video/x-vnd.on2.vp9"
    "av01" -> "video/av01"
    "a52", "ac3", "ac-3" -> "audio/ac3"
    "eac3", "ec-3", "ec3" -> "audio/eac3"
    "ac-4", "ac4" -> "audio/ac4"
    "mp4a", "aac" -> "audio/mp4a-latm"
    "mpga", "mp2", "mp2a", "mp3" -> "audio/mpeg"
    else -> null
}
