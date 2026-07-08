package com.coveninja.cove.player

import android.util.Log
import com.coveninja.cove.api.CoveApiClient
import com.coveninja.cove.api.TvEpisode
import java.time.LocalDate

/**
 * Resolves the next *aired* episode after (season, episode).
 *
 * Mirrors web/src/lib/nextEpisode.ts:
 *   - Checks (episode+1) in the same season first.
 *   - Falls back to episode 1 of season+1 if no next in current season.
 *   - Returns null if the candidate hasn't aired yet (user is caught up or
 *     the next episode is in the future).
 *
 * Uses java.time.LocalDate (API 26+; our minSdk is 29).
 */
data class NextEp(
    val season: Int,
    val episodeNumber: Int,
    val name: String,
)

object NextEpisodeResolver {

    private const val TAG = "NextEpisodeResolver"

    suspend fun resolve(tmdbId: Int, season: Int, episode: Int): NextEp? {
        return try {
            // Try next episode in same season
            val sameSeason = fetchEpisodes(tmdbId, season)
            val next = sameSeason.find { it.episodeNumber == episode + 1 }
            if (next != null) {
                return if (hasAired(next)) NextEp(season, next.episodeNumber, next.name) else null
            }

            // Try first episode of next season
            val nextSeason = fetchEpisodes(tmdbId, season + 1)
            val first = nextSeason
                .filter { it.episodeNumber >= 1 }
                .minByOrNull { it.episodeNumber }
            if (first != null && hasAired(first)) {
                NextEp(season + 1, first.episodeNumber, first.name)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolve($tmdbId s$season e$episode) failed: ${e.message}")
            null
        }
    }

    private suspend fun fetchEpisodes(tmdbId: Int, season: Int): List<TvEpisode> =
        try {
            CoveApiClient.getOrNull<List<TvEpisode>>("/tv/episodes?id=$tmdbId&season=$season")
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    /** True if the episode's air_date is on or before today (local date). */
    private fun hasAired(ep: TvEpisode): Boolean {
        if (ep.airDate.isBlank()) return false
        return try {
            !LocalDate.parse(ep.airDate).isAfter(LocalDate.now())
        } catch (_: Exception) {
            false
        }
    }
}
