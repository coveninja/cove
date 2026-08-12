package com.coveninja.cove.shared.data

import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface AccountState {
    data object Loading : AccountState

    /**
     * No account backend at all: a build with no Supabase credentials, or a host
     * that cannot reach one. Carries the reason so the UI can say which, instead
     * of offering a sign-in form that could never succeed.
     */
    data class Unavailable(val reason: String) : AccountState

    data object SignedOut : AccountState

    data class SignedIn(val email: String, val userId: String) : AccountState
}

/**
 * What the last sync did, and when.
 *
 * [lastSyncedAt] is in-memory: after a restart it reads null until the launch
 * sync lands, which is honest — the app has not verified anything with the
 * server yet either.
 */
data class SyncStatus(
    val running: Boolean = false,
    val lastSyncedAt: Instant? = null,
    /** A failed sync, or the partial-push report of one that otherwise finished. */
    val lastError: String? = null,
    /**
     * True when the last attempt did not complete at all, as opposed to finishing
     * with rows it could not carry. Worth separating: one means nothing moved,
     * the other means almost everything did, and telling the user they are the
     * same thing is how a working sync gets mistaken for a broken one.
     */
    val failed: Boolean = false,
)

/**
 * The result of one auth attempt.
 *
 * Returned rather than thrown: a wrong password or an unverified address is
 * ordinary user input, the same reasoning [AddonRepository] gives for bad
 * manifest URLs, and a form needs the message in hand to render it in place.
 */
sealed interface AuthOutcome {
    data object Success : AuthOutcome

    /** Registration succeeded but the emailed token has to be confirmed first. */
    data object ConfirmationRequired : AuthOutcome

    data class Failure(val message: String) : AuthOutcome
}

/**
 * The signed-in account and cross-device sync.
 *
 * Sync is not something the user drives: implementations sync on launch, on a
 * timer, and after local changes settle. [syncNow] exists for the times someone
 * wants to watch it happen — it ignores the auto-sync preference.
 */
interface AccountRepository {
    val account: StateFlow<AccountState>
    val syncStatus: StateFlow<SyncStatus>

    suspend fun signIn(email: String, password: String): AuthOutcome
    suspend fun register(email: String, password: String, profileName: String): AuthOutcome
    suspend fun confirmRegistration(
        email: String,
        token: String,
        password: String,
        profileName: String,
    ): AuthOutcome

    suspend fun sendOtp(email: String): AuthOutcome
    suspend fun verifyOtp(email: String, token: String): AuthOutcome

    suspend fun signOut()
    suspend fun syncNow()
}

/** Stands in where no account backend is configured — see [UnavailablePlaybackRepository]. */
object UnavailableAccountRepository : AccountRepository {
    private const val REASON = "Cove sync is not configured in this build."

    override val account: StateFlow<AccountState> =
        MutableStateFlow(AccountState.Unavailable(REASON))
    override val syncStatus: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus())

    override suspend fun signIn(email: String, password: String) = AuthOutcome.Failure(REASON)
    override suspend fun register(email: String, password: String, profileName: String) =
        AuthOutcome.Failure(REASON)

    override suspend fun confirmRegistration(
        email: String,
        token: String,
        password: String,
        profileName: String,
    ) = AuthOutcome.Failure(REASON)

    override suspend fun sendOtp(email: String) = AuthOutcome.Failure(REASON)
    override suspend fun verifyOtp(email: String, token: String) = AuthOutcome.Failure(REASON)
    override suspend fun signOut() = Unit
    override suspend fun syncNow() = Unit
}
