package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.network.CoveApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LiveAddonRepository(
    private val api: CoveApi,
    scope: CoroutineScope,
) : AddonRepository {

    override val supportsNuvio: Boolean = true

    private val _state = MutableStateFlow<AddonsState>(AddonsState.Loading)
    override val state: StateFlow<AddonsState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        scope.launch { reload() }
    }

    override suspend fun reload() {
        try {
            // Nuvio is optional on the backend; a failure there must not blank out
            // the Stremio addon list, which is the part that makes playback work.
            val addons = api.addons()
            val repos = runCatching { api.nuvioRepos() }.getOrDefault(emptyList())
            _state.value = AddonsState.Ready(addons, repos)
        } catch (error: Exception) {
            _state.value = AddonsState.Failed(error.message ?: "Unknown error loading addons")
        }
    }

    override suspend fun addAddon(url: String) = mutate {
        api.addAddon(url.trim())
    }

    override suspend fun setAddonEnabled(id: String, enabled: Boolean) = mutate {
        api.setAddonEnabled(id, enabled)
    }

    override suspend fun removeAddon(id: String) = mutate {
        api.removeAddon(id)
    }

    override suspend fun refreshAddon(id: String) = mutate {
        api.refreshAddon(id)
    }

    override suspend fun addNuvioRepo(url: String) = mutate {
        api.addNuvioRepo(url.trim())
    }

    override suspend fun setNuvioRepoEnabled(id: String, enabled: Boolean) = mutate {
        api.setNuvioRepoEnabled(id, enabled)
    }

    override suspend fun removeNuvioRepo(id: String) = mutate {
        api.removeNuvioRepo(id)
    }

    override suspend fun setNuvioScraperEnabled(
        repoId: String,
        scraperId: String,
        enabled: Boolean,
    ) = mutate {
        api.setNuvioScraperEnabled(repoId, scraperId, enabled)
    }

    // Every mutation reloads: the backend derives kind and manifest on add, and
    // enabling one thing can change others, so echoing a local guess would drift.
    private suspend inline fun mutate(block: () -> Unit) {
        _lastError.value = null
        try {
            block()
            reload()
        } catch (error: Exception) {
            _lastError.value = error.message ?: "That did not work"
        }
    }
}

/** Stands in where no backend HTTP host is running — see [UnavailablePlaybackRepository]. */
object UnavailableAddonRepository : AddonRepository {
    private const val REASON = "addons are unavailable: the backend HTTP host is not running"

    override val state: StateFlow<AddonsState> = MutableStateFlow(AddonsState.Failed(REASON))
    override val lastError: StateFlow<String?> = MutableStateFlow(null)

    override suspend fun reload() = Unit
    override suspend fun addAddon(url: String) = throw IllegalStateException(REASON)
    override suspend fun setAddonEnabled(id: String, enabled: Boolean) = throw IllegalStateException(REASON)
    override suspend fun removeAddon(id: String) = throw IllegalStateException(REASON)
    override suspend fun refreshAddon(id: String) = throw IllegalStateException(REASON)
    override suspend fun addNuvioRepo(url: String) = throw IllegalStateException(REASON)
    override suspend fun setNuvioRepoEnabled(id: String, enabled: Boolean) = throw IllegalStateException(REASON)
    override suspend fun removeNuvioRepo(id: String) = throw IllegalStateException(REASON)
    override suspend fun setNuvioScraperEnabled(
        repoId: String,
        scraperId: String,
        enabled: Boolean,
    ) = throw IllegalStateException(REASON)
}
