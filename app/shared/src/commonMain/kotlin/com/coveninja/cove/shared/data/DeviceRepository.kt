package com.coveninja.cove.shared.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Rendering preferences that belong to this installation, not to a signed-in profile.
 *
 * [lowPerformanceRecommended] is a platform hint, not an automatic decision. A device can
 * recommend the mode without changing [lowPerformanceMode], and the viewer can dismiss that
 * recommendation independently of the toggle.
 */
data class DevicePerformanceState(
    val lowPerformanceMode: Boolean = false,
    val lowPerformanceRecommended: Boolean = false,
    val recommendationDismissed: Boolean = false,
)

/**
 * Settings that belong to this installation rather than to the profile, and so
 * deliberately do not roam with sync: the mpv configuration file and the app
 * build itself. Update lifecycle state is intentionally kept in
 * [UpdateRepository], because checking, staging, and installation have a
 * different security boundary from editable player settings.
 */
interface DeviceRepository {
    /** False on hosts without editable local device settings, such as remote backends. */
    val available: Boolean

    /** Build version string, or "dev" for a local build. */
    val appVersion: String

    /** Device-local rendering policy. It deliberately never travels through profile sync. */
    val performance: StateFlow<DevicePerformanceState>

    suspend fun setLowPerformanceMode(enabled: Boolean)
    suspend fun dismissLowPerformanceRecommendation()

    suspend fun readMpvConfig(): String
    suspend fun writeMpvConfig(content: String)
}

/** Stands in where none of this exists — see [UnavailablePlaybackRepository]. */
object UnavailableDeviceRepository : DeviceRepository {
    override val available: Boolean = false
    override val appVersion: String = ""
    override val performance = MutableStateFlow(DevicePerformanceState())

    override suspend fun setLowPerformanceMode(enabled: Boolean) = Unit
    override suspend fun dismissLowPerformanceRecommendation() = Unit

    override suspend fun readMpvConfig(): String = ""
    override suspend fun writeMpvConfig(content: String) = Unit
}
