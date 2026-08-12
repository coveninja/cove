package com.coveninja.cove

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import com.coveninja.cove.backend.AndroidBackendRuntime
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.ui.state.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSession
    private lateinit var notifications: NotificationManager
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
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Cove").build())
            isActive = true
        }
        startForeground(PLAYBACK_NOTIFICATION_ID, playbackNotification(player.status.value))
        serviceScope.launch {
            player.status.collectLatest { status ->
                updateMediaSession(status)
                notifications.notify(PLAYBACK_NOTIFICATION_ID, playbackNotification(status))
            }
        }
    }

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

    private fun playbackNotification(status: PlaybackStatus): Notification {
        val playAction = if (status.paused) "Play" else "Pause"
        val playIcon = if (status.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        return Notification.Builder(this, PLAYBACK_CHANNEL)
            .setSmallIcon(R.drawable.ic_cove)
            .setContentTitle("Cove")
            .setContentText(
                when {
                    status.error != null -> status.error
                    status.waitingForData -> "Buffering…"
                    status.paused -> "Playback paused"
                    else -> "Playing"
                },
            )
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

    private fun updateMediaSession(status: PlaybackStatus) {
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

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val PLAYBACK_NOTIFICATION_ID = 101
        const val PLAYBACK_CHANNEL = "cove_playback"
        const val ACTION_PLAY_PAUSE = "com.coveninja.cove.PLAY_PAUSE"
        const val ACTION_REWIND = "com.coveninja.cove.REWIND"
        const val ACTION_FORWARD = "com.coveninja.cove.FORWARD"
        const val ACTION_STOP = "com.coveninja.cove.STOP_PLAYBACK"
    }
}

class RemoteAccessService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = (application as CoveMobileApplication).backendRuntime()

    override fun onCreate() {
        super.onCreate()
        ensureChannel(REMOTE_CHANNEL, "Remote access", NotificationManager.IMPORTANCE_LOW)
        startForeground(REMOTE_NOTIFICATION_ID, notification())
        runtime.startRemoteAccessHost()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            serviceScope.launch {
                val repository = runtime.graph.settings
                val current = (repository.settings.value as? SettingsState.Ready)?.settings
                if (current != null) repository.update(current.copy(remoteAccessEnabled = false))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runtime.stopRemoteAccessHost()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification = Notification.Builder(this, REMOTE_CHANNEL)
        .setSmallIcon(R.drawable.ic_cove)
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
