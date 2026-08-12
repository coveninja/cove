package com.coveninja.cove.backend.updater

import com.coveninja.cove.backend.http.RouteUpdater
import com.coveninja.cove.shared.network.UpdateCheckDto

/**
 * Package-manager-safe updater boundary for the in-process backend.
 *
 * Compose packages are currently delivered through Flatpak/AUR/NSIS, so the
 * Kotlin runtime never rewrites its own installation. Keeping the compatibility
 * routes explicit prevents old clients from interpreting a 404 as a network
 * failure while packaging remains the authority for updates.
 */
class UpdateService(private val currentVersion: String) : RouteUpdater {
    override fun check(): UpdateCheckDto = UpdateCheckDto(currentVersion = currentVersion)

    override fun apply(): Nothing = throw IllegalStateException(
        "self-update is unavailable for this package; use the platform package manager",
    )
}
