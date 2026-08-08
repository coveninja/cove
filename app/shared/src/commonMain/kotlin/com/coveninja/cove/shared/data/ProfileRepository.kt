package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.Profile
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
