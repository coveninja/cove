package com.coveninja.cove.ui.state

import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaVideo
import com.coveninja.cove.ui.model.MediaType as UiMediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the player tells the outside world it is playing.
 *
 * This is the whole of the fix for a lock screen that read "Cove": the host never received the
 * media's identity, only a URL, so the notification had nothing else it could have said.
 */
class NowPlayingTest {

    @Test
    fun `a film is its own title, with nothing under it`() {
        val playing = PlaybackRequest(media = movie()).nowPlaying()
        assertEquals("Fight Club", playing.title)
        assertNull(playing.subtitle, "a film has no episode line")
    }

    /**
     * The title and the episode stay apart, because the two consumers use them differently: a
     * notification puts them on separate lines and a window title uses only the first.
     */
    @Test
    fun `an episode keeps its title and its number separate`() {
        val playing = PlaybackRequest(
            media = series(),
            season = 2,
            episode = 4,
            episodeTitle = "Down",
        ).nowPlaying()
        assertEquals("Breaking Bad", playing.title)
        assertEquals("S2E4 · Down", playing.subtitle)
    }

    // Many releases carry no episode names at all; the number alone is still worth showing.
    @Test
    fun `an untitled episode is still numbered`() {
        val playing = PlaybackRequest(media = series(), season = 1, episode = 7).nowPlaying()
        assertEquals("S1E7", playing.subtitle)
    }

    @Test
    fun `a blank episode name is treated as no name`() {
        val playing = PlaybackRequest(
            media = series(),
            season = 1,
            episode = 7,
            episodeTitle = "   ",
        ).nowPlaying()
        assertEquals("S1E7", playing.subtitle)
    }

    /** An extra is named by the video, under the title it belongs to. */
    @Test
    fun `a trailer names itself beneath the film`() {
        val playing = PlaybackRequest(media = movie(), extra = trailer()).nowPlaying()
        assertEquals("Fight Club", playing.title)
        assertEquals("Official Trailer", playing.subtitle)
    }

    // The one-line form feeds the panels that have room for a single string, and must stay
    // consistent with the two-part form above rather than being composed separately.
    @Test
    fun `the single line label is the two parts joined`() {
        val request = PlaybackRequest(
            media = series(),
            season = 2,
            episode = 4,
            episodeTitle = "Down",
        )
        assertEquals("Breaking Bad · S2E4 · Down", request.label)
        assertEquals("Fight Club", PlaybackRequest(media = movie()).label)
    }

    @Test
    fun `a title with neither name falls back rather than showing nothing`() {
        val nameless = movie().copy(title = null, name = null)
        assertEquals("Untitled", PlaybackRequest(media = nameless).nowPlaying().title)
    }

    /**
     * A stored poster can still be a bare TMDB path from an older install; a notification
     * cannot fetch one of those, so it has to be resolved to a real URL on the way out.
     */
    @Test
    fun `a bare tmdb path is resolved into a fetchable url`() {
        val withPoster = movie().copy(posterUrl = "/abc123.jpg")
        val artwork = PlaybackRequest(media = withPoster).nowPlaying().artworkUrl
        assertTrue(artwork != null && artwork.startsWith("http"), "was $artwork")
    }

    @Test
    fun `a title with no poster reports none rather than a broken url`() {
        assertNull(PlaybackRequest(media = movie()).nowPlaying().artworkUrl)
    }
}

private fun series() = Media(
    id = "Series:1396",
    tmdbId = 1396,
    title = null,
    name = "Breaking Bad",
    overview = null,
    released = null,
    firstAirDate = null,
    posterUrl = null,
    logoUrl = null,
    backdropUrl = null,
    rating = 8.9,
    type = UiMediaType.Series,
    popularity = null,
    adult = null,
    originalLanguage = null,
)

private fun movie() = Media(
    id = "Movie:550",
    tmdbId = 550,
    title = "Fight Club",
    name = null,
    overview = null,
    released = null,
    firstAirDate = null,
    posterUrl = null,
    logoUrl = null,
    backdropUrl = null,
    rating = 8.8,
    type = UiMediaType.Movie,
    popularity = null,
    adult = null,
    originalLanguage = null,
)

private fun trailer() = MediaVideo(
    id = "Movie:550-abc",
    title = "Official Trailer",
    thumbnailUrl = null,
    type = "Trailer",
)
