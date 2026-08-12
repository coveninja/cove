package com.coveninja.cove.backend.calendar

import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.db.Library_entries
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaDetails
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.TvSeason
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * Builds the profile release calendar directly from SQLite and TMDB.
 *
 * Dates come from kotlinx-datetime rather than `java.time` so this can live in
 * commonMain: Android needs the same calendar the desktop has, and the only thing that
 * kept this desktop-only was the date library.
 */
class CalendarService(
    private val database: CoveDatabase,
    private val session: ActiveProfileSession,
    private val catalog: MediaCatalog,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    suspend fun calendar(): List<CalendarItem> = coroutineScope {
        val profileId = session.profileId.value
        val today = clock.todayIn(timeZone)
        val cutoff = today.plus(CALENDAR_HORIZON_DAYS, DateTimeUnit.DAY)
        val completedMovies = mutableSetOf<Int>()
        val completedEpisodes = mutableMapOf<Int, MutableSet<Pair<Int, Int>>>()
        database.coveQueries.selectWatchProgress(profileId).executeAsList()
            .filter { it.completed != 0L }
            .forEach { progress ->
                when (progress.media_type) {
                    MediaType.Movie.wireName -> completedMovies += progress.tmdb_id.toInt()
                    MediaType.Tv.wireName -> {
                        val season = progress.season?.toInt() ?: return@forEach
                        val episode = progress.episode?.toInt() ?: return@forEach
                        if (season > 0 && episode > 0) {
                            completedEpisodes.getOrPut(progress.tmdb_id.toInt()) { mutableSetOf() } +=
                                season to episode
                        }
                    }
                }
            }

        val entries = database.coveQueries.selectLibraryEntries(profileId).executeAsList()
            .filter { it.status == LibraryStatus.Watching.wireName || it.status == LibraryStatus.WatchLater.wireName }
        val concurrency = Semaphore(6)
        entries.map { entry ->
            async {
                concurrency.withPermit {
                    runCatching {
                        if (entry.media_type == MediaType.Movie.wireName) {
                            movie(entry, today, cutoff, completedMovies)
                        } else {
                            tv(entry, today, cutoff, completedEpisodes[entry.tmdb_id.toInt()].orEmpty())
                        }
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten().sortedWith(
            compareBy<CalendarItem> { if (it.kind == "available") 0 else 1 }
                .thenComparator { left, right ->
                    if (left.kind == "available" && right.kind == "available") {
                        right.date.compareTo(left.date)
                    } else {
                        left.date.compareTo(right.date)
                    }
                }
                .thenBy { it.title.lowercase() },
        )
    }

    private suspend fun movie(
        entry: Library_entries,
        today: LocalDate,
        cutoff: LocalDate,
        completed: Set<Int>,
    ): List<CalendarItem> {
        val details = catalog.details(entry.tmdb_id.toInt(), MediaType.Movie)
        val date = details.releaseDate.toDateOrNull() ?: return emptyList()
        if (date > cutoff) return emptyList()
        if (date <= today && entry.status == LibraryStatus.WatchLater.wireName) return emptyList()
        if (date <= today && entry.tmdb_id.toInt() in completed) return emptyList()
        return listOf(
            CalendarItem(
                date = date.toString(),
                kind = if (date <= today) "available" else "movie",
                tmdbId = entry.tmdb_id.toInt(),
                mediaType = MediaType.Movie.wireName,
                title = details.displayTitle.ifBlank { entry.title },
                posterPath = details.posterPath.ifBlank { entry.poster_path },
            ),
        )
    }

    private suspend fun tv(
        entry: Library_entries,
        today: LocalDate,
        cutoff: LocalDate,
        completed: Set<Pair<Int, Int>>,
    ): List<CalendarItem> {
        val id = entry.tmdb_id.toInt()
        val details = catalog.details(id, MediaType.Tv)
        val seasons = details.seasons
            .filter { it.seasonNumber > 0 && it.episodeCount > 0 }
            .sortedBy(TvSeason::seasonNumber)
        val title = details.displayTitle.ifBlank { entry.title }
        val poster = details.posterPath.ifBlank { entry.poster_path }
        val result = mutableListOf<CalendarItem>()

        if (entry.status == LibraryStatus.Watching.wireName) {
            details.lastEpisodeToAir?.let { aired ->
                val backlog = airedBacklog(seasons, aired.seasonNumber, aired.episodeNumber, completed)
                if (backlog.waiting > 0) {
                    val episode = runCatching { catalog.episodes(id, backlog.season) }
                        .getOrDefault(emptyList())
                        .firstOrNull { it.episodeNumber == backlog.episode }
                    result += CalendarItem(
                        date = episode?.airDate?.takeIf(String::isNotBlank) ?: entry.last_air_date,
                        kind = "available",
                        tmdbId = id,
                        mediaType = MediaType.Tv.wireName,
                        title = title,
                        posterPath = poster,
                        seasonNumber = backlog.season,
                        episodeNumber = backlog.episode,
                        episodeName = episode?.name.orEmpty(),
                        stillPath = episode?.stillPath.orEmpty(),
                        waitingCount = backlog.waiting,
                    )
                }
            }
        }

        val next = details.nextEpisodeToAir
        if (next == null || next.seasonNumber <= 0 || next.episodeNumber <= 0) return result
        val nextDate = next.airDate.toDateOrNull() ?: return result
        if (nextDate <= today || nextDate > cutoff) return result

        if (entry.status == LibraryStatus.WatchLater.wireName) {
            result += CalendarItem(
                date = next.airDate,
                kind = "episode",
                tmdbId = id,
                mediaType = MediaType.Tv.wireName,
                title = title,
                posterPath = poster,
                seasonNumber = next.seasonNumber,
                episodeNumber = next.episodeNumber,
                episodeName = next.name,
                stillPath = next.stillPath,
            )
            return result
        }

        val upcoming = futureEpisodes(id, next.seasonNumber, today, cutoff, title, poster)
        result += upcoming
        if (upcoming.isNotEmpty() && seasons.any { it.seasonNumber == next.seasonNumber + 1 }) {
            result += futureEpisodes(id, next.seasonNumber + 1, today, cutoff, title, poster)
        }
        return result
    }

    private suspend fun futureEpisodes(
        id: Int,
        season: Int,
        today: LocalDate,
        cutoff: LocalDate,
        title: String,
        poster: String,
    ): List<CalendarItem> = catalog.episodes(id, season).mapNotNull { episode ->
        val date = episode.airDate?.toDateOrNull() ?: return@mapNotNull null
        if (episode.episodeNumber <= 0 || date <= today || date > cutoff) return@mapNotNull null
        CalendarItem(
            date = date.toString(),
            kind = "episode",
            tmdbId = id,
            mediaType = MediaType.Tv.wireName,
            title = title,
            posterPath = poster,
            seasonNumber = season,
            episodeNumber = episode.episodeNumber,
            episodeName = episode.name.orEmpty(),
            stillPath = episode.stillPath.orEmpty(),
        )
    }

    private fun airedBacklog(
        seasons: List<TvSeason>,
        airedSeason: Int,
        airedEpisode: Int,
        completed: Set<Pair<Int, Int>>,
    ): Backlog {
        var nextSeason = 0
        var nextEpisode = 0
        var waiting = 0
        seasons.takeWhile { it.seasonNumber <= airedSeason }.forEach { season ->
            val last = if (season.seasonNumber == airedSeason) {
                minOf(season.episodeCount, airedEpisode)
            } else {
                season.episodeCount
            }
            (1..last).forEach { episode ->
                if ((season.seasonNumber to episode) in completed) return@forEach
                if (waiting == 0) {
                    nextSeason = season.seasonNumber
                    nextEpisode = episode
                }
                waiting++
            }
        }
        return Backlog(nextSeason, nextEpisode, waiting)
    }

    private data class Backlog(val season: Int, val episode: Int, val waiting: Int)

    private fun String.toDateOrNull(): LocalDate? =
        takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private companion object {
        /** How far ahead the schedule reaches; beyond this TMDB dates are mostly guesses. */
        const val CALENDAR_HORIZON_DAYS = 90
    }
}
