package com.coveninja.cove.updater

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.coveninja.cove.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Describes an available update fetched from GitHub releases. */
data class UpdateResult(
    val apkUrl: String,
    val shaUrl: String,
    val versionCode: Int,
)

// ── kotlinx.serialization DTOs ────────────────────────────────────────────────

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)

/**
 * APK self-updater: checks GitHub releases for a newer version, downloads and
 * SHA-256 verifies the APK, then installs it via PackageInstaller.
 *
 * All network operations are expected to be called on Dispatchers.IO. Every
 * public method throws on error — callers are responsible for catching and
 * falling back to normal app launch (fail-open design).
 *
 * Mirrors the Go internal/updater package in spirit: same two-client approach
 * (short API timeout / long download timeout), same asset-name expectations,
 * same versionCode formula as build.gradle.kts.
 */
class ApkUpdater {

    // Short-lived client for the tiny GitHub API response (should be sub-second).
    private val apiClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // Long-lived client for the APK download (30+ MB on a slow connection).
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Checks the GitHub releases API for a version newer than [currentVersionCode].
     * Returns [UpdateResult] when one is found, or null otherwise.
     *
     * Dev guard: returns null immediately when [currentVersionCode] ≤ 1 and
     * [BuildConfig.UPDATE_BASE_URL] is empty — local builds without COVE_VERSION
     * produce versionCode=1 and should never hit production infrastructure.
     */
    fun checkForUpdate(currentVersionCode: Int): UpdateResult? {
        if (currentVersionCode <= 1 && BuildConfig.UPDATE_BASE_URL.isEmpty()) {
            Log.d(TAG, "Dev build — skipping update check")
            return null
        }

        // UPDATE_BASE_URL supports local end-to-end testing (e.g. http://10.0.2.2:8000).
        // When empty, use the real GitHub API.
        val baseUrl = if (BuildConfig.UPDATE_BASE_URL.isNotEmpty()) {
            BuildConfig.UPDATE_BASE_URL.trimEnd('/')
        } else {
            GITHUB_API_BASE
        }

        return checkForUpdateAt(currentVersionCode, baseUrl)
    }

    /**
     * Release-lookup half of [checkForUpdate], split out so unit tests can point
     * it at a stub server. [baseUrl] is already trimmed of any trailing slash.
     */
    internal fun checkForUpdateAt(currentVersionCode: Int, baseUrl: String): UpdateResult? {
        val req = Request.Builder()
            .url("$baseUrl/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()

        val body = apiClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GitHub releases API returned ${resp.code}")
                return null
            }
            resp.body?.string() ?: run {
                Log.w(TAG, "Empty releases API response")
                return null
            }
        }

        val release = json.decodeFromString<GitHubRelease>(body)
        val tag = release.tagName

        // Clean-semver gate: tag must be v<major>.<minor>.<patch> (or without "v").
        // takeWhile matches build.gradle.kts's `part.takeWhile { it.isDigit() }`.
        val vStr = tag.removePrefix("v")
        val parts = vStr.split(".")
        if (parts.size < 3) {
            Log.w(TAG, "Unrecognised release tag: $tag")
            return null
        }
        val maj = parts[0].toIntOrNull() ?: run { Log.w(TAG, "Bad major in tag: $tag"); return null }
        val min = parts[1].toIntOrNull() ?: run { Log.w(TAG, "Bad minor in tag: $tag"); return null }
        val pat = parts[2].takeWhile { it.isDigit() }.toIntOrNull()
            ?: run { Log.w(TAG, "Bad patch in tag: $tag"); return null }

        val remoteVersionCode = tagToVersionCode(maj, min, pat)
        if (remoteVersionCode <= currentVersionCode) {
            Log.d(TAG, "Already up-to-date (local=$currentVersionCode remote=$remoteVersionCode)")
            return null
        }

        // Require both the APK and its SHA-256 checksum — fail closed if either is missing.
        val apkAsset = release.assets.find { it.name == "cove-android.apk" } ?: run {
            Log.w(TAG, "cove-android.apk missing from release $tag")
            return null
        }
        val shaAsset = release.assets.find { it.name == "cove-android.apk.sha256" } ?: run {
            Log.w(TAG, "cove-android.apk.sha256 missing from release $tag")
            return null
        }

