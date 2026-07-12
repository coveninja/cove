package com.coveninja.cove.sync

import android.util.Log
import com.coveninja.cove.api.CoveApiClient
import com.coveninja.cove.api.SyncResponse
import com.coveninja.cove.auth.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-wide sync coordinator. Mirrors the desktop focus-sync model from App.svelte:
 *
 *  - syncOnResume(): called on app foreground (ProcessLifecycleOwner ON_START).
 *    Throttled to at most once every 60 s. Skips when signed out.
 *
 *  - syncAfterMutation(): debounced fire-and-forget called after local writes
 *    (library add/remove, playback session end). Multiple rapid calls coalesce
 *    into one sync after DEBOUNCE_MS quiet time.
 *
 *  - handleSyncResponse(): called by both this coordinator AND AuthViewModel's
 *    manual sync so that library_generation is tracked from one shared place.
 *    When the generation changes, libraryVersion StateFlow is bumped so Home
 *    and Library screens can refetch.
 *
 *  - signedOutEvents: emits Unit when a sync returns 401. CoveApp collects this
 *    and delegates to AuthViewModel.handleSyncSignOut().
 */
object SyncCoordinator {

    private const val TAG = "SyncCoordinator"
    private const val THROTTLE_MS = 60_000L
    private const val DEBOUNCE_MS = 3_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // lastSyncMs is only updated on successful attempts (not -1) so a cold-start
    // network failure doesn't suppress the first real resume sync for 60 s.
    @Volatile private var lastSyncMs = 0L

    // Seeded by handleSyncResponse() on the startup probe so the first resume
    // sync doesn't spuriously bump libraryVersion when nothing changed remotely.
    @Volatile private var lastLibraryGeneration = -1L

    // Incremented when a sync detects the remote library changed. Screens
    // observe this and call vm.load() when the value changes.
    private val _libraryVersion = MutableStateFlow(0)
    val libraryVersion: StateFlow<Int> = _libraryVersion.asStateFlow()

    // Emits Unit when any sync returns 401 so CoveApp can sign the user out.
    val signedOutEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var debounceJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called from ProcessLifecycleOwner ON_START (whole-app foreground).
     * Throttled: skips if fewer than 60 s have passed since the last sync.
     */
    fun syncOnResume() {
        if (TokenStore.get() == null) {
            Log.d(TAG, "syncOnResume: skipped — not signed in")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSyncMs < THROTTLE_MS) {
            Log.d(TAG, "syncOnResume: throttled (${(now - lastSyncMs) / 1000}s since last sync, need 60s)")
            return
        }
        Log.d(TAG, "syncOnResume: triggering sync")
        scope.launch { runSync("resume") }
    }

    /**
     * Debounced fire-and-forget sync after a local write (library add/remove,
     * end of playback session). Multiple rapid calls coalesce into a single
     * sync that fires DEBOUNCE_MS after the last call.
     */
    fun syncAfterMutation() {
        if (TokenStore.get() == null) {
            Log.d(TAG, "syncAfterMutation: skipped — not signed in")
            return
        }
        debounceJob?.cancel()
        Log.d(TAG, "syncAfterMutation: debounce reset (fires in ${DEBOUNCE_MS}ms)")
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            runSync("mutation")
        }
    }

    /**
     * Updates the shared library_generation baseline and bumps libraryVersion
     * if the remote generation changed. Called by both runSync() internally and
     * AuthViewModel.sync() so the manual Sync Now button also refreshes screens.
     *
     * Pass null for [gen] when the backend omits library_generation (older build
     * without supabase tag) — always bumps in that case for backwards compat.
     */
    fun handleSyncResponse(gen: Long?) {
        if (gen != null) {
            if (gen != lastLibraryGeneration) {
                Log.d(TAG, "handleSyncResponse: generation $lastLibraryGeneration → $gen, bumping libraryVersion")
                lastLibraryGeneration = gen
                _libraryVersion.value = _libraryVersion.value + 1
            } else {
                Log.d(TAG, "handleSyncResponse: generation $gen unchanged, no UI refresh needed")
            }
        } else {
            // Older backend — no generation field; always bump for compat.
            Log.d(TAG, "handleSyncResponse: no library_generation in response (older backend) — bumping")
            _libraryVersion.value = _libraryVersion.value + 1
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun runSync(reason: String) {
        Log.d(TAG, "runSync($reason): calling POST /auth/sync")
        val (code, body) = CoveApiClient.postForStatus("/auth/sync", "{}")
        when (code) {
            200 -> {
                // Advance throttle clock only on success.
                lastSyncMs = System.currentTimeMillis()
                val resp = runCatching {
                    CoveApiClient.json.decodeFromString<SyncResponse>(body)
                }.getOrNull()
                Log.d(TAG, "runSync($reason): HTTP 200, library_generation=${resp?.libraryGeneration}")
                handleSyncResponse(resp?.libraryGeneration)
            }
            401 -> {
                Log.d(TAG, "runSync($reason): HTTP 401 — clearing session, emitting sign-out")
                TokenStore.clear()
                CoveApiClient.setJwt(null)
                signedOutEvents.emit(Unit)
            }
            503 -> Log.d(TAG, "runSync($reason): HTTP 503 — auth unavailable (non-supabase build?)")
            -1  -> Log.d(TAG, "runSync($reason): network error — lastSyncMs NOT updated (will retry on next resume)")
            else -> {
                lastSyncMs = System.currentTimeMillis()
                Log.d(TAG, "runSync($reason): HTTP $code — unexpected response")
            }
        }
    }
}
