package com.coveninja.cove.backend.updater

import com.coveninja.cove.shared.data.AppUpdateRelease
import com.coveninja.cove.shared.data.AppUpdateState
import com.coveninja.cove.shared.data.UpdateApplyResult
import com.coveninja.cove.shared.data.UpdateRepository
import com.coveninja.cove.shared.model.UPDATE_MANIFEST_ASSET_NAME
import com.coveninja.cove.shared.model.UPDATE_MANIFEST_SIGNATURE_NAME
import com.coveninja.cove.shared.model.UpdateManifest
import com.coveninja.cove.shared.model.UpdateManifestAsset
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Signed, fail-closed update engine shared by the desktop and Android runtimes. */
internal class SignedUpdateService(
    override val currentVersion: String,
    private val platform: UpdatePlatform,
    private val client: HttpClient,
    private val scope: CoroutineScope,
    apiBase: String,
    publicKeys: Map<String, String>,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : UpdateRepository, AutoCloseable {
    private val apiBase = apiBase.trimEnd('/')
    private val strictGitHubTransport = runCatching { URI(this.apiBase).host == "api.github.com" }
        .getOrDefault(false)
    private val verifier = SignedManifestVerifier(publicKeys)
    private val operation = Mutex()
    private val preferences = platform.readPreferences()
    private val _automatic = MutableStateFlow(preferences.automaticUpdatesEnabled)
    override val automaticUpdatesEnabled = _automatic.asStateFlow()
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    override val state = _state.asStateFlow()
    override val available: Boolean = parseStableVersion(currentVersion) != null && publicKeys.isNotEmpty()

    private var scheduleJob: Job? = null
    private var pendingCandidate: VerifiedCandidate? = null
    private var stagedCandidate: VerifiedCandidate? = null
    private var lastCheckAttemptEpochMillis = preferences.lastCheckEpochMillis
    private var closed = false

    override fun start() {
        if (!available || scheduleJob != null || closed) return
        scheduleJob = scope.launch {
            restoreStagedUpdate()
            launch {
                platform.installEvents.collect { event ->
                    when (event) {
                        PlatformInstallEvent.Success -> clearStaged(AppUpdateState.Idle)
                        is PlatformInstallEvent.Failure -> _state.value = AppUpdateState.Failed(event.message)
                    }
                }
            }
            delay(LAUNCH_CHECK_DELAY_MILLIS)
            while (!closed) {
                val saved = platform.readPreferences()
                if (_automatic.value) {
                    if (_state.value is AppUpdateState.Ready) {
                        delay(CHECK_INTERVAL_MILLIS)
                        continue
                    }
                    val lastAttempt = maxOf(saved.lastCheckEpochMillis, lastCheckAttemptEpochMillis)
                    val remaining = CHECK_INTERVAL_MILLIS -
                        (nowEpochMillis() - lastAttempt).coerceAtLeast(0L)
                    if (remaining > 0L) {
                        delay(remaining)
                        // A manual check or re-enable may have updated the timestamp
                        // while this delay was suspended. Recompute before any request.
                        continue
                    }
                    if (_automatic.value && _state.value !is AppUpdateState.Ready) {
                        check(manual = false)
                    }
                } else {
                    delay(CHECK_INTERVAL_MILLIS)
                }
            }
        }
    }

    override suspend fun setAutomaticUpdatesEnabled(enabled: Boolean) {
        var checkAfterUnlock = false
        operation.withLock {
            _automatic.value = enabled
            val old = platform.readPreferences()
            platform.writePreferences(old.copy(automaticUpdatesEnabled = enabled))
            if (!enabled) {
                clearStaged(AppUpdateState.Idle)
            } else if (available && scheduleJob != null && _state.value.letsCheckStart()) {
                checkAfterUnlock = true
            }
        }
        if (checkAfterUnlock) check(manual = false)
    }

    override suspend fun checkNow() {
        if (!available) return
        check(manual = true)
    }

    private suspend fun check(manual: Boolean) = operation.withLock {
        if (closed) return@withLock
        _state.value = AppUpdateState.Checking(manual)
        runCatching {
            val checkedAt = nowEpochMillis()
            lastCheckAttemptEpochMillis = checkedAt
            val old = platform.readPreferences()
            platform.writePreferences(old.copy(lastCheckEpochMillis = checkedAt))
            val candidate = fetchLatestCandidate()
            if (candidate == null) {
                pendingCandidate = null
                _state.value = AppUpdateState.UpToDate(checkedAt)
            } else if (platform.isMeteredConnection()) {
                pendingCandidate = candidate
                _state.value = AppUpdateState.MeteredApprovalRequired(candidate.release)
            } else {
                download(candidate)
            }
        }.onFailure { error ->
            _state.value = AppUpdateState.Failed(error.safeUpdateMessage())
        }
    }

    override suspend fun approveMeteredDownload() = operation.withLock {
        val candidate = pendingCandidate ?: return@withLock
        runCatching { download(candidate) }.onFailure { error ->
            _state.value = AppUpdateState.Failed(error.safeUpdateMessage())
        }
    }

    override suspend fun applyReadyUpdate(): UpdateApplyResult = operation.withLock {
        val candidate = stagedCandidate ?: return@withLock UpdateApplyResult.NothingToApply
        val payload = platform.stagingDirectory.resolve(PAYLOAD_FILE)
        if (!Files.isRegularFile(payload)) {
            clearStaged(AppUpdateState.Failed("The staged update is missing. Check for updates again."))
            return@withLock UpdateApplyResult.NothingToApply
        }
        _state.value = AppUpdateState.Installing(candidate.release)
        val stagedPayloadIntact = runCatching {
            Files.size(payload) == candidate.asset.sizeBytes && sha256(payload) == candidate.asset.sha256
        }.getOrDefault(false)
        if (!stagedPayloadIntact) {
            clearStaged(AppUpdateState.Failed("The staged update changed after verification and was removed."))
            return@withLock UpdateApplyResult.NothingToApply
        }
        val result = runCatching { platform.install(payload, candidate.release) }
            .getOrElse { error ->
                _state.value = AppUpdateState.Failed(error.safeUpdateMessage())
                return@withLock UpdateApplyResult.NothingToApply
            }
        if (result == UpdateApplyResult.PermissionRequired) {
            _state.value = AppUpdateState.PermissionRequired(candidate.release)
        }
        result
    }

    override suspend fun resumePendingAction(): UpdateApplyResult {
        if (_state.value !is AppUpdateState.PermissionRequired || !platform.canResumePermissionRequest()) {
            return UpdateApplyResult.NothingToApply
        }
        return applyReadyUpdate()
    }

    override fun dismissReadyForSession() {
        when (val current = _state.value) {
            is AppUpdateState.Ready -> _state.value = current.copy(promptVisible = false)
            is AppUpdateState.MeteredApprovalRequired -> {
                pendingCandidate = null
                _state.value = AppUpdateState.Idle
            }
            is AppUpdateState.PermissionRequired -> {
                val candidate = stagedCandidate
                _state.value = candidate?.let { AppUpdateState.Ready(it.release, promptVisible = false) }
                    ?: AppUpdateState.Idle
            }
            else -> Unit
        }
    }

    private suspend fun fetchLatestCandidate(): VerifiedCandidate? {
        val release: GitHubRelease = getSmall("$apiBase/releases/latest", MAX_RELEASE_BYTES)
        require(!release.draft && !release.prerelease) { "the latest release is not stable" }
        val remote = parseStableVersion(release.tagName)
            ?: throw IllegalStateException("the latest release has an invalid version")
        val current = parseStableVersion(currentVersion) ?: return null
        if (remote <= current) return null
        require(release.assets.map { it.name }.distinct().size == release.assets.size) {
            "the release contains duplicate asset names"
        }

        val manifestAsset = release.assets.singleOrNull { it.name == UPDATE_MANIFEST_ASSET_NAME }
            ?: throw IllegalStateException("the release has no signed update manifest")
        val signatureAsset = release.assets.singleOrNull { it.name == UPDATE_MANIFEST_SIGNATURE_NAME }
            ?: throw IllegalStateException("the release has no update manifest signature")
        validateAssetUrl(manifestAsset)
        validateAssetUrl(signatureAsset)
        val manifestBytes = getSmallBytes(manifestAsset.browserDownloadUrl, SignedManifestVerifier.MAX_MANIFEST_BYTES)
        val signatureBytes = getSmallBytes(signatureAsset.browserDownloadUrl, SignedManifestVerifier.MAX_SIGNATURE_BYTES)
        val manifest = verifier.verify(manifestBytes, signatureBytes)
        require(release.tagName.removePrefix("v") == manifest.version.removePrefix("v")) {
            "the signed manifest does not match the release tag"
        }

        val selected = manifest.assets.singleOrNull { it.target == platform.target }
            ?: throw IllegalStateException("this release has no update for ${platform.target}")
        val releaseAsset = release.assets.singleOrNull { it.name == selected.name }
            ?: throw IllegalStateException("the signed update asset is missing from the release")
        validateAssetUrl(releaseAsset)
        return VerifiedCandidate(
            manifest = manifest,
            manifestBytes = manifestBytes,
            signatureBytes = signatureBytes,
            asset = selected,
            assetUrl = releaseAsset.browserDownloadUrl,
        )
    }

    private suspend fun download(candidate: VerifiedCandidate) {
        pendingCandidate = null
        Files.createDirectories(platform.stagingDirectory)
        val temporary = platform.stagingDirectory.resolve("$PAYLOAD_FILE.part-${UUID.randomUUID()}")
        val payload = platform.stagingDirectory.resolve(PAYLOAD_FILE)
        _state.value = AppUpdateState.Downloading(candidate.release, 0L, candidate.asset.sizeBytes)
        try {
            val response = client.get(candidate.assetUrl)
            require(response.status.isSuccess()) { "update download returned HTTP ${response.status.value}" }
            response.headers["Content-Length"]?.toLongOrNull()?.let { length ->
                require(length == candidate.asset.sizeBytes) { "update download size does not match the manifest" }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            BufferedOutputStream(Files.newOutputStream(temporary)).use { output ->
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    val count = channel.readAvailable(buffer)
                    if (count == -1) break
                    if (count == 0) continue
                    downloaded += count
                    require(downloaded <= candidate.asset.sizeBytes) { "update download exceeds signed size" }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    _state.value = AppUpdateState.Downloading(
                        candidate.release,
                        downloaded,
                        candidate.asset.sizeBytes,
                    )
                }
            }
            require(downloaded == candidate.asset.sizeBytes) { "update download is incomplete" }
            val actual = digest.digest().toHex()
            if (actual != candidate.asset.sha256) throw SecurityException("update checksum does not match")
            atomicMove(temporary, payload)
            Files.write(platform.stagingDirectory.resolve(MANIFEST_FILE), candidate.manifestBytes)
            Files.write(platform.stagingDirectory.resolve(SIGNATURE_FILE), candidate.signatureBytes)
            stagedCandidate = candidate
            _state.value = AppUpdateState.Ready(candidate.release)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private suspend fun restoreStagedUpdate() = operation.withLock {
        val directory = platform.stagingDirectory
        val manifestFile = directory.resolve(MANIFEST_FILE)
        val signatureFile = directory.resolve(SIGNATURE_FILE)
        val payload = directory.resolve(PAYLOAD_FILE)
        val hasManifest = Files.isRegularFile(manifestFile)
        val hasSignature = Files.isRegularFile(signatureFile)
        val hasPayload = Files.isRegularFile(payload)
        val hasPartial = hasPartialPayload(directory)
        if (!hasManifest && !hasSignature && !hasPayload && !hasPartial) {
            return@withLock
        }
        if (!hasManifest || !hasSignature || !hasPayload) {
            resetCheckAfterInvalidStage()
            clearStaged(AppUpdateState.Failed("An incomplete staged update was removed and will be downloaded again."))
            return@withLock
        }
        runCatching {
            require(Files.size(manifestFile) <= SignedManifestVerifier.MAX_MANIFEST_BYTES) {
                "staged update manifest is too large"
            }
            require(Files.size(signatureFile) <= SignedManifestVerifier.MAX_SIGNATURE_BYTES) {
                "staged update signature is too large"
            }
            val manifestBytes = Files.readAllBytes(manifestFile)
            val signatureBytes = Files.readAllBytes(signatureFile)
            val manifest = verifier.verify(manifestBytes, signatureBytes)
            val asset = manifest.assets.single { it.target == platform.target }
            require(parseStableVersion(manifest.version)!! > parseStableVersion(currentVersion)!!) {
                "staged update is not newer"
            }
            require(Files.size(payload) == asset.sizeBytes) { "staged update has the wrong size" }
            require(sha256(payload) == asset.sha256) { "staged update checksum does not match" }
            VerifiedCandidate(manifest, manifestBytes, signatureBytes, asset, "")
        }.onSuccess { candidate ->
            stagedCandidate = candidate
            _state.value = AppUpdateState.Ready(candidate.release)
        }.onFailure {
            resetCheckAfterInvalidStage()
            clearStaged(AppUpdateState.Failed("A previously staged update was invalid and was removed."))
        }
    }

    private fun resetCheckAfterInvalidStage() {
        lastCheckAttemptEpochMillis = 0L
        runCatching {
            val saved = platform.readPreferences()
            platform.writePreferences(saved.copy(lastCheckEpochMillis = 0L))
        }
    }

    private fun clearStaged(nextState: AppUpdateState) {
        pendingCandidate = null
        stagedCandidate = null
        val directory = platform.stagingDirectory
        runCatching {
            Files.deleteIfExists(directory.resolve(PAYLOAD_FILE))
            Files.deleteIfExists(directory.resolve(MANIFEST_FILE))
            Files.deleteIfExists(directory.resolve(SIGNATURE_FILE))
            Files.list(directory).use { files ->
                files.filter { it.fileName.toString().startsWith("$PAYLOAD_FILE.part-") }
                    .forEach(Files::deleteIfExists)
            }
        }
        _state.value = nextState
    }

    private fun hasPartialPayload(directory: Path): Boolean {
        if (!Files.isDirectory(directory)) return false
        return runCatching {
            Files.list(directory).use { files ->
                files.anyMatch { it.fileName.toString().startsWith("$PAYLOAD_FILE.part-") }
            }
        }.getOrDefault(false)
    }

    private suspend inline fun <reified T> getSmall(url: String, limit: Int): T =
        CoveJson.decodeFromString(getSmallBytes(url, limit).decodeToString())

    private suspend fun getSmallBytes(url: String, limit: Int): ByteArray {
        val response = client.get(url)
        require(response.status == HttpStatusCode.OK) { "update service returned HTTP ${response.status.value}" }
        response.headers["Content-Length"]?.toLongOrNull()?.let { length ->
            require(length in 0..limit.toLong()) { "update response is too large" }
        }
        val output = ByteArrayOutputStream(minOf(limit, SMALL_RESPONSE_INITIAL_BYTES))
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(SMALL_RESPONSE_BUFFER_BYTES)
        var received = 0
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count == -1) break
            if (count == 0) continue
            received += count
            require(received <= limit) { "update response is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun validateAssetUrl(asset: GitHubAsset) {
        require(asset.name.isNotBlank()) { "release asset has no name" }
        val uri = URI(asset.browserDownloadUrl)
        if (strictGitHubTransport) {
            require(uri.scheme == "https" && uri.host == "github.com") { "release asset URL is not trusted" }
            require(uri.path.startsWith("/coveninja/cove/releases/download/")) { "release asset URL is not Cove" }
        }
    }

    override fun close() {
        closed = true
        scheduleJob?.cancel()
        client.close()
    }

    private data class VerifiedCandidate(
        val manifest: UpdateManifest,
        val manifestBytes: ByteArray,
        val signatureBytes: ByteArray,
        val asset: UpdateManifestAsset,
        val assetUrl: String,
    ) {
        val release = AppUpdateRelease(
            version = manifest.version,
            name = manifest.releaseName,
            publishedAt = manifest.publishedAt,
            downloadSizeBytes = asset.sizeBytes,
        )
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val name: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
    )

    companion object {
        const val DEFAULT_API_BASE = "https://api.github.com/repos/coveninja/cove"
        private const val MAX_RELEASE_BYTES = 512 * 1024
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val SMALL_RESPONSE_BUFFER_BYTES = 8 * 1024
        private const val SMALL_RESPONSE_INITIAL_BYTES = 8 * 1024
        private const val LAUNCH_CHECK_DELAY_MILLIS = 2_000L
        private const val CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MANIFEST_FILE = "manifest.json"
        private const val SIGNATURE_FILE = "manifest.sig"
        private const val PAYLOAD_FILE = "update.payload"
    }
}

private fun Throwable.safeUpdateMessage(): String = when (this) {
    is SecurityException -> message ?: "The update could not be verified."
    else -> message ?: "The update operation failed."
}

private fun AppUpdateState.letsCheckStart(): Boolean =
    this is AppUpdateState.Idle || this is AppUpdateState.UpToDate || this is AppUpdateState.Failed

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}

private fun atomicMove(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
