package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.Addon
import com.coveninja.cove.shared.model.AddonCatalogDescriptor
import com.coveninja.cove.shared.model.AddonCatalogPage
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

    /**
     * Every catalog the profile can currently draw, newest addon last.
     *
     * A *list* rather than a [StateFlow] because nothing watches it continuously: Home and
     * Explore ask once per load, and the addon mutations that could change it already
     * republish [state]. Hosts that cannot serve catalogs report none rather than failing,
     * which is what leaves a compatibility client with the rest of the app working.
     */
    suspend fun catalogs(): List<AddonCatalogDescriptor> = emptyList()

    /**
     * One page of [catalogId], resolved onto media this app can key on.
     *
     * Page with the returned [AddonCatalogPage.nextSkip] rather than by counting what came
     * back — entries that could not be resolved are dropped, so the two differ, and adding
     * the survivors would re-request the dropped ones forever. An empty [
     * AddonCatalogPage.medias] means the catalog is exhausted.
     */
    suspend fun catalogPage(
        addonId: String,
        type: String,
        catalogId: String,
        skip: Int = 0,
        limit: Int = 20,
    ): AddonCatalogPage = AddonCatalogPage()

    /**
     * Shows or hides one catalog, keyed as [AddonCatalogDescriptor.key].
     *
     * Only on an addon this profile owns. An inherited one resolves through the primary,
     * and writing to it would quietly give this profile a private copy of the primary's
     * addon — so the settings screen must not offer the switch there.
     */
    suspend fun setCatalogEnabled(addonId: String, catalogKey: String, enabled: Boolean) {}

    suspend fun addNuvioRepo(url: String)
    suspend fun setNuvioRepoEnabled(id: String, enabled: Boolean)
    suspend fun removeNuvioRepo(id: String)
    suspend fun setNuvioScraperEnabled(repoId: String, scraperId: String, enabled: Boolean)
}
