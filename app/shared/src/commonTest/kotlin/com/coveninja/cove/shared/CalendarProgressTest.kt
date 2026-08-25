package com.coveninja.cove.shared

import com.coveninja.cove.shared.data.applyWatchProgress
import com.coveninja.cove.shared.data.pendingEpisodes
import com.coveninja.cove.shared.model.AiredSeason
import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarProgressTest {

    private fun backlog(
        tmdbId: Int = 2710,
        season: Int = 18,
        episode: Int = 1,
        waitingCount: Int = 2,
        airedSeasons: List<AiredSeason> = listOf(AiredSeason(18, 2)),
    ) = CalendarItem(
        date = "2026-08-17",
        kind = CalendarItem.KIND_AVAILABLE,
        tmdbId = tmdbId,
        mediaType = MediaType.Tv.wireName,
        title = "It's Always Sunny in Philadelphia",
        posterPath = "/poster.jpg",
        seasonNumber = season,
        episodeNumber = episode,
        episodeName = "Frank Marries a Corpse",
        stillPath = "/still.jpg",
        waitingCount = waitingCount,
        airedSeasons = airedSeasons,
    )

    private fun watched(
        tmdbId: Int = 2710,
        mediaType: MediaType = MediaType.Tv,
        season: Int? = null,
        episode: Int? = null,
        completed: Boolean = true,
    ) = WatchProgress(
        id = "$tmdbId:$season:$episode",
        tmdbId = tmdbId,
        mediaType = mediaType,
        season = season,
        episode = episode,
        completed = completed,
        watchedAt = "2026-08-19T20:00:00Z",
    )

    // The reported bug: both aired episodes of the season were finished, and the entry went
    // on advertising two waiting because the snapshot predated the viewing.
    @Test
    fun `a backlog watched to the end leaves the calendar`() {
        val items = applyWatchProgress(
            listOf(backlog()),
            listOf(watched(season = 18, episode = 1), watched(season = 18, episode = 2)),
        )

        assertTrue(items.isEmpty(), "a show with nothing left to watch is not waiting")
    }

    @Test
    fun `finishing one episode advances the entry and decrements the count`() {
        val item = applyWatchProgress(listOf(backlog()), listOf(watched(season = 18, episode = 1)))
            .single()

        assertEquals(18, item.seasonNumber)
        assertEquals(2, item.episodeNumber)
        assertEquals(1, item.waitingCount)
    }

    // The name and still came from the episode the snapshot named. Once the head moves past
    // it they describe something already watched, so the card must not keep showing them.
    @Test
    fun `an advanced entry drops the episode name and still it no longer describes`() {
        val item = applyWatchProgress(listOf(backlog()), listOf(watched(season = 18, episode = 1)))
            .single()

        assertEquals("", item.episodeName)
        assertEquals("", item.stillPath)
    }

    // The other half of the rule above: an entry whose head has not moved still describes
    // the episode it names, so recounting must leave it exactly as it was.
    @Test
    fun `an entry nothing has been watched from is returned untouched`() {
        val item = backlog()

        val result = applyWatchProgress(listOf(item), listOf(watched(season = 17, episode = 8)))

        assertEquals(item, result.single())
    }

    // Watching out of order still counts: the head is the first episode that is *not* done,
    // not the one after the last one that is.
    @Test
    fun `the head is the earliest unwatched episode, not the one after the newest`() {
        val item = applyWatchProgress(
            listOf(backlog(episode = 1, waitingCount = 3, airedSeasons = listOf(AiredSeason(18, 3)))),
            listOf(watched(season = 18, episode = 2)),
        ).single()

        assertEquals(1, item.episodeNumber)
        assertEquals(2, item.waitingCount)
    }

    // Progress rows are per title, and a set keyed by episode alone would let one show's
    // viewing erase another's backlog.
    @Test
    fun `another show's viewing does not count against this one`() {
        val items = applyWatchProgress(
            listOf(backlog(tmdbId = 2710), backlog(tmdbId = 1668)),
            listOf(watched(tmdbId = 2710, season = 18, episode = 1)),
        )

        assertEquals(listOf(1, 2), items.map { it.waitingCount })
    }

    @Test
    fun `an episode left part-way through is still waiting`() {
        val item = applyWatchProgress(
            listOf(backlog()),
            listOf(watched(season = 18, episode = 1, completed = false)),
        ).single()

        assertEquals(2, item.waitingCount)
        assertEquals(1, item.episodeNumber)
    }

    // A dated entry is about when something comes out, not about whether it has been seen —
    // a re-release the viewer already watched still has a date worth showing.
    @Test
    fun `a dated release stays on the calendar even when it has been watched`() {
        val upcoming = CalendarItem(
            date = "2026-09-15",
            kind = CalendarItem.KIND_MOVIE,
            tmdbId = 615173,
            mediaType = MediaType.Movie.wireName,
            title = "The Witch: Part 2",
            posterPath = "/poster.jpg",
        )

        val result = applyWatchProgress(
            listOf(upcoming),
            listOf(watched(tmdbId = 615173, mediaType = MediaType.Movie)),
        )

        assertEquals(upcoming, result.single())
    }

    @Test
    fun `a watched movie leaves the calendar`() {
        val movie = CalendarItem(
            date = "2026-08-01",
            kind = CalendarItem.KIND_AVAILABLE,
            tmdbId = 615173,
            mediaType = MediaType.Movie.wireName,
            title = "The Witch: Part 2",
            posterPath = "/poster.jpg",
        )

        val result = applyWatchProgress(
            listOf(movie),
            listOf(watched(tmdbId = 615173, mediaType = MediaType.Movie)),
        )

        assertTrue(result.isEmpty(), "a film that has been watched is not waiting")
    }

    // Snapshots written before the aired seasons existed cannot be recounted, and guessing
    // is worse than waiting for the refresh the schema bump forces.
    @Test
    fun `an entry from an older snapshot is passed through`() {
        val legacy = backlog(airedSeasons = emptyList())

        val result = applyWatchProgress(
            listOf(legacy),
            listOf(watched(season = 18, episode = 1), watched(season = 18, episode = 2)),
        )

        assertEquals(legacy, result.single())
    }

    @Test
    fun `pending episodes run season by season and stop at what has aired`() {
        val pending = pendingEpisodes(
            listOf(AiredSeason(1, 3), AiredSeason(2, 2)),
            completed = setOf(1 to 2),
        )

        assertEquals(listOf(1 to 1, 1 to 3, 2 to 1, 2 to 2), pending)
    }
}
