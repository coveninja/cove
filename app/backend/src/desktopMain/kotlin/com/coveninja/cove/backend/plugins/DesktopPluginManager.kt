package com.coveninja.cove.backend.plugins

import com.coveninja.cove.shared.data.InstalledPlugin
import com.coveninja.cove.shared.data.PluginCapability
import com.coveninja.cove.shared.data.PluginCatalog
import com.coveninja.cove.shared.data.PluginCatalogEntry
import com.coveninja.cove.shared.data.PluginMediaRequest
import com.coveninja.cove.shared.data.PluginMetadataAugment
import com.coveninja.cove.shared.data.PluginPlaybackActivity
import com.coveninja.cove.shared.data.PluginRepository
import com.coveninja.cove.shared.data.PluginRuntimeStatus
import com.coveninja.cove.shared.data.PluginStreamResult
import com.coveninja.cove.shared.data.PluginTransportCommand
import com.coveninja.cove.shared.data.PluginsState
import com.coveninja.cove.shared.data.PluginSettingType
import com.coveninja.cove.shared.model.MediaTimestamps
import com.coveninja.cove.shared.model.SubtitleSource
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopPluginManager(
    dataDirectory: Path,
    private val activeProfileIds: kotlinx.coroutines.flow.StateFlow<String>,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient,
    private val catalogApiBase: String,
    publicKeys: Map<String, String>,
    private val currentCoveVersion: String,
    private val allowLan: () -> Boolean,
) : PluginRepository, AutoCloseable {
    override val available: Boolean = true
    private val root = dataDirectory.resolve("plugins").toAbsolutePath().normalize()
    private val packages = root.resolve("packages")
    private val store = PluginStore(root.resolve("state.json"))
    private val verifier = PluginSignatureVerifier(publicKeys)
    private val signingKeysAvailable = publicKeys.isNotEmpty()
    private val mutation = Mutex()
    private val lifecycle = Mutex()
    private var document = activateSafeStagedUpdates(store.read())
    private var catalog = PluginCatalog(keyId = "", publishedAt = "", plugins = emptyList())
    private val runtime = ConcurrentHashMap<String, RuntimeRecord>()
    private val discord = ConcurrentHashMap<String, DiscordIpcClient>()
    private val statuses = ConcurrentHashMap<String, Pair<PluginRuntimeStatus, String>>()
    private val logs = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val crashHistory = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val crashBlocked = ConcurrentHashMap.newKeySet<String>()
    private var lastPlayback = PluginPlaybackActivity()
    private var closed = false

    private val _state = MutableStateFlow<PluginsState>(PluginsState.Loading)
    override val state = _state.asStateFlow()
    private val _transportCommands = MutableSharedFlow<PluginTransportCommand>(extraBufferCapacity = 32)
    override val transportCommands = _transportCommands.asSharedFlow()

    init {
        Files.createDirectories(packages)
        store.write(document)
        publishState()
        scope.launch {
            activeProfileIds.collectLatest {
                stopAll(clearPresence = true)
                reconcile()
            }
        }
        scope.launch {
            delay(CATALOG_LAUNCH_DELAY_MILLIS)
            while (!closed) {
                runCatching { refreshCatalog() }
                delay(CATALOG_REFRESH_MILLIS)
            }
        }
    }

    override suspend fun refreshCatalog() {
        runCatching {
            require(verifierAvailable()) { "official plugin signing keys are not configured" }
            val release = getSmall<PluginGitHubRelease>(
                "${catalogApiBase.trimEnd('/')}/releases/latest",
                MAX_RELEASE_BYTES,
            )
            require(!release.draft && !release.prerelease) { "latest plugin catalog is not stable" }
            val manifestAsset = release.assets.singleOrNull { it.name == PLUGIN_CATALOG_ASSET }
                ?: error("plugin release has no signed catalog")
            val signatureAsset = release.assets.singleOrNull { it.name == PLUGIN_CATALOG_SIGNATURE_ASSET }
                ?: error("plugin release has no catalog signature")
            val bytes = getBytes(manifestAsset.browserDownloadUrl, PluginSignatureVerifier.MAX_CATALOG_BYTES)
            val signature = getBytes(signatureAsset.browserDownloadUrl, PluginSignatureVerifier.MAX_SIGNATURE_BYTES)
            val verified = verifier.verifyCatalog(bytes, signature)
            mutation.withLock {
                catalog = verified
                stageAvailableUpdates()
                publishState()
            }
        }.onFailure { error ->
            mutation.withLock {
                publishState(catalogError = safeMessage(error))
            }
        }
    }

    override suspend fun install(pluginId: String) {
        val entry = catalog.plugins.firstOrNull { it.manifest.id == pluginId }
            ?: throw IllegalArgumentException("plugin is not in the official catalog")
        val installed = downloadAndExtract(entry, catalog.keyId)
        mutation.withLock {
            document = document.copy(
                installed = document.installed + (pluginId to StoredPlugin(
                    version = installed.manifest.version,
                    keyId = catalog.keyId,
                )),
            )
            statuses[pluginId] = PluginRuntimeStatus.PermissionRequired to "Review permissions to finish installing."
            saveAndPublish()
        }
    }

    override suspend fun installLocal(path: String) {
        val bytes = withContext(Dispatchers.IO) {
            require(document.developerMode) { "developer mode is required for local plugins" }
            val file = Path.of(path).toAbsolutePath().normalize()
            require(Files.isRegularFile(file)) { "local plugin file does not exist" }
            require(Files.size(file) <= PluginSignatureVerifier.MAX_PACKAGE_BYTES) { "plugin package is too large" }
            Files.readAllBytes(file)
        }
        val extracted = PluginArchive.extract(bytes, packages)
        checkCoveCompatibility(extracted.manifest.minimumCoveVersion)
        mutation.withLock {
            document = document.copy(
                installed = document.installed + (extracted.manifest.id to StoredPlugin(
                    version = extracted.manifest.version,
                    unsigned = true,
                )),
            )
            statuses[extracted.manifest.id] = PluginRuntimeStatus.PermissionRequired to
                "Unsigned developer plugin. Review every permission before enabling it."
            saveAndPublish()
        }
    }

    override suspend fun approve(pluginId: String, capabilities: Set<PluginCapability>) {
        mutation.withLock {
            val stored = requireStored(pluginId)
            val activeManifest = readManifest(pluginId, stored.version)
            val requestedCapabilities = stored.stagedVersion
                ?.let { readManifest(pluginId, it).capabilities }
                ?: activeManifest.capabilities
            require(capabilities.all { it in requestedCapabilities }) { "cannot grant undeclared capabilities" }
            document = document.copy(installed = document.installed + (
                pluginId to stored.copy(approvedCapabilities = capabilities)
            ))
            if (!capabilities.containsAll(activeManifest.capabilities)) {
                stop(pluginId, clearPresence = true)
            }
            if (!capabilities.containsAll(requestedCapabilities)) {
                statuses[pluginId] = PluginRuntimeStatus.PermissionRequired to
                    "Grant every required permission before enabling this plugin."
            } else if (stored.stagedVersion != null && runtime.containsKey(pluginId)) {
                statuses[pluginId] = PluginRuntimeStatus.UpdateStaged to
                    "Update ${stored.stagedVersion} will activate when the plugin stops."
            } else {
                activateStagedIfStopped(pluginId)
                statuses[pluginId] = PluginRuntimeStatus.Disabled to
                    "Permissions saved. Enable it for this profile when ready."
            }
            saveAndPublish()
        }
        reconcile()
    }

    override suspend fun uninstall(pluginId: String) {
        mutation.withLock {
            stop(pluginId, clearPresence = true)
            document = document.copy(installed = document.installed - pluginId)
            statuses.remove(pluginId)
            logs.remove(pluginId)
            withContext(Dispatchers.IO) { deleteTree(packages.resolve(pluginId)) }
            saveAndPublish()
        }
    }

    override suspend fun setEnabled(pluginId: String, enabled: Boolean) {
        mutation.withLock {
            val stored = requireStored(pluginId)
            val manifest = readManifest(pluginId, stored.version)
            require(!enabled || stored.approvedCapabilities.containsAll(manifest.capabilities)) {
                "approve this plugin's permissions before enabling it"
            }
            val profile = activeProfileIds.value
            if (enabled && profile !in stored.enabledProfiles) {
                crashBlocked.remove(pluginId)
                crashHistory.remove(pluginId)
            }
            val enabledProfiles = if (enabled) stored.enabledProfiles + profile else stored.enabledProfiles - profile
            document = document.copy(installed = document.installed + (
                pluginId to stored.copy(enabledProfiles = enabledProfiles)
            ))
            if (!enabled) stop(pluginId, clearPresence = true)
            activateStagedIfStopped(pluginId)
            saveAndPublish()
        }
        reconcile()
    }

    override suspend fun updateSetting(pluginId: String, key: String, value: JsonElement) {
        val processAndSettings = mutation.withLock {
            val stored = requireStored(pluginId)
            val manifest = readManifest(pluginId, stored.version)
            val definition = manifest.settings.singleOrNull { it.key == key }
                ?: throw IllegalArgumentException("unknown plugin setting")
            require(definition.type != PluginSettingType.Action) { "actions do not hold values" }
            validatePluginSetting(definition, value)
            val profile = activeProfileIds.value
            val current = stored.profileSettings[profile].orEmpty() + (key to value)
            document = document.copy(installed = document.installed + (
                pluginId to stored.copy(profileSettings = stored.profileSettings + (profile to current))
            ))
            saveAndPublish()
            runtime[pluginId]?.process to JsonObject(current)
        }
        processAndSettings.first?.let { process ->
            runCatching {
                process.invoke("settingsChanged", processAndSettings.second, EVENT_TIMEOUT_MILLIS)
            }.onFailure { runtimeFailed(pluginId, process, it) }
        }
    }

    override suspend fun invokeAction(pluginId: String, key: String) {
        val record = runtime[pluginId] ?: throw IllegalStateException("plugin is not running")
        val manifest = record.manifest
        require(manifest.settings.any { it.key == key && it.type == PluginSettingType.Action }) {
            "unknown plugin action"
        }
        runCatching {
            record.process.invoke(
                "onAction",
                JsonObject(mapOf("key" to JsonPrimitive(key))),
                EVENT_TIMEOUT_MILLIS,
            )
        }.onFailure { runtimeFailed(pluginId, record.process, it) }.getOrThrow()
    }

    override suspend fun retry(pluginId: String) {
        mutation.withLock {
            stop(pluginId, clearPresence = true)
            crashBlocked.remove(pluginId)
            crashHistory.remove(pluginId)
            statuses[pluginId] = PluginRuntimeStatus.Starting to "Retrying…"
            publishState()
        }
        reconcile()
    }

    override suspend fun setDeveloperMode(enabled: Boolean) {
        mutation.withLock {
            document = document.copy(developerMode = enabled)
            saveAndPublish()
        }
    }

    override fun publishPlayback(activity: PluginPlaybackActivity) {
        if (activity == lastPlayback) return
        lastPlayback = activity
        val payload = CoveJson.encodeToJsonElement(PluginPlaybackActivity.serializer(), activity)
        runtime.values.forEach { record ->
            if (PluginCapability.PlaybackObserve in record.manifest.capabilities) {
                scope.launch {
                    runCatching {
                        record.process.invoke("onPlaybackChanged", payload, EVENT_TIMEOUT_MILLIS)
                    }.onFailure { runtimeFailed(record.manifest.id, record.process, it) }
                }
            }
        }
    }

    override suspend fun streams(request: PluginMediaRequest): List<PluginStreamResult> {
        val payload = CoveJson.encodeToJsonElement(PluginMediaRequest.serializer(), request)
        return runtime.values.filter {
            PluginCapability.MediaStreams in it.manifest.capabilities
        }.flatMap { record ->
            runCatching {
                CoveJson.decodeFromJsonElement(
                    ListSerializer(PluginStreamResult.serializer()),
                    record.process.invoke("provideStreams", payload, PROVIDER_TIMEOUT_MILLIS),
                ).asSequence()
                    .take(MAX_PROVIDER_RESULTS)
                    .mapNotNull(::sanitizePluginStream)
                    .map { it.copy(pluginId = record.manifest.id, pluginName = record.manifest.name) }
                    .toList()
            }.onFailure { runtimeFailed(record.manifest.id, record.process, it) }.getOrDefault(emptyList())
        }
    }

    override suspend fun subtitles(request: PluginMediaRequest): List<SubtitleSource> =
        invokeProviders(PluginCapability.MediaSubtitles, "provideSubtitles", request) { payload ->
            CoveJson.decodeFromJsonElement(ListSerializer(SubtitleSource.serializer()), payload)
        }.flatten().asSequence()
            .filter { it.url.isNotBlank() && it.url.length <= MAX_URL_CHARACTERS }
            .take(MAX_PROVIDER_RESULTS)
            .map { it.copy(id = it.id.take(500), lang = it.lang.take(32)) }
            .toList()

    override suspend fun timestamps(request: PluginMediaRequest): MediaTimestamps {
        val results = invokeProviders(PluginCapability.MediaTimestamps, "provideTimestamps", request) { payload ->
            CoveJson.decodeFromJsonElement(MediaTimestamps.serializer(), payload)
        }
        return sanitizePluginTimestamps(results.fold(MediaTimestamps.None, ::fillMissingTimestamps))
    }

    override suspend fun augmentMetadata(request: PluginMediaRequest): List<PluginMetadataAugment> =
        invokeProviders(PluginCapability.MetadataAugment, "augmentMetadata", request) { payload ->
            CoveJson.decodeFromJsonElement(PluginMetadataAugment.serializer(), payload)
        }.take(MAX_METADATA_RESULTS).map(::sanitizeMetadataAugment)

    private suspend fun <T> invokeProviders(
        capability: PluginCapability,
        method: String,
        request: PluginMediaRequest,
        decode: (JsonElement) -> T,
    ): List<T> {
        val payload = CoveJson.encodeToJsonElement(PluginMediaRequest.serializer(), request)
        return runtime.values.filter { capability in it.manifest.capabilities }.mapNotNull { record ->
            runCatching {
                decode(record.process.invoke(method, payload, PROVIDER_TIMEOUT_MILLIS))
            }.onFailure { runtimeFailed(record.manifest.id, record.process, it) }.getOrNull()
        }
    }

    private suspend fun reconcile() {
        lifecycle.withLock {
            if (closed) return
            val profile = activeProfileIds.value
            val desired = mutation.withLock {
                val before = document
                document.installed.keys.filter { !runtime.containsKey(it) }.forEach(::activateStagedIfStopped)
                if (document != before) store.write(document)
                document.installed.mapNotNull { (id, stored) ->
                    id.takeIf {
                        id !in crashBlocked &&
                            profile in stored.enabledProfiles && stored.approvedCapabilities.containsAll(
                                readManifest(id, stored.version).capabilities,
                            )
                    }
                }.toSet()
            }
            runtime.keys.filter { it !in desired }.forEach { stop(it, clearPresence = true) }
            desired.filter { !runtime.containsKey(it) }.forEach { start(it) }
            publishState()
        }
    }

    private suspend fun start(pluginId: String) {
        val stored = mutation.withLock { requireStored(pluginId) }
        val manifest = readManifest(pluginId, stored.version)
        val source = withContext(Dispatchers.IO) {
            Files.readString(packageDirectory(pluginId, stored.version).resolve(manifest.entrypoint))
        }
        statuses[pluginId] = PluginRuntimeStatus.Starting to "Starting…"
        publishState()
        val settings = resolvedSettings(manifest, stored, activeProfileIds.value)
        lateinit var process: PluginProcess
        process = PluginProcess(
            init = PluginWorkerInit(
                manifest = manifest,
                source = source,
                settings = settings,
                storage = stored.profileSettings[storageProfile(activeProfileIds.value)].orEmpty(),
                grantedCapabilities = stored.approvedCapabilities,
                allowLan = allowLan(),
            ),
            scope = scope,
            onBroker = { method, payload -> handleBroker(pluginId, manifest, method, payload) },
            onLog = { appendLog(pluginId, it) },
            onExit = { message -> scope.launch { workerExited(pluginId, process, message) } },
        )
        runCatching {
            process.awaitReady()
            runtime[pluginId] = RuntimeRecord(manifest, process)
            if (PluginCapability.PlaybackObserve in manifest.capabilities) {
                process.invoke(
                    "onPlaybackChanged",
                    CoveJson.encodeToJsonElement(PluginPlaybackActivity.serializer(), lastPlayback),
                    EVENT_TIMEOUT_MILLIS,
                )
            }
        }
            .onSuccess {
                statuses[pluginId] = PluginRuntimeStatus.Running to "Running"
            }
            .onFailure { error ->
                runtimeFailed(pluginId, process, error, allowUnregistered = true)
            }
        publishState()
    }

    private suspend fun workerExited(pluginId: String, process: PluginProcess, message: String?) {
        runtimeFailed(pluginId, process, IllegalStateException(message ?: "Plugin process stopped unexpectedly."))
    }

    private suspend fun handleBroker(
        pluginId: String,
        manifest: com.coveninja.cove.shared.data.PluginManifest,
        method: String,
        payload: JsonElement,
    ) {
        when (method) {
            "player.setPaused" -> _transportCommands.emit(
                PluginTransportCommand.SetPaused(payload.jsonObject["paused"]?.jsonPrimitive?.content.toBoolean()),
            )
            "player.seek" -> payload.seconds()?.let { _transportCommands.emit(PluginTransportCommand.SeekAbsolute(it)) }
            "player.seekRelative" -> payload.seconds()?.let {
                _transportCommands.emit(PluginTransportCommand.SeekRelative(it))
            }
            "player.stop" -> _transportCommands.emit(PluginTransportCommand.Stop)
            "discord.setActivity" -> {
                val appId = requireNotNull(manifest.discordApplicationId)
                val client = discord.getOrPut(pluginId) { DiscordIpcClient(appId) }
                runCatching { client.setActivity(payload) }
                    .onSuccess { statuses[pluginId] = PluginRuntimeStatus.Running to "Connected to Discord" }
                    .onFailure { statuses[pluginId] = PluginRuntimeStatus.Waiting to "Waiting for Discord" }
                publishState()
            }
            "discord.clear" -> discord.remove(pluginId)?.clear()
            "storage.set", "storage.delete" -> updateStorage(pluginId, method, payload)
        }
    }

    private suspend fun updateStorage(pluginId: String, method: String, payload: JsonElement) {
        val key = payload.jsonObject["key"]?.jsonPrimitive?.content.orEmpty()
        require(key.matches(Regex("[a-zA-Z][a-zA-Z0-9._-]{0,63}"))) { "invalid plugin storage key" }
        mutation.withLock {
            val stored = requireStored(pluginId)
            val profile = activeProfileIds.value
            val current = stored.profileSettings[storageProfile(profile)].orEmpty().toMutableMap()
            if (method == "storage.delete") current.remove(key)
            else current[key] = payload.jsonObject["value"] ?: JsonNull
            val encodedSize = CoveJson.encodeToString(JsonObject(current)).encodeToByteArray().size
            require(encodedSize <= MAX_PROFILE_STORAGE_BYTES) { "plugin profile storage exceeds 1 MiB" }
            document = document.copy(installed = document.installed + (
                pluginId to stored.copy(
                    profileSettings = stored.profileSettings + (storageProfile(profile) to current),
                )
            ))
            store.write(document)
        }
    }

    private fun stop(pluginId: String, clearPresence: Boolean) {
        runtime.remove(pluginId)?.process?.close()
        if (clearPresence) discord.remove(pluginId)?.clear()
        if (document.installed.containsKey(pluginId)) {
            statuses[pluginId] = PluginRuntimeStatus.Disabled to "Disabled for this profile"
        }
    }

    private fun stopAll(clearPresence: Boolean) {
        runtime.keys.toList().forEach { stop(it, clearPresence) }
    }

    override fun close() {
        closed = true
        stopAll(clearPresence = true)
    }

    private suspend fun stageAvailableUpdates() {
        for ((id, stored) in document.installed) {
            if (stored.unsigned) continue
            val entry = catalog.plugins.firstOrNull { it.manifest.id == id } ?: continue
            val available = parsePluginVersion(entry.manifest.version) ?: continue
            val current = parsePluginVersion(stored.version) ?: continue
            if (available <= current || stored.stagedVersion == entry.manifest.version) continue
            runCatching {
                downloadAndExtract(entry, catalog.keyId)
                val added = entry.manifest.capabilities - stored.approvedCapabilities
                document = document.copy(installed = document.installed + (id to stored.copy(
                    stagedVersion = entry.manifest.version,
                    stagedCapabilities = entry.manifest.capabilities,
                )))
                statuses[id] = if (added.isEmpty()) {
                    PluginRuntimeStatus.UpdateStaged to "Update ${entry.manifest.version} will activate when idle."
                } else {
                    PluginRuntimeStatus.PermissionRequired to "Update ${entry.manifest.version} requests new permissions."
                }
                if (!runtime.containsKey(id) && added.isEmpty()) activateStagedIfStopped(id)
                store.write(document)
            }.onFailure { appendLog(id, "update: ${safeMessage(it)}") }
        }
    }

    private fun activateStagedIfStopped(pluginId: String) {
        if (runtime.containsKey(pluginId)) return
        val stored = document.installed[pluginId] ?: return
        val staged = stored.stagedVersion ?: return
        if (!stored.approvedCapabilities.containsAll(stored.stagedCapabilities)) return
        document = document.copy(installed = document.installed + (pluginId to stored.copy(
            version = staged,
            stagedVersion = null,
            stagedCapabilities = emptySet(),
        )))
    }

    private suspend fun downloadAndExtract(entry: PluginCatalogEntry, keyId: String): ExtractedPlugin {
        checkCoveCompatibility(entry.manifest.minimumCoveVersion)
        val bytes = getBytes(entry.packageUrl, PluginSignatureVerifier.MAX_PACKAGE_BYTES.toInt())
        require(bytes.size.toLong() == entry.sizeBytes) { "plugin package size does not match the catalog" }
        require(sha256(bytes) == entry.sha256) { "plugin package checksum does not match the catalog" }
        val signature = getBytes(entry.signatureUrl, PluginSignatureVerifier.MAX_SIGNATURE_BYTES)
        verifier.verifyPackage(bytes, signature, keyId)
        val inspected = PluginArchive.inspect(bytes)
        require(inspected == entry.manifest) { "plugin package manifest does not match the signed catalog" }
        return withContext(Dispatchers.IO) { PluginArchive.extract(bytes, packages) }
    }

    private fun checkCoveCompatibility(minimum: String) {
        val current = parsePluginVersion(currentCoveVersion) ?: return
        val required = requireNotNull(parsePluginVersion(minimum))
        require(current >= required) { "plugin requires Cove $minimum or newer" }
    }

    private fun resolvedSettings(
        manifest: com.coveninja.cove.shared.data.PluginManifest,
        stored: StoredPlugin,
        profile: String,
    ): Map<String, JsonElement> = buildMap {
        manifest.settings.filter { it.type != PluginSettingType.Action && it.default !is JsonNull }
            .forEach { put(it.key, it.default) }
        putAll(stored.profileSettings[profile].orEmpty())
    }

    private fun readManifest(id: String, version: String): com.coveninja.cove.shared.data.PluginManifest =
        CoveJson.decodeFromString(Files.readString(packageDirectory(id, version).resolve("plugin.json")))

    private fun readManifest(id: String, it: StoredPlugin): com.coveninja.cove.shared.data.PluginManifest =
        readManifest(id, it.version)

    private fun packageDirectory(id: String, version: String): Path = packages.resolve(id).resolve(version)

    private fun requireStored(id: String): StoredPlugin = document.installed[id]
        ?: throw IllegalArgumentException("plugin is not installed")

    private fun publishState(catalogError: String? = null) {
        val profile = activeProfileIds.value
        val installed = document.installed.mapNotNull { (id, stored) ->
            runCatching {
                val manifest = readManifest(id, stored.version)
                val status = statuses[id] ?: when {
                    !stored.approvedCapabilities.containsAll(manifest.capabilities) ->
                        PluginRuntimeStatus.PermissionRequired to "Review permissions to continue."
                    profile !in stored.enabledProfiles ->
                        PluginRuntimeStatus.Disabled to "Disabled for this profile"
                    else -> PluginRuntimeStatus.Starting to "Starting…"
                }
                InstalledPlugin(
                    manifest = manifest,
                    enabled = profile in stored.enabledProfiles,
                    approvedCapabilities = stored.approvedCapabilities,
                    requestedCapabilities = stored.stagedVersion
                        ?.let { readManifest(id, it).capabilities }
                        ?: manifest.capabilities,
                    status = status.first,
                    statusMessage = status.second,
                    settings = resolvedSettings(manifest, stored, profile),
                    unsigned = stored.unsigned,
                    updateVersion = stored.stagedVersion,
                )
            }.onFailure { appendLog(id, "state: ${safeMessage(it)}") }.getOrNull()
        }.sortedBy { it.manifest.name.lowercase() }
        _state.value = PluginsState.Ready(
            catalog = catalog.plugins,
            installed = installed,
            developerMode = document.developerMode,
            catalogError = catalogError,
        )
    }

    private fun saveAndPublish() {
        store.write(document)
        publishState()
    }

    private fun appendLog(pluginId: String, message: String) {
        val queue = logs.computeIfAbsent(pluginId) { ArrayDeque() }
        synchronized(queue) {
            queue.addLast(message.lineSequence().first().take(2_000))
            while (queue.size > 100) queue.removeFirst()
        }
    }

    private fun runtimeFailed(
        pluginId: String,
        process: PluginProcess,
        error: Throwable,
        allowUnregistered: Boolean = false,
    ) {
        val active = runtime[pluginId]
        if (active == null && !allowUnregistered) return
        if (active != null && active.process !== process) return
        runtime.remove(pluginId)
        process.close()
        discord.remove(pluginId)?.clear()
        val message = safeMessage(error)
        appendLog(pluginId, message)
        val now = System.currentTimeMillis()
        val history = crashHistory.computeIfAbsent(pluginId) { ArrayDeque() }
        val count = synchronized(history) {
            while (history.isNotEmpty() && now - history.first() > CRASH_WINDOW_MILLIS) {
                history.removeFirst()
            }
            history.addLast(now)
            history.size
        }
        if (count >= MAX_CRASHES_IN_WINDOW) {
            crashBlocked.add(pluginId)
            statuses[pluginId] = PluginRuntimeStatus.Failed to
                "$message Plugin stopped after repeated failures; retry it manually."
        } else {
            val delayMillis = if (count == 1) 1_000L else 4_000L
            statuses[pluginId] = PluginRuntimeStatus.Waiting to
                "$message Restarting in ${delayMillis / 1_000}s…"
            scope.launch {
                delay(delayMillis)
                reconcile()
            }
        }
        publishState()
    }

    private fun verifierAvailable(): Boolean = signingKeysAvailable

    private fun activateSafeStagedUpdates(input: PluginStoreDocument): PluginStoreDocument = input.copy(
        installed = input.installed.mapValues { (_, stored) ->
            val staged = stored.stagedVersion
            if (staged != null && stored.approvedCapabilities.containsAll(stored.stagedCapabilities)) {
                stored.copy(version = staged, stagedVersion = null, stagedCapabilities = emptySet())
            } else stored
        },
    )

    private suspend inline fun <reified T> getSmall(url: String, maxBytes: Int): T =
        CoveJson.decodeFromString(getBytes(url, maxBytes).decodeToString())

    private suspend fun getBytes(url: String, maxBytes: Int): ByteArray {
        require(url.startsWith("https://") || url.startsWith("http://127.0.0.1")) {
            "plugin download must use HTTPS"
        }
        val response = httpClient.get(url)
        require(response.status.isSuccess()) { "plugin download returned HTTP ${response.status.value}" }
        response.headers["Content-Length"]?.toLongOrNull()?.let {
            require(it <= maxBytes) { "plugin download is too large" }
        }
        val output = java.io.ByteArrayOutputStream()
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count == -1) break
            if (count == 0) continue
            require(output.size() + count <= maxBytes) { "plugin download is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private data class RuntimeRecord(
        val manifest: com.coveninja.cove.shared.data.PluginManifest,
        val process: PluginProcess,
    )

    private companion object {
        const val MAX_RELEASE_BYTES = 256 * 1024
        const val MAX_PROFILE_STORAGE_BYTES = 1024 * 1024
        const val MAX_PROVIDER_RESULTS = 100
        const val MAX_METADATA_RESULTS = 32
        const val MAX_URL_CHARACTERS = 8_192
        const val CATALOG_LAUNCH_DELAY_MILLIS = 15_000L
        const val CATALOG_REFRESH_MILLIS = 24 * 60 * 60 * 1_000L
        const val PROVIDER_TIMEOUT_MILLIS = 30_000L
        const val EVENT_TIMEOUT_MILLIS = 5_000L
        const val CRASH_WINDOW_MILLIS = 5 * 60 * 1_000L
        const val MAX_CRASHES_IN_WINDOW = 3
    }
}

