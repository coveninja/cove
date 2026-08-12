package com.coveninja.cove.ui.pages.home

import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.model.uiMediaId
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.coveninja.cove.ui.model.MediaType as UiMediaType

class HomeModelTest {

    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun entry(
        tmdbId: Int,
        title: String,
        status: LibraryStatus = LibraryStatus.Watching,
        type: MediaType = MediaType.Tv,
        lastWatchedAt: String? = null,
        updatedAt: String = "2026-01-01T00:00:00Z",
        watched: Pair<Int, Int>? = null,
        aired: Pair<Int, Int>? = null,
    ): LibraryEntry = LibraryEntry(
        id = "entry-$tmdbId",
        tmdbId = tmdbId,
        mediaType = type,
        title = title,
        status = status,
        lastWatchedAt = lastWatchedAt,
        lastWatchedSeason = watched?.first,
        lastWatchedEpisode = watched?.second,
        lastAiredSeason = aired?.first,
        lastAiredEpisode = aired?.second,
        updatedAt = updatedAt,
    )

    private fun progress(
        tmdbId: Int,
        position: Double,
        duration: Double,
        type: MediaType = MediaType.Tv,
        completed: Boolean = false,
        watchedAt: String = "2026-01-01T00:00:00Z",
    ): WatchProgress = WatchProgress(
        id = "progress-$tmdbId",
        libraryEntryId = "entry-$tmdbId",
        tmdbId = tmdbId,
        mediaType = type,
        positionSeconds = position,
        durationSeconds = duration,
        completed = completed,
        watchedAt = watchedAt,
    )

    /** Indexes progress rows the way `WatchProgressIndex` does, so ids cannot drift. */
    private fun lookup(rows: List<WatchProgress>): (String) -> WatchProgress? {
        val byId = rows.associateBy { uiMediaId(it.tmdbId, it.mediaType) }
        return { id -> byId[id] }
    }

    private fun rows(
        entries: List<LibraryEntry>,
        progress: List<WatchProgress> = emptyList(),
        limit: Int = CONTINUE_ROW_LIMIT,
    ): List<ContinueRow> = continueWatchingRows(
        entries = entries,
        progressFor = lookup(progress),
        enrich = LibraryEntry::toUiMedia,
        limit = limit,
    )

    /**
     * A title as the catalog delivers one: TMDB fills `title` for films and `name` for
     * series, never both. Populating both would make any test of the fallback pass whichever
     * field the code happens to read.
     */
    private fun media(
        title: String,
        type: UiMediaType = UiMediaType.Movie,
        backdrop: String? = "/backdrop.jpg",
    ): Media = Media(
        id = "${type.name}:${title.hashCode() and 0x7fffffff}",
        tmdbId = title.hashCode() and 0x7fffffff,
        title = title.takeIf { type == UiMediaType.Movie },
        name = title.takeIf { type == UiMediaType.Series },
        overview = null,
        released = null,
        firstAirDate = null,
        posterUrl = "/poster.jpg",
        logoUrl = null,
        backdropUrl = backdrop,
        rating = null,
        type = type,
        popularity = null,
        adult = null,
        originalLanguage = null,
    )

    private fun calendarItem(
        tmdbId: Int,
        title: String,
        date: String,
        kind: String = CalendarItem.KIND_EPISODE,
        waitingCount: Int = 0,
    ): CalendarItem = CalendarItem(
        date = date,
        kind = kind,
        tmdbId = tmdbId,
        mediaType = MediaType.Tv.wireName,
        title = title,
        posterPath = "/poster.jpg",
        waitingCount = waitingCount,
    )

    private fun rail(id: String, media: List<Media>, ordered: Boolean = false) = HomeRail(
        id = id,
        title = id,
        subtitle = "",
        icon = "lucide:layout-grid",
        media = media,
        ordered = ordered,
    )

    // ── continueWatchingRows ────────────────────────────────────────────────

