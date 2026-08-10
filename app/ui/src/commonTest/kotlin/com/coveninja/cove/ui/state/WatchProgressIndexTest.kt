package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchProgressIndexTest {

    private fun progress(
        tmdbId: Int = 1,
        mediaType: MediaType = MediaType.Movie,
        season: Int? = null,
        episode: Int? = null,
        position: Double = 0.0,
        duration: Double = 0.0,
        completed: Boolean = false,
        watchedAt: String = "2026-08-01T00:00:00Z",
    ) = WatchProgress(
        id = "p-$tmdbId-$season-$episode",
        libraryEntryId = "entry-$tmdbId",
        tmdbId = tmdbId,
        mediaType = mediaType,
        season = season,
        episode = episode,
        positionSeconds = position,
        durationSeconds = duration,
        completed = completed,
        watchedAt = watchedAt,
    )

    private fun entry(
        lastWatchedSeason: Int? = null,
        lastWatchedEpisode: Int? = null,
        lastAiredSeason: Int? = null,
        lastAiredEpisode: Int? = null,
    ) = LibraryEntry(
        id = "entry",
        tmdbId = 1,
        mediaType = MediaType.Tv,
        title = "Show",
        status = LibraryStatus.Watching,
        lastWatchedSeason = lastWatchedSeason,
        lastWatchedEpisode = lastWatchedEpisode,
        lastAiredSeason = lastAiredSeason,
        lastAiredEpisode = lastAiredEpisode,
    )

    // Zero position over zero duration is 0/0. That is NaN, and NaN fails every range
    // comparison, so it would slip past the bounds check below and reach the bar as a
    // width no layout can use — only the duration guard stops it.
    // Mutation applied to verify: relaxed the duration guard to `< 0.0` → test failed,
    // watchFraction returned NaN.
    @Test
    fun `a resume point with no duration cannot be a fraction`() {
        assertNull(watchFraction(progress(position = 300.0, duration = 0.0)))
        assertNull(watchFraction(progress(position = 0.0, duration = 0.0)))
        assertNull(watchFraction(null))
    }

    // A finished title is not "mid-watch"; a full bar would say the opposite of the truth.
    // Mutation applied to verify: ignored the completed flag → test failed, a finished
    // movie reported 0.5.
    @Test
    fun `a completed title reports no fraction`() {
        assertNull(watchFraction(progress(position = 600.0, duration = 1200.0, completed = true)))
    }

    // Mutation applied to verify: removed the lower and upper bounds → test failed, a
    // title abandoned after ten seconds drew a visible bar.
    @Test
    fun `barely started and all but finished both count as nothing to draw`() {
        assertNull(watchFraction(progress(position = 10.0, duration = 7200.0)))
        assertNull(watchFraction(progress(position = 7190.0, duration = 7200.0)))
        assertEquals(0.5f, watchFraction(progress(position = 3600.0, duration = 7200.0)))
    }

    // Mutation applied to verify: kept the first row seen rather than the newest → test
    // failed, the index reported the older episode's position.
    @Test
    fun `the newest row wins for a title with several`() {
        val rows = listOf(
            progress(
                tmdbId = 5, mediaType = MediaType.Tv, season = 1, episode = 1,
                position = 100.0, duration = 1000.0, watchedAt = "2026-08-01T00:00:00Z",
            ),
            progress(
                tmdbId = 5, mediaType = MediaType.Tv, season = 1, episode = 2,
                position = 500.0, duration = 1000.0, watchedAt = "2026-08-09T00:00:00Z",
            ),
        )

        val index = WatchProgressIndex(watchProgressByUiId(rows))

        assertEquals(2, index.progressFor("Series:5")?.episode)
        assertEquals(0.5f, index.fractionFor("Series:5"))
    }

    // Mutation applied to verify: preferred the completed row on a tie → test failed, a
    // show sitting mid-episode showed the episode before it instead.
    @Test
    fun `on an identical timestamp the unfinished episode wins`() {
        val rows = listOf(
            progress(
                tmdbId = 5, mediaType = MediaType.Tv, season = 1, episode = 1,
                position = 1000.0, duration = 1000.0, completed = true,
                watchedAt = "2026-08-09T00:00:00Z",
            ),
            progress(
                tmdbId = 5, mediaType = MediaType.Tv, season = 1, episode = 2,
                position = 300.0, duration = 1000.0,
                watchedAt = "2026-08-09T00:00:00Z",
            ),
        )

        val index = WatchProgressIndex(watchProgressByUiId(rows))

        assertEquals(2, index.progressFor("Series:5")?.episode)
    }

    // Mutation applied to verify: keyed movies and series alike → test failed, the movie
    // and the series with the same TMDB id collapsed into one entry.
    @Test
    fun `a movie and a series sharing an id stay separate`() {
        val rows = listOf(
            progress(tmdbId = 7, mediaType = MediaType.Movie, position = 100.0, duration = 1000.0),
            progress(
                tmdbId = 7, mediaType = MediaType.Tv, season = 1, episode = 1,
                position = 800.0, duration = 1000.0,
            ),
        )

        val index = WatchProgressIndex(watchProgressByUiId(rows))

        assertEquals(0.1f, index.fractionFor("Movie:7"))
        assertEquals(0.8f, index.fractionFor("Series:7"))
    }

    // Mutation applied to verify: compared episode numbers without the season → test
    // failed, season 4 episode 1 read as behind season 3 episode 7.
    @Test
    fun `a new season counts as new episodes even at episode one`() {
        assertTrue(
            entry(
                lastWatchedSeason = 3, lastWatchedEpisode = 7,
                lastAiredSeason = 4, lastAiredEpisode = 1,
            ).hasUnwatchedAired(),
        )
    }

    // Mutation applied to verify: used >= instead of > → test failed, a fully caught-up
    // show claimed to have new episodes forever.
    @Test
    fun `caught up is not new`() {
        assertFalse(
            entry(
                lastWatchedSeason = 3, lastWatchedEpisode = 7,
                lastAiredSeason = 3, lastAiredEpisode = 7,
            ).hasUnwatchedAired(),
        )
    }

    // An unstarted show has nothing "new" about it — that is just a show, and the card
    // already says so by having no progress.
    // Mutation applied to verify: defaulted the missing watched position to (0, 0) → test
    // failed, every unstarted series wore a NEW badge.
    @Test
    fun `a show never started has no new episodes`() {
        assertFalse(entry(lastAiredSeason = 2, lastAiredEpisode = 8).hasUnwatchedAired())
    }
}
