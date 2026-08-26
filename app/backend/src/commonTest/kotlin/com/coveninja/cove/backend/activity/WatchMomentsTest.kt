package com.coveninja.cove.backend.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The moment arithmetic, which is the half of the insights page that makes a claim about a
 * specific day rather than about an average. A wrong aggregate looks slightly off; a wrong
 * moment tells someone they watched nine hours on a day they were at work.
 */
class WatchMomentsTest {

    private val names = mapOf(
        "1:tv" to TitleName("Severance", "/sev.jpg"),
        "2:movie" to TitleName("Arrival", "/arr.jpg"),
        "3:tv" to TitleName("Andor", "/andor.jpg"),
    )

    private fun hour(date: String, hour: Int, minutes: Int) =
        HourCell(date, hour, minutes * 60L)

    private fun title(date: String, key: String, minutes: Int) =
        TitleCell(date, key, minutes * 60L)

    // ── Biggest day ──────────────────────────────────────────────────────────

    @Test
    fun `the biggest day is the day with the most time, not the most rows`() {
        val hours = listOf(
            // Four thin hours: more rows than the winner, less time in them.
            hour("2026-03-01", 9, 20),
            hour("2026-03-01", 10, 20),
            hour("2026-03-01", 11, 20),
            hour("2026-03-01", 12, 20),
            hour("2026-03-14", 20, 60),
            hour("2026-03-14", 21, 55),
        )
        val moments = buildMoments(hours, emptyList(), names, selectedYear = null)
        // Counting rows rather than summing seconds picks 1 March here.
        assertEquals("2026-03-14", moments.biggestDay?.date)
        assertEquals(115 * 60L, moments.biggestDay?.seconds)
    }

    @Test
    fun `the biggest day carries whatever led that day`() {
        val hours = listOf(hour("2026-03-14", 20, 60), hour("2026-03-14", 21, 60))
        val titles = listOf(
            title("2026-03-14", "1:tv", 30),
            title("2026-03-14", "2:movie", 90),
            // A different day's leader must not leak into this one.
            title("2026-03-01", "3:tv", 600),
        )
        val moment = buildMoments(hours, titles, names, selectedYear = null).biggestDay
        assertNotNull(moment)
        assertEquals("Arrival", moment.title)
        assertEquals(2, moment.tmdbId)
        assertEquals("movie", moment.mediaType)
        assertEquals("/arr.jpg", moment.posterPath)
        // The seconds are the day's total, not the leading title's share of it.
        assertEquals(120 * 60L, moment.seconds)
    }

    @Test
    fun `a title the library no longer knows still keeps its day`() {
        val hours = listOf(hour("2026-03-14", 20, 60), hour("2026-03-14", 21, 60))
        val titles = listOf(title("2026-03-14", "99:tv", 90))
        val moment = buildMoments(hours, titles, names, selectedYear = null).biggestDay
        assertNotNull(moment)
        // Dropping the moment entirely would lose a true fact about the day because of a
        // library edit that has nothing to do with it.
        assertEquals(120 * 60L, moment.seconds)
        assertEquals(99, moment.tmdbId)
        assertEquals("", moment.title)
    }

    // ── Range ────────────────────────────────────────────────────────────────

    @Test
    fun `a selected year excludes every other year`() {
        val hours = listOf(
            hour("2025-06-01", 20, 300),
            hour("2026-03-14", 20, 60),
        )
        val moments = buildMoments(hours, emptyList(), names, selectedYear = 2026)
        // 2025 holds the bigger day overall; narrowing to 2026 must not reach it.
        assertEquals("2026-03-14", moments.biggestDay?.date)
        assertEquals("2026-03-14", moments.firstWatch?.date)

        val allTime = buildMoments(hours, emptyList(), names, selectedYear = null)
        assertEquals("2025-06-01", allTime.biggestDay?.date)
    }

    @Test
    fun `nothing in range produces no moments at all`() {
        val hours = listOf(hour("2025-06-01", 20, 300))
        val moments = buildMoments(hours, emptyList(), names, selectedYear = 2026)
        assertNull(moments.biggestDay)
        assertNull(moments.firstWatch)
        assertNull(moments.longestSession)
        assertTrue(moments.monthlyHeadliners.isEmpty())
    }

    // ── First watch ──────────────────────────────────────────────────────────

    @Test
    fun `the first watch is the earliest date, whatever order the rows arrive in`() {
        val hours = listOf(
            hour("2026-07-02", 20, 60),
            hour("2026-01-09", 21, 30),
            hour("2026-04-11", 22, 90),
        )
        val moments = buildMoments(hours, emptyList(), names, selectedYear = 2026)
        // Rows come back in whatever order SQLite likes; taking the first is not the same
        // as taking the earliest.
        assertEquals("2026-01-09", moments.firstWatch?.date)
        assertEquals(30 * 60L, moments.firstWatch?.seconds)
    }

    // ── Monthly headliners ───────────────────────────────────────────────────

