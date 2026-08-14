package com.coveninja.cove.backend.updater

import com.coveninja.cove.shared.model.UPDATE_MANIFEST_SCHEMA_VERSION
import com.coveninja.cove.shared.model.UpdateManifest
import com.coveninja.cove.shared.network.CoveJson
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider

internal class SignedManifestVerifier(
    private val encodedPublicKeys: Map<String, String>,
) {
    fun verify(manifestBytes: ByteArray, encodedSignature: ByteArray): UpdateManifest {
        require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "update manifest is too large" }
        require(encodedSignature.size <= MAX_SIGNATURE_BYTES) { "update signature is too large" }

        // key_id is untrusted until the signature passes. It is used only to select
        // one already-embedded public key, never to load key material from the feed.
        val untrusted = runCatching {
            CoveJson.decodeFromString<UpdateManifest>(manifestBytes.decodeToString())
        }.getOrElse { throw SecurityException("update manifest is malformed", it) }
        val encodedKey = encodedPublicKeys[untrusted.keyId]
            ?: throw SecurityException("update manifest uses an unknown signing key")
        val keyBytes = runCatching { Base64.getDecoder().decode(encodedKey) }
            .getOrElse { throw SecurityException("embedded update public key is malformed", it) }
        val signatureBytes = runCatching {
            Base64.getDecoder().decode(encodedSignature.decodeToString().trim())
        }.getOrElse { throw SecurityException("update manifest signature is malformed", it) }

        val provider = BouncyCastleProvider()
        val publicKey = KeyFactory.getInstance("Ed25519", provider)
            .generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier = Signature.getInstance("Ed25519", provider)
        verifier.initVerify(publicKey)
        verifier.update(manifestBytes)
        if (!verifier.verify(signatureBytes)) {
            throw SecurityException("update manifest signature is invalid")
        }

        require(untrusted.schemaVersion == UPDATE_MANIFEST_SCHEMA_VERSION) {
            "unsupported update manifest schema ${untrusted.schemaVersion}"
        }
        require(untrusted.keyId.matches(KEY_ID)) { "invalid update signing key id" }
        require(parseStableVersion(untrusted.version) != null) { "invalid stable update version" }
        require(untrusted.assets.isNotEmpty()) { "update manifest has no assets" }
        require(untrusted.assets.map { it.name }.distinct().size == untrusted.assets.size) {
            "update manifest contains duplicate asset names"
        }
        require(untrusted.assets.map { it.target }.distinct().size == untrusted.assets.size) {
            "update manifest contains duplicate targets"
        }
        untrusted.assets.forEach { asset ->
            require(asset.target.matches(TARGET)) { "invalid update target" }
            require(asset.name.matches(ASSET_NAME) && '/' !in asset.name && '\\' !in asset.name) {
                "invalid update asset name"
            }
            require(asset.sizeBytes in 1..MAX_ASSET_BYTES) { "invalid update asset size" }
            require(asset.sha256.matches(SHA256)) { "invalid update asset checksum" }
        }
        return untrusted
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 256 * 1024
        const val MAX_SIGNATURE_BYTES = 4096
        const val MAX_ASSET_BYTES = 1L shl 30
        private val KEY_ID = Regex("[a-zA-Z0-9._-]{1,64}")
        private val TARGET = Regex("[a-z0-9-]{1,64}")
        private val ASSET_NAME = Regex("[a-zA-Z0-9._-]{1,128}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal data class StableVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<StableVersion> {
    override fun compareTo(other: StableVersion): Int =
        compareValuesBy(this, other, StableVersion::major, StableVersion::minor, StableVersion::patch)
}

internal fun parseStableVersion(value: String): StableVersion? {
    val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$").matchEntire(value)
        ?: return null
    return runCatching {
        StableVersion(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        )
    }.getOrNull()
}

/** Parses `key-id=base64-x509,key-id-2=...` from build-time configuration. */
internal fun parseUpdatePublicKeys(value: String): Map<String, String> = value
    .split(',')
    .mapNotNull { entry ->
        val separator = entry.indexOf('=')
        if (separator <= 0 || separator == entry.lastIndex) null
        else entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
    }
    .filter { (id, encoded) -> id.matches(Regex("[a-zA-Z0-9._-]{1,64}")) && encoded.isNotBlank() }
    .toMap()
