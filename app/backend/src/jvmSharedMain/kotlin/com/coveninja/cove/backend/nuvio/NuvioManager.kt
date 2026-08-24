package com.coveninja.cove.backend.nuvio

import com.coveninja.cove.backend.addons.AddonStream
import com.coveninja.cove.backend.addons.AddonUrlPolicy
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
    private val sandbox: NuvioSandbox,
    private val urlPolicy: AddonUrlPolicy,
) {
    private val mutation = Mutex()

    // Manager-wide, deliberately: a per-request limit let a playback request and a prefetch warm
    // each start their own set of child sandboxes, so the machine saw twice the concurrency either
    // of them asked for and every scraper missed its deadline.
    private val batchSlots = Semaphore(MAX_CONCURRENT_BATCHES)
    private val foregroundRuns = AtomicInteger()
    private val cache = ConcurrentHashMap<String, CachedStreams>()
    private val scraperCache = ConcurrentHashMap<String, CachedStreams>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<List<AddonStream>>>()

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
            // Dropping the source rather than keeping it against a possible re-enable:
            // the whole store is one row, and on Android a row has a hard size limit it
            // must not reach (see AndroidDatabase). Turning a scraper back on refetches
            // below, because that branch already treats blank code as "not fetched yet".
            !enabled -> scraper.copy(enabled = false, code = "", codeFetchedAt = null, codeErr = "")
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

    /**
     * @param background prefetch warming rather than someone waiting on the player. A whole
     *   fan-out is expensive enough that running one behind a live request only makes that
     *   request miss its deadline, so background work stands down while one is in flight.
     */
    suspend fun streams(
        mediaType: MediaType,
        tmdbId: Int,
        imdbId: String,
        title: String,
        year: Int,
        season: Int?,
        episode: Int?,
        background: Boolean = false,
    ): List<AddonStream> {
        val key = "${mediaType.wireName}|$tmdbId|${season ?: "-"}|${episode ?: "-"}"
        cache[key]?.takeIf { System.currentTimeMillis() < it.expiresAt }?.let { return it.streams }
        if (background && foregroundRuns.get() > 0) return emptyList()
        val attempt = CompletableDeferred<List<AddonStream>>()
        inFlight.putIfAbsent(key, attempt)?.let { return it.await() }
        if (!background) foregroundRuns.incrementAndGet()
        return try {
            // The previous attempt can finish between the first cache read and our
            // putIfAbsent. Recheck after becoming the owner before starting workers.
            val streams = cache[key]
                ?.takeIf { System.currentTimeMillis() < it.expiresAt }
                ?.streams
                ?: runScrapers(key, mediaType, tmdbId, imdbId, title, year, season, episode)
            attempt.complete(streams)
            streams
        } catch (error: Throwable) {
            attempt.completeExceptionally(error)
            throw error
        } finally {
            if (!background) foregroundRuns.decrementAndGet()
            inFlight.remove(key, attempt)
        }
    }

    private suspend fun runScrapers(
        key: String,
        mediaType: MediaType,
        tmdbId: Int,
        imdbId: String,
        title: String,
        year: Int,
        season: Int?,
        episode: Int?,
    ): List<AddonStream> {
        val enabled = load().repos.filter(NuvioRepo::enabled).flatMap { repo ->
            repo.scrapers.filter { scraper ->
                scraper.enabled && scraper.code.isNotBlank() &&
                    (scraper.supportedTypes.isEmpty() || mediaType.wireName in scraper.supportedTypes)
            }
        }
        if (enabled.isEmpty()) return emptyList()
        val startedAt = System.currentTimeMillis()

        // A scraper that already answered for this title is served from its own cache and never
        // reaches the sandbox. The aggregate cache below cannot do this job: it is written only
        // when every scraper agreed, so with a couple of dozen enabled the whole fan-out was
        // rerun on every play and the prefetch warm was discarded along with it.
        val cached = mutableMapOf<String, List<AddonStream>>()
        val pending = mutableListOf<NuvioScraper>()
        enabled.forEach { scraper ->
            val hit = scraperCache["${scraper.id}|$key"]
                ?.takeIf { System.currentTimeMillis() < it.expiresAt }
            if (hit != null) cached[scraper.id] = hit.streams else pending += scraper
        }

        val ran = ConcurrentHashMap<String, List<AddonStream>>()
        val failed = ConcurrentHashMap<String, String>()
        val outcomes = AtomicInteger()
        val timings = ConcurrentHashMap<String, Long>()
        var returnedEarly = false
        val allFinished = if (pending.isEmpty()) true else {
            // The batch runs under a job of our own rather than the caller's, because
            // coroutineScope cannot return until every child has, and a scraper wedged in
            // uninterruptible I/O then held the request open long past the budget — on Android
            // indefinitely, which is what left the player on "Finding sources" for good. Detached,
            // the deadline below is the only thing that decides when this returns, and outcomes
            // already reported are kept whatever the stragglers do.
            val batchJob = SupervisorJob()
            // Children here are deliberately abandoned at the deadline, so nothing is left to
            // observe what they throw on the way out. Without a handler of its own that reaches
            // the global one — which on Android takes the app down over a failed scraper.
            val batchFailures = CoroutineExceptionHandler { _, error ->
                logNuvio("scraper batch for $key ended with ${error.message ?: error::class.simpleName}")
            }
            val worker = CoroutineScope(
                currentCoroutineContext().minusKey(Job) + batchJob + batchFailures,
            )
            try {
                val job = worker.launch {
                    batchSlots.withPermit {
                        sandbox.runBatch(
                            NuvioBatch(
                                invocations = pending.map { scraper ->
                                    NuvioInvocation(
                                        scraper.id,
                                        scraper.code,
                                        tmdbId,
                                        mediaType.wireName,
                                        title,
                                        year,
                                        imdbId,
                                        season,
                                        episode,
                                    )
                                },
                                concurrency = SCRAPER_CONCURRENCY,
                                perScraperTimeoutMillis = PER_SCRAPER_CAP_MILLIS,
                            ),
                        ) { outcome ->
                            outcomes.incrementAndGet()
                            timings[outcome.scraperId] = outcome.elapsedMillis
                            if (outcome.error.isNotBlank()) {
                                failed[outcome.scraperId] = outcome.error
                            } else {
                                val streams = outcome.streams.mapNotNull(NuvioScrapedStream::toAddonStream)
                                ran[outcome.scraperId] = streams
                                scraperCache["${outcome.scraperId}|$key"] = CachedStreams(
                                    streams,
                                    System.currentTimeMillis() + SCRAPER_CACHE_MILLIS,
                                )
                            }
                        }
                    }
                }

                // Tear the scope down whichever way the batch ends: on its own completion, or at
                // the hard budget if a scraper is still going when we have long since answered.
                job.invokeOnCompletion { batchJob.cancel() }
                worker.launch {
                    delay(AGGREGATE_BUDGET_MILLIS)
                    batchJob.cancel()
                }

                // Wait for the results to go quiet rather than for the last scraper. The fan-out
                // is already parallel — a run where one scraper ran took 14 577 ms and a run
                // where all twenty-seven ran took 14 730 ms — so the whole wait was one straggler
                // and the per-scraper cache could never help while an uncached one held the
                // request. Counting polls rather than reading a clock keeps this on virtual time,
                // which is the only way runTest can exercise it.
                var lastSeen = -1
                var quietPolls = 0
                val completed = withTimeoutOrNull(AGGREGATE_BUDGET_MILLIS) {
                    while (job.isActive) {
                        val seen = outcomes.get()
                        quietPolls = if (seen == lastSeen) quietPolls + 1 else 0
                        lastSeen = seen
                        val enough = ran.values.sumOf { it.size } >= MIN_EARLY_RESULTS
                        if (quietPolls >= QUIET_POLLS && enough) {
                            returnedEarly = true
                            return@withTimeoutOrNull false
                        }
                        delay(QUIET_POLL_MILLIS)
                    }
                    true
                }
                // Only a batch that actually finished counts as complete. An early return leaves
                // the rest running on purpose: every outcome still writes its own cache entry, so
                // the stragglers land there for the next open instead of being thrown away.
                completed == true
            } finally {
                // Everything except a deliberate early return is cancelled here and now: a
                // completed batch (already torn down above), the hard budget, and a caller who
                // gave up on the player. Only the early return leaves the batch alive, because
                // cancelling it would discard the very results it returned early to collect.
                if (!returnedEarly) batchJob.cancel()
            }
        }

        // Ordered as the profile has them rather than as they happened to answer, so a given
        // library and a given set of scrapers always produce the same list.
        val streams = enabled.flatMap { scraper ->
            cached[scraper.id] ?: ran[scraper.id] ?: emptyList()
        }
        val answered = cached.size + ran.size + failed.size
        logNuvio(
            "$key in ${System.currentTimeMillis() - startedAt} ms — ${enabled.size} scraper(s): " +
                "${cached.size} cached, ${ran.size} ran, ${failed.size} failed, " +
                "${enabled.size - answered} still out" +
                when {
                    returnedEarly -> " (returned early, ${pending.size - ran.size - failed.size} " +
                        "still running into the cache)"
                    allFinished -> ""
                    else -> " (budget exhausted)"
                },
        )
        // Named rather than summarised: the wait is whatever the slowest one costs, so knowing
        // which they are is the whole basis for tuning any of the budgets above.
        timings.entries.sortedByDescending { it.value }.take(3).takeIf { it.isNotEmpty() }?.let {
            logNuvio("slowest for $key: " + it.joinToString { (id, ms) -> "$id ${ms}ms" })
        }
        failed.forEach { (scraperId, error) -> logNuvio("scraper $scraperId failed for $key: $error") }

        // A legitimate no-results response is cacheable. A runtime failure is not: caching
        // that as an empty result made a transient or compatibility error look like a healthy
        // provider response for fifteen minutes and hid recovery after the first request. A run
        // that ran out of budget is incomplete for the same reason, even though nothing failed.
        if (allFinished && failed.isEmpty() && answered == enabled.size) {
            cache[key] = CachedStreams(streams, System.currentTimeMillis() + SCRAPER_CACHE_MILLIS)
        }
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
        scraperCache.clear()
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

    private data class ScraperExecution(
        val scraperId: String,
        val streams: List<AddonStream>,
        val error: Throwable? = null,
    )

    /**
     * Deliberately not a CancellationException: a scraper that ran out of its slice is a failure
     * of that scraper, and must not read as the batch or the caller being cancelled.
     */
    private class ScraperTimeout(millis: Long) : RuntimeException("timed out after $millis ms")

    private companion object {
        /** What the whole fan-out may cost the viewer waiting on "Finding sources". */
        const val AGGREGATE_BUDGET_MILLIS = 20_000L

        /**
         * No single scraper may eat the whole budget. Several legitimately need more than ten
         * seconds of provider round-trips, and cutting them there lost their results while the
         * aggregate budget still had room; the worst case a viewer waits is unchanged either way.
         */
        const val PER_SCRAPER_CAP_MILLIS = 15_000L

        /** Scrapers in flight within one request. They are network-bound once started. */
        const val SCRAPER_CONCURRENCY = 12

        /** Requests that may each hold a sandbox worker at once. */
        const val MAX_CONCURRENT_BATCHES = 2

        const val SCRAPER_CACHE_MILLIS = 15 * 60_000L

        /** How often the wait checks whether answers are still arriving. */
        const val QUIET_POLL_MILLIS = 200L

        /** Consecutive quiet polls that mean the fan-out has stopped producing. */
        const val QUIET_POLLS = 6

        /** Never cut a run short before it has something worth offering. */
        const val MIN_EARLY_RESULTS = 3
    }
}

private fun logNuvio(message: String) = System.err.println("Cove Nuvio: $message")

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
