package com.coveninja.cove.backend.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.coveninja.cove.shared.data.AppUpdateRelease
import com.coveninja.cove.shared.data.UnavailableUpdateRepository
import com.coveninja.cove.shared.data.UpdateApplyResult
import com.coveninja.cove.shared.data.UpdateRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal object AndroidInstallResultBus {
    private val mutableEvents = MutableSharedFlow<PlatformInstallEvent>(extraBufferCapacity = 4)
    val events = mutableEvents.asSharedFlow()
    fun success() = mutableEvents.tryEmit(PlatformInstallEvent.Success)
    fun failure(message: String) = mutableEvents.tryEmit(PlatformInstallEvent.Failure(message))
}

internal fun createAndroidUpdateRepository(
    context: Context,
    currentVersion: String,
    scope: CoroutineScope,
    publicKeys: String,
    apiBase: String,
): UpdateRepository {
    val keys = parseUpdatePublicKeys(publicKeys)
    if (parseStableVersion(currentVersion) == null || keys.isEmpty()) return UnavailableUpdateRepository
    val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10 * 60 * 1_000L
            connectTimeoutMillis = 30_000L
            socketTimeoutMillis = 60_000L
        }
    }
    return SignedUpdateService(
        currentVersion = currentVersion,
        platform = AndroidUpdatePlatform(context.applicationContext),
        client = client,
        scope = scope,
        apiBase = apiBase.ifBlank { SignedUpdateService.DEFAULT_API_BASE },
        publicKeys = keys,
    )
}

private class AndroidUpdatePlatform(private val context: Context) : UpdatePlatform {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    override val target: String = "android"
    override val stagingDirectory: Path = File(context.filesDir, "updates/staged").toPath()
    override val installEvents = AndroidInstallResultBus.events

    override fun readPreferences() = UpdatePreferences(
        automaticUpdatesEnabled = preferences.getBoolean(AUTOMATIC, true),
        lastCheckEpochMillis = preferences.getLong(LAST_CHECK, 0L),
    )

    override fun writePreferences(preferences: UpdatePreferences) {
        check(
            this.preferences.edit()
                .putBoolean(AUTOMATIC, preferences.automaticUpdatesEnabled)
                .putLong(LAST_CHECK, preferences.lastCheckEpochMillis)
                .commit(),
        ) { "Unable to save update preferences" }
    }

    override fun isMeteredConnection(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false

    override fun canResumePermissionRequest(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    override fun install(payload: Path, release: AppUpdateRelease): UpdateApplyResult {
        val apk = payload.toFile()
        verifyPackage(apk)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return UpdateApplyResult.PermissionRequired
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            try {
                session.openWrite("cove-update.apk", 0L, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val result = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, AndroidPackageInstallerReceiver::class.java),
                    flags,
                )
                session.commit(result.intentSender)
            } catch (error: Throwable) {
                session.abandon()
                throw error
            }
        }
        return UpdateApplyResult.Started
    }

    private fun verifyPackage(apk: File) {
        require(apk.isFile) { "The verified APK is missing." }
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
            ?: throw SecurityException("The update is not a valid APK.")
        require(archive.packageName == context.packageName) { "The update APK has the wrong package name." }
        @Suppress("DEPRECATION")
        val current = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        require(archive.longVersionCode > current.longVersionCode) { "The update APK is not newer." }
        val archiveSigners = archive.signingInfo?.apkContentsSigners.orEmpty().map(::fingerprint).toSet()
        val currentSigners = current.signingInfo?.apkContentsSigners.orEmpty().map(::fingerprint).toSet()
        require(archiveSigners.isNotEmpty() && archiveSigners == currentSigners) {
            "The update APK is not signed by Cove's installed signing certificate."
        }
    }

    private fun fingerprint(signature: android.content.pm.Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val PREFERENCES = "cove-update-preferences"
        const val AUTOMATIC = "automatic-updates-enabled"
        const val LAST_CHECK = "last-check-epoch-millis"
    }
}
