package com.coveninja.cove.backend.auth

import com.coveninja.cove.shared.data.AccountRepository
import com.coveninja.cove.shared.data.AccountState
import com.coveninja.cove.shared.data.AuthOutcome
import com.coveninja.cove.shared.data.LibraryRepository
import com.coveninja.cove.shared.data.ProfileRepository
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.data.SettingsRepository
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.data.SyncStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The signed-in account, in front of [AuthService].
 *
 * Beyond exposing state to the UI this owns *when* Cove syncs: once at launch,
 * on a heartbeat, and after local changes settle — all of it gated on
 * `AppSettings.autoSyncEnabled`, and all of it paced by [SyncTriggerPolicy].
 *
 * The pacing is not decoration. Pulling remote data writes to the same library
 * the change-watcher observes, so an unpaced loop would sync, observe its own
 * merge, and sync again.
 */
class LocalAccountRepository(
    private val auth: AuthService,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
    private val profiles: ProfileRepository,
    private val scope: CoroutineScope,
    private val policy: SyncTriggerPolicy = SyncTriggerPolicy(),
    private val clock: Clock = Clock.System,
) : AccountRepository {
    private val _account = MutableStateFlow<AccountState>(AccountState.Loading)
    override val account: StateFlow<AccountState> = _account.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus())
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val syncing = Mutex()
    private var lastAttemptAt: Instant? = null
    private var pendingChangeAt: Instant? = null

    // Which profile the last sync actually carried. Guarded by [syncing], because
    // a sync is itself allowed to change the active profile.
    private var lastSyncedActiveId: String? = null

    // Conflated: several changes arriving while the loop is busy are one wake-up,
    // which is exactly the coalescing the policy then reasons about.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        refreshAccountState()
        // drop(1): the first emission is the flow's current value, which is the
        // state at startup rather than a change worth syncing for.
        scope.launch { library.entries.drop(1).collect { markLocalChange() } }
        scope.launch { settings.settings.drop(1).collect { markLocalChange() } }
        // Switching profile syncs at once rather than marking a pending change: a
        // profile that arrived from another device has no data here yet, and the
        // policy's debounce and minimum interval would leave it looking empty for
        // minutes. Every other roster edit is paced like any ordinary local change.
        scope.launch {
            profileStates()
                .map { it.activeProfileId }
                .distinctUntilChanged()
                .drop(1)
                .collect { syncForProfileSwitch(it) }
        }
        scope.launch {
            profileStates()
                .map { state -> state.profiles.map { it.id to it.name } }
                .distinctUntilChanged()
                .drop(1)
                .collect { markLocalChange() }
        }
        scope.launch { runSyncLoop() }
    }

    override suspend fun signIn(email: String, password: String): AuthOutcome = attempt {
        auth.login(email, password, runSync = false)
        onSignedIn()
        AuthOutcome.Success
    }

    override suspend fun register(
        email: String,
        password: String,
        profileName: String,
    ): AuthOutcome = attempt {
        when (auth.register(email, password, profileName)) {
            RegistrationOutcome.ConfirmationRequired -> AuthOutcome.ConfirmationRequired
            is RegistrationOutcome.Complete -> {
                onSignedIn()
                AuthOutcome.Success
            }
        }
    }

    override suspend fun confirmRegistration(
        email: String,
        token: String,
        password: String,
        profileName: String,
    ): AuthOutcome = attempt {
        auth.confirmRegistration(email, token, password, profileName)
        onSignedIn()
        AuthOutcome.Success
    }

    override suspend fun sendOtp(email: String): AuthOutcome = attempt {
        auth.sendOtp(email)
        AuthOutcome.Success
    }

    override suspend fun verifyOtp(email: String, token: String): AuthOutcome = attempt {
        auth.verifyOtp(email, token, runSync = false)
        onSignedIn()
        AuthOutcome.Success
    }

    override suspend fun signOut() {
        runCatching { auth.logout() }
        lastAttemptAt = null
        pendingChangeAt = null
        _syncStatus.value = SyncStatus()
        refreshAccountState()
        wake.trySend(Unit)
    }

    /** Always runs, auto-sync preference or not — the user asked for this one. */
    override suspend fun syncNow() {
        if (_account.value !is AccountState.SignedIn) return
        sync()
    }

    private suspend fun onSignedIn() {
        refreshAccountState()
        // A first sync is what makes signing in mean anything; its failure is
        // reported through syncStatus rather than as a failed sign-in.
        sync()
    }

    private fun refreshAccountState() {
        val me = runCatching { auth.me() }.getOrNull()
        _account.value = when {
            me == null -> AccountState.Unavailable("Could not read the stored account.")
            me.authenticated -> AccountState.SignedIn(email = me.email, userId = me.userId)
            else -> AccountState.SignedOut
        }
    }

    private fun profileStates(): Flow<ProfilesState.Ready> =
        profiles.profiles.filterIsInstance<ProfilesState.Ready>()

    /**
     * Syncs because the viewer switched profile — unless this profile is the one the
     * last sync already carried.
     *
     * That guard is what stops a loop. `reconcileRoster` re-keys a fresh device onto
     * the account's primary, so a sync can change the active profile itself, and
     * reacting to its own write would sync again immediately. Checked under
     * [syncing] because the sync that made the change may still be running.
     */
    private suspend fun syncForProfileSwitch(activeId: String) {
        if (_account.value !is AccountState.SignedIn) return
        if (syncing.withLock { activeId == lastSyncedActiveId }) return
        sync()
    }

    private fun markLocalChange() {
        if (pendingChangeAt == null) pendingChangeAt = clock.now()
        wake.trySend(Unit)
    }

    private suspend fun runSyncLoop() {
        while (scope.isActive) {
            val decision = policy.decide(
                now = clock.now(),
                autoSyncEnabled = autoSyncEnabled(),
                signedIn = _account.value is AccountState.SignedIn,
                running = _syncStatus.value.running,
                lastAttemptAt = lastAttemptAt,
                lastSyncedAt = _syncStatus.value.lastSyncedAt,
                pendingChangeAt = pendingChangeAt,
            )
            when (decision) {
                SyncDecision.Now -> sync()
                is SyncDecision.Wait -> withTimeoutOrNull(decision.delay) { wake.receive() }
                SyncDecision.Idle -> wake.receive()
            }
        }
    }

    private fun autoSyncEnabled(): Boolean =
        (settings.settings.value as? SettingsState.Ready)?.settings?.autoSyncEnabled ?: true

    /**
     * One sync at a time. The mutex also covers [pendingChangeAt]: it is cleared
     * before the pull so that a change made *during* the sync is still pending
     * afterwards rather than being swallowed by it.
     */
    private suspend fun sync() = syncing.withLock {
        _syncStatus.value = _syncStatus.value.copy(running = true)
        pendingChangeAt = null
        lastAttemptAt = clock.now()
        try {
            val result = auth.syncNow()
            _syncStatus.value = SyncStatus(
                running = false,
                lastSyncedAt = clock.now(),
                // A sync that finished with rows it could not push or read still
                // synced everything else; the shortfall is reported rather than
                // being either hidden or dressed up as a total failure.
                lastError = listOf(result.pushError, result.pullWarning)
                    .filter(String::isNotBlank)
                    .joinToString("; ")
                    .takeIf(String::isNotBlank),
            )
        } catch (cancellation: CancellationException) {
            _syncStatus.value = _syncStatus.value.copy(running = false)
            throw cancellation
        } catch (error: Throwable) {
            _syncStatus.value = _syncStatus.value.copy(
                running = false,
                lastError = error.readableMessage(),
                failed = true,
            )
        } finally {
            lastSyncedActiveId = (profiles.profiles.value as? ProfilesState.Ready)?.activeProfileId
            wake.trySend(Unit)
        }
    }

    /**
     * Runs one auth attempt on this repository's scope rather than the caller's.
     *
     * The caller is a form, and forms go away. The onboarding step is unmounted the instant
     * someone presses Continue, and on the caller's own scope that cancels the attempt
     * wherever it had got to — including after Supabase accepted the credentials but before
     * [refreshAccountState] had run, which leaves an account that exists everywhere except on
     * the device that just signed into it. Detached, the worst case is a sign-in the viewer
     * stops watching: it still finishes, and [account] still reports it.
     *
     * It also moves the work off the caller's dispatcher, which is the composition's — every
     * one of these ends in SQLite writes and a full sync, on the UI thread.
     */
    private suspend fun attempt(block: suspend () -> AuthOutcome): AuthOutcome = scope.async {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            AuthOutcome.Failure(error.readableMessage())
        }
    }.await()
}

private fun Throwable.readableMessage(): String = when (this) {
    is SupabaseException -> detail
    else -> message
}.orEmpty().ifBlank { "Something went wrong. Please try again." }
