package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.ActivityStats
import com.coveninja.cove.shared.model.DiscoveryInsights
import com.coveninja.cove.shared.model.InsightsRange
import com.coveninja.cove.shared.model.TraktStats
import com.coveninja.cove.shared.network.CoveApi

/**
 * Insights over the HTTP boundary, for `--api-base` against a remote or compatibility
 * host. Both in-process hosts use `LocalInsightsRepository` instead.
 *
 * Wrapped for the same reason as [LiveDiscoveryRepository]: a host that predates these
 * routes answers 404, and an absent section reads as "no history yet" — which is a state
 * the page already draws — rather than failing the whole screen.
 *
 * No response caching here. Both routes are a page load apart at worst, and a stale
 * streak count is more confusing than a second request.
 */
class LiveInsightsRepository(private val api: CoveApi) : InsightsRepository {

    override suspend fun activity(range: InsightsRange): ActivityStats =
        runCatching { api.activityStats(range) }.getOrDefault(ActivityStats())

    override suspend fun taste(): DiscoveryInsights =
        runCatching { api.discoverInsights() }.getOrDefault(DiscoveryInsights())

    override suspend fun trakt(): TraktStats? = runCatching { api.traktStats() }.getOrNull()
}
