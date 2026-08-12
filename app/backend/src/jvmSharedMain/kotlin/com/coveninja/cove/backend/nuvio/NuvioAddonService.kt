package com.coveninja.cove.backend.nuvio

import com.coveninja.cove.backend.addons.LocalNuvioService
import com.coveninja.cove.shared.model.NuvioRepoSummary
import com.coveninja.cove.shared.model.NuvioScraperSummary

/** Makes the platform Nuvio store available to the in-process addon repository. */
internal class NuvioAddonService(
    private val manager: NuvioManager,
) : LocalNuvioService {
    override suspend fun repos(): List<NuvioRepoSummary> = manager.repos().map { it.toSummary() }

    override suspend fun addRepo(url: String) {
        manager.add(url)
    }

    override suspend fun setRepoEnabled(id: String, enabled: Boolean) {
        manager.setRepoEnabled(id, enabled)
    }

    override suspend fun removeRepo(id: String) {
        manager.remove(id)
    }

    override suspend fun setScraperEnabled(repoId: String, scraperId: String, enabled: Boolean) {
        manager.setScraperEnabled(repoId, scraperId, enabled)
    }
}

private fun NuvioRepo.toSummary() = NuvioRepoSummary(
    id = id,
    owner = owner,
    repo = repo,
    branch = branch,
    url = url,
    enabled = enabled,
    scrapers = scrapers.map { scraper ->
        NuvioScraperSummary(
            id = scraper.id,
            name = scraper.name,
            description = scraper.description,
            version = scraper.version,
            enabled = scraper.enabled,
            codeError = scraper.codeErr,
        )
    },
    fetchError = fetchErr,
)