    @Test
    fun `each month gets its own leader, not the year's`() {
        val titles = listOf(
            title("2026-01-04", "1:tv", 600),
            title("2026-01-19", "1:tv", 400),
            title("2026-01-20", "2:movie", 120),
            // February's leader is smaller than January's runner-up; ranking across the
            // whole year instead of within each month puts Severance here too.
            title("2026-02-03", "3:tv", 200),
            title("2026-02-08", "2:movie", 100),
        )
        val hours = listOf(hour("2026-01-04", 20, 60), hour("2026-02-03", 20, 60))
        val headliners = buildMoments(hours, titles, names, selectedYear = 2026).monthlyHeadliners

        assertEquals(2, headliners.size)
        // Earliest month first, so the row reads as a year rather than a ranking.
        assertEquals(listOf("2026-01-01", "2026-02-01"), headliners.map { it.date })
        assertEquals(listOf("Severance", "Andor"), headliners.map { it.title })
        // January's total for Severance is both of its rows, not just the larger one.
        assertEquals(1000 * 60L, headliners.first().seconds)
    }

    @Test
    fun `a month with nothing in it is absent rather than empty`() {
        val titles = listOf(
            title("2026-01-04", "1:tv", 600),
            title("2026-03-04", "2:movie", 300),
        )
        val hours = listOf(hour("2026-01-04", 20, 60))
        val headliners = buildMoments(hours, titles, names, selectedYear = 2026).monthlyHeadliners
        // Two entries, not three with a blank February: a hole in a row of posters reads as
        // a loading failure.
        assertEquals(listOf("2026-01-01", "2026-03-01"), headliners.map { it.date })
    }

    // ── Longest session ──────────────────────────────────────────────────────

    @Test
    fun `a session is a run of consecutive hours`() {
        val hours = listOf(
            hour("2026-03-14", 19, 60),
            hour("2026-03-14", 20, 60),
            hour("2026-03-14", 21, 45),
            // A gap at 22:00, so this is a separate, shorter run.
            hour("2026-03-14", 23, 50),
        )
        val session = buildMoments(hours, emptyList(), names, selectedYear = null).longestSession
        assertNotNull(session)
        // The three-hour run, not all four rows summed as if the gap were not there.
        assertEquals(165 * 60L, session.seconds)
        assertEquals("2026-03-14", session.date)
    }

    @Test
    fun `a session crossing midnight stays one session`() {
        val hours = listOf(
            hour("2026-03-14", 22, 60),
            hour("2026-03-14", 23, 60),
            hour("2026-03-15", 0, 60),
            hour("2026-03-15", 1, 30),
        )
        val session = buildMoments(hours, emptyList(), names, selectedYear = null).longestSession
        assertNotNull(session)
        // Refusing to step from 23:00 to the next day's 00:00 halves exactly the sessions
        // most worth naming, and would report 2h here instead of 3h30m.
        assertEquals(210 * 60L, session.seconds)
        // Dated to the evening it began, not the morning it ended.
        assertEquals("2026-03-14", session.date)
    }

    @Test
    fun `midnight only joins genuinely adjacent days`() {
        val hours = listOf(
            hour("2026-03-14", 22, 60),
            hour("2026-03-14", 23, 60),
            // A week later, so 23:00 and 00:00 must not be spliced together.
            hour("2026-03-21", 0, 60),
            hour("2026-03-21", 1, 60),
        )
        val session = buildMoments(hours, emptyList(), names, selectedYear = null).longestSession
        assertNotNull(session)
        assertEquals(120 * 60L, session.seconds)
    }

    @Test
    fun `thin hours do not add up to a session`() {
        val hours = buildList {
            // A whole day of ten-minute dips: twenty-four consecutive hours holding four
            // hours of actual watching between them.
            repeat(24) { add(hour("2026-03-14", it, 10)) }
            // A real three-hour evening, holding less total time than that chain.
            add(hour("2026-03-20", 20, 60))
            add(hour("2026-03-20", 21, 60))
            add(hour("2026-03-20", 22, 60))
        }
        val session = buildMoments(hours, emptyList(), names, selectedYear = null).longestSession
        assertNotNull(session)
        // Without the per-hour floor the chain sums to 4h and outranks the evening, and the
        // page tells someone they watched four hours straight on a day they never sat down
        // for more than ten minutes. The rows only say something played in that hour.
        assertEquals("2026-03-20", session.date)
        assertEquals(180 * 60L, session.seconds)
    }

    @Test
    fun `one hour on its own is not a session`() {
        val hours = listOf(hour("2026-03-14", 20, 55), hour("2026-03-20", 21, 50))
        assertNull(buildMoments(hours, emptyList(), names, selectedYear = null).longestSession)
    }

    @Test
    fun `sessions are ranked by the time in them`() {
        val hours = listOf(
            // Three hours, but thin ones — 60 minutes total.
            hour("2026-03-14", 9, 20),
            hour("2026-03-14", 10, 20),
            hour("2026-03-14", 11, 20),
            // Two hours holding twice as much.
            hour("2026-03-20", 20, 60),
            hour("2026-03-20", 21, 60),
        )
        val session = buildMoments(hours, emptyList(), names, selectedYear = null).longestSession
        assertNotNull(session)
        // The page prints the time, so ranking on span would print "1h" and call it the
        // longest sitting while a two-hour evening sat unmentioned.
        assertEquals("2026-03-20", session.date)
        assertEquals(120 * 60L, session.seconds)
    }
}
