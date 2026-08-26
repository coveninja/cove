package com.coveninja.cove.shared.model

import com.coveninja.cove.shared.network.CoveJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ActivityStats] is the wire contract for `GET /library/activity`, so a compatibility
 * client on an older build decodes whatever a newer host sends and vice versa. The
 * `@SerialName`s and the defaults are the whole of that agreement, and nothing about a
 * Kotlin field rename would fail a build if they drifted.
 */
class ActivityStatsWireTest {

    @Test
    fun `a payload from a host that predates moments still decodes`() {
        // Exactly what an older backend answers with: no moment fields at all.
        val legacy = """
            {
              "total_seconds": 7200,
              "total_titles": 4,
              "current_streak": 2,
              "longest_streak": 9,
              "titles_this_year": 3,
              "calendar": { "2026-03-14": 7200 }
            }
        """.trimIndent()

        val decoded = CoveJson.decodeFromString<ActivityStats>(legacy)

        assertEquals(7200L, decoded.totalSeconds)
        // Absent means absent, not zero-filled with a moment that never happened. A page
        // reading these has to be able to tell "no biggest day" from "a biggest day of 0s".
        assertNull(decoded.biggestDay)
        assertNull(decoded.longestSession)
        assertNull(decoded.firstWatch)
        assertTrue(decoded.monthlyHeadliners.isEmpty())
    }

    @Test
    fun `the moment field names survive a round trip`() {
        val stats = ActivityStats(
            totalSeconds = 7200,
            biggestDay = WatchMoment(
                date = "2026-03-14",
                seconds = 7200,
                tmdbId = 42,
                mediaType = "tv",
                title = "Severance",
                posterPath = "/sev.jpg",
            ),
            monthlyHeadliners = listOf(
                WatchMoment(date = "2026-01-01", seconds = 3600, tmdbId = 7, mediaType = "movie"),
            ),
            longestSession = WatchMoment(date = "2026-03-14", seconds = 5400, tmdbId = 42, mediaType = "tv"),
            firstWatch = WatchMoment(date = "2026-01-02", seconds = 900, tmdbId = 7, mediaType = "movie"),
        )

        val encoded = CoveJson.encodeToString(stats)

        // The snake_case names are the contract; a Kotlin rename that dropped a SerialName
        // would still round-trip through Kotlin and break every HTTP client silently.
        assertTrue(encoded.contains("\"biggest_day\""), encoded)
        assertTrue(encoded.contains("\"monthly_headliners\""), encoded)
        assertTrue(encoded.contains("\"longest_session\""), encoded)
        assertTrue(encoded.contains("\"first_watch\""), encoded)
        assertTrue(encoded.contains("\"tmdb_id\""), encoded)
        assertTrue(encoded.contains("\"poster_path\""), encoded)

        assertEquals(stats, CoveJson.decodeFromString<ActivityStats>(encoded))
    }

    @Test
    fun `a moment with no date is empty whatever seconds it carries`() {
        assertTrue(WatchMoment(date = "", seconds = 900).isEmpty)
        assertTrue(WatchMoment(date = "2026-03-14", seconds = 0).isEmpty)
        assertTrue(!WatchMoment(date = "2026-03-14", seconds = 1).isEmpty)
    }
}
