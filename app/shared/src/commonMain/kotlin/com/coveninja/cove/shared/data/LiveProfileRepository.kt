package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.Profile
import com.coveninja.cove.shared.network.CoveApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Profiles over the HTTP boundary. Every mutation re-reads the list rather than
 * patching it locally: switching the active profile changes what every other
 * repository will answer, so the authoritative view has to come from the host.
 */
class LiveProfileRepository(
    private val api: CoveApi,
    private val scope: CoroutineScope,
) : ProfileRepository {
    private val _profiles = MutableStateFlow<ProfilesState>(ProfilesState.Loading)
    override val profiles: StateFlow<ProfilesState> = _profiles.asStateFlow()

    init {
        scope.launch { reload() }
    }

    override suspend fun create(name: String): Profile =
        api.createProfile(name).also { reload() }

    override suspend fun rename(id: String, name: String) {
        api.renameProfile(id, name)
        reload()
    }

    override suspend fun activate(id: String) {
        api.activateProfile(id)
        reload()
    }

    override suspend fun delete(id: String) {
        api.deleteProfile(id)
        reload()
    }

    private suspend fun reload() {
        _profiles.value = try {
            val response = api.profiles()
            ProfilesState.Ready(response.profiles, response.activeProfileId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            ProfilesState.Failed(error.message ?: "Could not load profiles.")
        }
    }
}