@Serializable
private data class PluginGitHubRelease(
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<PluginGitHubAsset> = emptyList(),
)

@Serializable
private data class PluginGitHubAsset(
    val name: String,
    @kotlinx.serialization.SerialName("browser_download_url") val browserDownloadUrl: String,
)

private fun JsonElement.seconds(): Double? = jsonObject["seconds"]?.jsonPrimitive?.doubleOrNull

private fun storageProfile(profile: String): String = "__storage__:$profile"

private fun fillMissingTimestamps(first: MediaTimestamps, second: MediaTimestamps) = MediaTimestamps(
    intro = first.intro.ifEmpty { second.intro },
    recap = first.recap.ifEmpty { second.recap },
    credits = first.credits.ifEmpty { second.credits },
    preview = first.preview.ifEmpty { second.preview },
)

private fun sanitizePluginStream(result: PluginStreamResult): PluginStreamResult? {
    val url = result.url?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8_192 }
    val infoHash = result.infoHash?.trim()?.lowercase()?.takeIf {
        it.matches(Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})"))
    }
    if (url == null && infoHash == null) return null
    val headers = result.headers.asSequence()
        .filter { (name, value) ->
            name.lowercase() !in FORBIDDEN_STREAM_HEADERS &&
                name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}")) &&
                value.length <= 8_192
        }
        .take(32)
        .associate { it.key to it.value }
    return result.copy(
        name = result.name.take(500),
        title = result.title.take(1_000),
        url = url,
        infoHash = infoHash,
        fileIndex = result.fileIndex?.takeIf { it >= 0 },
        headers = headers,
        sizeBytes = result.sizeBytes.coerceAtLeast(0),
        seeders = result.seeders.coerceAtLeast(0),
    )
}