    // Mutation applied to verify: dropped the `watchFraction != null` term from the filter →
    // test failed, the part-watched film disappeared from the result.
    @Test
    fun `a part-watched title is offered even when it is not marked as watching`() {
        val entries = listOf(
            entry(1, "Half Done", status = LibraryStatus.WatchLater, type = MediaType.Movie),
        )
        val progress = listOf(progress(1, position = 600.0, duration = 3_600.0, type = MediaType.Movie))

        assertEquals(listOf("Half Done"), rows(entries, progress).map { it.displayTitle })
    }

    // Mutation applied to verify: dropped the `status == Watching` term → test failed, the
    // started-but-unplayed show vanished.
    @Test
    fun `a show marked as watching is offered with no progress recorded at all`() {
        assertEquals(listOf("Started"), rows(listOf(entry(1, "Started"))).map { it.displayTitle })
    }

    // This is the bug that prompted the rewrite: marking an episode watched writes a
    // *completed* progress row, which leaves no resume fraction, so the row used to survive
    // purely on the show-level status — putting a finished episode on a rail headed "where
    // you left off".
    // Mutation applied to verify: re-added `status == Watching` as a qualifying term → test
    // failed, the caught-up show came back.
    @Test
    fun `a show whose last episode is finished is not offered`() {
        val entries = listOf(entry(1, "Caught Up", watched = 1 to 6))
        val finished = listOf(progress(1, position = 1_400.0, duration = 1_400.0, completed = true))

        assertTrue(rows(entries, finished).isEmpty())
    }

    // The same shape without a progress row at all: episodes ticked off by hand still move
    // the library counters, and a show marked Watching forever must not outlive them.
    // Mutation applied to verify: dropped the `lastWatchedSeason == null` term from
    // `neverPlayed` → test failed, the show reappeared as "unstarted".
    @Test
    fun `a show with watched episodes is not mistaken for an unstarted one`() {
        assertTrue(rows(listOf(entry(1, "Ticked Off", watched = 2 to 4))).isEmpty())
    }

    // A show with episodes waiting belongs to the backlog rail, which knows how many are
    // waiting; carrying it here too would list one show twice for two different reasons.
    // Mutation applied to verify: re-added `hasNewEpisodes` as a qualifying term → test
    // failed, the show appeared on both rails.
    @Test
    fun `a show with episodes waiting is left to the backlog rail`() {
        val entries = listOf(entry(1, "Aired Ahead", watched = 2 to 3, aired = 2 to 5))

        assertTrue(rows(entries).isEmpty())
    }

    // Mutation applied to verify: removed the `status != Dropped` filter → test failed, the
    // abandoned show came back.
    @Test
    fun `a dropped show is never offered however recently it was watched`() {
        val entries = listOf(
            entry(1, "Abandoned", status = LibraryStatus.Dropped, watched = 1 to 2, aired = 1 to 9),
        )

        assertTrue(rows(entries).isEmpty())
    }

    // Finishing something should clear it off the page. Its episode counters may still argue
    // it is behind, and offering to resume a finished title reads as a bug.
    // Mutation applied to verify: removed the Finished guard → test failed, the completed
    // show was offered again.
    @Test
    fun `a finished show with nothing part-watched is not offered`() {
        val entries = listOf(
            entry(1, "All Done", status = LibraryStatus.Finished, watched = 1 to 8, aired = 2 to 1),
        )

        assertTrue(rows(entries).isEmpty())
    }

    // Mutation applied to verify: sorted ascending instead of descending → test failed, the
    // stale title led.
    @Test
    fun `the most recently watched title comes first`() {
        val entries = listOf(
            entry(1, "Stale", lastWatchedAt = "2026-01-01T00:00:00Z"),
            entry(2, "Fresh", lastWatchedAt = "2026-08-01T00:00:00Z"),
        )

        assertEquals(listOf("Fresh", "Stale"), rows(entries).map { it.displayTitle })
    }

