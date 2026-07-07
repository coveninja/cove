package com.coveninja.cove.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coveninja.cove.api.CoveApiClient
import com.coveninja.cove.api.ConfirmRegisterResponse
import com.coveninja.cove.api.LoginResponse
import com.coveninja.cove.api.Profile
import com.coveninja.cove.api.ProfilesListResponse
import com.coveninja.cove.api.RegisterResponse
import com.coveninja.cove.api.SyncResponse
import com.coveninja.cove.sync.SyncCoordinator
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Represents the current authentication and availability state.
 *
 * Loading  — startup probe in flight (shows spinner).
 * Unavailable — /api/auth/sync returned 503; backend not built with supabase
 *               tag, or SUPABASE_URL was empty at build time.
 * SignedOut — auth is available but no session exists.
 * SignedIn  — session active; shows profile / sync controls.
 */
sealed class AuthState {
    object Loading : AuthState()
    object Unavailable : AuthState()
    object SignedOut : AuthState()
    data class SignedIn(
        val email: String,
        val activeProfile: Profile,
        val profiles: List<Profile>,
    ) : AuthState()
}

/** Sub-state for the active form shown when signed out. */
enum class AuthForm { LOGIN, REGISTER, CONFIRM_REGISTER }

class AuthViewModel : ViewModel() {

    var authState by mutableStateOf<AuthState>(AuthState.Loading)
        private set

    // The form currently shown (login or register or OTP confirm).
    var activeForm by mutableStateOf(AuthForm.LOGIN)
        private set

    // Input fields
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var confirmToken by mutableStateOf("")   // OTP from confirmation email
    var profileName by mutableStateOf("")    // optional; used during register

    // Feedback
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    // Sync feedback
    var syncInProgress by mutableStateOf(false)
        private set
    var lastSyncResult by mutableStateOf<String?>(null)
        private set

    // Profile create — drives the inline "New profile" form in SignedInSection.
    var showCreateProfile by mutableStateOf(false)
    var newProfileName by mutableStateOf("")
    var createProfileError by mutableStateOf<String?>(null)
    var createProfileLoading by mutableStateOf(false)
        private set

    // Carry password/profile_name from register step → confirm step.
    private var pendingRegisterPassword = ""
    private var pendingProfileName = ""

    // ── Startup probe ───────────────────────────────────────────────────────────

    /**
     * Must be called once the embedded backend is ready (backendReady = true).
     * Fires POST /api/auth/sync which discriminates all states in one call:
     *   503 → Unavailable
     *   401 → SignedOut (token missing or expired)
     *   200 → SignedIn (and acts as the startup background sync)
     *   -1  → treat as SignedOut (offline / backend not yet ready)
     */
    fun probeOnBackendReady() {
        viewModelScope.launch {
            val stored = TokenStore.get()
            if (stored == null) {
                // No stored session — probe for availability without a token.
                val (code, _) = CoveApiClient.postForStatus("/auth/sync", "{}")
                authState = when (code) {
                    503  -> AuthState.Unavailable
                    else -> AuthState.SignedOut
                }
                return@launch
            }

            // We have a stored token — already loaded into CoveApiClient at startup.
            val (code, body) = CoveApiClient.postForStatus("/auth/sync", "{}")
            when (code) {
                200  -> {
                    // Sync succeeded — seed SyncCoordinator with the current
                    // library_generation so the first resume sync doesn't
                    // spuriously bump libraryVersion when nothing changed remotely.
                    val resp = runCatching {
                        CoveApiClient.json.decodeFromString<SyncResponse>(body)
                    }.getOrNull()
                    SyncCoordinator.handleSyncResponse(resp?.libraryGeneration)
                    // Load profile list to populate the signed-in view.
                    loadSignedInState(stored.email)
                }
                503  -> {
                    authState = AuthState.Unavailable
                }
                401  -> {
                    // Token expired or invalid. Clear stored session.
                    TokenStore.clear()
                    CoveApiClient.setJwt(null)
                    authState = AuthState.SignedOut
                }
                else -> {
                    // Network error or unexpected — keep stored session, show
                    // signed-in with a stale-data warning.
                    loadSignedInState(stored.email)
                }
            }
        }
    }

    // ── Auth operations ─────────────────────────────────────────────────────────

