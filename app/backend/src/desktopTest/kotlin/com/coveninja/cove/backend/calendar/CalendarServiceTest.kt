package com.coveninja.cove.backend.calendar

import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.shared.model.AiredEpisode
import com.coveninja.cove.shared.model.AiredSeason
import com.coveninja.cove.shared.model.CatalogSort
import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaDetails
import com.coveninja.cove.shared.model.MediaGenre
import com.coveninja.cove.shared.model.MediaImages
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.MediaVideos
import com.coveninja.cove.shared.model.PersonDetails
import com.coveninja.cove.shared.model.TvEpisode
import com.coveninja.cove.shared.model.TvSeason
import com.coveninja.cove.shared.model.UpcomingEpisode
import com.coveninja.cove.shared.network.SearchResultsDto
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/** kotlin.time has no fixed-clock factory, and the schedule has to be tested at a known day. */
private class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}

class CalendarServiceTest {
    @Test
    fun calendarUsesCompletedEpisodesAndSortsAvailableBeforeUpcoming() = runTest {
        DesktopDatabase.inMemory().use { handle ->
            val q = handle.database.coveQueries
            q.insertProfile("p1", "Primary", 1, null, "")
            q.setActiveProfile("p1")
            q.upsertLibraryEntry(
                "movie-entry", "p1", 1, "movie", "Fallback movie", "/old.jpg", "watching",
                null, 0.0, "", null, null, null, null, null, "now", "now",
            )
            q.upsertLibraryEntry(
                "tv-entry", "p1", 2, "tv", "Fallback show", "/old-tv.jpg", "watching",
                null, 0.0, "2026-08-07", null, null, null, null, null, "now", "now",
            )
            q.upsertWatchProgress(
                "2:tv:1:1", "p", "p1", "tv-entry", 2, "tv", 1, 1,
                1.0, 1.0, 1, "2026-08-01T00:00:00Z",
            )
            val service = CalendarService(
                handle.database,
                ActiveProfileSession(handle.database),
                FakeCalendarCatalog(),
                FixedClock(Instant.parse("2026-08-08T12:00:00Z")),
                TimeZone.UTC,
            )

            val items = service.calendar()

            assertEquals(listOf("available", "available", "episode", "episode"), items.map { it.kind })
            assertEquals("Localized movie", items[0].title)
            val backlog = items.first { it.tmdbId == 2 && it.kind == "available" }
            assertEquals(1, backlog.seasonNumber)
            assertEquals(2, backlog.episodeNumber)
            assertEquals(2, backlog.waitingCount)
            // The five-episode season is counted only as far as the third episode, which is
            // what has aired. Travelling with the entry is what lets the count be redone
            // later against watch progress without asking TMDB again.
            assertEquals(listOf(AiredSeason(1, 3)), backlog.airedSeasons)
            assertEquals(listOf("2026-08-10", "2026-08-17"), items.filter { it.kind == "episode" }.map { it.date })
        }
    }
}

private class FakeCalendarCatalog : MediaCatalog {
    override suspend fun details(id: Int, type: MediaType): MediaDetails = when (type) {
        MediaType.Movie -> MediaDetails(
            title = "Localized movie",
            posterPath = "/movie.jpg",
            releaseDate = "2026-08-06",
        )
        MediaType.Tv -> MediaDetails(
            name = "Localized show",
            posterPath = "/show.jpg",
            seasons = listOf(TvSeason(1, 5, "Season 1")),
            lastEpisodeToAir = AiredEpisode(1, 3, "2026-08-07"),
            nextEpisodeToAir = UpcomingEpisode("Fourth", 1, 4, "2026-08-10", "/4.jpg"),
        )
    }

    override suspend fun episodes(id: Int, season: Int): List<TvEpisode> = listOf(
        TvEpisode(1, "One", airDate = "2026-07-20"),
        TvEpisode(2, "Two", stillPath = "/2.jpg", airDate = "2026-07-27"),
        TvEpisode(3, "Three", airDate = "2026-08-07"),
        TvEpisode(4, "Four", airDate = "2026-08-10"),
        TvEpisode(5, "Five", airDate = "2026-08-17"),
    )

    override suspend fun discover(type: MediaType, limit: Int) = emptyList<Media>()
    override suspend fun searchMulti(query: String) = SearchResultsDto()
    override suspend fun media(id: Int, type: MediaType) = Media(id)
    override suspend fun images(id: Int, type: MediaType) = MediaImages()
    override suspend fun videos(id: Int, type: MediaType) = MediaVideos()
    override suspend fun similar(id: Int, type: MediaType) = emptyList<Media>()
    override suspend fun seasons(id: Int) = emptyList<TvSeason>()
    override suspend fun imdbId(id: Int, type: MediaType) = "tt$id"
    override suspend fun person(id: Int) = PersonDetails(id = id)
    override suspend fun genres(type: MediaType) = emptyList<MediaGenre>()
    override suspend fun discoverFiltered(
        type: MediaType,
        genreId: Int?,
        keywordId: Int?,
        personId: Int?,
        sort: CatalogSort,
        page: Int,
    ) = emptyList<Media>()
}
