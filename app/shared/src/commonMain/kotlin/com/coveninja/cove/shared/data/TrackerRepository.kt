package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.TrackerProvider
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface TrackerState {
    data object Loading : TrackerState

    /** No client credentials in this build, so linking can never succeed. */
    data class Unconfigured(val reason: String) : TrackerState

    data class Unlinked(val error: String? = null) : TrackerState

    /**
     * The device/PIN flow: the user types [userCode] at [verificationUrl] while the app
     * polls. Both strings come from the tracker and are shown verbatim.
     *
     * [expiresAt] is carried because the code is a transcription task on a deadline — the
     * tracker gives Cove the window and the screen is the only place the viewer could ever
     * learn it. Null when the tracker did not say.
     */
    data class Pending(
        val userCode: String,
        val verificationUrl: String,
        val expiresAt: Instant? = null,
    ) : TrackerState

    /**
     * A linked account.
     *
     * [lastSyncAt] is the cursor the service already keeps, surfaced rather than recomputed:
     * without it "Sync now" is a button with no before and no after.
     */
    data class Linked(
        val username: String,
        val lastSyncAt: Instant? = null,
        val syncing: Boolean = false,
        val syncError: String? = null,
    ) : TrackerState
}

/**
 * One third-party tracker account.
 *
 * Worth having separately from [AccountRepository]: these are third-party accounts with
 * their own lifetimes, and the scrobble/sync settings that depend on one are inert until
 * it is linked.
 */
interface TrackerRepository {
    val provider: TrackerProvider

    val state: StateFlow<TrackerState>

    /** Starts the link flow and polls until it is accepted, denied or expires. */
    suspend fun startLink()
    suspend fun cancelLink()
    suspend fun unlink()

    /** Runs a full library sync with the tracker; no-op while unlinked. */
    suspend fun syncNow()
}

/** Stands in where a tracker is not reachable — see [UnavailablePlaybackRepository]. */
class UnavailableTrackerRepository(override val provider: TrackerProvider) : TrackerRepository {
    override val state: StateFlow<TrackerState> = MutableStateFlow(
        TrackerState.Unconfigured("${provider.label} is not available on this device."),
    )

    override suspend fun startLink() = Unit
    override suspend fun cancelLink() = Unit
    override suspend fun unlink() = Unit
    override suspend fun syncNow() = Unit
}