    fun login() {
        if (email.isBlank() || password.isBlank()) {
            errorMessage = "Email and password are required"
            return
        }
        isLoading = true
        errorMessage = null
        viewModelScope.launch {
            val body = Json.encodeToString(mapOf("email" to email, "password" to password))
            val (code, responseBody) = CoveApiClient.postForStatus("/auth/login", body)
            isLoading = false
            when (code) {
                200  -> {
                    val resp = runCatching {
                        CoveApiClient.json.decodeFromString<LoginResponse>(responseBody)
                    }.getOrNull()
                    if (resp == null || resp.accessToken.isEmpty()) {
                        errorMessage = "Unexpected response from server"
                        return@launch
                    }
                    persistSession(resp.accessToken, resp.refreshToken, email)
                    loadSignedInState(email)
                }
                401  -> errorMessage = "Incorrect email or password"
                503  -> {
                    authState = AuthState.Unavailable
                }
                -1   -> errorMessage = "Network error — check connection"
                else -> errorMessage = "Login failed (HTTP $code)"
            }
        }
    }

    fun register() {
        if (email.isBlank() || password.isBlank()) {
            errorMessage = "Email and password are required"
            return
        }
        pendingRegisterPassword = password
        pendingProfileName = profileName
        isLoading = true
        errorMessage = null
        viewModelScope.launch {
            val body = Json.encodeToString(buildMap {
                put("email", email)
                put("password", password)
                if (profileName.isNotBlank()) put("profile_name", profileName)
            })
            val (code, responseBody) = CoveApiClient.postForStatus("/auth/register", body)
            isLoading = false
            when (code) {
                200  -> {
                    val resp = runCatching {
                        CoveApiClient.json.decodeFromString<RegisterResponse>(responseBody)
                    }.getOrNull()
                    when {
                        resp == null              -> errorMessage = "Unexpected response from server"
                        resp.confirmationRequired -> {
                            // Email confirmation required — show OTP input.
                            activeForm = AuthForm.CONFIRM_REGISTER
                        }
                        resp.accessToken.isNotEmpty() -> {
                            // Immediate session (email confirmation disabled).
                            persistSession(resp.accessToken, resp.refreshToken, email)
                            loadSignedInState(email)
                        }
                        else -> errorMessage = "Unexpected response from server"
                    }
                }
                400  -> errorMessage = extractError(responseBody) ?: "Registration failed"
                503  -> authState = AuthState.Unavailable
                -1   -> errorMessage = "Network error — check connection"
                else -> errorMessage = "Registration failed (HTTP $code)"
            }
        }
    }

