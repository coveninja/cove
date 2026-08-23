package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.Addon
import com.coveninja.cove.shared.model.NuvioRepoSummary
import kotlinx.coroutines.flow.StateFlow

/**
 * How the active profile relates to the household's shared addon list.
 *
 * The policy itself is an [com.coveninja.cove.shared.model.AppSettings] field on
 * the *primary* profile's row, which a secondary profile cannot read through its
 * own settings state. This is that resolved answer, so a profile can explain why
 * part of its addon list is locked.
 */
data class AddonSharing(
    /**
     * The policy is switched on. Read with [editable]: on the primary that means
     * it is sharing its addons out, and everywhere else that this profile's list
     * carries [Addon.managed] rows it did not install.
     */
    val enabled: Boolean = false,
    /** The active profile is the primary, and so is the one that sets the policy. */
    val editable: Boolean = false,
    val primaryName: String = "",
)

sealed interface AddonsState {
    data object Loading : AddonsState
    data class Ready(
        val addons: List<Addon>,
        val nuvioRepos: List<NuvioRepoSummary>,
        val sharing: AddonSharing = AddonSharing(),
    ) : AddonsState

    data class Failed(val message: String) : AddonsState
}

/**
 * Provider addons and Nuvio scraper repositories.
 *
 * Built-in metadata integrations may be seeded, but a fresh profile has no
 * third-party stream provider. Mutations report failure through [lastError]
 * rather than by throwing, since a bad manifest URL is ordinary user input,
 * not an exceptional condition.
 */
interface AddonRepository {
    val state: StateFlow<AddonsState>

    /** Set when a mutation fails, cleared when the next one is attempted. */
    val lastError: StateFlow<String?>

    /** Whether this host can install and execute Nuvio JavaScript scrapers. */
    val supportsNuvio: Boolean get() = false

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
