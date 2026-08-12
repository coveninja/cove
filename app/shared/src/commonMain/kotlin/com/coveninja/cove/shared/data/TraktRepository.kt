package com.coveninja.cove.shared.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface TraktState {
    data object Loading : TraktState

    /** No client credentials in this build, so linking can never succeed. */
    data class Unconfigured(val reason: String) : TraktState

    data class Unlinked(val error: String? = null) : TraktState

    /**
     * Trakt's device flow: the user types [userCode] at [verificationUrl] while
     * the app polls. Both strings come from Trakt and are shown verbatim.
     */
    data class Pending(val userCode: String, val verificationUrl: String) : TraktState

    data class Linked(val username: String) : TraktState
}

/**
 * Trakt account linking.
 *
 * Worth having separately from [AccountRepository]: this is a third-party
 * account with its own lifetime, and the scrobble/sync settings that depend on
 * it are inert until it is linked.
 */
interface TraktRepository {
    val state: StateFlow<TraktState>

    /** Starts the device flow and polls until it is accepted, denied or expires. */
    suspend fun startLink()
    suspend fun cancelLink()
    suspend fun unlink()

    /** Queues a full library sync with Trakt; no-op while unlinked. */
    suspend fun syncNow()
}

/** Stands in where Trakt is not reachable — see [UnavailablePlaybackRepository]. */
object UnavailableTraktRepository : TraktRepository {
    private const val REASON = "Trakt is not available on this device."

    override val state: StateFlow<TraktState> =
        MutableStateFlow(TraktState.Unconfigured(REASON))

    override suspend fun startLink() = Unit
    override suspend fun cancelLink() = Unit
    override suspend fun unlink() = Unit
    override suspend fun syncNow() = Unit
}
