package com.coveninja.cove.backend.plugins

import com.coveninja.cove.shared.data.PluginCapability
import com.coveninja.cove.shared.network.CoveJson
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class PluginStoreDocument(
    val developerMode: Boolean = false,
    val installed: Map<String, StoredPlugin> = emptyMap(),
)

@Serializable
internal data class StoredPlugin(
    val version: String,
    val keyId: String? = null,
    val unsigned: Boolean = false,
    val approvedCapabilities: Set<PluginCapability> = emptySet(),
    val enabledProfiles: Set<String> = emptySet(),
    val profileSettings: Map<String, Map<String, JsonElement>> = emptyMap(),
    val stagedVersion: String? = null,
    val stagedCapabilities: Set<PluginCapability> = emptySet(),
)

internal class PluginStore(private val file: Path) {
    fun read(): PluginStoreDocument {
        if (!Files.isRegularFile(file)) return PluginStoreDocument()
        return runCatching { CoveJson.decodeFromString<PluginStoreDocument>(Files.readString(file)) }
            .getOrDefault(PluginStoreDocument())
    }

    fun write(document: PluginStoreDocument) {
        atomicWrite(file, CoveJson.encodeToString(document).encodeToByteArray())
    }
}