    /**
     * Confirm registration: POST /api/auth/register/confirm {email, token, password, profile_name?}.
     * The password must be carried over from the register step — backend re-signs in after OTP verify.
     */
    fun confirmRegistration() {
        if (confirmToken.isBlank()) {
            errorMessage = "Enter the code from your email"
            return
        }
        isLoading = true
        errorMessage = null
        viewModelScope.launch {
            val body = Json.encodeToString(buildMap {
                put("email", email)
                put("token", confirmToken)
                put("password", pendingRegisterPassword)
                if (pendingProfileName.isNotBlank()) put("profile_name", pendingProfileName)
            })
            val (code, responseBody) = CoveApiClient.postForStatus("/auth/register/confirm", body)
            isLoading = false
            when (code) {
                200  -> {
                    val resp = runCatching {
                        CoveApiClient.json.decodeFromString<ConfirmRegisterResponse>(responseBody)
                    }.getOrNull()
                    if (resp == null || resp.accessToken.isEmpty()) {
                        errorMessage = "Unexpected response from server"
                        return@launch
                    }
                    persistSession(resp.accessToken, resp.refreshToken, email)
                    loadSignedInState(email)
                }
                400  -> errorMessage = extractError(responseBody) ?: "Invalid or expired code"
                401  -> errorMessage = "Invalid or expired code"
                503  -> authState = AuthState.Unavailable
                -1   -> errorMessage = "Network error — check connection"
                else -> errorMessage = "Confirmation failed (HTTP $code)"
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // Backend logout is a best-effort fire-and-forget (handlers.go just returns OK).
            CoveApiClient.postForStatus("/auth/logout", "{}")
            clearSession()
            authState = AuthState.SignedOut
            email = ""
            password = ""
            activeForm = AuthForm.LOGIN
        }
    }

    fun sync() {
        syncInProgress = true
        lastSyncResult = null
        viewModelScope.launch {
            val (code, body) = CoveApiClient.postForStatus("/auth/sync", "{}")
            syncInProgress = false
            when (code) {
                200  -> {
                    // Parse library_generation and forward to SyncCoordinator so the
                    // manual Sync Now button also triggers Home/Library refreshes when
                    // the remote library changed — mirrors the desktop behaviour.
                    val resp = runCatching {
                        CoveApiClient.json.decodeFromString<SyncResponse>(body)
                    }.getOrNull()
                    SyncCoordinator.handleSyncResponse(resp?.libraryGeneration)
                    lastSyncResult = "Synced"
                }
                401  -> handleSyncSignOut()
                503  -> lastSyncResult = "Sync unavailable (auth not configured)"
                -1   -> lastSyncResult = "Sync failed (offline)"
                else -> lastSyncResult = "Sync failed (HTTP $code)"
            }
        }
    }

    /**
     * Called when any sync (background or manual) returns HTTP 401.
     * Clears the stored session and resets UI state without making a network call.
     * Also called by SyncCoordinator via its signedOutEvents flow.
     */
    fun handleSyncSignOut() {
        clearSession()
        authState = AuthState.SignedOut
        email = ""
        password = ""
        activeForm = AuthForm.LOGIN
        lastSyncResult = null
        syncInProgress = false
    }

    fun switchProfile(profileId: String) {
        viewModelScope.launch {
            try {
                CoveApiClient.post<com.coveninja.cove.api.Profile>("/profiles/$profileId/activate", "{}")
                val stored = TokenStore.get()
                loadSignedInState(stored?.email ?: "")
            } catch (_: Exception) {}
        }
    }

    /**
     * Creates a new profile via POST /api/profiles {name}, then reloads the
     * signed-in state so the new profile appears in the list immediately.
     * Collapses the inline create form on success.
     */
    fun createProfile() {
        val name = newProfileName.trim()
        if (name.isEmpty()) {
            createProfileError = "Profile name cannot be empty"
            return
        }
        createProfileLoading = true
        createProfileError = null
        viewModelScope.launch {
            try {
                val body = Json.encodeToString(mapOf("name" to name))
                val created = CoveApiClient.post<com.coveninja.cove.api.Profile>("/profiles", body)
                if (created == null || created.id.isEmpty()) {
                    createProfileError = "Failed to create profile"
                } else {
                    showCreateProfile = false
                    newProfileName = ""
                    val stored = TokenStore.get()
                    loadSignedInState(stored?.email ?: "")
                }
            } catch (e: Exception) {
                createProfileError = e.message ?: "Failed to create profile"
            } finally {
                createProfileLoading = false
            }
        }
    }

    fun cancelCreateProfile() {
        showCreateProfile = false
        newProfileName = ""
        createProfileError = null
    }

    // ── Form navigation ─────────────────────────────────────────────────────────

    fun showLogin() {
        activeForm = AuthForm.LOGIN
        errorMessage = null
    }

    fun showRegister() {
        activeForm = AuthForm.REGISTER
        errorMessage = null
        profileName = ""
    }

    fun clearError() { errorMessage = null }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private fun persistSession(accessToken: String, refreshToken: String, emailAddr: String) {
        // refresh_token is stored for future use but is NOT used for silent token
        // renewal. The backend (handlers.go) exposes no /auth/refresh route, and
        // the desktop client (api.ts) performs no client-side Supabase token refresh
        // either. Decision: accept re-login on JWT expiry (~1 h Supabase default).
        // On 401 from /auth/sync, the user is returned to the sign-in form.
        TokenStore.save(accessToken, refreshToken, emailAddr)
        CoveApiClient.setJwt(accessToken)
    }

    private fun clearSession() {
        TokenStore.clear()
        CoveApiClient.setJwt(null)
    }

    private suspend fun loadSignedInState(emailAddr: String) {
        try {
            val resp = CoveApiClient.get<ProfilesListResponse>("/profiles")
            val active = resp.profiles.firstOrNull { it.id == resp.activeProfileId }
                ?: resp.profiles.firstOrNull()
                ?: Profile()
            authState = AuthState.SignedIn(emailAddr, active, resp.profiles)
        } catch (_: Exception) {
            // Profiles endpoint unreachable — still mark as signed in with minimal info.
            authState = AuthState.SignedIn(emailAddr, Profile(), emptyList())
        }
    }

    /** Tries to pull a human-readable message out of a plain-text error body. */
    private fun extractError(body: String): String? {
        val trimmed = body.trim()
        return if (trimmed.isNotEmpty() && !trimmed.startsWith("{")) trimmed else null
    }
}
