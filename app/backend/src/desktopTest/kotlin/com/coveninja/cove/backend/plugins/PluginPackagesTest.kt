package com.coveninja.cove.backend.plugins

import com.coveninja.cove.shared.data.PluginCatalog
import com.coveninja.cove.shared.data.PluginCatalogEntry
import com.coveninja.cove.shared.data.PluginManifest
import com.coveninja.cove.shared.network.CoveJson
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class PluginPackagesTest {
    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKeys = mapOf(KEY_ID to Base64.getEncoder().encodeToString(keyPair.public.encoded))

    @Test
    fun `signed catalog and package authenticate exact bytes`() {
        val packageBytes = pluginZip()
        val entry = PluginCatalogEntry(
            manifest = manifest(),
            packageUrl = "https://github.com/coveninja/cove-plugins/releases/download/v1/plugin.zip",
            signatureUrl = "https://github.com/coveninja/cove-plugins/releases/download/v1/plugin.zip.sig",
            sizeBytes = packageBytes.size.toLong(),
            sha256 = sha256(packageBytes),
        )
        val catalogBytes = CoveJson.encodeToString(
            PluginCatalog(keyId = KEY_ID, publishedAt = "2026-08-23T00:00:00Z", plugins = listOf(entry)),
        ).encodeToByteArray()
        val verifier = PluginSignatureVerifier(publicKeys)

        assertEquals(listOf(entry), verifier.verifyCatalog(catalogBytes, sign(catalogBytes)).plugins)
        verifier.verifyPackage(packageBytes, sign(packageBytes), KEY_ID)

        val tampered = packageBytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFailsWith<SecurityException> { verifier.verifyPackage(tampered, sign(packageBytes), KEY_ID) }
        assertFailsWith<SecurityException> {
            PluginSignatureVerifier(mapOf("other" to publicKeys.getValue(KEY_ID)))
                .verifyPackage(packageBytes, sign(packageBytes), KEY_ID)
        }
    }

    @Test
    fun `archive extraction stays inside plugin root and requires its entrypoint`() {
        val root = createTempDirectory("cove-plugin-archive")
        try {
            val extracted = PluginArchive.extract(pluginZip(), root)
            assertEquals(manifest(), extracted.manifest)
            assertTrue(extracted.directory.resolve("main.js").toFile().isFile)

            val traversal = zip(
                "plugin.json" to CoveJson.encodeToString(manifest()).encodeToByteArray(),
                "main.js" to "module.exports = {};".encodeToByteArray(),
                "../escaped.txt" to "no".encodeToByteArray(),
            )
            assertFailsWith<IllegalArgumentException> { PluginArchive.inspect(traversal) }
            assertTrue(!root.parent.resolve("escaped.txt").toFile().exists())

            val missingEntrypoint = zip(
                "plugin.json" to CoveJson.encodeToString(manifest()).encodeToByteArray(),
            )
            assertFailsWith<IllegalArgumentException> { PluginArchive.extract(missingEntrypoint, root) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `version and public key parsers fail closed`() {
        assertTrue(parsePluginVersion("1.2.3")!! > parsePluginVersion("1.2.2")!!)
        assertEquals(null, parsePluginVersion("1.2.3-beta"))
        assertEquals(mapOf("one" to "abc", "two" to "def"), parsePluginPublicKeys("one=abc,two=def"))
        assertEquals(emptyMap(), parsePluginPublicKeys("bad id=abc,missing"))
    }

    private fun pluginZip(): ByteArray = zip(
        "plugin.json" to CoveJson.encodeToString(manifest()).encodeToByteArray(),
        "main.js" to "module.exports = { activate() {} };".encodeToByteArray(),
    )

    private fun manifest() = PluginManifest(
        id = "io.github.coveninja.test-plugin",
        name = "Test plugin",
        version = "1.0.0",
        publisher = "Cove tests",
        minimumCoveVersion = "1.1.1",
    )

    private fun sign(bytes: ByteArray): ByteArray {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(bytes)
        return Base64.getEncoder().encode(signer.sign())
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private companion object {
        const val KEY_ID = "plugins-test-1"
    }
}
