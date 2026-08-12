package com.coveninja.cove.ui.pages.mylist

import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.shared.model.MediaType as DomainMediaType
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MyListModelTest {

    private fun row(
        title: String,
        category: MyListCategory = MyListCategory.Watching,
        type: MediaType = MediaType.Movie,
        addedAt: String = "2026-01-01T00:00:00Z",
        lastWatchedAt: String? = null,
        updatedAt: String = "2026-01-01T00:00:00Z",
        rating: Double? = null,
        released: String? = null,
        firstAirDate: String? = null,
        lastWatchedSeason: Int? = null,
        lastWatchedEpisode: Int? = null,
        watchFraction: Float? = null,
        progress: WatchProgress? = null,
        hasNewEpisodes: Boolean = false,
    ): MyListRow {
        val tmdbId = title.hashCode() and 0x7fffffff
        return MyListRow(
            media = Media(
                id = "${type.name}:$tmdbId",
                tmdbId = tmdbId,
                title = title,
                name = title,
                overview = null,
                released = released,
                firstAirDate = firstAirDate,
                posterUrl = null,
                logoUrl = null,
                backdropUrl = null,
                rating = null,
                type = type,
                popularity = null,
                adult = null,
                originalLanguage = null,
            ),
            entry = LibraryEntry(
                id = "entry-$tmdbId",
                tmdbId = tmdbId,
                mediaType = if (type == MediaType.Movie) {
                    DomainMediaType.Movie
                } else {
                    DomainMediaType.Tv
                },
                title = title,
                status = LibraryStatus.Watching,
                rating = rating,
                lastWatchedAt = lastWatchedAt,
                lastWatchedSeason = lastWatchedSeason,
                lastWatchedEpisode = lastWatchedEpisode,
                addedAt = addedAt,
                updatedAt = updatedAt,
            ),
            category = category,
            watchFraction = watchFraction,
            progress = progress,
            hasNewEpisodes = hasNewEpisodes,
        )
    }

    /** A completed resume point, as `setEpisodeWatched` writes one. */
    private fun watched(title: String): WatchProgress {
        val tmdbId = title.hashCode() and 0x7fffffff
        return WatchProgress(
            id = "progress-$tmdbId",
            libraryEntryId = "entry-$tmdbId",
            tmdbId = tmdbId,
            mediaType = DomainMediaType.Tv,
            positionSeconds = 1_400.0,
            durationSeconds = 1_400.0,
            completed = true,
        )
    }

    private fun titles(rows: List<MyListRow>) = rows.map { it.displayTitle }

    // ── continueWatching ────────────────────────────────────────────────────

    // The reported bug: marking an episode watched writes a *completed* progress row, which
    // leaves no resume fraction, so the hero fell back to the `Watching` category alone —
    // offering Resume on an episode already finished.
    // Mutation applied to verify: dropped the `hasSomethingToPlay` term → test failed, the
    // finished show became the hero again.
    @Test
    fun `the hero skips a title whose last episode is finished`() {
        val rows = listOf(
            row(
                "Caught Up",
                lastWatchedAt = "2026-08-10T00:00:00Z",
                lastWatchedSeason = 1,
                lastWatchedEpisode = 6,
                progress = watched("Caught Up"),
            ),
            row("Half Done", lastWatchedAt = "2026-08-01T00:00:00Z", watchFraction = 0.4f),
        )

        assertEquals("Half Done", continueWatching(rows)?.displayTitle)
    }

    // No hero beats a hero pointing at something that is over.
    // Mutation applied to verify: returned the most recent row regardless → test failed, the
    // finished show was offered with nothing left to play.
    @Test
    fun `there is no hero when everything is finished`() {
        val rows = listOf(
            row(
                "Caught Up",
                lastWatchedSeason = 2,
                lastWatchedEpisode = 4,
                progress = watched("Caught Up"),
            ),
        )

        assertNull(continueWatching(rows))
    }

    // A title marked Watching that has never been played is a legitimate offer — the hero
    // says Play rather than Resume.
    // Mutation applied to verify: required a non-null `watchFraction` → test failed, the
    // untouched title was skipped and the hero disappeared.
    @Test
    fun `the hero offers a title that has never been started`() {
        assertEquals("Fresh", continueWatching(listOf(row("Fresh")))?.displayTitle)
    }

    // Mutation applied to verify: dropped the category filter → test failed, a part-watched
    // Watch Later title displaced the show actually being watched.
    @Test
    fun `the hero only considers the watching category`() {
        val rows = listOf(
            row("Saved", category = MyListCategory.WatchLater, watchFraction = 0.5f,
                lastWatchedAt = "2026-08-10T00:00:00Z"),
            row("Watching Now", watchFraction = 0.2f, lastWatchedAt = "2026-08-01T00:00:00Z"),
        )

        assertEquals("Watching Now", continueWatching(rows)?.displayTitle)
    }

    // Mutation applied to verify: used `minByOrNull` → test failed, the stalest title led.
    @Test
    fun `the hero picks the most recently watched of the candidates`() {
        val rows = listOf(
            row("Stale", watchFraction = 0.3f, lastWatchedAt = "2026-01-01T00:00:00Z"),
            row("Fresh", watchFraction = 0.3f, lastWatchedAt = "2026-08-10T00:00:00Z"),
        )

        assertEquals("Fresh", continueWatching(rows)?.displayTitle)
    }

    // Mutation applied to verify: dropped the `descending` branch so the comparator was
    // always ascending → test failed, the oldest title came first.
    @Test
    fun `recently added sorts newest first and flips on demand`() {
        val rows = listOf(
            row("Old", addedAt = "2026-01-01T00:00:00Z"),
            row("New", addedAt = "2026-08-01T00:00:00Z"),
            row("Middle", addedAt = "2026-04-01T00:00:00Z"),
        )

        val descending = applyFilters(rows, MyListFilters(sort = MyListSort.RecentlyAdded))
        assertEquals(listOf("New", "Middle", "Old"), titles(descending))

        val ascending = applyFilters(
            rows,
            MyListFilters(sort = MyListSort.RecentlyAdded, descending = false),
        )
        assertEquals(listOf("Old", "Middle", "New"), titles(ascending))
    }

    // A blank date is not "the oldest" — the backend simply never recorded one, and
    // flipping the order should not parade those titles first.
    // Mutation applied to verify: treated "" as a sortable value instead of partitioning
    // it out → test failed, Undated led the ascending list.
    @Test
    fun `titles with no added date sort last in both directions`() {
        val rows = listOf(
            row("Undated", addedAt = ""),
            row("Old", addedAt = "2026-01-01T00:00:00Z"),
            row("New", addedAt = "2026-08-01T00:00:00Z"),
        )

        assertEquals(
            listOf("New", "Old", "Undated"),
            titles(applyFilters(rows, MyListFilters(sort = MyListSort.RecentlyAdded))),
        )
        assertEquals(
            listOf("Old", "New", "Undated"),
            titles(
                applyFilters(
                    rows,
                    MyListFilters(sort = MyListSort.RecentlyAdded, descending = false),
                ),
            ),
        )
    }

    // Mutation applied to verify: fell back to addedAt instead of updatedAt → test failed,
    // the title watched yesterday ranked below one added later but never played.
    @Test
    fun `recently watched falls back to the updated timestamp`() {
        val rows = listOf(
            row("Played", lastWatchedAt = "2026-08-09T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"),
            row("Touched", lastWatchedAt = null, updatedAt = "2026-08-08T00:00:00Z"),
            row("Stale", lastWatchedAt = null, updatedAt = "2026-02-01T00:00:00Z"),
        )

        assertEquals(
            listOf("Played", "Touched", "Stale"),
            titles(applyFilters(rows, MyListFilters(sort = MyListSort.RecentlyWatched))),
        )
    }

    // Mutation applied to verify: dropped the title tiebreaker → test failed
    // intermittently, two titles sharing a date came back in input order.
    @Test
    fun `titles sharing a sort key are ordered by name`() {
        val rows = listOf(
            row("Zulu", addedAt = "2026-05-05T00:00:00Z"),
            row("Alpha", addedAt = "2026-05-05T00:00:00Z"),
        )

        assertEquals(
            listOf("Alpha", "Zulu"),
            titles(applyFilters(rows, MyListFilters(sort = MyListSort.RecentlyAdded))),
        )
    }

    // Mutation applied to verify: used a case-sensitive contains → test failed on the
    // lowercase query.
    @Test
    fun `search matches case-insensitively and ignores surrounding space`() {
        val rows = listOf(row("Breaking Bad"), row("Better Call Saul"))

        assertEquals(
            listOf("Breaking Bad"),
            titles(applyFilters(rows, MyListFilters(query = "  breaking  ".trimEnd()))),
        )
        assertEquals(
            listOf("Better Call Saul", "Breaking Bad"),
            titles(applyFilters(rows, MyListFilters(query = "  ", sort = MyListSort.Title)))
                .sorted(),
        )
    }

    // Mutation applied to verify: ORed the category and type filters instead of ANDing
    // them → test failed, a finished movie survived a "watching series" filter.
    @Test
    fun `category and type filters both have to pass`() {
        val rows = listOf(
            row("Watched series", category = MyListCategory.Watching, type = MediaType.Series),
            row("Watched movie", category = MyListCategory.Watching, type = MediaType.Movie),
            row("Finished series", category = MyListCategory.Finished, type = MediaType.Series),
        )

        val filtered = applyFilters(
            rows,
            MyListFilters(category = MyListCategory.Watching, type = MediaType.Series),
        )

        assertEquals(listOf("Watched series"), titles(filtered))
    }

    // Mutation applied to verify: counted the filtered list rather than every row → test
    // failed, the pills reported one entry per category regardless of the library.
    @Test
    fun `category counts cover every saved title`() {
        val rows = listOf(
            row("A", category = MyListCategory.Watching),
            row("B", category = MyListCategory.Watching),
            row("C", category = MyListCategory.Finished),
        )

        assertEquals(
            mapOf(MyListCategory.Watching to 2, MyListCategory.Finished to 1),
            categoryCounts(rows),
        )
    }

    // A series carries a full first-air date and a movie only a year, so the series key
    // has to be truncated or every series outranks a movie from the same year.
    // Mutation applied to verify: compared the series' full date against the movie's bare
    // year → test failed, the 2011 series jumped above the 2011 movie instead of tying
    // and falling back to the title.
    @Test
    fun `release year compares movies and series on the same scale`() {
        val rows = listOf(
            row("Movie 1999", type = MediaType.Movie, released = "1999"),
            row("Zeta series 2011", type = MediaType.Series, firstAirDate = "2011-04-17"),
            row("Alpha movie 2011", type = MediaType.Movie, released = "2011"),
            row("Movie 2020", type = MediaType.Movie, released = "2020"),
        )

        assertEquals(
            listOf("Movie 2020", "Alpha movie 2011", "Zeta series 2011", "Movie 1999"),
            titles(applyFilters(rows, MyListFilters(sort = MyListSort.ReleaseYear))),
        )
    }

    // Mutation applied to verify: returned null only for a null input, letting a rating of
    // 0.0 sort as "unrated" → test failed, the unrated title outranked the rated one.
    @Test
    fun `unrated titles sort after rated ones`() {
        val rows = listOf(
            row("Unrated", rating = null),
            row("Poor", rating = 2.0),
            row("Great", rating = 9.0),
        )

        assertEquals(
            listOf("Great", "Poor", "Unrated"),
            titles(applyFilters(rows, MyListFilters(sort = MyListSort.YourRating))),
        )
    }

    // Mutation applied to verify: built the marker from lastAired instead of lastWatched
    // → test failed, the row claimed the viewer was further along than they were.
    @Test
    fun `the episode marker reports where the viewer stopped`() {
        val watched = row("Show", lastWatchedSeason = 3, lastWatchedEpisode = 7)
        assertEquals("S3 E7", watched.episodeMarker)
        assertNull(row("Movie").episodeMarker)
    }

    // Mutation applied to verify: always appended the year → test failed, a date in the
    // current year read "12 Aug 2026".
    @Test
    fun `short dates name the year only when it is not this one`() {
        val today = LocalDate.parse("2026-08-10")

        assertEquals("12 Aug", shortDateLabel("2026-08-12T09:30:00Z", today))
        assertEquals("3 Nov 2025", shortDateLabel("2025-11-03T09:30:00Z", today))
        assertEquals("1 Feb", shortDateLabel("2026-02-01", today))
    }

    // Mutation applied to verify: returned the raw string on a parse failure → test
    // failed, "not a date" appeared where a date belongs.
    @Test
    fun `an unreadable timestamp produces no label at all`() {
        val today = LocalDate.parse("2026-08-10")

        assertNull(shortDateLabel("not a date", today))
        assertNull(shortDateLabel("", today))
        assertNull(shortDateLabel(null, today))
    }
}
