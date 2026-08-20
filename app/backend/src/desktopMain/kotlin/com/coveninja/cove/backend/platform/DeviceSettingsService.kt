package com.coveninja.cove.backend.platform

import com.coveninja.cove.backend.http.MpvConfigStore
import com.coveninja.cove.backend.storage.TorrentCachePolicyStore
import com.coveninja.cove.shared.data.DevicePerformanceState
import com.coveninja.cove.shared.data.TorrentCachePolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Device-global settings that intentionally do not roam with profiles.
 *
 * The cache policy is here rather than in `AppSettings` for the same reason the mpv config is: a
 * disk budget belongs to the machine. Twenty gigabytes is a reasonable allowance on the desktop
 * that chose it and an impossible one on a phone that synced it.
 */
class DeviceSettingsService(dataDirectory: Path) : MpvConfigStore, TorrentCachePolicyStore {
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
        val values = readValues()
        return DevicePerformanceState(
            lowPerformanceMode = values[LOW_PERFORMANCE_MODE].toBoolean(),
            // Desktop offers the switch but does not pretend to know the machine's limits.
            lowPerformanceRecommended = false,
            recommendationDismissed = values[RECOMMENDATION_DISMISSED].toBoolean(),
        )
    }

    fun writePerformanceState(state: DevicePerformanceState) {
        // Merged rather than replaced: the cache policy shares this file, and rewriting it from
        // these two keys alone would reset the viewer's disk budget every time the performance
        // switch was touched.
        val values = readValues().toMutableMap()
        values[LOW_PERFORMANCE_MODE] = state.lowPerformanceMode.toString()
        values[RECOMMENDATION_DISMISSED] = state.recommendationDismissed.toString()
        writeValues(values)
    }

    override fun read(): TorrentCachePolicy {
        val values = readValues()
        return TorrentCachePolicy(
            limitBytes = values[CACHE_LIMIT_BYTES]?.toLongOrNull() ?: DEFAULT_LIMIT_BYTES,
            downloadAheadBytes = values[DOWNLOAD_AHEAD_BYTES]?.toLongOrNull() ?: DEFAULT_AHEAD_BYTES,
            deleteAfterWatching = values[DELETE_AFTER_WATCHING].toBoolean(),
            maxAgeDays = values[MAX_AGE_DAYS]?.toIntOrNull() ?: DEFAULT_MAX_AGE_DAYS,
        )
    }

    override fun write(policy: TorrentCachePolicy) {
        // Merged into whatever else the file holds: performance state and the cache policy share
        // it, and writing one key set from its own defaults would silently reset the other.
        val values = readValues().toMutableMap()
        values[CACHE_LIMIT_BYTES] = policy.limitBytes.toString()
        values[DOWNLOAD_AHEAD_BYTES] = policy.downloadAheadBytes.toString()
        values[DELETE_AFTER_WATCHING] = policy.deleteAfterWatching.toString()
        values[MAX_AGE_DAYS] = policy.maxAgeDays.toString()
        writeValues(values)
    }

    private fun readValues(): Map<String, String> {
        if (!Files.isRegularFile(deviceSettings)) return emptyMap()
        return runCatching {
            Files.readAllLines(deviceSettings)
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeValues(values: Map<String, String>) {
        atomicWrite(
            target = deviceSettings,
            content = buildString {
                for ((key, value) in values.toSortedMap()) {
                    append(key).append('=').appendLine(value)
                }
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
        private const val CACHE_LIMIT_BYTES = "torrent-cache-limit-bytes"
        private const val DOWNLOAD_AHEAD_BYTES = "torrent-download-ahead-bytes"
        private const val DELETE_AFTER_WATCHING = "torrent-delete-after-watching"
        private const val MAX_AGE_DAYS = "torrent-cache-max-age-days"

        /**
         * Defaults for a machine with a real disk. Chosen so an untouched install stops growing
         * without ever deleting something the viewer would notice: twenty gigabytes is dozens of
         * episodes, and a month is longer than anyone waits to finish a series.
         */
        private const val DEFAULT_LIMIT_BYTES = 20L * 1024 * 1024 * 1024
        private const val DEFAULT_AHEAD_BYTES = 512L * 1024 * 1024
        private const val DEFAULT_MAX_AGE_DAYS = 30
    }
}