        Log.i(TAG, "Update available: $tag (versionCode=$remoteVersionCode)")
        return UpdateResult(
            apkUrl = apkAsset.browserDownloadUrl,
            shaUrl = shaAsset.browserDownloadUrl,
            versionCode = remoteVersionCode,
        )
    }

    /**
     * Downloads [apkUrl] to [destFile] while computing SHA-256, then verifies
     * the digest against the expected hash fetched from [shaUrl].
     *
     * The [onProgress] callback is called with values 0..100 as bytes arrive;
     * 100 signals a clean completion. Callers should post to the main thread
     * before touching any UI from [onProgress].
     *
     * On mismatch: deletes [destFile] and throws [SecurityException].
     */
    fun downloadAndVerify(
        apkUrl: String,
        shaUrl: String,
        destFile: File,
        onProgress: (Int) -> Unit,
    ) {
        // Fetch the expected hash first (tiny file; first whitespace field is the hash).
        val expectedHash = downloadClient.newCall(
            Request.Builder().url(shaUrl).get().build()
        ).execute().use { resp ->
            val raw = resp.body?.string()?.trim()
                ?: throw IllegalStateException("Empty SHA-256 response")
            val hash = raw.split(Regex("\\s+")).firstOrNull()
                ?: throw IllegalStateException("Cannot parse SHA-256 response: $raw")
            if (!hash.matches(Regex("[0-9a-fA-F]{64}"))) {
                throw IllegalStateException("Invalid SHA-256 hash: $hash")
            }
            hash.lowercase()
        }
        Log.d(TAG, "Expected SHA-256: $expectedHash")

        // Stream the APK while computing SHA-256 on-the-fly.
        val digest = MessageDigest.getInstance("SHA-256")
        val apkResp = downloadClient.newCall(
            Request.Builder().url(apkUrl).get().build()
        ).execute()

        try {
            val contentLength = apkResp.body?.contentLength() ?: -1L
            val inputStream = apkResp.body?.byteStream()
                ?: throw IllegalStateException("Empty APK response body")
            var downloaded = 0L
            val buf = ByteArray(8 * 1024)

            destFile.outputStream().use { out ->
                while (true) {
                    val n = inputStream.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    downloaded += n
                    if (contentLength > 0) {
                        onProgress(((downloaded * 100) / contentLength).toInt().coerceIn(0, 99))
                    }
                }
                out.flush()
            }
        } finally {
            apkResp.close()
        }

        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualHash != expectedHash) {
            destFile.delete()
            throw SecurityException("SHA-256 mismatch: expected $expectedHash got $actualHash")
        }

        onProgress(100)
        Log.i(TAG, "APK verified: ${destFile.path}")
    }

    /**
     * Deletes any stale `cove-update*.apk` files from [cacheDir] left over
     * from a previously interrupted download.
     */
    fun cleanStaleCacheFiles(cacheDir: File) {
        cacheDir.listFiles { f ->
            f.name.startsWith("cove-update") && f.name.endsWith(".apk")
        }?.forEach { f ->
            Log.d(TAG, "Removing stale cache file: ${f.name}")
            f.delete()
        }
    }

    /**
     * Installs [apkFile] via PackageInstaller. Commits the session with
     * [statusIntent] so [PackageInstallerReceiver] receives the result.
     *
     * On API 31+ sets USER_ACTION_NOT_REQUIRED so subsequent updates (once
     * Cove is its own installer-of-record) are silent. The very first update
     * still shows a system confirm dialog; a user cancellation arrives as a
     * failure status in [PackageInstallerReceiver].
     *
     * [statusIntent] MUST be a FLAG_MUTABLE PendingIntent — PackageInstaller
     * writes EXTRA_STATUS and EXTRA_INTENT into it; FLAG_IMMUTABLE breaks the
     * callback silently on API 31+.
     */
    fun installApk(context: Context, apkFile: File, statusIntent: PendingIntent) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("cove-update.apk", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            session.commit(statusIntent.intentSender)
            Log.i(TAG, "PackageInstaller session $sessionId committed")
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }

    companion object {
        private const val TAG = "ApkUpdater"

        private const val GITHUB_API_BASE = "https://api.github.com/repos/coveninja/cove"

        /**
         * Converts a semver triple to a versionCode using the same formula as
         * build.gradle.kts: major*10000 + minor*100 + patch.
         */
        fun tagToVersionCode(major: Int, minor: Int, patch: Int): Int =
            major * 10000 + minor * 100 + patch
    }
}
