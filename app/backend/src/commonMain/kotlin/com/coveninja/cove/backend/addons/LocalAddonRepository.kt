package com.coveninja.cove.backend.addons

import com.coveninja.cove.shared.data.AddonRepository
import com.coveninja.cove.shared.data.AddonsState
import com.coveninja.cove.shared.model.Addon
import com.coveninja.cove.shared.model.AddonKind as SharedAddonKind
import com.coveninja.cove.shared.model.AddonManifestSummary
import com.coveninja.cove.shared.model.NuvioRepoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-process adapter from the backend's persistent addon services to the shared UI.
 *
 * Addon CRUD has no URL-producing boundary or request-local registry, so making an
 * embedded host call its own HTTP routes only adds a failure mode. Playback remains
 * behind HTTP because the player needs the media proxy and stream registry there.
 */
internal class LocalAddonRepository(
    private val addons: AddonManager,
    activeProfileIds: StateFlow<String>,
    scope: CoroutineScope,
    private val nuvio: LocalNuvioService? = null,
) : AddonRepository {
    private val operation = Mutex()
    private val _state = MutableStateFlow<AddonsState>(AddonsState.Loading)
    private val _lastError = MutableStateFlow<String?>(null)

    override val state: StateFlow<AddonsState> = _state.asStateFlow()
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()
    override val supportsNuvio: Boolean = nuvio != null

    init {
        // ActiveProfileSession is the source of truth for every local store. A profile
        // change must repaint this list just as it does library and settings state.
        scope.launch {
            activeProfileIds.collectLatest { reload() }
        }
    }

    override suspend fun reload() = operation.withLock {
        reloadLocked()
    }

    override suspend fun addAddon(url: String) = mutate {
        addons.add(url.trim())
    }

    override suspend fun setAddonEnabled(id: String, enabled: Boolean) = mutate {
        addons.setEnabled(id, null, enabled)
    }

    override suspend fun removeAddon(id: String) = mutate {
        addons.remove(id, null)
    }

    override suspend fun refreshAddon(id: String) = mutate {
        addons.refresh(id, null)
    }

    override suspend fun addNuvioRepo(url: String) = mutate {
        requireNuvio().addRepo(url.trim())
    }

    override suspend fun setNuvioRepoEnabled(id: String, enabled: Boolean) = mutate {
        requireNuvio().setRepoEnabled(id, enabled)
    }

    override suspend fun removeNuvioRepo(id: String) = mutate {
        requireNuvio().removeRepo(id)
    }

    override suspend fun setNuvioScraperEnabled(
        repoId: String,
        scraperId: String,
        enabled: Boolean,
    ) = mutate {
        requireNuvio().setScraperEnabled(repoId, scraperId, enabled)
    }

    private suspend inline fun mutate(crossinline mutation: suspend () -> Unit) {
        _lastError.value = null
        operation.withLock {
            try {
                mutation()
                reloadLocked()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _lastError.value = error.message ?: "That did not work"
            }
        }
    }

    private suspend fun reloadLocked() {
        _state.value = try {
            val entries = addons.entries().map(AddonEntry::toSharedModel)
            val repos = try {
                nuvio?.repos().orEmpty()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
            AddonsState.Ready(entries, repos)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AddonsState.Failed(error.message ?: "Unknown error loading addons")
        }
    }

    private fun requireNuvio(): LocalNuvioService = requireNotNull(nuvio) {
        "Nuvio scrapers are unavailable on this platform"
    }
}

/** Platform adapter for the desktop-only Nuvio sandbox and persistent store. */
internal interface LocalNuvioService {
    suspend fun repos(): List<NuvioRepoSummary>
    suspend fun addRepo(url: String)
    suspend fun setRepoEnabled(id: String, enabled: Boolean)
    suspend fun removeRepo(id: String)
    suspend fun setScraperEnabled(repoId: String, scraperId: String, enabled: Boolean)
}

private fun AddonEntry.toSharedModel() = Addon(
    id = id,
    url = url,
    manifest = AddonManifestSummary(
        id = manifest.id,
        name = manifest.name,
        description = manifest.description,
        version = manifest.version,
        types = manifest.types,
    ),
    kind = when (kind) {
        AddonKind.Provider -> SharedAddonKind.Provider
        AddonKind.Subtitle -> SharedAddonKind.Subtitle
        AddonKind.Timestamps -> SharedAddonKind.Timestamps
    },
    source = source,
    enabled = enabled,
)
