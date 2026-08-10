package com.coveninja.cove.ui.state

import com.coveninja.cove.ui.model.MediaSeason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpisodeSequenceTest {

    private fun season(number: Int, episodes: Int) =
        MediaSeason(number = number, title = "Season $number", episodeCount = episodes)

    // Mutation applied to verify: used >= instead of > → test failed, it offered
    // an episode 13 in a season that only has twelve.
    @Test
    fun `the next episode in the same season comes first`() {
        assertEquals(2 to 5, nextEpisodeAfter(listOf(season(2, 12)), season = 2, episode = 4))
    }

    // Mutation applied to verify: returned season + 1 → test failed on a show
    // whose next season number is not consecutive.
    @Test
    fun `the last episode rolls into the next season that exists`() {
        val seasons = listOf(season(1, 12), season(3, 10))

        assertEquals(3 to 1, nextEpisodeAfter(seasons, season = 1, episode = 12))
    }

    // Mutation applied to verify: fell back to the first season → test failed,
    // finishing the show looped back to the beginning.
    @Test
    fun `the end of the last season has nothing after it`() {
        assertNull(nextEpisodeAfter(listOf(season(1, 12)), season = 1, episode = 12))
    }

    // Without the details payload there is no episode count, so advancing would
    // be a guess at an episode that may not exist.
    // Mutation applied to verify: assumed a large season when the list was empty
    // → test failed, it advanced with no evidence.
    @Test
    fun `no season data means no next episode`() {
        assertNull(nextEpisodeAfter(emptyList(), season = 1, episode = 1))
    }

    // Mutation applied to verify: picked the largest later season → test failed,
    // it skipped season 2 entirely.
    @Test
    fun `the nearest later season wins, not the furthest`() {
        val seasons = listOf(season(1, 6), season(2, 6), season(5, 6))

        assertEquals(2 to 1, nextEpisodeAfter(seasons, season = 1, episode = 6))
    }
}