    // The library row also moves for things that are not watching — rating a title, dragging
    // it to another list — so the progress row is the better clock when there is one.
    // Mutation applied to verify: read `entry.lastWatchedAt` first in `activityKey` → test
    // failed, the title with the stale library timestamp but fresher playback sorted last.
    @Test
    fun `playback timestamps outrank library timestamps when ordering`() {
        val entries = listOf(
            entry(1, "Touched Later", lastWatchedAt = "2026-08-09T00:00:00Z"),
            entry(2, "Played Later", lastWatchedAt = "2026-08-01T00:00:00Z"),
        )
        // Far enough in to count: `watchFraction` discards anything under 2%, which would
        // leave the row unqualified rather than merely unordered.
        val progress = listOf(
            progress(2, position = 600.0, duration = 3_600.0, watchedAt = "2026-08-10T00:00:00Z"),
        )

        assertEquals(
            listOf("Played Later", "Touched Later"),
            rows(entries, progress).map { it.displayTitle },
        )
    }

    // Mutation applied to verify: removed the `take(limit)` → test failed, all six rows came
    // back.
    @Test
    fun `the rail stops at the limit`() {
        val entries = (1..6).map { entry(it, "Show $it") }

        assertEquals(2, rows(entries, limit = 2).size)
    }

    // ── ContinueRow labels ──────────────────────────────────────────────────

    // Mutation applied to verify: read the entry's watched season/episode before the progress
    // row's in `episodeMarker` → test failed, it reported S1 E2 rather than where playback
    // actually is.
    @Test
    fun `the episode marker follows playback rather than the library counter`() {
        val entries = listOf(entry(1, "Show", watched = 1 to 2))
        val rowProgress = progress(1, position = 300.0, duration = 2_400.0)
            .copy(season = 3, episode = 7)

        val row = rows(entries, listOf(rowProgress)).single()

        assertEquals("S3 E7", row.episodeMarker)
        assertEquals("S3 E7  ·  35 min left", row.caption)
    }

    // The one fact that distinguishes this card from the resume cards beside it. Naming an
    // episode would invent a position the viewer has never reached, and the format label is
    // already obvious from the artwork.
    // Mutation applied to verify: gave `Unstarted` the same caption branch as `Resume` →
    // test failed, the card captioned itself "Series".
    @Test
    fun `an unstarted title says so rather than naming a position`() {
        val row = rows(listOf(entry(1, "Fresh", type = MediaType.Tv))).single()

        assertEquals(ContinueReason.Unstarted, row.reason)
        assertEquals("Not started", row.caption)
    }

    // ── thumbnailEpisode ────────────────────────────────────────────────────

    // The frame you stopped on beats every other image the card could carry, so playback wins
    // even when the library counters point at a different episode.
    // Mutation applied to verify: checked the library counters before the progress row → test
    // failed, it asked for the still of S1 E2.
    @Test
    fun `the thumbnail follows the episode being played`() {
        val entries = listOf(entry(1, "Show", watched = 1 to 2, aired = 4 to 1))
        val playing = progress(1, position = 300.0, duration = 2_400.0).copy(season = 3, episode = 7)

        assertEquals(EpisodeRef(3, 7), rows(entries, listOf(playing)).single().thumbnailEpisode())
    }

    // A still from an episode the viewer has not reached is both a spoiler and a lie about
    // where they are; a film has no episode to take one from at all.
    // Mutation applied to verify: defaulted the missing watched counters to season 1
    // episode 1 → test failed, both asked for a still that means nothing.
    @Test
    fun `an unstarted title or a film has no episode thumbnail`() {
        val unstarted = rows(listOf(entry(1, "Fresh"))).single()
        val film = rows(
            listOf(entry(2, "Movie", type = MediaType.Movie, status = LibraryStatus.WatchLater)),
            listOf(progress(2, position = 600.0, duration = 3_600.0, type = MediaType.Movie)),
        ).single()

        assertNull(unstarted.thumbnailEpisode())
        assertEquals(ContinueReason.Resume, film.reason)
        assertNull(film.thumbnailEpisode())
    }

