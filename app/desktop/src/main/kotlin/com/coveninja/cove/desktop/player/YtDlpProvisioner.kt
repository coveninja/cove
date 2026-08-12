package com.coveninja.cove.desktop.player

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Keeps a working copy of yt-dlp where mpv can find it.
 *
 * mpv turns a YouTube page into a stream by shelling out to yt-dlp, and requiring
 * every viewer to install it by hand to watch a trailer is a poor trade. What is
 * fetched here is the official release binary, checked against the checksum file
 * published beside it.
 *
 * It is **not** bundled into the application image, for two reasons: the platform
 * binaries are around 40 MB each, and — the one that actually decides it — yt-dlp
 * has to keep up with YouTube, which breaks extraction every few weeks. A copy
 * frozen at release time stops working long before the release does, and a copy
 * inside the install directory could not replace itself there anyway; Cove never
 * rewrites its own installation (see UpdateService). This copy lives in the data
 * directory, which the app owns and can refresh.
 *
 * A yt-dlp the viewer installed themselves always wins — see [ytdlSearchPath].
 */
class YtDlpProvisioner(
    private val toolsDirectory: Path,
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val architecture: String = System.getProperty("os.arch").orEmpty(),
    private val http: HttpClient = defaultHttpClient(),
    private val clock: () -> Instant = Instant::now,
) {
    /** Where the managed copy lives, whether or not it is there yet. */
    val managedPath: Path = toolsDirectory.resolve(managedFileName(osName)).toAbsolutePath().normalize()

    fun isInstalled(): Boolean = Files.isRegularFile(managedPath) && Files.isExecutable(managedPath)

    /** True once the copy is old enough that YouTube has probably moved on. */
    fun isStale(): Boolean {
        if (!isInstalled()) return false
        val modified = runCatching { Files.getLastModifiedTime(managedPath).toInstant() }.getOrNull()
            ?: return true
        return needsRefresh(modified, clock(), REFRESH_INTERVAL)
    }

    /**
     * Downloads the release binary and puts it in place, reporting progress as a
     * fraction of the total where the server declares a length.
     *
     * Verified before it is installed: the download goes to a temporary file, its
     * SHA-256 is compared against the release's own SHA2-256SUMS, and only then is
     * it moved into place. A mismatch leaves nothing behind.
     */
    fun install(onProgress: (Float?) -> Unit = {}): Result<Path> = runCatching {
        val asset = requireNotNull(assetName(osName, architecture)) {
            "No yt-dlp build is published for $osName ($architecture)."
        }
        val expected = requireNotNull(fetchChecksum(asset)) {
            "The yt-dlp release did not list a checksum for $asset."
        }

        Files.createDirectories(toolsDirectory)
        val temporary = toolsDirectory.resolve("${managedPath.fileName}.part-${UUID.randomUUID()}")
        try {
            val actual = download(assetUrl(asset), temporary, onProgress)
            check(actual.equals(expected, ignoreCase = true)) {
                "The downloaded yt-dlp did not match its published checksum."
            }
            temporary.toFile().setExecutable(true, true)
            moveInto(temporary, managedPath)
            managedPath
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun fetchChecksum(asset: String): String? {
        val request = HttpRequest.newBuilder(URI.create("$RELEASE_BASE/$CHECKSUM_FILE")).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        return parseChecksums(response.body())[asset]
    }

    private fun download(url: String, target: Path, onProgress: (Float?) -> Unit): String {
        val request = HttpRequest.newBuilder(URI.create(url)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "yt-dlp could not be downloaded (HTTP ${response.statusCode()})." }
        val total = response.headers().firstValueAsLong("content-length").orElse(-1L)
        return response.body().use { body -> copyHashing(body, target, total, onProgress) }
    }

    private fun copyHashing(
        source: InputStream,
        target: Path,
        totalBytes: Long,
        onProgress: (Float?) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        var lastReport = 0L
        Files.newOutputStream(target).use { sink ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                sink.write(buffer, 0, read)
                copied += read
                // Reported in steps rather than per buffer: this drives a line of
                // text on screen, and 600 updates a second is 600 recompositions.
                if (copied - lastReport >= PROGRESS_STEP_BYTES) {
                    lastReport = copied
                    onProgress(if (totalBytes > 0) (copied.toFloat() / totalBytes) else null)
                }
            }
        }
        onProgress(1f)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Replacing the file while mpv is running yt-dlp out of it is fine on Unix,
     * where the running process keeps the old inode. Windows refuses, and the
     * refresh simply fails — which is why refreshes are background work whose
     * failure nobody is told about, and why they are retried on the next play.
     */
    private fun moveInto(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun assetUrl(asset: String) = "$RELEASE_BASE/$asset"

    companion object {
        /** Always the newest release: an old yt-dlp is the thing being avoided. */
        private const val RELEASE_BASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download"
        private const val CHECKSUM_FILE = "SHA2-256SUMS"
        private const val PROGRESS_STEP_BYTES = 512 * 1024L
        private val REFRESH_INTERVAL: Duration = Duration.ofDays(7)

        private fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()
    }
}

/**
 * The release asset for this machine, or null where yt-dlp publishes none.
 *
 * The standalone builds are chosen over the small zipapp on purpose: the zipapp
 * needs a Python 3.9+ interpreter on the system, which is exactly the assumption
 * this whole mechanism exists to avoid.
 */
internal fun assetName(osName: String, architecture: String): String? {
    val arch = architecture.lowercase()
    val arm64 = arch == "aarch64" || arch == "arm64"
    val x86 = arch == "x86" || arch == "i386" || arch == "i686"
    return when {
        osName.startsWith("Windows", ignoreCase = true) -> when {
            arm64 -> "yt-dlp_arm64.exe"
            x86 -> "yt-dlp_x86.exe"
            else -> "yt-dlp.exe"
        }
        // One universal build covers both Apple silicon and Intel.
        osName.startsWith("Mac", ignoreCase = true) -> "yt-dlp_macos"
        osName.startsWith("Linux", ignoreCase = true) -> when {
            arm64 -> "yt-dlp_linux_aarch64"
            arch == "amd64" || arch == "x86_64" -> "yt-dlp_linux"
            // 32-bit x86 and armv7 ship only as zips, which would need unpacking
            // for a platform this app does not otherwise support.
            else -> null
        }
        else -> null
    }
}

/** Windows resolves executables by extension; nothing else cares. */
internal fun managedFileName(osName: String): String =
    if (osName.startsWith("Windows", ignoreCase = true)) "yt-dlp.exe" else "yt-dlp"

/**
 * The value for mpv's `ytdl_hook-ytdl_path`, which is a search list rather than a
 * single path: the managed copy first, then the names mpv would have looked for
 * anyway, so a viewer whose package manager installs yt-dlp keeps using theirs and
 * nothing is ever downloaded for them.
 */
internal fun ytdlSearchPath(managed: Path, osName: String): String {
    val separator = if (osName.startsWith("Windows", ignoreCase = true)) ";" else ":"
    return listOf(managed.toString(), "yt-dlp", "yt-dlp_x86", "youtube-dl").joinToString(separator)
}

/** `<hex>  <filename>` per line, as published beside every yt-dlp release. */
internal fun parseChecksums(body: String): Map<String, String> = body.lineSequence()
    .mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || parts[0].length != 64) return@mapNotNull null
        parts[1].trim() to parts[0].lowercase()
    }
    .toMap()

internal fun needsRefresh(installedAt: Instant, now: Instant, interval: Duration): Boolean =
    Duration.between(installedAt, now) >= interval

/**
 * What mpv should ask yt-dlp for.
 *
 * Not left to mpv's default, which prefers the highest separate video and audio
 * streams and lands on opus/webm: YouTube answers ffmpeg's request for those with
 * 403 Forbidden often enough to look like a broken player, while the mp4-family
 * streams play. Capped at 1080p because this is a trailer in a box on a page, and
 * the last fallback is the progressive stream, which is always allowed.
 */
internal const val YTDL_FORMAT =
    "bv*[vcodec^=avc1][height<=?1080]+ba[acodec^=mp4a]/b[height<=?1080]/b"
