package net.etekweb.hdhrmeter.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.etekweb.hdhrmeter.MainActivity
import net.etekweb.hdhrmeter.R
import net.etekweb.hdhrmeter.ui.MeterViewModel
import net.etekweb.hdhrmeter.ui.PlaybackPhase
import net.etekweb.hdhrmeter.ui.Screen
import net.etekweb.hdhrmeter.ui.UiState
import net.etekweb.hdhrmeter.ui.isTelevisionUi

class PlaybackService : Service() {
    private val store = ViewModelStore()
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var notifications: NotificationManager
    private lateinit var openPlayer: PendingIntent
    private lateinit var model: MeterViewModel
    private var foreground = false
    private var card: MediaCard? = null
    private var idleStop: Job? = null

    inner class Connection : Binder() {
        val viewModel: MeterViewModel get() = model
        val canKeepPlaying: Boolean get() = foreground
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            model.setPlaybackPlaying(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        model = ViewModelProvider.create(
            store,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[MeterViewModel::class]
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.media_playback), NotificationManager.IMPORTANCE_LOW),
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:playback")
            .apply { setReferenceCounted(false) }
        openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession(this, "HDHomeRun").apply {
            setSessionActivity(openPlayer)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = model.setPlaybackPlaying(true)
                override fun onPause() = model.setPlaybackPlaying(false)
                override fun onStop() = model.onBack()
                override fun onRewind() = skipFromRemote(-SKIP_STEP_MS)
                override fun onFastForward() = skipFromRemote(SKIP_STEP_MS)
                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == ACTION_STOP) onStop()
                }

