package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.Addon
import com.coveninja.cove.shared.model.NuvioRepoSummary
import kotlinx.coroutines.flow.StateFlow

sealed interface AddonsState {
    data object Loading : AddonsState
    data class Ready(
        val addons: List<Addon>,
        val nuvioRepos: List<NuvioRepoSummary>,
    ) : AddonsState

    data class Failed(val message: String) : AddonsState
}

/**
 * Provider addons and Nuvio scraper repositories.
 *
 * Nothing is seeded: a fresh profile has no addons at all, which is why stream
 * resolution comes back empty until something here is added. Mutations report
 * failure through [lastError] rather than by throwing, since a bad manifest URL
 * is ordinary user input, not an exceptional condition.
 */
interface AddonRepository {
    val state: StateFlow<AddonsState>

    /** Set when a mutation fails, cleared when the next one is attempted. */
    val lastError: StateFlow<String?>

    suspend fun reload()

    suspend fun addAddon(url: String)
    suspend fun setAddonEnabled(id: String, enabled: Boolean)
    suspend fun removeAddon(id: String)
    suspend fun refreshAddon(id: String)

    suspend fun addNuvioRepo(url: String)
    suspend fun setNuvioRepoEnabled(id: String, enabled: Boolean)
    suspend fun removeNuvioRepo(id: String)
    suspend fun setNuvioScraperEnabled(repoId: String, scraperId: String, enabled: Boolean)
}
