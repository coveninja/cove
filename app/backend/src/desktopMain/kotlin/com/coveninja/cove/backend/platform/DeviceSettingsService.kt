package com.coveninja.cove.backend.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Device-global settings that intentionally do not roam with profiles. */
class DeviceSettingsService(dataDirectory: Path) {
    private val mpvConfig = dataDirectory.resolve("mpv/mpv.conf").toAbsolutePath().normalize()

    fun readMpvConfig(): String = if (Files.isRegularFile(mpvConfig)) {
        Files.readString(mpvConfig)
    } else {
        ""
    }

    fun writeMpvConfig(content: String) {
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

    companion object {
        private const val MAX_MPV_CONFIG_BYTES = 1 shl 20
    }
}
