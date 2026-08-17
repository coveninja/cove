package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.ActivityStats
import com.coveninja.cove.shared.model.DiscoveryInsights
import com.coveninja.cove.shared.model.InsightsRange
import com.coveninja.cove.shared.model.TraktStats

/**
 * What the viewer's own history says about them, for the insights page.
 *
 * The two halves are one interface because one screen shows both, but they cost wildly
 * different amounts and callers need to know which is which:
 *
 * - [activity] is **cheap and local** — it reads pre-aggregated counters straight out of
 *   SQLite and touches no network. Safe to call on first paint.
 * - [taste] is **expensive on a cold cache** — behind it is a profile that costs one
 *   metadata request per saved title. Load it separately and let it fill in, rather than
 *   holding the whole page on it.
 *
 * Both return empty rather than throwing when there is nothing to say. A profile that has
 * watched nothing is an ordinary state, and the page is built to render around it — an
 * exception here would turn "you are new" into "something broke".
 */
interface InsightsRepository {
    /**
     * Watch-time counters: totals, streaks, and the by-month/day/hour breakdowns.
     *
     * [range] narrows the figures that vary by period. See [InsightsRange] for which ones
     * deliberately stay all-time regardless.
     */
    suspend fun activity(range: InsightsRange = InsightsRange.AllTime): ActivityStats

    /** The taste profile: genres, keywords, people, studios, and what shaped them. */
    suspend fun taste(): DiscoveryInsights

    /**
     * All-time totals from a linked Trakt account, or null when there is no account, no
     * network, or nothing recorded against it.
     *
     * Null rather than an empty value because the difference matters to the caller: an
     * empty [ActivityStats] is still worth a page, whereas no Trakt is a section that
     * should not appear at all.
     */
    suspend fun trakt(): TraktStats? = null
}

/**
 * Stands in where nothing aggregates history — see [UnavailableDiscoveryRepository].
 *
 * Like discovery and unlike playback, this one does not throw: the insights page is
 * expected to render its own empty state, and an empty answer is indistinguishable from a
 * profile that simply has no history yet. That is the honest response, and it is one the
 * page already knows how to draw.
 */
object UnavailableInsightsRepository : InsightsRepository {
    override suspend fun activity(range: InsightsRange): ActivityStats = ActivityStats()
    override suspend fun taste(): DiscoveryInsights = DiscoveryInsights()
    override suspend fun trakt(): TraktStats? = null
}
