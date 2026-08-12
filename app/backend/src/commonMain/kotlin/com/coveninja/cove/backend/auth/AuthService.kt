package com.coveninja.cove.backend.auth

import com.coveninja.cove.backend.store.LocalProfileRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.Profile
import com.coveninja.cove.shared.network.AuthMeResponse
import com.coveninja.cove.shared.network.AuthSessionResponse
import com.coveninja.cove.shared.network.LoginResponse
import kotlin.time.Clock

class AuthService(
    private val client: SupabaseClient,
    private val sessions: AuthSessionStore,
    private val profiles: LocalProfileRepository,
    private val settings: LocalSettingsRepository,
    private val sync: SupabaseSyncService,
    private val epochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    suspend fun register(email: String, password: String, profileName: String): RegistrationOutcome {
        require(email.isNotBlank() && password.isNotBlank()) { "email and password required" }
        val result = client.signUp(email.trim(), password)
        val session = result.session ?: return RegistrationOutcome.ConfirmationRequired
        finishRegistration(session, profileName)
        return RegistrationOutcome.Complete(session.response(activeProfile()))
    }

    suspend fun confirmRegistration(
        email: String,
        token: String,
        password: String,
        profileName: String,
    ): AuthSessionResponse {
        require(email.isNotBlank() && token.isNotBlank() && password.isNotBlank()) {
            "email, token, and password required"
        }
        client.verifySignup(email.trim(), token.trim())
        val session = client.signIn(email.trim(), password)
        finishRegistration(session, profileName)
        return session.response(activeProfile())
    }

    /**
     * [runSync] exists for callers that report sync separately — the account
     * repository shows a sync failure in its own status line rather than as a
     * failed sign-in. The session is stored before syncing either way: a flaky
     * network must not throw away credentials that were accepted.
     */
    suspend fun login(email: String, password: String, runSync: Boolean = true): LoginResponse {
        require(email.isNotBlank() && password.isNotBlank()) { "email and password required" }
        val session = client.signIn(email.trim(), password)
        sessions.save(session)
        if (runSync) sync.reconcileAndSync(session.userId, session.accessToken)
        return loginResponse(session)
    }

    suspend fun sendOtp(email: String) {
        require(email.isNotBlank()) { "email required" }
        client.sendOtp(email.trim())
    }

    suspend fun verifyOtp(email: String, token: String, runSync: Boolean = true): LoginResponse {
        require(email.isNotBlank() && token.isNotBlank()) { "email and token required" }
        val session = client.verifyOtp(email.trim(), token.trim())
        sessions.save(session)
        if (runSync) sync.reconcileAndSync(session.userId, session.accessToken)
        return loginResponse(session)
    }

    suspend fun refresh(): LoginResponse {
        val current = sessions.get() ?: throw IllegalStateException("no stored auth session")
        require(current.refreshToken.isNotBlank()) { "stored auth session has no refresh token" }
        val refreshed = client.refresh(current.refreshToken)
        sessions.save(refreshed)
        return loginResponse(refreshed)
    }

    suspend fun synchronize(bearerToken: String): SyncResult {
        require(bearerToken.isNotBlank()) { "authorization required" }
        val user = client.user(bearerToken)
        val result = sync.reconcileAndSync(user.id, bearerToken)
        val current = sessions.get()
        if (current?.userId == user.id && current.accessToken != bearerToken) {
            sessions.save(current.copy(accessToken = bearerToken))
        }
        return result
    }

    /**
     * Syncs with the stored session, refreshing the access token first when it is
     * spent. Every other caller used to own that dance, and the periodic sync
     * would otherwise start failing an hour into a session.
     */
    suspend fun syncNow(): SyncResult {
        val stored = sessions.get() ?: throw IllegalStateException("not signed in")
        val session = if (isExpiring(stored)) {
            require(stored.refreshToken.isNotBlank()) { "stored auth session has no refresh token" }
            client.refresh(stored.refreshToken).also(sessions::save)
        } else {
            stored
        }
        return sync.reconcileAndSync(session.userId, session.accessToken)
    }

    suspend fun logout() {
        sessions.clear()
        activeProfileOrNull()?.let { profiles.unlinkSupabase(it.id) }
    }

    /**
     * Safe to call before the profile store has loaded: the account UI collects
     * this on composition, and throwing there would take the settings page down
     * rather than show a signed-out state for a moment.
     */
    fun me(): AuthMeResponse {
        val profile = activeProfileOrNull()
        val stored = sessions.get()
        return AuthMeResponse(
            profile = profile,
            linked = profile?.supabaseUid != null,
            authenticated = stored != null,
            email = stored?.email.orEmpty(),
            userId = stored?.userId.orEmpty(),
        )
    }

    // Treats a token that dies within the minute as already dead: a sync started
    // now would otherwise race its own expiry mid-request.
    private fun isExpiring(session: SupabaseSession): Boolean {
        val expiresAt = session.expiresAtEpochSeconds ?: return false
        return epochSeconds() + EXPIRY_MARGIN_SECONDS >= expiresAt
    }

    private suspend fun finishRegistration(session: SupabaseSession, profileName: String) {
        val profileId = activeProfile().id
        try {
            sync.registerProfile(session.userId, session.accessToken, profileName)
            sessions.save(session)
        } catch (error: Throwable) {
            runCatching { profiles.unlinkSupabase(profileId) }
            throw error
        }
    }

    private fun loginResponse(session: SupabaseSession): LoginResponse {
        val state = profiles.profiles.value as? ProfilesState.Ready
            ?: error("profiles are not ready")
        val settingsState = settings.settings.value as? SettingsState.Ready
        return LoginResponse(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            profiles = state.profiles,
            active = state.profiles.first { it.id == state.activeProfileId },
            onboardingDone = settingsState?.settings?.onboardingDone == true,
        )
    }

    private fun activeProfile(): Profile =
        activeProfileOrNull() ?: error("profiles are not ready")

    private fun activeProfileOrNull(): Profile? {
        val state = profiles.profiles.value as? ProfilesState.Ready ?: return null
        return state.profiles.firstOrNull { it.id == state.activeProfileId }
    }

    private companion object {
        const val EXPIRY_MARGIN_SECONDS = 60L
    }
}

sealed interface RegistrationOutcome {
    data object ConfirmationRequired : RegistrationOutcome
    data class Complete(val session: AuthSessionResponse) : RegistrationOutcome
}

private fun SupabaseSession.response(profile: Profile) = AuthSessionResponse(
    accessToken = accessToken,
    refreshToken = refreshToken,
    profile = profile,
)