private fun sanitizePluginTimestamps(value: MediaTimestamps): MediaTimestamps {
    fun clean(segments: List<com.coveninja.cove.shared.model.TimestampSegment>) = segments.asSequence()
        .take(32)
        .filter { segment ->
            val start = segment.startMs
            val end = segment.endMs
            start != null && end != null && start >= 0 && end > start && end <= MAX_MEDIA_MILLIS
        }
        .toList()
    return MediaTimestamps(
        intro = clean(value.intro),
        recap = clean(value.recap),
        credits = clean(value.credits),
        preview = clean(value.preview),
    )
}

private fun sanitizeMetadataAugment(value: PluginMetadataAugment): PluginMetadataAugment = value.copy(
    overview = value.overview?.take(50_000),
    tagline = value.tagline?.take(1_000),
    posterUrl = value.posterUrl?.takeIf(::isSafePublicMetadataUrl),
    backdropUrl = value.backdropUrl?.takeIf(::isSafePublicMetadataUrl),
    links = value.links.asSequence()
        .take(32)
        .filter { (name, url) -> name.length <= 100 && isSafePublicMetadataUrl(url) }
        .associate { it.key to it.value },
)

private fun isSafePublicMetadataUrl(value: String): Boolean = runCatching {
    require(value.length <= 8_192)
    val uri = java.net.URI(value)
    require(uri.scheme == "https" && uri.rawUserInfo == null)
    com.coveninja.cove.backend.addons.validateResolvedPublicUrl(value)
}.isSuccess

private const val MAX_MEDIA_MILLIS = 7L * 24 * 60 * 60 * 1_000
private val FORBIDDEN_STREAM_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding")

private fun safeMessage(error: Throwable): String =
    (error.message ?: error::class.simpleName ?: "plugin operation failed").lineSequence().first().take(500)
