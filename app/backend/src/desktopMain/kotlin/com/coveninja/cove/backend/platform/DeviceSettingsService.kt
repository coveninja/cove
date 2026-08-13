package com.coveninja.cove.backend.platform

import com.coveninja.cove.backend.http.MpvConfigStore
import com.coveninja.cove.shared.data.DevicePerformanceState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Device-global settings that intentionally do not roam with profiles. */
class DeviceSettingsService(dataDirectory: Path) : MpvConfigStore {
    private val mpvConfig = dataDirectory.resolve("mpv/mpv.conf").toAbsolutePath().normalize()
    private val deviceSettings = dataDirectory.resolve("device-settings.properties")
        .toAbsolutePath()
        .normalize()

    override suspend fun readMpvConfig(): String = if (Files.isRegularFile(mpvConfig)) {
        Files.readString(mpvConfig)
    } else {
        ""
    }

    override suspend fun writeMpvConfig(content: String) {
        require(content.toByteArray().size <= MAX_MPV_CONFIG_BYTES) { "mpv.conf exceeds 1 MiB" }
        Files.createDirectories(mpvConfig.parent)
        val temporary = mpvConfig.resolveSibling("${mpvConfig.fileName}.tmp-${UUID.randomUUID()}")
        Files.writeString(temporary, content)
        try {
            Files.move(temporary, mpvConfig, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, mpvConfig, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun readPerformanceState(): DevicePerformanceState {
        if (!Files.isRegularFile(deviceSettings)) return DevicePerformanceState()
        val values = runCatching {
            Files.readAllLines(deviceSettings)
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim() to
                        line.substring(separator + 1).trim()
                }
                .toMap()
        }.getOrDefault(emptyMap())
        return DevicePerformanceState(
            lowPerformanceMode = values[LOW_PERFORMANCE_MODE].toBoolean(),
            // Desktop offers the switch but does not pretend to know the machine's limits.
            lowPerformanceRecommended = false,
            recommendationDismissed = values[RECOMMENDATION_DISMISSED].toBoolean(),
        )
    }

    fun writePerformanceState(state: DevicePerformanceState) {
        atomicWrite(
            target = deviceSettings,
            content = buildString {
                append(LOW_PERFORMANCE_MODE)
                append('=')
                appendLine(state.lowPerformanceMode)
                append(RECOMMENDATION_DISMISSED)
                append('=')
                appendLine(state.recommendationDismissed)
            },
        )
    }

    private fun atomicWrite(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling("${target.fileName}.tmp-${UUID.randomUUID()}")
        Files.writeString(temporary, content)
        try {
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private const val MAX_MPV_CONFIG_BYTES = 1 shl 20
        private const val LOW_PERFORMANCE_MODE = "low-performance-mode"
        private const val RECOMMENDATION_DISMISSED = "recommendation-dismissed"
    }
}
