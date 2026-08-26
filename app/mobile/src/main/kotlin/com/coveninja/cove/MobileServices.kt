package com.coveninja.cove

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.Bitmap
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.coveninja.cove.backend.AndroidBackendRuntime
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.ui.state.NowPlaying
import com.coveninja.cove.ui.state.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSession
    private lateinit var notifications: NotificationManager

    /** The current title's poster, once it has been fetched. Null until then, and on failure. */
    private var artwork: Bitmap? = null

    /** What the last published metadata described, so an unchanged one is not republished. */
    private var lastMetadataKey: List<Any?>? = null
    private val player get() = (application as CoveMobileApplication).playerHost()

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        ensureChannel(PLAYBACK_CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
        mediaSession = MediaSession(this, "Cove playback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = player.setPaused(false)
                override fun onPause() = player.setPaused(true)
                override fun onStop() = player.stop()
                override fun onSeekTo(pos: Long) = player.seek(pos / 1_000.0)
                override fun onSkipToPrevious() = player.seekRelative(-10.0)
                override fun onSkipToNext() = player.seekRelative(30.0)
            })
            isActive = true
        }
        startForeground(
            PLAYBACK_NOTIFICATION_ID,
            playbackNotification(player.status.value, player.nowPlaying.value),
        )
        // Both, because the two answer different halves of what a lock screen shows and
        // neither changes when the other does: the title arrives once when a request opens,
        // the position four times a second.
        serviceScope.launch {
            combine(player.status, player.nowPlaying, ::Pair).collectLatest { (status, playing) ->
                updateMediaSession(status, playing)
                notifications.notify(
                    PLAYBACK_NOTIFICATION_ID,
                    playbackNotification(status, playing),
                )
            }
        }
        // Artwork is fetched off the status path: it is one image per title, it can fail, and
        // it must never hold up the transport state that shares this notification.
        serviceScope.launch {
            player.nowPlaying.collectLatest { playing ->
                artwork = playing?.artworkUrl?.let { loadArtwork(it) }
                notifications.notify(
                    PLAYBACK_NOTIFICATION_ID,
                    playbackNotification(player.status.value, playing),
                )
                updateMediaSession(player.status.value, playing)
            }
        }
    }

    /**
     * The poster, or null.
     *
     * Through the app's own Coil loader rather than a fresh HTTP call, so a title already on
     * screen costs nothing to show again here — it is the same URL the details page fetched.
     */
    private suspend fun loadArtwork(url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(this)
            .data(url)
            .build()
        (SingletonImageLoader.get(this).execute(request) as? SuccessResult)
            ?.image
            ?.toBitmap()
    }.getOrNull()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> player.togglePause()
            ACTION_REWIND -> player.seekRelative(-10.0)
            ACTION_FORWARD -> player.seekRelative(30.0)
            ACTION_STOP -> player.stop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private fun playbackNotification(
        status: PlaybackStatus,
        playing: NowPlaying?,
    ): Notification {
        val playAction = if (status.paused) "Play" else "Pause"
        val playIcon = if (status.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        return Notification.Builder(this, PLAYBACK_CHANNEL)
            .setSmallIcon(R.drawable.ic_cove_notification)
            .setContentTitle(playing?.title ?: "Cove")
            // The episode normally, but a transient state displaces it: someone glancing at a
            // stalled player needs to know it is buffering more than they need to be told
            // which episode they already chose.
            .setContentText(
                when {
                    status.error != null -> status.error
                    status.waitingForData -> "Buffering…"
                    // A film has no episode line, so it says "Paused" and stops there rather
                    // than padding it out with a word that describes nothing.
                    status.paused -> listOfNotNull("Paused", playing?.subtitle).joinToString(" · ")
                    else -> playing?.subtitle ?: "Playing"
                },
            )
            .apply { artwork?.let { setLargeIcon(it) } }
            .setContentIntent(activityIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(!status.paused)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_rew,
                    "Rewind 10 seconds",
                    serviceIntent(ACTION_REWIND, 1),
                ).build(),
            )
            .addAction(Notification.Action.Builder(playIcon, playAction, serviceIntent(ACTION_PLAY_PAUSE, 2)).build())
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_ff,
                    "Forward 30 seconds",
                    serviceIntent(ACTION_FORWARD, 3),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    serviceIntent(ACTION_STOP, 4),
                ).build(),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun updateMediaSession(status: PlaybackStatus, playing: NowPlaying?) {
        updateMetadata(status, playing)
        val state = when {
            status.endReached -> PlaybackState.STATE_STOPPED
            status.waitingForData || (!status.fileLoaded && !status.paused) -> PlaybackState.STATE_BUFFERING
            status.paused -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_PLAYING
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SKIP_TO_NEXT
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, (status.positionSeconds * 1_000).toLong(), status.speed.toFloat())
                .build(),
        )
    }

    /**
     * Pushes the metadata, but only when it has actually changed.
     *
     * The transport state arrives about four times a second and shares this method's caller;
     * republishing a bitmap at that rate is wasted work and makes some lock screens flicker.
     * The playback state below is cheap and does update every tick, which is what keeps the
     * scrubber moving.
     */
    private fun updateMetadata(status: PlaybackStatus, playing: NowPlaying?) {
        val durationMillis = if (status.durationSeconds > 0.0) {
            (status.durationSeconds * 1_000).toLong()
        } else {
            0L
        }
        val key = listOf(playing?.title, playing?.subtitle, durationMillis, artwork != null)
        if (key == lastMetadataKey) return
        lastMetadataKey = key

        // Without a duration the system media control draws no scrubber at all, which is why
        // the lock screen used to offer nothing but a pair of buttons.
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, playing?.title ?: "Cove")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, playing?.title ?: "Cove")
                .apply {
                    playing?.subtitle?.let {
                        putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                        putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
                    }
                    artwork?.let {
                        putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                        putBitmap(MediaMetadata.METADATA_KEY_ART, it)
                    }
                    if (status.durationSeconds > 0.0) {
                        putLong(
                            MediaMetadata.METADATA_KEY_DURATION,
                            (status.durationSeconds * 1_000).toLong(),
                        )
                    }
                }
                .build(),
        )
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // Not private: the picture-in-picture window offers the same three transport actions the
    // notification does, and both should mean the same thing by them.
    companion object {
        private const val PLAYBACK_NOTIFICATION_ID = 101
        private const val PLAYBACK_CHANNEL = "cove_playback"
        const val ACTION_PLAY_PAUSE = "com.coveninja.cove.PLAY_PAUSE"
        const val ACTION_REWIND = "com.coveninja.cove.REWIND"
        const val ACTION_FORWARD = "com.coveninja.cove.FORWARD"
        const val ACTION_STOP = "com.coveninja.cove.STOP_PLAYBACK"
    }
}

