package com.eamat.hdhrmeter.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.eamat.hdhrmeter.data.StreamFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

class PlaybackEngine(context: Context) {
    private val appContext = context.applicationContext
    private val _stats = MutableStateFlow(VideoStats())
    private val _phase = MutableStateFlow(EnginePhase.Idle)
    private val _error = MutableStateFlow<PlaybackFailure?>(null)

    val stats: StateFlow<VideoStats> = _stats.asStateFlow()
    val phase: StateFlow<EnginePhase> = _phase.asStateFlow()
    val error: StateFlow<PlaybackFailure?> = _error.asStateFlow()

    private val lib: LibVLC?
    private val player: MediaPlayer?
    private val unavailable: String?
    private val commands = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hdhr-playback").apply { isDaemon = true }
    }
    private val commandGeneration = AtomicInteger(0)

    @Volatile private var pendingUrl: String? = null
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

    fun play(url: String) {
        val mediaPlayer = player
        if (mediaPlayer == null || lib == null) {
            _error.value = PlaybackFailure(unavailable ?: "Video playback could not start on this phone.")
            _phase.value = EnginePhase.Idle
            return
        }
        pendingUrl = url
        _error.value = null
        _stats.value = VideoStats()
        _phase.value = EnginePhase.Buffering
        runCatching { mediaPlayer.volume = 100 }
        enqueueStart()
    }

    fun attach(layout: VLCVideoLayout) {
        val mediaPlayer = player ?: return
        if (layout === attachedLayout) return
        detachInternal()
        try {
            mediaPlayer.attachViews(layout, null, false, true)
            mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            attachedLayout = layout
            enqueueStart()
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
    }

    fun release() {
        pendingUrl = null
        playingUrl = null
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

    private fun enqueueStart() {
        val generation = commandGeneration.get()
        val mediaPlayer = player ?: return
        commands.execute {
            if (generation != commandGeneration.get()) return@execute
            startLocked(mediaPlayer, generation)
        }
    }

    private fun startLocked(mediaPlayer: MediaPlayer, generation: Int) {
        val library = lib ?: return
        val url = pendingUrl ?: return
        if (attachedLayout == null) return
        if (generation != commandGeneration.get()) return
        if (playingUrl == url && mediaPlayer.isPlaying) return
        ignoreNextStop = true
        mediaPlayer.stop()
        if (generation != commandGeneration.get()) return
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
    }

    private fun detachInternal() {
        if (attachedLayout == null) return
        runCatching { player?.detachViews() }
        attachedLayout = null
    }

    private fun onPlayerEvent(event: MediaPlayer.Event) {
        val mediaPlayer = player ?: return
        when (event.type) {
            MediaPlayer.Event.Opening -> _phase.value = EnginePhase.Buffering
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
                publishTracks(mediaPlayer)
            }
            MediaPlayer.Event.TimeChanged -> publishTracks(mediaPlayer)
            MediaPlayer.Event.Stopped -> {
                if (ignoreNextStop) {
                    ignoreNextStop = false
                    return
                }
                if (pendingUrl == null) {
                    _phase.value = EnginePhase.Idle
                }
            }
            MediaPlayer.Event.EndReached -> {
                ignoreNextStop = false
                playingUrl = null
                _phase.value = EnginePhase.Ended
                _error.value = PlaybackFailure("The stream ended.")
            }
            MediaPlayer.Event.EncounteredError -> {
                ignoreNextStop = false
                playingUrl = null
                _phase.value = EnginePhase.Idle
                _error.value = PlaybackFailure("Playback failed. The stream could not be decoded.")
            }
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
                        width = track.width.takeIf { it > 0 }
                        height = displayHeight(track.height).takeIf { it > 0 }
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

private fun displayHeight(height: Int): Int {
    // MPEG-2 pads 1080-line frames to a multiple of 16, so the coded height is 1088.
    return if (height == 1088) 1080 else height
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
