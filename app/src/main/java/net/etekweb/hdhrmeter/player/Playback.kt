package net.etekweb.hdhrmeter.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import net.etekweb.hdhrmeter.data.StreamFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

data class VideoStats(
    val videoMime: String? = null,
    val audioMime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val bitrateBps: Long? = null,
    val audioChannels: Int? = null,
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
    private val commandGeneration = AtomicInteger(0)
    /** Each play() bumps this. An older queued start must not swallow the latest seek. */
    private val startSerial = AtomicInteger(0)

    @Volatile private var wantPlaying = true
    @Volatile private var pendingUrl: String? = null
    @Volatile private var ignoreErrorUntilElapsed = 0L
    @Volatile private var playingUrl: String? = null
    @Volatile private var attachedLayout: VLCVideoLayout? = null
    @Volatile private var ignoreNextStop = false

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
        val media = Media(library, Uri.parse(url))
        media.addOption(":http-user-agent=HDHomeRunSignalMeter")
        media.addOption(":network-caching=1200")
        media.addOption(":live-caching=1200")
        // Hardware first, then VLC's own decoder. Pixel phones have no MPEG-2 MediaCodec.
        media.setHWDecoderEnabled(true, false)
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
        playingUrl = url
        if (!wantPlaying) runCatching { mediaPlayer.pause() }
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
            var videoMime: String? = null
            var audioMime: String? = null
            var width: Int? = null
            var height: Int? = null
            var frameRate: Float? = null
            var audioChannels: Int? = null
            var trackBits: Long? = null
            val count = media.trackCount
            for (index in 0 until count) {
                when (val track = media.getTrack(index)) {
                    is IMedia.VideoTrack -> if (videoMime == null) {
                        videoMime = mimeForCodec(track.codec, track.originalCodec)
                        val size = pictureSize(track, mediaPlayer)
                        width = size?.first
                        height = size?.second
                        frameRate = frameRateOf(track.frameRateNum, track.frameRateDen)
                        trackBits = track.bitrate.takeIf { it > 0 }?.toLong()
                    }
                    is IMedia.AudioTrack -> if (audioMime == null) {
                        audioMime = mimeForCodec(track.codec, track.originalCodec)
                        audioChannels = track.channels.takeIf { it > 0 }
                    }
                }
            }
            val stats = media.stats
            // libvlc reports these rates in bytes per microsecond.
            val demuxBps = bitsPerSecond(stats?.demuxBitrate ?: 0f)
            val inputBps = bitsPerSecond(stats?.inputBitrate ?: 0f)
            _stats.value = VideoStats(
                videoMime = videoMime,
                audioMime = audioMime,
                width = width,
                height = height,
                frameRate = frameRate,
                bitrateBps = demuxBps ?: inputBps ?: trackBits,
                audioChannels = audioChannels,
            )
        } catch (_: RuntimeException) {
            // Tracks are not ready on the first buffering events.
        } finally {
            runCatching { media.release() }
        }
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
    }
}

fun playbackFailure(failure: StreamFailure): PlaybackFailure {
    return PlaybackFailure(message = failure.message, hdhrCode = failure.hdhrCode)
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
