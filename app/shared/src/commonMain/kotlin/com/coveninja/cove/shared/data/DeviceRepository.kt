package com.coveninja.cove.shared.data

/**
 * Settings that belong to this installation rather than to the profile, and so
 * deliberately do not roam with sync: the mpv configuration file and the app
 * build itself.
 *
 * Deliberately no update check. `UpdateService` reports the running version and
 * nothing else — there is no release feed behind it — so a "check for updates"
 * control here would only ever tell the user what they already see.
 */
interface DeviceRepository {
    /** False on hosts with no mpv and no local files — Android, remote backends. */
    val available: Boolean

    /** Build version string, or "dev" for a local build. */
    val appVersion: String

    suspend fun readMpvConfig(): String
    suspend fun writeMpvConfig(content: String)
}

/** Stands in where none of this exists — see [UnavailablePlaybackRepository]. */
object UnavailableDeviceRepository : DeviceRepository {
    override val available: Boolean = false
    override val appVersion: String = ""

    override suspend fun readMpvConfig(): String = ""
    override suspend fun writeMpvConfig(content: String) = Unit
}
