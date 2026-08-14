package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val UPDATE_MANIFEST_SCHEMA_VERSION = 1
const val UPDATE_MANIFEST_ASSET_NAME = "cove-update-manifest-v1.json"
const val UPDATE_MANIFEST_SIGNATURE_NAME = "cove-update-manifest-v1.json.sig"

@Serializable
data class UpdateManifest(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("key_id") val keyId: String,
    val version: String,
    @SerialName("release_name") val releaseName: String,
    @SerialName("published_at") val publishedAt: String,
    val assets: List<UpdateManifestAsset>,
)

@Serializable
data class UpdateManifestAsset(
    /** android, windows-installer, or windows-portable */
    val target: String,
    val name: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
)
