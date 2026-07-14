package com.coveninja.cove.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.coveninja.cove.BuildConfig
import com.coveninja.cove.WebViewActivity
import java.io.File

/**
 * Foreground service that owns the embedded Go backend lifecycle.
 *
 * Started from CoveApplication.onCreate via startForegroundService().
 * Calls startForeground() immediately in onStartCommand (Android requires
 * this within ~5 s of startForegroundService() or throws
 * ForegroundServiceDidNotStartInTimeException). The actual mobile.Mobile.start()
 * runs on a background Thread to avoid blocking the main thread.
 *
 * android:foregroundServiceType="mediaPlayback" — declared in the manifest —
 * satisfies the API 34 requirement that mediaPlayback services pass
 * ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK to startForeground().
 */
class CoveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Post the notification immediately — must happen before mobile.Mobile.start()
        // (which blocks until the HTTP server is up) to stay within the 5 s window.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        // Guard double-start — isRunning() is safe to call from any thread.
        if (!mobile.Mobile.isRunning()) {
            Thread {
                try {
                    // torrents in filesDir, not cacheDir: OS cache eviction could
                    // purge a partially-downloaded torrent piece mid-stream.
                    val torrentDir = File(filesDir, "torrents").absolutePath
                    mobile.Mobile.start(
                        /* bindAddr        */ "127.0.0.1:6969",
                        /* dataDir         */ filesDir.absolutePath,
                        /* cacheDir        */ cacheDir.absolutePath,
                        /* torrentDir      */ torrentDir,
                        /* tmdbAPIKey      */ BuildConfig.TMDB_API_KEY,
                        /* supabaseURL     */ BuildConfig.SUPABASE_URL,
                        /* supabaseAnonKey */ BuildConfig.SUPABASE_ANON_KEY,
                        /* version         */ BuildConfig.VERSION_NAME,
                    )
                    Log.i(TAG, "Go backend started on 127.0.0.1:6969")
                } catch (e: Exception) {
                    Log.e(TAG, "Go backend failed to start: ${e.message}", e)
                }
            }.start()
        } else {
            Log.i(TAG, "Go backend already running — skipping start")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mobile.Mobile.stop()
            Log.i(TAG, "Go backend stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Go backend stop error: ${e.message}", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cove",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Cove media backend service"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WebViewActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Cove")
            .setContentText("Backend running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG           = "CoveService"
        const val CHANNEL_ID            = "cove_backend"
        private const val NOTIFICATION_ID = 1
    }
}