class RemoteAccessService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtime: AndroidBackendRuntime? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(REMOTE_CHANNEL, "Remote access", NotificationManager.IMPORTANCE_LOW)
        startForeground(REMOTE_NOTIFICATION_ID, notification())
        serviceScope.launch {
            (application as CoveMobileApplication).awaitBackendRuntime().also {
                runtime = it
                it.startRemoteAccessHost()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            serviceScope.launch {
                val activeRuntime = runtime
                    ?: (application as CoveMobileApplication).awaitBackendRuntime()
                val repository = activeRuntime.graph.settings
                val current = (repository.settings.value as? SettingsState.Ready)?.settings
                if (current != null) repository.update(current.copy(remoteAccessEnabled = false))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runtime?.stopRemoteAccessHost()
        runtime = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification = Notification.Builder(this, REMOTE_CHANNEL)
        .setSmallIcon(R.drawable.ic_cove_notification)
        .setContentTitle("Cove remote access")
        .setContentText("Available on your local network at port ${AndroidBackendRuntime.REMOTE_API_PORT}")
        .setContentIntent(activityIntent())
        .setOngoing(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Turn off",
                PendingIntent.getService(
                    this,
                    20,
                    Intent(this, RemoteAccessService::class.java).setAction(ACTION_DISABLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build(),
        )
        .build()

    private companion object {
        const val REMOTE_NOTIFICATION_ID = 102
        const val REMOTE_CHANNEL = "cove_remote_access"
        const val ACTION_DISABLE = "com.coveninja.cove.DISABLE_REMOTE_ACCESS"
    }
}

private fun Service.ensureChannel(id: String, name: String, importance: Int) {
    getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel(id, name, importance))
}

private fun Service.activityIntent(): PendingIntent = PendingIntent.getActivity(
    this,
    0,
    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)
