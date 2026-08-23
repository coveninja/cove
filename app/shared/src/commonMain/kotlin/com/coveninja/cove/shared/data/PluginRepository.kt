package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.MediaTimestamps
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.SubtitleSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

const val COVE_PLUGIN_API_VERSION = 1

@Serializable
enum class PluginCapability {
    @SerialName("playback.observe") PlaybackObserve,
    @SerialName("playback.transport") PlaybackTransport,
    @SerialName("media.streams") MediaStreams,
    @SerialName("media.subtitles") MediaSubtitles,
    @SerialName("media.timestamps") MediaTimestamps,
    @SerialName("metadata.augment") MetadataAugment,
    @SerialName("network.http") NetworkHttp,
    @SerialName("network.lan") NetworkLan,
    @SerialName("storage.profile") StorageProfile,
    @SerialName("ui.settings") UiSettings,
    @SerialName("discord.presence") DiscordPresence,
}

@Serializable
enum class PluginSettingType {
    @SerialName("boolean") Boolean,
    @SerialName("string") String,
    @SerialName("number") Number,
    @SerialName("select") Select,
    @SerialName("action") Action,
}

@Serializable
data class PluginSettingOption(val value: String, val label: String)

@Serializable
data class PluginSettingDefinition(
    val key: String,
    val type: PluginSettingType,
    val label: String,
    val description: String = "",
    val default: JsonElement = JsonNull,
    val options: List<PluginSettingOption> = emptyList(),
    val minimum: Double? = null,
    val maximum: Double? = null,
)

@Serializable
data class PluginManifest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("api_version") val apiVersion: Int = COVE_PLUGIN_API_VERSION,
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val publisher: String,
    val entrypoint: String = "main.js",
    @SerialName("minimum_cove_version") val minimumCoveVersion: String = "0.0.0",
    val capabilities: Set<PluginCapability> = emptySet(),
    @SerialName("allowed_hosts") val allowedHosts: Set<String> = emptySet(),
    @SerialName("discord_application_id") val discordApplicationId: String? = null,
    val settings: List<PluginSettingDefinition> = emptyList(),
)

@Serializable
data class PluginCatalogEntry(
    val manifest: PluginManifest,
    @SerialName("package_url") val packageUrl: String,
    @SerialName("signature_url") val signatureUrl: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class PluginCatalog(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("key_id") val keyId: String,
    @SerialName("published_at") val publishedAt: String,
    val plugins: List<PluginCatalogEntry>,
)

enum class PluginRuntimeStatus {
    Installed,
    PermissionRequired,
    Disabled,
    Starting,
    Running,
    Waiting,
    Failed,
    UpdateStaged,
}

data class InstalledPlugin(
    val manifest: PluginManifest,
    val enabled: Boolean = false,
    val approvedCapabilities: Set<PluginCapability> = emptySet(),
    val requestedCapabilities: Set<PluginCapability> = manifest.capabilities,
    val status: PluginRuntimeStatus = PluginRuntimeStatus.Installed,
    val statusMessage: String = "",
    val settings: Map<String, JsonElement> = emptyMap(),
    val unsigned: Boolean = false,
    val updateVersion: String? = null,
)

sealed interface PluginsState {
    data object Loading : PluginsState
    data class Ready(
        val catalog: List<PluginCatalogEntry> = emptyList(),
        val installed: List<InstalledPlugin> = emptyList(),
        val developerMode: Boolean = false,
        val catalogError: String? = null,
    ) : PluginsState

    data class Failed(val message: String) : PluginsState
}

@Serializable
data class PluginPlaybackActivity(
    val active: Boolean = false,
    val tmdbId: Int? = null,
    val mediaType: String? = null,
    val title: String = "",
    val artworkUrl: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val extraTitle: String? = null,
    val phase: String = "idle",
    val paused: Boolean = true,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val speed: Double = 1.0,
    val reconnecting: Boolean = false,
)

sealed interface PluginTransportCommand {
    data class SetPaused(val paused: Boolean) : PluginTransportCommand
    data class SeekAbsolute(val seconds: Double) : PluginTransportCommand
    data class SeekRelative(val seconds: Double) : PluginTransportCommand
    data object Stop : PluginTransportCommand
}

@Serializable
data class PluginMediaRequest(
    val tmdbId: Int,
    val mediaType: MediaType,
    val imdbId: String = "",
    val title: String = "",
    val year: Int = 0,
    val season: Int? = null,
    val episode: Int? = null,
)

@Serializable
data class PluginStreamResult(
    val name: String = "",
    val title: String = "",
    val url: String? = null,
    val infoHash: String? = null,
    val fileIndex: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val sizeBytes: Long = 0,
    val seeders: Int = 0,
    val pluginId: String = "",
    val pluginName: String = "",
)

@Serializable
data class PluginMetadataAugment(
    val overview: String? = null,
    val tagline: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val links: Map<String, String> = emptyMap(),
)

interface PluginRepository {
    val available: Boolean
    val state: StateFlow<PluginsState>
    val transportCommands: Flow<PluginTransportCommand>

    suspend fun refreshCatalog()
    suspend fun install(pluginId: String)
    suspend fun installLocal(path: String)
    suspend fun approve(pluginId: String, capabilities: Set<PluginCapability>)
    suspend fun uninstall(pluginId: String)
    suspend fun setEnabled(pluginId: String, enabled: Boolean)
    suspend fun updateSetting(pluginId: String, key: String, value: JsonElement)
    suspend fun invokeAction(pluginId: String, key: String)
    suspend fun retry(pluginId: String)
    suspend fun setDeveloperMode(enabled: Boolean)

    fun publishPlayback(activity: PluginPlaybackActivity)

    suspend fun streams(request: PluginMediaRequest): List<PluginStreamResult>
    suspend fun subtitles(request: PluginMediaRequest): List<SubtitleSource>
    suspend fun timestamps(request: PluginMediaRequest): MediaTimestamps
    suspend fun augmentMetadata(request: PluginMediaRequest): List<PluginMetadataAugment>
}

object UnavailablePluginRepository : PluginRepository {
    override val available: Boolean = false
    override val state: StateFlow<PluginsState> = MutableStateFlow(PluginsState.Ready())
    override val transportCommands: Flow<PluginTransportCommand> = emptyFlow()

    override suspend fun refreshCatalog() = Unit
    override suspend fun install(pluginId: String) = Unit
    override suspend fun installLocal(path: String) = Unit
    override suspend fun approve(pluginId: String, capabilities: Set<PluginCapability>) = Unit
    override suspend fun uninstall(pluginId: String) = Unit
    override suspend fun setEnabled(pluginId: String, enabled: Boolean) = Unit
    override suspend fun updateSetting(pluginId: String, key: String, value: JsonElement) = Unit
    override suspend fun invokeAction(pluginId: String, key: String) = Unit
    override suspend fun retry(pluginId: String) = Unit
    override suspend fun setDeveloperMode(enabled: Boolean) = Unit
    override fun publishPlayback(activity: PluginPlaybackActivity) = Unit
    override suspend fun streams(request: PluginMediaRequest): List<PluginStreamResult> = emptyList()
    override suspend fun subtitles(request: PluginMediaRequest): List<SubtitleSource> = emptyList()
    override suspend fun timestamps(request: PluginMediaRequest): MediaTimestamps = MediaTimestamps.None
    override suspend fun augmentMetadata(request: PluginMediaRequest): List<PluginMetadataAugment> = emptyList()
}
