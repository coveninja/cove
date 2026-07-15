package com.coveninja.cove.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Manifest-declared broadcast receiver for PackageInstaller session commit
 * status callbacks (exported="false" — targeted via explicit component).
 *
 * The PendingIntent delivered to [ApkUpdater.installApk] must be FLAG_MUTABLE
 * so PackageInstaller can write EXTRA_STATUS and EXTRA_INTENT into it.
 *
 * Outcomes:
 *  - STATUS_PENDING_USER_ACTION → extract EXTRA_INTENT and start it; the OS
 *    presents the system "install unknown apps" or confirm dialog.
 *  - STATUS_SUCCESS → log only; the OS kills and restarts the process, and
 *    PostUpdateReceiver handles the relaunch notification.
 *  - Any other status (failure/abort) → send ACTION_INSTALL_FAILED scoped to
 *    this package so WebViewActivity's runtime receiver can fall back to normal UI.
 */
class PackageInstallerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        Log.d(TAG, "PackageInstaller status: $status")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Extract the confirm intent and launch it. On API 33+ use the
                // typed overload to avoid the unchecked-cast deprecation warning.
                val confirmIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    Log.i(TAG, "Launching user-action confirm dialog")
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                } else {
                    Log.w(TAG, "STATUS_PENDING_USER_ACTION but EXTRA_INTENT is null — falling back")
                    broadcastFailure(context)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // Install succeeded. PostUpdateReceiver (MY_PACKAGE_REPLACED) will
                // fire in the new process and handle the TV relaunch / phone notification.
                Log.i(TAG, "Package install succeeded")
            }

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Package install failed: status=$status message=$msg")
                broadcastFailure(context)
            }
        }
    }

    /** Fires ACTION_INSTALL_FAILED so WebViewActivity can fall back to normal UI. */
    private fun broadcastFailure(context: Context) {
        context.sendBroadcast(
            Intent(ACTION_INSTALL_FAILED).setPackage(context.packageName)
        )
    }

    companion object {
        private const val TAG = "PackageInstallerReceiver"

        /** Broadcast action sent on install failure so the activity can fall back. */
        const val ACTION_INSTALL_FAILED = "com.coveninja.cove.ACTION_INSTALL_FAILED"
    }
}
