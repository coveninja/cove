package com.coveninja.cove.backend.updater

import com.coveninja.cove.backend.platform.DesktopBackendEnvironment
import com.coveninja.cove.shared.data.AppUpdateRelease
import com.coveninja.cove.shared.data.ManagedUpdateRepository
import com.coveninja.cove.shared.data.UnavailableUpdateRepository
import com.coveninja.cove.shared.data.UpdateApplyResult
import com.coveninja.cove.shared.data.UpdateRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

internal fun createDesktopUpdateRepository(
    dataDirectory: Path,
    currentVersion: String,
    scope: CoroutineScope,
    environment: Map<String, String> = System.getenv(),
    osName: String = System.getProperty("os.name").orEmpty(),
): UpdateRepository {
    if (osName.startsWith("Linux", ignoreCase = true)) {
        return ManagedUpdateRepository(
            currentVersion,
            "Linux updates stay with the installation channel: update cove-bin through pacman; " +
                "replace standalone Flatpak or tarball builds manually.",
        )
    }
    if (!osName.startsWith("Windows", ignoreCase = true)) return UnavailableUpdateRepository

    val keys = parseUpdatePublicKeys(DesktopBackendEnvironment.updatePublicKeys(environment))
    if (parseStableVersion(currentVersion) == null || keys.isEmpty()) return UnavailableUpdateRepository
    val platform = WindowsUpdatePlatform(dataDirectory)
    val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10 * 60 * 1_000L
            connectTimeoutMillis = 30_000L
            socketTimeoutMillis = 60_000L
        }
    }
    return SignedUpdateService(
        currentVersion = currentVersion,
        platform = platform,
        client = client,
        scope = scope,
        apiBase = DesktopBackendEnvironment.updateApiBase(environment),
        publicKeys = keys,
    )
}

private class WindowsUpdatePlatform(dataDirectory: Path) : UpdatePlatform {
    private val preferencesFile = dataDirectory.resolve("updates/update.properties")
    override val stagingDirectory: Path = dataDirectory.resolve("updates/staged")
    private val executable = ProcessHandle.current().info().command().orElse("")
        .takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.toAbsolutePath()
        ?.normalize()
        ?: throw IllegalStateException("Cove could not locate its Windows launcher")
    private val installDirectory = executable.parent
    private val portable = Files.isRegularFile(installDirectory.resolve(PORTABLE_MARKER)) ||
        (!Files.isRegularFile(installDirectory.resolve(INSTALLED_MARKER)) &&
            !Files.isRegularFile(installDirectory.resolve("uninstall.exe")))

    override val target: String = if (portable) "windows-portable" else "windows-installer"

    override fun readPreferences(): UpdatePreferences {
        if (!Files.isRegularFile(preferencesFile)) return UpdatePreferences()
        return runCatching {
            Properties().apply { Files.newInputStream(preferencesFile).use(::load) }.let { values ->
                UpdatePreferences(
                    automaticUpdatesEnabled = values.getProperty(AUTOMATIC, "true").toBoolean(),
                    lastCheckEpochMillis = values.getProperty(LAST_CHECK, "0").toLongOrNull() ?: 0L,
                )
            }
        }.getOrDefault(UpdatePreferences())
    }

    override fun writePreferences(preferences: UpdatePreferences) {
        val values = Properties().apply {
            setProperty(AUTOMATIC, preferences.automaticUpdatesEnabled.toString())
            setProperty(LAST_CHECK, preferences.lastCheckEpochMillis.toString())
        }
        Files.createDirectories(preferencesFile.parent)
        val temporary = preferencesFile.resolveSibling("${preferencesFile.fileName}.tmp-${UUID.randomUUID()}")
        Files.newOutputStream(temporary).use { values.store(it, "Cove device-local updater") }
        try {
            Files.move(temporary, preferencesFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, preferencesFile, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun install(payload: Path, release: AppUpdateRelease): UpdateApplyResult {
        require(Files.isRegularFile(payload)) { "The verified Windows updater is missing." }
        windowsUpdaterProcess(
            portable = portable,
            payload = payload.toAbsolutePath(),
            installDirectory = installDirectory.toAbsolutePath(),
            processId = ProcessHandle.current().pid(),
            version = release.version,
        )
            .directory(stagingDirectory.toFile())
            .start()
        return UpdateApplyResult.ExitRequired
    }

    private companion object {
        const val AUTOMATIC = "automatic-updates-enabled"
        const val LAST_CHECK = "last-check-epoch-millis"
        const val INSTALLED_MARKER = ".cove-installed"
        const val PORTABLE_MARKER = ".cove-portable"
    }
}

/**
 * Installed NSIS helpers request administrator rights. CreateProcess (and thus
 * ProcessBuilder) does not display UAC for such an executable, so delegate only
 * that launch to PowerShell's ShellExecute-backed RunAs verb. Values travel in
 * the child environment and are never interpolated into the PowerShell source.
 */
internal fun windowsUpdaterProcess(
    portable: Boolean,
    payload: Path,
    installDirectory: Path,
    processId: Long,
    version: String,
): ProcessBuilder {
    val arguments = listOf(
        "/UPDATE=1",
        "/TARGET=$installDirectory",
        "/PID=$processId",
        "/VERSION=$version",
    )
    if (portable) return ProcessBuilder(listOf(payload.toString()) + arguments)

    val builder = ProcessBuilder(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        $$"""$updateArgs = '/UPDATE=1 /TARGET="{0}" /PID={1} /VERSION={2}' -f $env:COVE_UPDATE_TARGET, $env:COVE_UPDATE_PID, $env:COVE_UPDATE_VERSION; try { Start-Process -FilePath $env:COVE_UPDATE_HELPER -ArgumentList $updateArgs -Verb RunAs -Wait -ErrorAction Stop } finally { if (Test-Path $env:COVE_UPDATE_APP) { Start-Process -FilePath $env:COVE_UPDATE_APP } }""",
    )
    builder.environment().apply {
        put("COVE_UPDATE_HELPER", payload.toString())
        put("COVE_UPDATE_TARGET", installDirectory.toString())
        put("COVE_UPDATE_APP", installDirectory.resolve("Cove.exe").toString())
        put("COVE_UPDATE_PID", processId.toString())
        put("COVE_UPDATE_VERSION", version)
    }
    return builder
}