    // A film has no episode to take a frame from, and neither has a show never started.
    // Mutation applied to verify: defaulted the missing counters to season 1 episode 1 → test
    // failed, both asked for a still that means nothing.
    @Test
    fun `a film or an unstarted show has no episode thumbnail`() {
        val film = entry(1, "Film", type = MediaType.Movie, status = LibraryStatus.Watching)
        val unstarted = entry(2, "Unstarted")

        assertNull(rows(listOf(film)).single().thumbnailEpisode())
        assertNull(rows(listOf(unstarted)).single().thumbnailEpisode())
    }

    // ── remainingLabel ──────────────────────────────────────────────────────

    // Deliberately not a whole number of minutes: 1530 seconds is 25.5, which rounds to 26
    // and truncates to 25. A remainder that divided evenly would pass either way.
    // Mutation applied to verify: replaced `roundToInt` with `toInt` → test failed, the
    // countdown reported "25 min left".
    @Test
    fun `remaining time is reported in whole minutes`() {
        assertEquals("26 min left", remainingLabel(progress(1, position = 100.0, duration = 1_630.0)))
    }

    // Mutation applied to verify: dropped the hours branch → test failed, a 95-minute
    // remainder came back as "95 min left".
    @Test
    fun `over an hour remaining is reported in hours and minutes`() {
        assertEquals("1 h 35 min left", remainingLabel(progress(1, position = 0.0, duration = 5_700.0)))
        assertEquals("2 h left", remainingLabel(progress(1, position = 0.0, duration = 7_200.0)))
    }

    // "0 min left" on something effectively over is worse than saying nothing.
    // Mutation applied to verify: removed the `remaining < 60` guard → test failed, the
    // nearly-finished title reported "1 min left".
    @Test
    fun `no countdown is shown for a title that is over or unplayable`() {
        assertNull(remainingLabel(null))
        assertNull(remainingLabel(progress(1, position = 3_580.0, duration = 3_600.0)))
        assertNull(remainingLabel(progress(1, position = 10.0, duration = 0.0)))
        assertNull(remainingLabel(progress(1, position = 10.0, duration = 3_600.0, completed = true)))
    }

    // ── heroPick ────────────────────────────────────────────────────────────

    private fun backlog(vararg items: CalendarItem): List<BacklogRow> =
        backlogRows(items.toList()) { item -> media(item.title, UiMediaType.Series) }

    // Mutation applied to verify: swapped the resume and backlog branches → test failed, the
    // hero led with the show that merely had episodes waiting.
    @Test
    fun `something half-watched outranks something with new episodes`() {
        val entries = listOf(entry(2, "Half Watched", lastWatchedAt = "2026-01-01T00:00:00Z"))
        val progress = listOf(progress(2, position = 600.0, duration = 3_600.0))
        val waiting = backlog(
            calendarItem(1, "Aired", "2026-08-04", CalendarItem.KIND_AVAILABLE, waitingCount = 3),
        )

        val hero = heroPick(rows(entries, progress), waiting, listOf(media("Popular")))

        assertEquals("Half Watched", hero?.media?.title ?: hero?.media?.name)
        assertEquals(HomeHeroKind.Resume, hero?.kind)
    }

    // Mutation applied to verify: returned the spotlight before checking the backlog → test
    // failed, a merely-popular title displaced the show waiting for the viewer.
    @Test
    fun `a show with new episodes outranks whatever is trending`() {
        val waiting = backlog(
            calendarItem(1, "Aired", "2026-08-04", CalendarItem.KIND_AVAILABLE, waitingCount = 3),
        )

        val hero = heroPick(emptyList(), waiting, listOf(media("Popular")))

        assertEquals("Aired", hero?.media?.name)
        assertEquals(HomeHeroKind.NewEpisodes, hero?.kind)
        assertTrue(hero!!.caption.startsWith("3 waiting"))
    }

