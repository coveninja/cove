package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.AiredSeason
import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.WatchProgress

/**
 * The aired episodes of [seasons] the viewer has not finished, first to last.
 *
 * A backlog entry *is* this list: its length is the waiting count and its head is the
 * episode to play next. The arithmetic lives here rather than in the calendar builder so
 * that a snapshot cached hours ago can be re-counted with the same rule that built it.
 */
fun pendingEpisodes(
    seasons: List<AiredSeason>,
    completed: Set<Pair<Int, Int>>,
): List<Pair<Int, Int>> = seasons.flatMap { season ->
    (1..season.episodeCount).mapNotNull { episode ->
        (season.seasonNumber to episode).takeIf { it !in completed }
    }
}

/**
 * Re-counts every watchable-now entry in [items] against the current [progress].
 *
 * The snapshot is rebuilt from TMDB at one request per saved title, so it is cached for
 * hours — but the thing that dates it fastest is purely local: finishing an episode. Rather
 * than spend that fan-out whenever playback ends, entries carry the aired seasons they were
 * counted from and the count is redone here, so an episode leaves the backlog the moment it
 * is watched instead of when the calendar next refreshes.
 *
 * Two things it deliberately does not do. Entries from a snapshot built before
 * [CalendarItem.airedSeasons] existed cannot be re-counted and are passed through untouched;
 * the cache-schema bump that shipped the field means those only exist until the first
 * refresh. And un-marking an episode does not put it back, because it was never in the aired
 * set this entry was built from — that too waits for the refresh.
 */
fun applyWatchProgress(
    items: List<CalendarItem>,
    progress: List<WatchProgress>,
): List<CalendarItem> {
    if (items.isEmpty()) return items

    val completedMovies = mutableSetOf<Int>()
    val completedEpisodes = mutableMapOf<Int, MutableSet<Pair<Int, Int>>>()
    progress.asSequence().filter(WatchProgress::completed).forEach { row ->
        when (row.mediaType) {
            MediaType.Movie -> completedMovies += row.tmdbId
            MediaType.Tv -> {
                val season = row.season ?: return@forEach
                val episode = row.episode ?: return@forEach
                if (season > 0 && episode > 0) {
                    completedEpisodes.getOrPut(row.tmdbId) { mutableSetOf() } += season to episode
                }
            }
        }
    }

    return items.mapNotNull { item ->
        when {
            // Dated entries are about what has aired, not about what has been seen.
            !item.available -> item
            item.type == MediaType.Movie -> item.takeIf { it.tmdbId !in completedMovies }
            item.airedSeasons.isEmpty() -> item
            else -> item.recount(completedEpisodes[item.tmdbId].orEmpty())
        }
    }
}

/** Null once nothing is left waiting — the entry has been watched out of existence. */
private fun CalendarItem.recount(completed: Set<Pair<Int, Int>>): CalendarItem? {
    val pending = pendingEpisodes(airedSeasons, completed)
    val (season, episode) = pending.firstOrNull() ?: return null
    val sameHead = season == seasonNumber && episode == episodeNumber
    return copy(
        seasonNumber = season,
        episodeNumber = episode,
        // The name and still describe whichever episode the snapshot named. Once the head
        // moves past it they describe something already watched, and blank is better than
        // wrong: the card falls back to the poster and the season/episode marker.
        episodeName = if (sameHead) episodeName else "",
        stillPath = if (sameHead) stillPath else "",
        waitingCount = pending.size,
    )
}
