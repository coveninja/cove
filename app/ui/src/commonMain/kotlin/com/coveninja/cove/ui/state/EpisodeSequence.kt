package com.coveninja.cove.ui.state

import com.coveninja.cove.ui.model.MediaSeason

/**
 * What follows an episode, or null when nothing does.
 *
 * Season lists come from the details payload and are the only record of how many
 * episodes a season holds, so a title opened without them cannot advance —
 * returning null is honest there, and better than guessing at an episode number
 * that may not exist and failing to resolve any source for it.
 */
fun nextEpisodeAfter(
    seasons: List<MediaSeason>,
    season: Int,
    episode: Int,
): Pair<Int, Int>? {
    val episodesInSeason = seasons.firstOrNull { it.number == season }?.episodeCount ?: 0
    if (episodesInSeason > episode) return season to episode + 1

    // Season numbers are not guaranteed contiguous — specials and gaps happen —
    // so the next season is the smallest one above this, not this plus one.
    val nextSeason = seasons.map { it.number }.filter { it > season }.minOrNull()
    return nextSeason?.let { it to 1 }
}
