package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface ProfilesState {
    data object Loading : ProfilesState
    data class Ready(
        val profiles: List<Profile>,
        val activeProfileId: String,
    ) : ProfilesState
    data class Failed(val message: String) : ProfilesState
}

interface ProfileRepository {
    val profiles: StateFlow<ProfilesState>
    suspend fun create(name: String): Profile
    suspend fun rename(id: String, name: String)
    suspend fun activate(id: String)
    suspend fun delete(id: String)
}

/** Stands in where no profile store is reachable — see [UnavailablePlaybackRepository]. */
object UnavailableProfileRepository : ProfileRepository {
    private const val REASON = "profiles are unavailable: no profile store is reachable"

    override val profiles: StateFlow<ProfilesState> = MutableStateFlow(ProfilesState.Failed(REASON))

    override suspend fun create(name: String): Profile = throw IllegalStateException(REASON)
    override suspend fun rename(id: String, name: String) = throw IllegalStateException(REASON)
    override suspend fun activate(id: String) = throw IllegalStateException(REASON)
    override suspend fun delete(id: String) = throw IllegalStateException(REASON)
}
