package com.coveninja.cove.backend.updater

import com.coveninja.cove.shared.data.AppUpdateRelease
import com.coveninja.cove.shared.data.AppUpdateState
import com.coveninja.cove.shared.data.UpdateApplyResult
import com.coveninja.cove.shared.model.UPDATE_MANIFEST_ASSET_NAME
import com.coveninja.cove.shared.model.UPDATE_MANIFEST_SCHEMA_VERSION
import com.coveninja.cove.shared.model.UPDATE_MANIFEST_SIGNATURE_NAME
import com.coveninja.cove.shared.model.UpdateManifest
import com.coveninja.cove.shared.model.UpdateManifestAsset
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.bouncycastle.jce.provider.BouncyCastleProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SignedUpdateServiceTest {
    private val provider = BouncyCastleProvider()
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519", provider).generateKeyPair()
    private val publicKeys = mapOf(KEY_ID to Base64.getEncoder().encodeToString(keyPair.public.encoded))

    @Test
    fun `verifier authenticates exact bytes and rejects tampering`() {
        val bytes = manifest().bytes()
        val verifier = SignedManifestVerifier(publicKeys)

        assertEquals("1.1.0", verifier.verify(bytes, sign(bytes)).version)

        val tampered = bytes.copyOf().also { it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 1).toByte() }
        assertFailsWith<SecurityException> { verifier.verify(tampered, sign(bytes)) }
        assertFailsWith<SecurityException> {
            SignedManifestVerifier(mapOf("another-key" to publicKeys.getValue(KEY_ID)))
                .verify(bytes, sign(bytes))
        }
    }

    @Test
    fun `verifier rejects duplicate targets and unsafe asset metadata`() {
        val duplicate = manifest().copy(
            assets = listOf(manifest().assets.single(), manifest().assets.single().copy(name = "other.exe")),
        )
        val duplicateBytes = duplicate.bytes()
        assertFailsWith<IllegalArgumentException> {
            SignedManifestVerifier(publicKeys).verify(duplicateBytes, sign(duplicateBytes))
        }

        val traversal = manifest().copy(
            assets = listOf(manifest().assets.single().copy(name = "../update.exe")),
        )
        val traversalBytes = traversal.bytes()
        assertFailsWith<IllegalArgumentException> {
            SignedManifestVerifier(publicKeys).verify(traversalBytes, sign(traversalBytes))
        }
    }

    @Test
    fun `stable version ordering excludes development and prerelease versions`() {
        assertTrue(parseStableVersion("v1.0.0")!! > parseStableVersion("0.31.3")!!)
        assertEquals(null, parseStableVersion("1.0.0-rc1"))
        assertEquals(null, parseStableVersion("dev"))
        assertEquals(mapOf("one" to "abc", "two" to "def"), parseUpdatePublicKeys("one=abc,two=def"))
    }

    @Test
    fun `service downloads verifies stages and starts platform installer`() = runTest {
        val payload = "verified windows updater".encodeToByteArray()
        val manifest = manifest(payload)
        val manifestBytes = manifest.bytes()
        val signature = sign(manifestBytes)
        val directory = createTempDirectory("cove-update-test")
        val platform = FakePlatform(directory)
        val client = updateClient(manifestBytes, signature, payload)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = SignedUpdateService(
            currentVersion = "1.0.0",
            platform = platform,
            client = client,
            scope = scope,
            apiBase = "http://updates.test",
            publicKeys = publicKeys,
        )
        try {
            service.checkNow()

            val ready = assertIs<AppUpdateState.Ready>(service.state.value)
            assertEquals("1.1.0", ready.release.version)
            assertEquals(payload.toList(), Files.readAllBytes(directory.resolve("update.payload")).toList())
            assertEquals(UpdateApplyResult.ExitRequired, service.applyReadyUpdate())
            assertEquals("1.1.0", platform.installed?.version)

            service.setAutomaticUpdatesEnabled(false)
            assertTrue(Files.notExists(directory.resolve("update.payload")))
        } finally {
            service.close()
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `service fails closed when downloaded payload is tampered`() = runTest {
        val signedPayload = "expected".encodeToByteArray()
        val manifest = manifest(signedPayload)
        val bytes = manifest.bytes()
        val directory = createTempDirectory("cove-update-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = SignedUpdateService(
            currentVersion = "1.0.0",
            platform = FakePlatform(directory),
            client = updateClient(bytes, sign(bytes), "tampered".encodeToByteArray()),
            scope = scope,
            apiBase = "http://updates.test",
            publicKeys = publicKeys,
        )
        try {
            service.checkNow()
            assertIs<AppUpdateState.Failed>(service.state.value)
            assertTrue(Files.notExists(directory.resolve("update.payload")))
        } finally {
            service.close()
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `service rechecks staged bytes immediately before platform install`() = runTest {
        val payload = "verified".encodeToByteArray()
        val manifest = manifest(payload)
        val bytes = manifest.bytes()
        val directory = createTempDirectory("cove-update-test")
        val platform = FakePlatform(directory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = SignedUpdateService(
            currentVersion = "1.0.0",
            platform = platform,
            client = updateClient(bytes, sign(bytes), payload),
            scope = scope,
            apiBase = "http://updates.test",
            publicKeys = publicKeys,
        )
        try {
            service.checkNow()
            Files.write(directory.resolve("update.payload"), "modified".encodeToByteArray())

            assertEquals(UpdateApplyResult.NothingToApply, service.applyReadyUpdate())
            assertIs<AppUpdateState.Failed>(service.state.value)
            assertEquals(null, platform.installed)
            assertTrue(Files.notExists(directory.resolve("update.payload")))
        } finally {
            service.close()
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `startup removes an incomplete stage and makes it immediately retryable`() = runTest {
        val directory = createTempDirectory("cove-update-test")
        Files.write(directory.resolve("update.payload.part-interrupted"), byteArrayOf(1, 2, 3))
        val platform = FakePlatform(directory).apply {
            preferences = UpdatePreferences(lastCheckEpochMillis = 123L)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = SignedUpdateService(
            currentVersion = "1.0.0",
            platform = platform,
            client = updateClient(byteArrayOf(), byteArrayOf(), byteArrayOf()),
            scope = scope,
            apiBase = "http://updates.test",
            publicKeys = publicKeys,
        )
        try {
            service.start()

            assertIs<AppUpdateState.Failed>(service.state.value)
            assertEquals(0L, platform.preferences.lastCheckEpochMillis)
            assertTrue(Files.notExists(directory.resolve("update.payload.part-interrupted")))
        } finally {
            service.close()
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    private fun updateClient(manifest: ByteArray, signature: ByteArray, payload: ByteArray): HttpClient {
        val release = """{
          "tag_name":"v1.1.0","name":"v1.1.0","draft":false,"prerelease":false,
          "assets":[
            {"name":"$UPDATE_MANIFEST_ASSET_NAME","browser_download_url":"http://updates.test/manifest"},
            {"name":"$UPDATE_MANIFEST_SIGNATURE_NAME","browser_download_url":"http://updates.test/signature"},
            {"name":"update.exe","browser_download_url":"http://updates.test/payload"}
          ]
        }""".trimIndent().encodeToByteArray()
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val body = when (request.url.encodedPath) {
                        "/releases/latest" -> release
                        "/manifest" -> manifest
                        "/signature" -> signature
                        "/payload" -> payload
                        else -> error("unexpected request ${request.url}")
                    }
                    respond(
                        body,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentLength, body.size.toString()),
                    )
                }
            }
        }
    }

    private fun manifest(payload: ByteArray = "payload".encodeToByteArray()) = UpdateManifest(
        schemaVersion = UPDATE_MANIFEST_SCHEMA_VERSION,
        keyId = KEY_ID,
        version = "1.1.0",
        releaseName = "Cove 1.1.0",
        publishedAt = "2026-08-13T00:00:00Z",
        assets = listOf(
            UpdateManifestAsset(
                target = "windows-installer",
                name = "update.exe",
                sizeBytes = payload.size.toLong(),
                sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(payload)
                    .joinToString("") { "%02x".format(it) },
            ),
        ),
    )

    private fun UpdateManifest.bytes() = CoveJson.encodeToString(this).encodeToByteArray()

    private fun sign(bytes: ByteArray): ByteArray {
        val signer = Signature.getInstance("Ed25519", provider)
        signer.initSign(keyPair.private)
        signer.update(bytes)
        return Base64.getEncoder().encode(signer.sign())
    }

    private class FakePlatform(override val stagingDirectory: Path) : UpdatePlatform {
        override val target = "windows-installer"
        var preferences = UpdatePreferences()
        var installed: AppUpdateRelease? = null
        override fun readPreferences() = preferences
        override fun writePreferences(preferences: UpdatePreferences) {
            this.preferences = preferences
        }
        override fun install(payload: Path, release: AppUpdateRelease): UpdateApplyResult {
            installed = release
            return UpdateApplyResult.ExitRequired
        }
    }

    private companion object {
        const val KEY_ID = "test-key"
    }
}
