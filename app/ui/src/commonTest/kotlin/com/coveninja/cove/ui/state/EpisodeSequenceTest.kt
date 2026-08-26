package com.coveninja.cove.ui.state

import com.coveninja.cove.ui.model.MediaSeason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpisodeSequenceTest {

    private fun season(number: Int, episodes: Int) =
        MediaSeason(number = number, title = "Season $number", episodeCount = episodes)

    @Test
    fun `the next episode in the same season comes first`() {
        assertEquals(2 to 5, nextEpisodeAfter(listOf(season(2, 12)), season = 2, episode = 4))
    }

    @Test
    fun `the last episode rolls into the next season that exists`() {
        val seasons = listOf(season(1, 12), season(3, 10))

        assertEquals(3 to 1, nextEpisodeAfter(seasons, season = 1, episode = 12))
    }

    @Test
    fun `the end of the last season has nothing after it`() {
        assertNull(nextEpisodeAfter(listOf(season(1, 12)), season = 1, episode = 12))
    }

    // Without the details payload there is no episode count, so advancing would
    // be a guess at an episode that may not exist.
    @Test
    fun `no season data means no next episode`() {
        assertNull(nextEpisodeAfter(emptyList(), season = 1, episode = 1))
    }

    @Test
    fun `the nearest later season wins, not the furthest`() {
        val seasons = listOf(season(1, 6), season(2, 6), season(5, 6))

        assertEquals(2 to 1, nextEpisodeAfter(seasons, season = 1, episode = 6))
    }

    @Test
    fun `the previous episode in the same season comes first`() {
        assertEquals(2 to 3, previousEpisodeBefore(listOf(season(2, 12)), season = 2, episode = 4))
    }

    // The whole point of stepping back across a boundary: episode one of season three goes to
    // the *last* episode of season two, not its first. Landing on episode one would make the
    // button useless for walking backwards through a series, which is all it is for.
    @Test
    fun `the first episode steps back onto the end of the previous season`() {
        val seasons = listOf(season(1, 12), season(3, 10))

        assertEquals(1 to 12, previousEpisodeBefore(seasons, season = 3, episode = 1))
    }

    @Test
    fun `the very first episode has nothing before it`() {
        assertNull(previousEpisodeBefore(listOf(season(1, 12)), season = 1, episode = 1))
    }

    // The count is what says which episode ended the previous season, so without it stopping at
    // the boundary is the honest answer rather than guessing at episode one.
    @Test
    fun `a previous season with no episode count stops the walk`() {
        val seasons = listOf(season(1, 0), season(2, 10))

        assertNull(previousEpisodeBefore(seasons, season = 2, episode = 1))
    }

    @Test
    fun `the nearest earlier season wins, not the earliest`() {
        val seasons = listOf(season(1, 6), season(2, 8), season(5, 6))

        assertEquals(2 to 8, previousEpisodeBefore(seasons, season = 5, episode = 1))
    }
}