    // A poster stretched into the hero's wide frame renders as a smear behind unreadable copy.
    // Mutation applied to verify: took `trending.first()` outright → test failed, the hero
    // picked the backdrop-less title.
    @Test
    fun `the spotlight prefers a title that actually has a backdrop`() {
        val trending = listOf(media("No Art", backdrop = null), media("Has Art"))

        val hero = heroPick(emptyList(), emptyList(), trending)

        assertEquals("Has Art", hero?.media?.title)
        assertEquals(HomeHeroKind.Spotlight, hero?.kind)
    }

    // Mutation applied to verify: returned a hero built from an empty list → test failed with
    // an index error rather than a null.
    @Test
    fun `there is no hero when there is nothing at all`() {
        assertNull(heroPick(emptyList(), emptyList(), emptyList()))
    }

    // Resume and Play are different promises; a spotlight can offer neither.
    // Mutation applied to verify: made every hero playable → test failed on the spotlight.
    @Test
    fun `only a personal hero offers playback`() {
        val resume = heroPick(
            rows(listOf(entry(1, "Show")), listOf(progress(1, 600.0, 3_600.0))),
            emptyList(),
            emptyList(),
        )
        val spotlight = heroPick(emptyList(), emptyList(), listOf(media("Popular")))

        assertTrue(resume!!.playable)
        assertEquals("Resume", resume.playLabel)
        assertTrue(!spotlight!!.playable)
    }

    // ── backlogRows ─────────────────────────────────────────────────────────

    // A card whose buttons cannot resolve a title would open and play nothing at all, which
    // is worse than the card simply not being there.
    // Mutation applied to verify: mapped unresolved items to a card anyway → test failed, the
    // orphaned entry produced a row.
    @Test
    fun `a calendar entry with no library title is dropped`() {
        val items = listOf(
            calendarItem(1, "Known", "2026-08-04", CalendarItem.KIND_AVAILABLE),
            calendarItem(2, "Orphan", "2026-08-04", CalendarItem.KIND_AVAILABLE),
        )

        val resolved = backlogRows(items) { item ->
            media(item.title, UiMediaType.Series).takeIf { item.title == "Known" }
        }

        assertEquals(listOf("Known"), resolved.map { it.displayTitle })
    }

    // The entry is on the calendar precisely because something waits, so a reported zero
    // would contradict the rail it sits on.
    // Mutation applied to verify: dropped the `coerceAtLeast(1)` → test failed, the badge
    // read "0 waiting".
    @Test
    fun `a backlog entry always claims at least one waiting episode`() {
        val row = backlog(
            calendarItem(1, "Uncounted", "2026-08-04", CalendarItem.KIND_AVAILABLE),
        ).single()

        assertEquals(1, row.waitingCount)
        assertEquals("1 waiting", row.badge)
    }

    // ── comingUp ────────────────────────────────────────────────────────────

    private val today = LocalDate(2026, 8, 11)

    // Backlog dates point backwards and would sort to the wrong end of a countdown.
    // Mutation applied to verify: removed the `!it.available` filter → test failed, the
    // watchable-now entry appeared on the upcoming strip.
    @Test
    fun `an already-watchable item is not something coming up`() {
        // Both dated inside the window, so only the availability check can separate them —
        // a backlog entry dated in the past would be dropped by the horizon regardless.
        val items = listOf(
            calendarItem(1, "Waiting", "2026-08-12", kind = CalendarItem.KIND_AVAILABLE),
            calendarItem(2, "Soon", "2026-08-12"),
        )

        assertEquals(listOf("Soon"), comingUp(items, today).map { it.title })
    }

    // Mutation applied to verify: widened the window to `days >= 0` → test failed, the entry
    // three weeks out appeared on a strip headed "this week".
    @Test
    fun `only the next week counts as coming up`() {
        val items = listOf(
            calendarItem(1, "Yesterday", "2026-08-10"),
            calendarItem(2, "Today", "2026-08-11"),
            calendarItem(3, "In A Week", "2026-08-18"),
            calendarItem(4, "Far Off", "2026-09-02"),
        )

        assertEquals(listOf("Today", "In A Week"), comingUp(items, today).map { it.title })
    }

