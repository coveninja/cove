package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.network.CoveApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The account as seen through a remote Cove backend (`--api-base`).
 *
 * The session itself lives on that host — this only drives it. Sync is manual
 * and on sign-in rather than on a timer: the host being talked to is the one
 * that would run a background sync, and two schedulers pushing the same tables
 * is worse than none.
 */
class LiveAccountRepository(
    private val api: CoveApi,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : AccountRepository {
    private val _account = MutableStateFlow<AccountState>(AccountState.Loading)
    override val account: StateFlow<AccountState> = _account.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus())
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val syncing = Mutex()

    /**
     * The bearer token POST /api/auth/sync demands. Not persisted here: it is
     * recovered from the host's own stored session through refresh().
     */
    private var accessToken: String = ""

    init {
        scope.launch { refresh() }
    }

    override suspend fun signIn(email: String, password: String): AuthOutcome = attempt {
        accessToken = api.login(email, password).accessToken
        onSignedIn()
        AuthOutcome.Success
    }

    override suspend fun register(
        email: String,
        password: String,
        profileName: String,
    ): AuthOutcome = attempt {
        val response = api.register(email, password, profileName)
        if (response.confirmationRequired) {
            AuthOutcome.ConfirmationRequired
        } else {
            accessToken = response.accessToken
            onSignedIn()
            AuthOutcome.Success
        }
    }

    override suspend fun confirmRegistration(
        email: String,
        token: String,
        password: String,
        profileName: String,
    ): AuthOutcome = attempt {
        accessToken = api.confirmRegistration(email, token, password, profileName).accessToken
        onSignedIn()
        AuthOutcome.Success
    }

    override suspend fun sendOtp(email: String): AuthOutcome = attempt {
        api.sendOtp(email)
        AuthOutcome.Success
    }

    override suspend fun verifyOtp(email: String, token: String): AuthOutcome = attempt {
        accessToken = api.verifyOtp(email, token).accessToken
        onSignedIn()
        AuthOutcome.Success
    }

    override suspend fun signOut() {
        runCatching { api.logout() }
        accessToken = ""
        _syncStatus.value = SyncStatus()
        refresh()
    }

    override suspend fun syncNow() {
        if (_account.value !is AccountState.SignedIn) return
        syncing.withLock {
            _syncStatus.value = _syncStatus.value.copy(running = true)
            try {
                if (accessToken.isBlank()) accessToken = api.refreshSession().accessToken
                val result = api.syncAccount(accessToken)
                _syncStatus.value = SyncStatus(
                    running = false,
                    lastSyncedAt = clock.now(),
                    lastError = result.pushError.takeIf(String::isNotBlank),
                )
            } catch (cancellation: CancellationException) {
                _syncStatus.value = _syncStatus.value.copy(running = false)
                throw cancellation
            } catch (error: Throwable) {
                _syncStatus.value = _syncStatus.value.copy(
                    running = false,
                    lastError = error.message ?: "Sync failed.",
                    failed = true,
                )
            }
        }
    }

    private suspend fun onSignedIn() {
        refresh()
        syncNow()
    }

    private suspend fun refresh() {
        _account.value = try {
            val me = api.authMe()
            if (me.authenticated) {
                AccountState.SignedIn(email = me.email, userId = me.userId)
            } else {
                AccountState.SignedOut
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // A backend built without Supabase answers 503 here, which is the
            // same "no account backend" the local graph reports.
            AccountState.Unavailable(error.message ?: "The backend has no account support.")
        }
    }

    private inline fun attempt(block: () -> AuthOutcome): AuthOutcome = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        AuthOutcome.Failure(error.message ?: "Something went wrong. Please try again.")
    }
}
