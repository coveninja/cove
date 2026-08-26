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

/**
 * What precedes an episode, or null when nothing does.
 *
 * The mirror of [nextEpisodeAfter], for the remote's skip-back button. Stepping back off the
 * front of a season lands on the *last* episode of the previous one, which is the only answer
 * that makes holding the button a way of walking backwards through a series; that needs the
 * previous season's `episodeCount`, so a title whose details never loaded stops at the season
 * boundary rather than guessing an episode number that may not exist.
 */
fun previousEpisodeBefore(
    seasons: List<MediaSeason>,
    season: Int,
    episode: Int,
): Pair<Int, Int>? {
    if (episode > 1) return season to episode - 1

    val previousSeason = seasons.map { it.number }.filter { it < season }.maxOrNull() ?: return null
    val count = seasons.firstOrNull { it.number == previousSeason }?.episodeCount ?: 0
    return if (count > 0) previousSeason to count else null
}