                override fun onSeekTo(pos: Long) {
                    val state = model.ui.value
                    val clock = state.playbackClock
                    val duration = clock.liveMs - clock.bufferStartMs
                    if (state.screen !is Screen.PlayRecording || !clock.timeshift || duration <= 0 || pos < 0) return
                    model.seekPlayback(clock.bufferStartMs + pos.coerceAtMost(duration))
                }
            })
        }
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch { model.ui.collect(::updatePlayback) }
    }

    override fun onBind(intent: Intent): Binder = Connection()

    private fun skipFromRemote(deltaMs: Long) {
        if (isTelevisionUi(resources.configuration.uiMode) && model.ui.value.schedule.pending == null) {
            model.skipPlayback(deltaMs)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> model.setPlaybackPlaying(true)
            ACTION_PAUSE -> model.setPlaybackPlaying(false)
            ACTION_STOP -> model.onBack()
        }
        if (!foreground && idleStop?.isActive != true) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        unregisterReceiver(noisyReceiver)
        if (wakeLock.isHeld) wakeLock.release()
        session.release()
        store.clear()
        super.onDestroy()
    }

    private fun updatePlayback(state: UiState) {
        val media = mediaCard(state)
        if (media == null) {
            if (wakeLock.isHeld) wakeLock.release()
            if (foreground) {
                foreground = false
                card = null
                session.isActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                idleStop = scope.launch {
                    // A stop from system controls can leave the service as the model's only owner.
                    model.recordingPositionSave?.join()
                    stopSelf()
                }
            }
            return
        }

        idleStop?.cancel()
        val clock = state.playbackClock
        val position = if (media.durationMs == null) PlaybackState.PLAYBACK_POSITION_UNKNOWN
        else (clock.positionMs - clock.bufferStartMs).coerceIn(0L, media.durationMs)
        val playing = media.state == PlaybackState.STATE_PLAYING
        val speed = if (playing && state.phase == PlaybackPhase.Ready) 1f else 0f
        val canSkip = media.controlsReady && clock.timeshift && isTelevisionUi(resources.configuration.uiMode)
        val actions = PlaybackState.ACTION_STOP or
            (if (media.controlsReady) PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE else 0L) or
            (if (canSkip) PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD else 0L) or
            (if (media.durationMs != null) PlaybackState.ACTION_SEEK_TO else 0L)
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(media.state, position, speed)
                .addCustomAction(ACTION_STOP, getString(R.string.media_stop), android.R.drawable.ic_menu_close_clear_cancel)
                .build(),
        )
        val previous = card
        if (previous?.title != media.title || previous.subtitle != media.subtitle ||
            previous.durationMs != media.durationMs
        ) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, media.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, media.subtitle)
                    .apply { media.durationMs?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) } }
                    .build(),
            )
        }
        if (!foreground) {
            try {
                // Start while the player is visible so playback can outlive the activity binding.
                startService(Intent(this, PlaybackService::class.java))
                session.isActive = true
                startForeground(NOTIFICATION_ID, notification(media))
                foreground = true
            } catch (error: IllegalStateException) {
                session.isActive = false
                stopSelf()
                Log.w(TAG, "Could not enter the playback foreground service", error)
                return
            }
        } else if (previous != media) {
            notifications.notify(NOTIFICATION_ID, notification(media))
        }
        card = media
        val needsWakeLock = playing || media.state == PlaybackState.STATE_BUFFERING
        if (needsWakeLock && !wakeLock.isHeld) wakeLock.acquire()
        if (!needsWakeLock && wakeLock.isHeld) wakeLock.release()
    }

    private fun notification(media: MediaCard): Notification {
        val stop = action(android.R.drawable.ic_menu_close_clear_cancel, R.string.media_stop, ACTION_STOP)
        val playing = media.state != PlaybackState.STATE_PAUSED
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(media.title)
            .setContentText(media.subtitle)
            .setContentIntent(openPlayer)
            .setDeleteIntent(stop.actionIntent)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .apply {
                if (media.controlsReady) {
                    addAction(
                        if (playing) action(android.R.drawable.ic_media_pause, R.string.media_pause, ACTION_PAUSE)
                        else action(android.R.drawable.ic_media_play, R.string.media_play, ACTION_PLAY),
                    )
                }
                addAction(stop)
                val style = Notification.MediaStyle().setMediaSession(session.sessionToken)
                if (media.controlsReady) style.setShowActionsInCompactView(0, 1)
                else style.setShowActionsInCompactView(0)
                setStyle(style)
            }
            .build()
    }

    private fun action(icon: Int, label: Int, command: String): Notification.Action {
        val pending = PendingIntent.getService(
            this,
            0,
            Intent(this, PlaybackService::class.java).setAction(command),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), pending)
            .build()
    }

    private fun mediaCard(state: UiState): MediaCard? {
        if (state.playbackError != null || state.tunerReleased || state.phase == PlaybackPhase.Idle) return null
        val clock = state.playbackClock
        val playbackState = when {
            !clock.active -> PlaybackState.STATE_BUFFERING
            // Android's loading indicator replaces the transport button. Once
            // transport is ready, let the user pause even during a rebuffer.
            clock.playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        return when (val screen = state.screen) {
            is Screen.Watch -> {
                val channel = screen.channel
                val station = "${channel.guideNumber} ${channel.guideName}".trim()
                val time = if (clock.active) clock.positionMs else state.guideNowMs
                val program = state.guide?.airing(channel.guideNumber, channel.guideName, time)
                MediaCard(program?.title ?: station, station.takeIf { program != null }, playbackState, clock.active)
            }
            is Screen.PlayRecording -> MediaCard(
                screen.recording.title,
                screen.recording.episodeTitle ?: screen.recording.channelName,
                playbackState,
                clock.active,
                (clock.liveMs - clock.bufferStartMs).takeIf { clock.timeshift && it > 0 },
            )
            Screen.Shell -> null
        }
    }

    private data class MediaCard(
        val title: String,
        val subtitle: String?,
        val state: Int,
        val controlsReady: Boolean,
        val durationMs: Long? = null,
    )

    private companion object {
        const val TAG = "HdhrPlaybackService"
        const val CHANNEL = "playback"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "net.etekweb.hdhrmeter.PLAY"
        const val ACTION_PAUSE = "net.etekweb.hdhrmeter.PAUSE"
        const val ACTION_STOP = "net.etekweb.hdhrmeter.STOP"
    }
}
