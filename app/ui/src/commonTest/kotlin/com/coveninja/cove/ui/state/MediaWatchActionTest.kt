package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType as DomainMediaType
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaSeason
import com.coveninja.cove.ui.model.MediaType as UiMediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaWatchActionTest {

    @Test
    fun `a title never played says Watch`() {
        assertEquals("Watch", mediaWatchAction(movie(), null, emptyList()).label)
        assertEquals(
            "Watch",
            mediaWatchAction(series(), entry(season = null, episode = null), emptyList()).label,
        )
    }

    @Test
    fun `a completed episode advances the series label`() {
        val action = mediaWatchAction(
            media = series(),
            entry = entry(season = 1, episode = 1),
            progressRows = listOf(progress(season = 1, episode = 1, completed = true)),
        )

        assertEquals("Continue S1E2", action.label)
        assertEquals(1, action.season)
        assertEquals(2, action.episode)
        assertNull(action.positionSeconds)
    }

    @Test
    fun `an unfinished episode includes its resume clock`() {
        val action = mediaWatchAction(
            media = series(),
            entry = entry(season = 1, episode = 2),
            progressRows = listOf(
                progress(
                    season = 1,
                    episode = 2,
                    positionSeconds = 1_951.0,
                    durationSeconds = 2_700.0,
                ),
            ),
        )

        assertEquals("Continue S1E2 32:31", action.label)
        assertEquals(1_951.0, action.positionSeconds)
    }

    @Test
    fun `an unfinished movie includes its resume clock`() {
        val action = mediaWatchAction(
            media = movie(),
            entry = null,
            progressRows = listOf(
                progress(
                    mediaType = DomainMediaType.Movie,
                    positionSeconds = 5_551.0,
                    durationSeconds = 7_200.0,
                ),
            ),
        )

        assertEquals("Continue 1:32:31", action.label)
    }

    @Test
    fun `a resume point in the tail is not advertised`() {
        val action = mediaWatchAction(
            media = movie(),
            entry = null,
            progressRows = listOf(
                progress(
                    mediaType = DomainMediaType.Movie,
                    positionSeconds = 3_590.0,
                    durationSeconds = 3_600.0,
                ),
            ),
        )

        assertEquals("Watch", action.label)
        assertNull(action.positionSeconds)
    }

    @Test
    fun `a completed title offers the episode playback will replay`() {
        val action = mediaWatchAction(
            media = series(seasons = listOf(MediaSeason(1, "Season 1", episodeCount = 2))),
            entry = entry(season = 1, episode = 2),
            progressRows = listOf(progress(season = 1, episode = 2, completed = true)),
        )

        assertEquals("Watch Again S1E2", action.label)
        assertEquals(1, action.season)
        assertEquals(2, action.episode)
    }

    private fun movie() = media(type = UiMediaType.Movie, seasons = emptyList())

    private fun series(
        seasons: List<MediaSeason> = listOf(MediaSeason(1, "Season 1", episodeCount = 8)),
    ) = media(type = UiMediaType.Series, seasons = seasons)

    private fun media(type: UiMediaType, seasons: List<MediaSeason>) = Media(
        id = "${type.name}:1396",
        tmdbId = 1396,
        title = "Example",
        name = null,
        overview = null,
        released = null,
        firstAirDate = null,
        posterUrl = null,
        logoUrl = null,
        backdropUrl = null,
        rating = null,
        type = type,
        popularity = null,
        adult = null,
        originalLanguage = null,
        seasons = seasons,
    )

    private fun entry(season: Int?, episode: Int?) = LibraryEntry(
        id = "entry",
        tmdbId = 1396,
        mediaType = DomainMediaType.Tv,
        title = "Example",
        status = LibraryStatus.Watching,
        lastWatchedSeason = season,
        lastWatchedEpisode = episode,
    )

    private fun progress(
        mediaType: DomainMediaType = DomainMediaType.Tv,
        season: Int? = null,
        episode: Int? = null,
        positionSeconds: Double = 0.0,
        durationSeconds: Double = 0.0,
        completed: Boolean = false,
    ) = WatchProgress(
        id = "progress-${season.orEmpty()}-${episode.orEmpty()}",
        tmdbId = 1396,
        mediaType = mediaType,
        season = season,
        episode = episode,
        positionSeconds = positionSeconds,
        durationSeconds = durationSeconds,
        completed = completed,
        watchedAt = "2026-08-22T12:00:00Z",
    )
}

private fun Int?.orEmpty(): String = this?.toString().orEmpty()
