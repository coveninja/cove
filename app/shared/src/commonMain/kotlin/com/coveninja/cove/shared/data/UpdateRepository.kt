package com.coveninja.cove.shared.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A verified release offered by Cove's device-local updater. */
data class AppUpdateRelease(
    val version: String,
    val name: String,
    val publishedAt: String,
    val downloadSizeBytes: Long,
)

/** User-visible state for checking, staging, and applying an application update. */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data class ManagedExternally(val message: String) : AppUpdateState
    data class Checking(val manual: Boolean) : AppUpdateState
    data class UpToDate(val checkedAtEpochMillis: Long) : AppUpdateState
    data class MeteredApprovalRequired(val release: AppUpdateRelease) : AppUpdateState
    data class Downloading(
        val release: AppUpdateRelease,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AppUpdateState
    data class Ready(val release: AppUpdateRelease, val promptVisible: Boolean = true) : AppUpdateState
    data class PermissionRequired(val release: AppUpdateRelease) : AppUpdateState
    data class Installing(val release: AppUpdateRelease) : AppUpdateState
    data class Failed(val message: String, val retryable: Boolean = true) : AppUpdateState
}

enum class UpdateApplyResult {
    /** The platform installer owns the rest of the operation. */
    Started,

    /** A detached desktop helper is running and Cove must now close cleanly. */
    ExitRequired,

    /** Android opened its per-app install permission screen. */
    PermissionRequired,

    /** No verified staged update was available. */
    NothingToApply,
}

/**
 * Application updates belong to this installation, never to a synced profile.
 *
 * Implementations perform network and filesystem work off the UI thread. Privileged
 * installation stays in the host process and cannot be triggered through Cove's LAN API.
 */
interface UpdateRepository {
    val available: Boolean
    val currentVersion: String
    val automaticUpdatesEnabled: StateFlow<Boolean>
    val state: StateFlow<AppUpdateState>

    /** Starts the delayed launch check and the once-per-day in-process schedule. */
    fun start()

    suspend fun setAutomaticUpdatesEnabled(enabled: Boolean)
    suspend fun checkNow()
    suspend fun approveMeteredDownload()
    suspend fun applyReadyUpdate(): UpdateApplyResult
    suspend fun resumePendingAction(): UpdateApplyResult
    fun dismissReadyForSession()
}

object UnavailableUpdateRepository : UpdateRepository {
    override val available: Boolean = false
    override val currentVersion: String = ""
    override val automaticUpdatesEnabled = MutableStateFlow(false)
    override val state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)

    override fun start() = Unit
    override suspend fun setAutomaticUpdatesEnabled(enabled: Boolean) = Unit
    override suspend fun checkNow() = Unit
    override suspend fun approveMeteredDownload() = Unit
    override suspend fun applyReadyUpdate() = UpdateApplyResult.NothingToApply
    override suspend fun resumePendingAction() = UpdateApplyResult.NothingToApply
    override fun dismissReadyForSession() = Unit
}

class ManagedUpdateRepository(
    override val currentVersion: String,
    message: String,
) : UpdateRepository {
    override val available: Boolean = false
    override val automaticUpdatesEnabled = MutableStateFlow(false)
    override val state = MutableStateFlow<AppUpdateState>(AppUpdateState.ManagedExternally(message))

    override fun start() = Unit
    override suspend fun setAutomaticUpdatesEnabled(enabled: Boolean) = Unit
    override suspend fun checkNow() = Unit
    override suspend fun approveMeteredDownload() = Unit
    override suspend fun applyReadyUpdate() = UpdateApplyResult.NothingToApply
    override suspend fun resumePendingAction() = UpdateApplyResult.NothingToApply
    override fun dismissReadyForSession() = Unit
}
