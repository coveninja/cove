package com.coveninja.cove.ui.pages.mylist.calendar

import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.MediaType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarModelTest {

    private val today = LocalDate.parse("2026-08-10")

    private fun item(
        date: String,
        title: String = "Show",
        kind: String = CalendarItem.KIND_EPISODE,
        season: Int? = null,
        episode: Int? = null,
        tmdbId: Int = 1,
    ) = CalendarItem(
        date = date,
        kind = kind,
        tmdbId = tmdbId,
        mediaType = MediaType.Tv.wireName,
        title = title,
        posterPath = "",
        seasonNumber = season,
        episodeNumber = episode,
    )

    // Mutation applied to verify: sorted days newest-first → test failed, tomorrow was
    // listed above today.
    @Test
    fun `days are grouped and ordered oldest first`() {
        val items = listOf(
            item("2026-08-12", title = "Later"),
            item("2026-08-10", title = "Now"),
            item("2026-08-11", title = "Soon"),
        )

        val days = groupByDay(items, today)

        assertEquals(
            listOf("2026-08-10", "2026-08-11", "2026-08-12"),
            days.map { it.date.toString() },
        )
        assertEquals(listOf("Today", "Tomorrow", "Wed 12 Aug"), days.map { it.label })
    }

    // Mutation applied to verify: dropped the mapNotNull guard → test failed with a
    // parse exception instead of quietly skipping the unusable row.
    @Test
    fun `an unreadable date is left out rather than crashing the agenda`() {
        val days = groupByDay(listOf(item("not-a-date"), item("2026-08-10")), today)

        assertEquals(1, days.size)
        assertEquals("2026-08-10", days.single().date.toString())
    }

    // Mutation applied to verify: labelled -1 as "in -1 days" → test failed, yesterday
    // read as a negative countdown.
    @Test
    fun `relative day labels name today and its neighbours`() {
        assertEquals("Today", dayLabel(LocalDate.parse("2026-08-10"), today))
        assertEquals("Tomorrow", dayLabel(LocalDate.parse("2026-08-11"), today))
        assertEquals("Yesterday", dayLabel(LocalDate.parse("2026-08-09"), today))
        assertEquals("Sat 15 Aug", dayLabel(LocalDate.parse("2026-08-15"), today))
    }

    // Mutation applied to verify: dropped the sign flip on past dates → test failed, an
    // episode from last week read "in -7 days".
    @Test
    fun `countdowns read forwards and backwards`() {
        assertEquals("today", countdownLabel(LocalDate.parse("2026-08-10"), today))
        assertEquals("tomorrow", countdownLabel(LocalDate.parse("2026-08-11"), today))
        assertEquals("yesterday", countdownLabel(LocalDate.parse("2026-08-09"), today))
        assertEquals("in 5 days", countdownLabel(LocalDate.parse("2026-08-15"), today))
        assertEquals("7 days ago", countdownLabel(LocalDate.parse("2026-08-03"), today))
    }

    // Mutation applied to verify: compared only the month and ignored the year → test
    // failed, next August's episode showed up in this August.
    @Test
    fun `the month window excludes the same month in another year`() {
        val items = listOf(
            item("2026-08-20", title = "This August"),
            item("2027-08-20", title = "Next August"),
            item("2026-09-01", title = "September"),
        )

        val inAugust = itemsInMonth(items, YearMonth(2026, 8))

        assertEquals(listOf("This August"), inAugust.map { it.title })
    }

    // Available items are pulled out of the month sections entirely, so they must not be
    // counted twice when the month they aired in is the one on screen.
    // Mutation applied to verify: let available items through the month filter → test
    // failed, the backlog episode appeared in both places.
    @Test
    fun `available items are kept out of the month sections`() {
        val items = listOf(
            item("2026-08-04", title = "Backlog", kind = CalendarItem.KIND_AVAILABLE),
            item("2026-08-20", title = "Upcoming"),
        )

        assertEquals(listOf("Upcoming"), itemsInMonth(items, YearMonth(2026, 8)).map { it.title })
        assertEquals(listOf("Backlog"), availableNow(items).map { it.title })
    }

    // Mutation applied to verify: sorted the backlog oldest-first → test failed, the
    // episode from months ago outranked last night's.
    @Test
    fun `the backlog leads with what aired most recently`() {
        val items = listOf(
            item("2026-05-01", title = "Ancient", kind = CalendarItem.KIND_AVAILABLE),
            item("2026-08-09", title = "Last night", kind = CalendarItem.KIND_AVAILABLE),
        )

        assertEquals(listOf("Last night", "Ancient"), availableNow(items).map { it.title })
    }

    // Mutation applied to verify: emitted "S null E null" for movies → test failed.
    @Test
    fun `episode markers appear only for episodes`() {
        assertEquals("S3 E7", item("2026-08-10", season = 3, episode = 7).episodeMarker())
        assertNull(item("2026-08-10").episodeMarker())
    }

    // Mutation applied to verify: left kind out of the id → test failed, a backlog entry
    // and an upcoming entry for the same episode collided as one lazy-list key.
    @Test
    fun `backlog and upcoming entries for one episode keep separate identities`() {
        val backlog = item("2026-08-04", kind = CalendarItem.KIND_AVAILABLE, season = 1, episode = 2)
        val upcoming = item("2026-08-20", kind = CalendarItem.KIND_EPISODE, season = 1, episode = 2)

        assertTrue(backlog.id != upcoming.id)
    }

    // The bug this guards: the row used tmdbImageSize, which only rewrites the size
    // segment of an existing URL. Handed a bare TMDB path it returns it unchanged, so
    // every calendar row rendered no artwork at all.
    // Mutation applied to verify: returned the path unchanged instead of prefixing it →
    // test failed, the "URL" was still "/poster.jpg".
    @Test
    fun `a bare TMDB path becomes a loadable URL`() {
        val url = calendarImageUrl(item("2026-08-10").copy(posterPath = "/poster.jpg"))

        assertEquals("https://image.tmdb.org/t/p/w185/poster.jpg", url)
    }

    // Mutation applied to verify: prefixed unconditionally → test failed, an already
    // proxied URL came back doubled up behind the TMDB host.
    @Test
    fun `a legacy loopback URL is recovered as a direct image URL`() {
        val proxied = "http://127.0.0.1:6969/api/v1/img/w500/abc.jpg"
        val url = calendarImageUrl(item("2026-08-10").copy(posterPath = proxied))

        assertEquals("https://image.tmdb.org/t/p/w185/abc.jpg", url)
    }

    // Mutation applied to verify: read stillPath first → test failed, an episode with
    // both showed the still where the consistent poster slot expects a poster.
    @Test
    fun `the poster wins and the still fills in for titles without one`() {
        val both = item("2026-08-10").copy(posterPath = "/poster.jpg", stillPath = "/still.jpg")
        assertEquals("https://image.tmdb.org/t/p/w185/poster.jpg", calendarImageUrl(both))

        val stillOnly = item("2026-08-10").copy(posterPath = "", stillPath = "/still.jpg")
        assertEquals("https://image.tmdb.org/t/p/w185/still.jpg", calendarImageUrl(stillOnly))

        assertNull(calendarImageUrl(item("2026-08-10").copy(posterPath = "", stillPath = "")))
    }

    // Mutation applied to verify: indexed the month tables from zero → test failed by one
    // month across the board.
    @Test
    fun `month labels name the month and year`() {
        assertEquals("August 2026", monthLabel(YearMonth(2026, 8)))
        assertEquals("January 2027", monthLabel(YearMonth(2027, 1)))
        assertEquals("December 2025", monthLabel(YearMonth(2025, 12)))
    }
}
