package com.coveninja.cove.updater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import com.coveninja.cove.WebViewActivity

/**
 * Manifest-declared broadcast receiver for [Intent.ACTION_MY_PACKAGE_REPLACED].
 * This implicit broadcast is exempt from Android 8+ background-broadcast
 * restrictions and fires reliably in the new process after a self-update.
 *
 * On TV: [Context.startActivity] from a broadcast receiver is permitted in
 * practice (no user-visible app is in the foreground after a silent update).
 * On phone: Android 10+ silently drops background startActivity calls, so we
 * post a "Cove updated — tap to open" notification instead.
 *
 * The notification channel is created idempotently here — [CoveService] may
 * not have started yet in the freshly-installed process, so we cannot rely on
 * its channel being present.
 */
class PostUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "MY_PACKAGE_REPLACED received — relaunching app")

        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        val isTV = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        if (isTV) {
            try {
                val launchIntent = Intent(context, WebViewActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(launchIntent)
                Log.i(TAG, "TV relaunch intent sent")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to relaunch on TV: ${e.message}", e)
            }
        } else {
            postUpdateNotification(context)
        }
    }

    private fun postUpdateNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        // Create the channel idempotently — no-op if it already exists.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Cove Updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Cove app update notifications" }
        )

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, WebViewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Cove updated")
            .setContentText("Tap to open")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "Update notification posted")
    }

    companion object {
        private const val TAG = "PostUpdateReceiver"
        private const val CHANNEL_ID = "cove_update"
        private const val NOTIFICATION_ID = 42
    }
}
