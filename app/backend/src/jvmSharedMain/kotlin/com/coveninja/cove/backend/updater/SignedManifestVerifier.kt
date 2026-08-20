package com.coveninja.cove.backend.updater

import com.coveninja.cove.shared.model.UPDATE_MANIFEST_SCHEMA_VERSION
import com.coveninja.cove.shared.model.UpdateManifest
import com.coveninja.cove.shared.network.CoveJson
import java.util.Base64
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

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

        val verifier = Ed25519Signer()
        verifier.init(false, ed25519PublicKey(keyBytes))
        verifier.update(manifestBytes, 0, manifestBytes.size)
        if (!verifier.verifySignature(signatureBytes)) {
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

private val ED25519_OID = ASN1ObjectIdentifier("1.3.101.112")

/**
 * Reads an RFC 8410 SubjectPublicKeyInfo through BouncyCastle's lightweight API rather than
 * the JCA, because the JCA path cannot survive R8.
 *
 * `BouncyCastleProvider`'s constructor registers each algorithm family by loading a
 * `<Family>$Mappings` class *by name* and swallowing any failure, so R8 -- which sees no call
 * site for `asymmetric.EdEC$Mappings` -- deletes it and the provider comes up with no Ed25519
 * at all. Nothing fails at build time; the release APK simply answers every update check with
 * "no such algorithm: Ed25519 for provider BC" while every unminified build works. Signer and
 * key parameters below are ordinary references R8 can follow, and they behave identically on
 * both hosts.
 */
private fun ed25519PublicKey(keyBytes: ByteArray): Ed25519PublicKeyParameters {
    val info = runCatching { SubjectPublicKeyInfo.getInstance(keyBytes) }
        .getOrElse { throw SecurityException("embedded update public key is malformed", it) }
    if (info.algorithm.algorithm != ED25519_OID) {
        throw SecurityException("embedded update public key is not an Ed25519 key")
    }
    val raw = runCatching { info.publicKeyData.octets }
        .getOrElse { throw SecurityException("embedded update public key is malformed", it) }
    if (raw.size != Ed25519PublicKeyParameters.KEY_SIZE) {
        throw SecurityException("embedded update public key has the wrong length")
    }
    return Ed25519PublicKeyParameters(raw, 0)
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
