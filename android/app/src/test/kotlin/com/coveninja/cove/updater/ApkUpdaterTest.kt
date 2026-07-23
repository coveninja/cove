package com.coveninja.cove.updater

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Covers the self-update path. The download half is security-critical: a wrong
 * SHA-256 must never leave an APK on disk, and a release missing either asset
 * must fail closed rather than installing something unverified.
 */
class ApkUpdaterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val updater = ApkUpdater()

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun releaseJson(tag: String, assetNames: List<String>): String {
        val assets = assetNames.joinToString(",") { name ->
            """{"name":"$name","browser_download_url":"https://example.test/$name"}"""
        }
        return """{"tag_name":"$tag","assets":[$assets]}"""
    }

    private val bothAssets = listOf("cove-android.apk", "cove-android.apk.sha256")

    // ── tagToVersionCode ─────────────────────────────────────────────────────

    @Test
    fun tagToVersionCodeMatchesBuildGradleFormula() {
        assertEquals(10000, ApkUpdater.tagToVersionCode(1, 0, 0))
        assertEquals(2900, ApkUpdater.tagToVersionCode(0, 29, 0))
        assertEquals(10203, ApkUpdater.tagToVersionCode(1, 2, 3))
    }

    @Test
    fun tagToVersionCodeOrdersMinorBelowNextMajor() {
        // 0.29.0 must sort below 1.0.0 or the updater would refuse a real release.
        assertTrue(
            ApkUpdater.tagToVersionCode(0, 29, 0) < ApkUpdater.tagToVersionCode(1, 0, 0),
        )
    }

    // ── checkForUpdate: dev guard ────────────────────────────────────────────

    @Test
    fun checkForUpdateSkipsDevBuildWithoutOverrideUrl() {
        // versionCode 1 is what a local build without COVE_VERSION produces, and
        // BuildConfig.UPDATE_BASE_URL is empty in unit tests, so this must bail
        // before issuing any request.
        assertNull(updater.checkForUpdate(1))
        assertEquals(0, server.requestCount)
    }

    // ── checkForUpdateAt: version gating ─────────────────────────────────────

    @Test
    fun checkForUpdateReturnsResultForNewerTag() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.2.3", bothAssets)))

        val result = updater.checkForUpdateAt(10000, baseUrl())

        assertNotNull(result)
        assertEquals(10203, result!!.versionCode)
        assertEquals("https://example.test/cove-android.apk", result.apkUrl)
        assertEquals("https://example.test/cove-android.apk.sha256", result.shaUrl)
    }

    @Test
    fun checkForUpdateRequestsLatestReleaseEndpoint() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.2.3", bothAssets)))

        updater.checkForUpdateAt(10000, baseUrl())

        assertEquals("/releases/latest", server.takeRequest().path)
    }

    @Test
    fun checkForUpdateReturnsNullForEqualVersion() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.2.3", bothAssets)))

        assertNull(updater.checkForUpdateAt(10203, baseUrl()))
    }

    @Test
    fun checkForUpdateReturnsNullForOlderVersion() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.0.0", bothAssets)))

        assertNull(updater.checkForUpdateAt(10203, baseUrl()))
    }

    @Test
    fun checkForUpdateAcceptsTagWithoutVPrefix() {
        server.enqueue(MockResponse().setBody(releaseJson("1.2.3", bothAssets)))

        assertEquals(10203, updater.checkForUpdateAt(10000, baseUrl())?.versionCode)
    }

    @Test
    fun checkForUpdateParsesPatchWithSuffix() {
        // takeWhile { isDigit() } mirrors build.gradle.kts, so "3-rc1" is patch 3.
        server.enqueue(MockResponse().setBody(releaseJson("v1.2.3-rc1", bothAssets)))

        assertEquals(10203, updater.checkForUpdateAt(10000, baseUrl())?.versionCode)
    }

    // ── checkForUpdateAt: malformed tags ─────────────────────────────────────

    @Test
    fun checkForUpdateReturnsNullForTagWithTooFewParts() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.2", bothAssets)))

        assertNull(updater.checkForUpdateAt(1000, baseUrl()))
    }

    @Test
    fun checkForUpdateReturnsNullForNonNumericMajor() {
        server.enqueue(MockResponse().setBody(releaseJson("vX.2.3", bothAssets)))

        assertNull(updater.checkForUpdateAt(1000, baseUrl()))
    }

    @Test
    fun checkForUpdateReturnsNullForNonNumericMinor() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.Y.3", bothAssets)))

        assertNull(updater.checkForUpdateAt(1000, baseUrl()))
    }

    @Test
    fun checkForUpdateReturnsNullForNonNumericPatch() {
        server.enqueue(MockResponse().setBody(releaseJson("v1.2.z", bothAssets)))

        assertNull(updater.checkForUpdateAt(1000, baseUrl()))
    }

    // ── checkForUpdateAt: fail-closed on missing assets ──────────────────────

    @Test
    fun checkForUpdateFailsClosedWhenApkAssetMissing() {
        server.enqueue(
            MockResponse().setBody(releaseJson("v1.2.3", listOf("cove-android.apk.sha256"))),
        )

        assertNull(updater.checkForUpdateAt(10000, baseUrl()))
    }

    @Test
    fun checkForUpdateFailsClosedWhenChecksumAssetMissing() {
        // An APK with no published checksum must never be offered — there would
        // be nothing to verify the download against.
        server.enqueue(
            MockResponse().setBody(releaseJson("v1.2.3", listOf("cove-android.apk"))),
        )

        assertNull(updater.checkForUpdateAt(10000, baseUrl()))
    }

    @Test
    fun checkForUpdateReturnsNullOnApiError() {
        server.enqueue(MockResponse().setResponseCode(503))

        assertNull(updater.checkForUpdateAt(10000, baseUrl()))
    }

    // ── downloadAndVerify ────────────────────────────────────────────────────

    private fun enqueueShaThenApk(shaBody: String, apk: ByteArray) {
        server.enqueue(MockResponse().setBody(shaBody))
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
    }

    @Test
    fun downloadAndVerifyWritesFileWhenHashMatches() {
        val apk = "pretend-apk-bytes".toByteArray()
        enqueueShaThenApk(sha256Of(apk), apk)
        val dest = File(tempFolder.root, "cove-update.apk")

        updater.downloadAndVerify(
            server.url("/apk").toString(),
            server.url("/sha").toString(),
            dest,
        ) {}

        assertTrue(dest.exists())
        assertArrayEquals(apk, dest.readBytes())
    }

    @Test
    fun downloadAndVerifyAcceptsShaSumLineWithFilename() {
        // sha256sum output is "<hash>  <filename>"; only the first field is the hash.
        val apk = "payload".toByteArray()
        enqueueShaThenApk("${sha256Of(apk)}  cove-android.apk\n", apk)
        val dest = File(tempFolder.root, "cove-update.apk")

        updater.downloadAndVerify(
            server.url("/apk").toString(),
            server.url("/sha").toString(),
            dest,
        ) {}

        assertTrue(dest.exists())
    }

    @Test
    fun downloadAndVerifyAcceptsUppercaseHash() {
        val apk = "payload".toByteArray()
        enqueueShaThenApk(sha256Of(apk).uppercase(), apk)
        val dest = File(tempFolder.root, "cove-update.apk")

        updater.downloadAndVerify(
            server.url("/apk").toString(),
            server.url("/sha").toString(),
            dest,
        ) {}

        assertTrue(dest.exists())
    }

    @Test
    fun downloadAndVerifyDeletesFileAndThrowsOnHashMismatch() {
        val apk = "real-bytes".toByteArray()
        // Advertise the hash of different content — the classic tampered-download case.
        enqueueShaThenApk(sha256Of("other-bytes".toByteArray()), apk)
        val dest = File(tempFolder.root, "cove-update.apk")

        val error = runCatching {
            updater.downloadAndVerify(
                server.url("/apk").toString(),
                server.url("/sha").toString(),
                dest,
            ) {}
        }.exceptionOrNull()

        assertTrue("expected SecurityException, got $error", error is SecurityException)
        assertFalse("unverified APK must not survive on disk", dest.exists())
    }

    @Test
    fun downloadAndVerifyRejectsMalformedHash() {
        server.enqueue(MockResponse().setBody("not-a-sha256"))
        val dest = File(tempFolder.root, "cove-update.apk")

        val error = runCatching {
            updater.downloadAndVerify(
                server.url("/apk").toString(),
                server.url("/sha").toString(),
                dest,
            ) {}
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertFalse(dest.exists())
    }

    @Test
    fun downloadAndVerifyRejectsShortHexHash() {
        // 63 hex chars — must not pass the [0-9a-fA-F]{64} gate.
        server.enqueue(MockResponse().setBody("a".repeat(63)))
        val dest = File(tempFolder.root, "cove-update.apk")

        val error = runCatching {
            updater.downloadAndVerify(
                server.url("/apk").toString(),
                server.url("/sha").toString(),
                dest,
            ) {}
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
    }

    @Test
    fun downloadAndVerifyReportsProgressEndingAt100() {
        val apk = ByteArray(64 * 1024) { it.toByte() }
        enqueueShaThenApk(sha256Of(apk), apk)
        val dest = File(tempFolder.root, "cove-update.apk")
        val progress = mutableListOf<Int>()

        updater.downloadAndVerify(
            server.url("/apk").toString(),
            server.url("/sha").toString(),
            dest,
        ) { progress.add(it) }

        assertEquals(100, progress.last())
        assertTrue("progress must never regress: $progress", progress.zipWithNext().all { it.first <= it.second })
        assertTrue("intermediate values must stay under 100", progress.dropLast(1).all { it in 0..99 })
    }

    // ── cleanStaleCacheFiles ─────────────────────────────────────────────────

    @Test
    fun cleanStaleCacheFilesRemovesOnlyMatchingApks() {
        val stale = File(tempFolder.root, "cove-update.apk").also { it.writeText("x") }
        val staleSuffixed = File(tempFolder.root, "cove-update-2.apk").also { it.writeText("x") }
        val otherApk = File(tempFolder.root, "something-else.apk").also { it.writeText("x") }
        val notApk = File(tempFolder.root, "cove-update.txt").also { it.writeText("x") }

        updater.cleanStaleCacheFiles(tempFolder.root)

        assertFalse(stale.exists())
        assertFalse(staleSuffixed.exists())
        assertTrue(otherApk.exists())
        assertTrue(notApk.exists())
    }

    @Test
    fun cleanStaleCacheFilesToleratesEmptyDirectory() {
        updater.cleanStaleCacheFiles(tempFolder.root)

        assertEquals(0, tempFolder.root.listFiles()?.size)
    }
}
