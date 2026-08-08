package com.coveninja.cove.backend.auth

import com.coveninja.cove.backend.store.LocalProfileRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.Profile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class AuthService(
    private val client: SupabaseClient,
    private val sessions: AuthSessionStore,
    private val profiles: LocalProfileRepository,
    private val settings: LocalSettingsRepository,
    private val sync: SupabaseSyncService,
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

    suspend fun login(email: String, password: String): LoginResponse {
        require(email.isNotBlank() && password.isNotBlank()) { "email and password required" }
        val session = client.signIn(email.trim(), password)
        sync.reconcileAndSync(session.userId, session.accessToken)
        sessions.save(session)
        return loginResponse(session)
    }

    suspend fun sendOtp(email: String) {
        require(email.isNotBlank()) { "email required" }
        client.sendOtp(email.trim())
    }

    suspend fun verifyOtp(email: String, token: String): LoginResponse {
        require(email.isNotBlank() && token.isNotBlank()) { "email and token required" }
        val session = client.verifyOtp(email.trim(), token.trim())
        sync.reconcileAndSync(session.userId, session.accessToken)
        sessions.save(session)
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

    suspend fun logout() {
        sessions.clear()
        profiles.unlinkSupabase(activeProfile().id)
    }

    fun me(): AuthMeResponse {
        val profile = activeProfile()
        val stored = sessions.get()
        return AuthMeResponse(
            profile = profile,
            linked = profile.supabaseUid != null,
            authenticated = stored != null,
            email = stored?.email.orEmpty(),
        )
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

    private fun activeProfile(): Profile {
        val state = profiles.profiles.value as? ProfilesState.Ready
            ?: error("profiles are not ready")
        return state.profiles.first { it.id == state.activeProfileId }
    }
}

sealed interface RegistrationOutcome {
    data object ConfirmationRequired : RegistrationOutcome
    data class Complete(val session: AuthSessionResponse) : RegistrationOutcome
}

@Serializable
data class AuthSessionResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val profile: Profile,
)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    val profiles: List<Profile>,
    val active: Profile,
    @SerialName("onboarding_done") val onboardingDone: Boolean,
)

@Serializable
data class AuthMeResponse(
    val profile: Profile,
    val linked: Boolean,
    val authenticated: Boolean,
    val email: String,
)

private fun SupabaseSession.response(profile: Profile) = AuthSessionResponse(
    accessToken = accessToken,
    refreshToken = refreshToken,
    profile = profile,
)
