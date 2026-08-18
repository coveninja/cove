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
        return loginResponse(renew(current))
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
     *
     * The expiry check alone is not enough, because it is arithmetic on the
     * *device's* clock: `expires_at` is recorded as "now plus expires_in" at
     * sign-in and compared against "now" here, so a machine whose clock is wrong
     * — or, more often, one that was suspended and woke up hours later with a
     * frozen clock, which is every emulator and every laptop lid — believes a
     * token is fresh long after Supabase has stopped accepting it. That is what
     * a permanent "Sync failed / JWT expired" is: not an expired session, just a
     * device that never noticed. Supabase's 401 is the authority, so it triggers
     * one refresh and one retry, which also covers a session stored without an
     * expiry at all and a token invalidated on the server.
     */
    suspend fun syncNow(): SyncResult {
        val stored = sessions.get() ?: throw IllegalStateException("not signed in")
        val session = if (isExpiring(stored)) renew(stored) else stored
        return try {
            sync.reconcileAndSync(session.userId, session.accessToken)
        } catch (rejected: SupabaseException) {
            if (rejected.statusCode != UNAUTHORIZED) throw rejected
            // Retried once, never in a loop: a refreshed token that is rejected
            // again is not a token problem, and a sync that keeps renewing
            // credentials to keep failing would hide whatever the real one is.
            val renewed = renew(session)
            sync.reconcileAndSync(renewed.userId, renewed.accessToken)
        }
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

    /**
     * Trades the stored refresh token for a fresh session, and saves it.
     *
     * A refusal here is separated from every other failure because it is the one
     * the user has to act on: a rejected refresh token cannot be recovered from
     * by waiting or retrying, and reporting Supabase's own "Invalid Refresh
     * Token: Refresh Token Not Found" would say nothing about what to do. The
     * session is deliberately left in place — Supabase rotates refresh tokens,
     * so two syncs overlapping can produce a spurious refusal, and signing the
     * device out on one of those would be a worse failure than the one it fixes.
     */
    private suspend fun renew(session: SupabaseSession): SupabaseSession {
        val refreshToken = session.refreshToken.takeIf(String::isNotBlank)
            ?: throw SessionExpiredException()
        return try {
            client.refresh(refreshToken).also(sessions::save)
        } catch (rejected: SupabaseException) {
            if (rejected.statusCode in REFRESH_REFUSED) {
                throw SessionExpiredException(rejected)
            }
            throw rejected
        }
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
        const val UNAUTHORIZED = 401

        /** Supabase answers a spent or unknown refresh token with either of these. */
        val REFRESH_REFUSED = setOf(400, 401)
    }
}

/**
 * The stored session can no longer be renewed, and only signing in again will fix it.
 *
 * Carries wording meant for the account page: everything else that goes wrong during a
 * sync is reported in Supabase's words, which is right for "Invalid login credentials"
 * and useless for a token the user never knew existed.
 */
class SessionExpiredException(cause: Throwable? = null) : RuntimeException(
    "Your Cove session has expired. Sign in again to resume syncing.",
    cause,
)

sealed interface RegistrationOutcome {
    data object ConfirmationRequired : RegistrationOutcome
    data class Complete(val session: AuthSessionResponse) : RegistrationOutcome
}

private fun SupabaseSession.response(profile: Profile) = AuthSessionResponse(
    accessToken = accessToken,
    refreshToken = refreshToken,
    profile = profile,
)
