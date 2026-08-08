package com.coveninja.cove.backend.nuvio

import com.coveninja.cove.backend.addons.AddonStream
import com.coveninja.cove.backend.addons.AddonUrlPolicy
import com.coveninja.cove.backend.addons.DesktopAddonUrlPolicy
import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

class NuvioManager internal constructor(
    private val database: CoveDatabase,
    private val session: ActiveProfileSession,
    private val httpClient: HttpClient,
    private val now: () -> String,
    private val sandbox: NuvioSandbox = ProcessNuvioSandbox(),
    private val urlPolicy: AddonUrlPolicy = DesktopAddonUrlPolicy,
) {
    private val mutation = Mutex()
    private val cache = ConcurrentHashMap<String, CachedStreams>()

    suspend fun repos(): List<NuvioRepo> = load().repos

    suspend fun add(rawUrl: String): NuvioRepo = mutation.withLock {
        val location = parseLocation(rawUrl)
        val (branch, manifest) = resolveManifest(location)
        val timestamp = now()
        val repo = NuvioRepo(
            id = "${location.owner}/${location.repo}",
            owner = location.owner,
            repo = location.repo,
            branch = branch,
            url = rawUrl.trim(),
            enabled = false,
            scrapers = parseManifest(manifest).map { it.toScraper() },
            fetchedAt = timestamp,
        )
        val current = load()
        persist(current.copy(
            repos = current.repos.filterNot { it.id == repo.id } + repo,
            updatedAt = timestamp,
        ))
        repo
    }

    suspend fun remove(id: String) = mutation.withLock {
        val current = load()
        require(current.repos.any { it.id == id }) { "repo not found" }
        persist(current.copy(repos = current.repos.filterNot { it.id == id }, updatedAt = now()))
    }

    suspend fun setRepoEnabled(id: String, enabled: Boolean) = mutation.withLock {
        val current = load()
        require(current.repos.any { it.id == id }) { "repo not found" }
        persist(current.copy(
            repos = current.repos.map { if (it.id == id) it.copy(enabled = enabled) else it },
            updatedAt = now(),
        ))
    }

    suspend fun setScraperEnabled(repoId: String, scraperId: String, enabled: Boolean) = mutation.withLock {
        val current = load()
        val repo = current.repos.firstOrNull { it.id == repoId } ?: error("repo not found")
        val scraper = repo.scrapers.firstOrNull { it.id == scraperId } ?: error("scraper not found")
        val updatedScraper = when {
            !enabled -> scraper.copy(enabled = false)
            scraper.code.isNotBlank() && scraper.codeErr.isBlank() -> scraper.copy(enabled = true)
            else -> runCatching {
                val code = fetchRaw(repo.owner, repo.repo, repo.branch, scraper.filename)
                scraper.copy(enabled = true, code = code, codeFetchedAt = now(), codeErr = "")
            }.getOrElse { error ->
                val failed = scraper.copy(enabled = false, codeErr = error.message ?: "could not fetch scraper")
                persist(current.copy(
                    repos = current.repos.replaceScraper(repoId, failed),
                    updatedAt = now(),
                ))
                throw IllegalArgumentException("could not fetch scraper code: ${failed.codeErr}", error)
            }
        }
        persist(current.copy(
            repos = current.repos.replaceScraper(repoId, updatedScraper),
            updatedAt = now(),
        ))
    }

    suspend fun refresh(id: String) = mutation.withLock {
        val current = load()
        val existing = current.repos.firstOrNull { it.id == id } ?: error("repo not found")
        val location = parseLocation(existing.url)
        val (branch, manifest) = resolveManifest(location.copy(branch = existing.branch))
        val previous = existing.scrapers.associateBy(NuvioScraper::id)
        val refreshed = parseManifest(manifest).map { manifestEntry ->
            val old = previous[manifestEntry.id]
            var next = manifestEntry.toScraper(old)
            if (old?.enabled == true) {
                next = runCatching {
                    next.copy(
                        enabled = true,
                        code = fetchRaw(existing.owner, existing.repo, branch, next.filename),
                        codeFetchedAt = now(),
                        codeErr = "",
                    )
                }.getOrElse { next.copy(enabled = false, code = "", codeErr = it.message.orEmpty()) }
            }
            next
        }
        persist(current.copy(
            repos = current.repos.map {
                if (it.id == id) it.copy(
                    branch = branch,
                    scrapers = refreshed,
                    fetchedAt = now(),
                    fetchErr = "",
                ) else it
            },
            updatedAt = now(),
        ))
    }

    suspend fun streams(
        mediaType: MediaType,
        tmdbId: Int,
        imdbId: String,
        title: String,
        year: Int,
        season: Int?,
        episode: Int?,
    ): List<AddonStream> {
        val key = "${mediaType.wireName}|$tmdbId|${season ?: "-"}|${episode ?: "-"}"
        cache[key]?.takeIf { System.currentTimeMillis() < it.expiresAt }?.let { return it.streams }
        val enabled = load().repos.filter(NuvioRepo::enabled).flatMap { repo ->
            repo.scrapers.filter { scraper ->
                scraper.enabled && scraper.code.isNotBlank() &&
                    (scraper.supportedTypes.isEmpty() || mediaType.wireName in scraper.supportedTypes)
            }
        }
        if (enabled.isEmpty()) return emptyList()
        val semaphore = Semaphore(12)
        val streams = withTimeoutOrNull(25_000) {
            coroutineScope {
                enabled.map { scraper ->
                    async {
                        semaphore.withPermit {
                            runCatching {
                                sandbox.run(NuvioInvocation(
                                    scraper.id,
                                    scraper.code,
                                    tmdbId,
                                    mediaType.wireName,
                                    title,
                                    year,
                                    imdbId,
                                    season,
                                    episode,
                                )).mapNotNull(NuvioScrapedStream::toAddonStream)
                            }.getOrDefault(emptyList())
                        }
                    }
                }.awaitAll().flatten()
            }
        }.orEmpty()
        cache[key] = CachedStreams(streams, System.currentTimeMillis() + 15 * 60_000)
        return streams
    }

    internal fun snapshotForSync(): NuvioStore = load()

    internal fun mergeFromRemote(json: String, updatedAt: String) {
        val local = load()
        if (updatedAt <= local.updatedAt) return
        val remote = CoveJson.decodeFromString<NuvioStore>(json)
        persist(remote.copy(updatedAt = updatedAt))
    }

    private fun load(): NuvioStore {
        val profileId = session.profileId.value
        database.coveQueries.selectNuvioState(profileId).executeAsOneOrNull()?.let {
            return CoveJson.decodeFromString(it.json)
        }
        val legacy = database.coveQueries.selectLegacyPayloadRecord(profileId, "nuvio").executeAsOneOrNull()
        val store = legacy?.let { CoveJson.decodeFromString<NuvioStore>(it.json) } ?: NuvioStore()
        database.coveQueries.upsertNuvioState(
            profileId,
            CoveJson.encodeToString(store),
            store.updatedAt.ifBlank { legacy?.updated_at.orEmpty() },
        )
        return store
    }

    private fun persist(store: NuvioStore) {
        database.coveQueries.upsertNuvioState(
            session.profileId.value,
            CoveJson.encodeToString(store),
            store.updatedAt,
        )
        cache.clear()
    }

    private suspend fun resolveManifest(location: RepoLocation): Pair<String, String> {
        if (location.manifestPath != null) {
            return location.branch.ifBlank { "main" } to fetchRaw(
                location.owner,
                location.repo,
                location.branch.ifBlank { "main" },
                location.manifestPath,
            )
        }
        val branches = location.branch.takeIf(String::isNotBlank)?.let(::listOf) ?: listOf("main", "master")
        var lastError: Throwable? = null
        branches.forEach { branch ->
            runCatching { fetchRaw(location.owner, location.repo, branch, "manifest.json") }
                .onSuccess { return branch to it }
                .onFailure { lastError = it }
        }
        throw IllegalArgumentException("could not fetch manifest.json", lastError)
    }

    private suspend fun fetchRaw(owner: String, repo: String, branch: String, path: String): String {
        require(owner.matches(GITHUB_PART) && repo.matches(GITHUB_PART) && branch.matches(GITHUB_BRANCH)) {
            "invalid GitHub repository path"
        }
        require(path.split('/').all { it.matches(GITHUB_PART) }) { "invalid scraper path" }
        val url = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        urlPolicy.validate(url)
        val response = httpClient.get(url) { header(HttpHeaders.UserAgent, "Cove Kotlin Backend") }
        require(response.status.isSuccess()) { "HTTP ${response.status.value} fetching $path" }
        val body = response.bodyAsText()
        require(body.encodeToByteArray().size <= 20 * 1024 * 1024) { "response exceeds 20 MiB" }
        return body
    }

    private fun parseManifest(json: String): List<NuvioManifestEntry> = when (
        val root = CoveJson.parseToJsonElement(json)
    ) {
        is JsonArray -> root.map { CoveJson.decodeFromJsonElement<NuvioManifestEntry>(it) }
        is JsonObject -> (root["scrapers"] ?: root["providers"])
            ?.let { it as? JsonArray }
            ?.map { CoveJson.decodeFromJsonElement<NuvioManifestEntry>(it) }
            .orEmpty()
        else -> emptyList<NuvioManifestEntry>()
    }.also { entries ->
        require(entries.all { it.id.isNotBlank() && it.filename.isNotBlank() }) {
            "manifest contains an invalid scraper"
        }
    }

    private fun parseLocation(raw: String): RepoLocation {
        val value = raw.trim()
        RAW_GITHUB.matchEntire(value)?.let { match ->
            return RepoLocation(
                match.groupValues[1],
                match.groupValues[2],
                match.groupValues[3],
                match.groupValues[4],
            )
        }
        GITHUB.matchEntire(value)?.let { match ->
            return RepoLocation(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
        throw IllegalArgumentException(
            "not a github.com/owner/repo URL or raw.githubusercontent.com manifest link",
        )
    }

    private data class RepoLocation(
        val owner: String,
        val repo: String,
        val branch: String = "",
        val manifestPath: String? = null,
    )

    private data class CachedStreams(val streams: List<AddonStream>, val expiresAt: Long)
}

private fun List<NuvioRepo>.replaceScraper(repoId: String, scraper: NuvioScraper): List<NuvioRepo> = map { repo ->
    if (repo.id != repoId) repo else repo.copy(
        scrapers = repo.scrapers.map { if (it.id == scraper.id) scraper else it },
    )
}

private val GITHUB = Regex(
    "^(?:https?://)?(?:www\\.)?github\\.com/([^/]+)/([^/]+?)(?:\\.git)?(?:/tree/([^/]+))?/?$",
)
private val RAW_GITHUB = Regex(
    "^(?:https?://)?raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.+)$",
)
private val GITHUB_PART = Regex("[A-Za-z0-9._-]+")
private val GITHUB_BRANCH = Regex("[A-Za-z0-9._/-]+")
