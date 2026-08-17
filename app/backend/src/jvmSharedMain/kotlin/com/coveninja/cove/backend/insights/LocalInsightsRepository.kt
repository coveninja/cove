package com.coveninja.cove.backend.insights

import com.coveninja.cove.backend.activity.ActivityService
import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.discovery.DiscoveryService
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.trakt.TraktService
import com.coveninja.cove.shared.data.InsightsRepository
import com.coveninja.cove.shared.model.ActivityStats
import com.coveninja.cove.shared.model.DiscoveryInsights
import com.coveninja.cove.shared.model.InsightsRange
import com.coveninja.cove.shared.model.TraktStats
import com.coveninja.cove.shared.network.CoveJson
import java.time.Clock

/**
 * Serves the in-process aggregations to the UI, and keeps the expensive one on disk.
 *
 * Lives in `jvmSharedMain` because [ActivityService] does — both hosts compile that source
 * set, so desktop and Android get the same insights rather than the page being desktop-only.
 *
 * Every service is nullable. A host may assemble a graph without a taste engine (no TMDB
 * key, say) while still having watch-time counters, and half a page of real numbers beats
 * refusing to draw any.
 */
class LocalInsightsRepository(
    private val activity: ActivityService?,
    private val discovery: DiscoveryService?,
    private val database: CoveDatabase? = null,
    private val session: ActiveProfileSession? = null,
    private val trakt: TraktService? = null,
    private val clock: Clock = Clock.systemUTC(),
) : InsightsRepository {

    override suspend fun activity(range: InsightsRange): ActivityStats =
        activity?.stats(range) ?: ActivityStats()

    /**
     * The taste profile, from disk when it is still valid and from TMDB when it is not.
     *
     * Building it costs one metadata request per saved title, and `DiscoveryService` only
     * holds it in memory for five minutes — so without this the page paid that price on
     * every launch and the whole taste half arrived late every time. The cache is keyed on
     * a signature of the library rather than on time alone: a snapshot built before the
     * viewer rated three things is stale no matter how recent it is.
     *
     * Failures are swallowed on purpose. This is the one half of the page that can fail for
     * reasons that have nothing to do with the viewer — a dropped connection, a rate limit —
     * and the watch-time half is still worth showing when that happens. A stale cache is
     * preferred to nothing in that case.
     */
    override suspend fun taste(): DiscoveryInsights {
        val service = discovery ?: return DiscoveryInsights()
        val cached = readCache()
        if (cached != null && cached.signature == librarySignature() && cached.fresh) {
            return cached.insights
        }
        val rebuilt = runCatching { service.insights() }.getOrNull()
            ?: return cached?.insights ?: DiscoveryInsights()
        writeCache(rebuilt)
        return rebuilt
    }

    override suspend fun trakt(): TraktStats? = trakt?.let { runCatching { it.stats() }.getOrNull() }

    private fun readCache(): CachedInsights? {
        val queries = database?.coveQueries ?: return null
        val profileId = session?.profileId?.value ?: return null
        val row = queries.selectInsightsCache(profileId).executeAsOneOrNull() ?: return null
        // A row that will not parse is worthless, not fatal: drop it and let the rebuild
        // that follows replace it.
        val insights = runCatching {
            CoveJson.decodeFromString(DiscoveryInsights.serializer(), row.payload)
        }.getOrNull() ?: return null
        val age = runCatching {
            clock.instant().epochSecond - row.refreshed_at.toLong()
        }.getOrDefault(Long.MAX_VALUE)
        return CachedInsights(insights, row.signature, age < FRESHNESS_SECONDS)
    }

    private fun writeCache(insights: DiscoveryInsights) {
        val queries = database?.coveQueries ?: return
        val profileId = session?.profileId?.value ?: return
        queries.upsertInsightsCache(
            profile_id = profileId,
            payload = CoveJson.encodeToString(DiscoveryInsights.serializer(), insights),
            refreshed_at = clock.instant().epochSecond.toString(),
            signature = librarySignature(),
        )
    }

    /**
     * What the snapshot was built from, cheaply.
     *
     * The size of the library plus the newest change to it. Anything that alters the taste
     * profile — adding, removing, restatusing or rating a title — moves one or the other,
     * and neither costs a metadata request to compute.
     */
    private fun librarySignature(): String {
        val queries = database?.coveQueries ?: return ""
        val profileId = session?.profileId?.value ?: return ""
        val entries = queries.selectLibraryEntries(profileId).executeAsList()
        val newest = entries.maxOfOrNull { it.updated_at }.orEmpty()
        return "${entries.size}:$newest"
    }

    private data class CachedInsights(
        val insights: DiscoveryInsights,
        val signature: String,
        val fresh: Boolean,
    )

    private companion object {
        /**
         * A day. The signature already catches anything the viewer did; this only exists so
         * upstream metadata edits — a genre added to a film, a keyword corrected — reach an
         * otherwise unchanging library eventually.
         */
        const val FRESHNESS_SECONDS = 24L * 60L * 60L
    }
}
