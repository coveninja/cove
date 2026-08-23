package com.coveninja.cove.backend.plugins

import com.coveninja.cove.shared.data.PluginCapability
import com.coveninja.cove.shared.data.PluginManifest
import java.io.BufferedReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
internal data class PluginWorkerInit(
    val protocolVersion: Int = 1,
    val manifest: PluginManifest,
    val source: String,
    val settings: Map<String, JsonElement> = emptyMap(),
    val storage: Map<String, JsonElement> = emptyMap(),
    val grantedCapabilities: Set<PluginCapability> = emptySet(),
    val allowLan: Boolean = false,
)

@Serializable
internal data class PluginHostFrame(
    val type: String,
    val id: String? = null,
    val method: String? = null,
    val payload: JsonElement = JsonNull,
)

@Serializable
internal data class PluginWorkerFrame(
    val type: String,
    val id: String? = null,
    val method: String? = null,
    val payload: JsonElement = JsonNull,
    val message: String? = null,
)

@Serializable
internal data class PluginFetchOptions(
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)

@Serializable
internal data class PluginFetchResponse(
    val status: Int,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val redirected: Boolean = false,
)

internal fun BufferedReader.readPluginProtocolLine(maxCharacters: Int): String? {
    val value = StringBuilder()
    while (true) {
        when (val next = read()) {
            -1 -> return value.takeIf { it.isNotEmpty() }?.toString()
            '\n'.code -> return value.toString()
            '\r'.code -> Unit
            else -> {
                require(value.length < maxCharacters) { "plugin protocol frame is too large" }
                value.append(next.toChar())
            }
        }
    }
}