    // Mutation applied to verify: sorted by title alone → test failed, the later date led.
    @Test
    fun `coming up is ordered soonest first`() {
        val items = listOf(
            calendarItem(1, "Alpha", "2026-08-15"),
            calendarItem(2, "Zulu", "2026-08-12"),
        )

        assertEquals(listOf("Zulu", "Alpha"), comingUp(items, today).map { it.title })
    }

    // ── greetingFor ─────────────────────────────────────────────────────────

    // Mutation applied to verify: shifted the evening band to start at 16 → test failed, 4pm
    // greeted the viewer with "Good evening".
    @Test
    fun `the greeting follows the hour`() {
        assertEquals("Good morning", greetingFor(8))
        assertEquals("Good afternoon", greetingFor(16))
        assertEquals("Good evening", greetingFor(17))
        assertEquals("Up late", greetingFor(2))
    }

    // ── libraryStats ────────────────────────────────────────────────────────

    // Mutation applied to verify: counted every entry rather than filtering by status → test
    // failed, the finished title inflated both counters.
    @Test
    fun `stats count each status separately`() {
        val entries = listOf(
            entry(1, "A", status = LibraryStatus.Watching),
            entry(2, "B", status = LibraryStatus.Watching),
            entry(3, "C", status = LibraryStatus.WatchLater),
            entry(4, "D", status = LibraryStatus.Finished),
        )

        val stats = libraryStats(entries, emptyList())

        assertEquals(2, stats.watching)
        assertEquals(1, stats.watchLater)
    }

    // A backlog entry is on the calendar *because* something waits, so a reported zero would
    // contradict the rail sitting right below the number.
    // Mutation applied to verify: dropped the `coerceAtLeast(1)` → test failed, the
    // uncounted backlog entry contributed nothing.
    @Test
    fun `every backlog entry counts at least one waiting episode`() {
        val items = listOf(
            calendarItem(1, "Counted", "2026-08-04", CalendarItem.KIND_AVAILABLE, waitingCount = 3),
            calendarItem(2, "Uncounted", "2026-08-05", CalendarItem.KIND_AVAILABLE),
            calendarItem(3, "Scheduled", "2026-08-20", waitingCount = 9),
        )

        assertEquals(4, libraryStats(emptyList(), items).episodesWaiting)
    }

    // ── buildHomeRails ──────────────────────────────────────────────────────

    // Mutation applied to verify: dropped the `distinct.size < minimumSize` guard → test
    // failed, the two-title rail survived.
    @Test
    fun `a rail with too few titles is not worth a heading`() {
        val long = rail("long", (1..6).map { media("Long $it") })
        // Marked ordered so the redundancy check cannot be what drops it: with distinct
        // titles and an exemption from the unseen rule, only the size guard is left.
        val short = rail("short", (1..2).map { media("Short $it") }, ordered = true)

        assertEquals(listOf("long"), buildHomeRails(listOf(long, short)).map { it.id })
    }

    // Mutation applied to verify: ignored the `ordered` flag → test failed, "Trending now"
    // was dropped for repeating the personal rail above it and the page lost its fallback.
    @Test
    fun `an ordered rail survives repeating what came before it`() {
        val shared = (1..6).map { media("Shared $it") }

        val kept = buildHomeRails(listOf(rail("personal", shared), rail("trending", shared, ordered = true)))

        assertEquals(listOf("personal", "trending"), kept.map { it.id })
    }

    // Mutation applied to verify: applied the unseen check to ordered rails only → test
    // failed, the duplicate membership rail survived.
    @Test
    fun `a membership rail that only repeats earlier titles is dropped`() {
        val shared = (1..6).map { media("Shared $it") }

        val kept = buildHomeRails(listOf(rail("first", shared), rail("second", shared)))

        assertEquals(listOf("first"), kept.map { it.id })
    }
}
