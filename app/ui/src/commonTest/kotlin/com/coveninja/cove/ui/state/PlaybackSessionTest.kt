package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.ContentDetails
import com.coveninja.cove.shared.data.ContentRepository
import com.coveninja.cove.shared.data.ExploreState
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.data.LibraryRepository
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.data.PlaybackRepository
import com.coveninja.cove.shared.data.SearchState
import com.coveninja.cove.shared.data.SettingsRepository
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.fixture.FixtureAppGraph
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaTimestamps
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.model.TvEpisode
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.shared.network.WatchProgressRequest
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaSeason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.coveninja.cove.shared.model.Media as DomainMedia
import com.coveninja.cove.shared.model.MediaType as DomainMediaType
import com.coveninja.cove.ui.model.MediaType as UiMediaType

// ── Fakes ────────────────────────────────────────────────────────────────────

private class FakeLibrary : LibraryRepository {
    val entriesList = MutableStateFlow<LibraryState>(LibraryState.Ready(emptyList()))
    override val entries: StateFlow<LibraryState> = entriesList

    var episodeStates: Map<Pair<Int, Int>, Boolean> = emptyMap()
    var storedProgress: WatchProgress? = null
    val recorded = mutableListOf<WatchProgressRequest>()

    override suspend fun add(
        tmdbId: Int,
        mediaType: DomainMediaType,
        title: String,
        posterPath: String,
        voteAverage: Double,
    ) = Unit

    override suspend fun remove(tmdbId: Int, mediaType: DomainMediaType) = Unit
    override suspend fun setStatus(tmdbId: Int, mediaType: DomainMediaType, status: LibraryStatus) = Unit
    override suspend fun setRating(tmdbId: Int, mediaType: DomainMediaType, rating: Double?) = Unit
    override suspend fun setDismissed(tmdbId: Int, mediaType: DomainMediaType, dismissed: Boolean) = Unit

    override suspend fun episodeWatchStates(
        tmdbId: Int,
        mediaType: DomainMediaType,
    ): Map<Pair<Int, Int>, Boolean> = episodeStates

    override suspend fun setEpisodeWatched(
        tmdbId: Int,
        title: String,
        posterPath: String,
        voteAverage: Double,
        season: Int,
        episode: Int,
        runtimeMinutes: Int?,
        watched: Boolean,
    ) = Unit

    override suspend fun progress(
        tmdbId: Int,
        mediaType: DomainMediaType,
        season: Int?,
        episode: Int?,
    ): WatchProgress? = storedProgress

    override suspend fun recordProgress(request: WatchProgressRequest): WatchProgress {
        recorded += request
        return WatchProgress(
            id = "p",
            libraryEntryId = "e",
            tmdbId = request.tmdbId,
            mediaType = request.mediaType,
            positionSeconds = request.positionSeconds,
            durationSeconds = request.durationSeconds,
            completed = request.completed,
        )
    }
}

private class FakePlayback(var sources: List<StreamSource>) : PlaybackRepository {
    var requestedSeason: Int? = null
    var requestedEpisode: Int? = null

    override suspend fun streams(
        tmdbId: Int,
        type: DomainMediaType,
        season: Int?,
        episode: Int?,
    ): List<StreamSource> {
        requestedSeason = season
        requestedEpisode = episode
        return sources
    }

    override suspend fun timestamps(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
    ): MediaTimestamps = MediaTimestamps.None

    override fun playUrl(source: StreamSource, season: Int?, episode: Int?): String =
        "http://127.0.0.1:6969/api/play?url=${source.url}"
}

private class FakeContent : ContentRepository {
    override val home: StateFlow<HomeState> = MutableStateFlow(HomeState.Ready(emptyList()))
    override val explore: StateFlow<ExploreState> =
        MutableStateFlow(ExploreState.Ready(emptyList(), emptyList()))
    override val searchResults: StateFlow<SearchState> = MutableStateFlow(SearchState.Idle)
    override suspend fun search(query: String) = Unit
    override suspend fun details(media: DomainMedia): ContentDetails =
        throw UnsupportedOperationException()

    override suspend fun episodes(id: Int, season: Int): List<TvEpisode> = emptyList()
}

private class FakeSettings(settings: AppSettings) : SettingsRepository {
    override val settings: StateFlow<SettingsState> = MutableStateFlow(SettingsState.Ready(settings))
    override suspend fun update(settings: AppSettings) = Unit
}

private class FakeHost : VideoPlayerHost {
    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status

    var loadedUrl: String? = null
    var loadedFrom: Double? = null
    var volumeSet: Double? = null

    fun report(position: Double, duration: Double) {
        _status.value = _status.value.copy(positionSeconds = position, durationSeconds = duration)
    }

    override fun load(url: String, startPositionSeconds: Double) {
        loadedUrl = url
        loadedFrom = startPositionSeconds
    }

    override fun setPaused(paused: Boolean) = Unit
    override fun togglePause() = Unit
    override fun seek(seconds: Double) = Unit
    override fun setVolume(volume: Double) { volumeSet = volume }
    override fun setScaling(scaling: VideoScaling) = Unit
    override fun selectAudioTrack(id: Int) = Unit
    override fun selectSubtitleTrack(id: Int?) = Unit
    override fun stop() = Unit

    @Composable
    override fun Surface(modifier: Modifier) = Unit
}

// ── Fixtures ─────────────────────────────────────────────────────────────────

private fun series(seasons: List<MediaSeason>) = Media(
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
    seasons = seasons,
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

private fun entry(season: Int, episode: Int) = LibraryEntry(
    id = "entry-1",
    tmdbId = 1396,
    mediaType = DomainMediaType.Tv,
    title = "Breaking Bad",
    status = LibraryStatus.Watching,
    lastWatchedSeason = season,
    lastWatchedEpisode = episode,
)

private val oneSource = listOf(StreamSource(name = "Only", url = "https://example.com/a.mkv"))

private class Harness(
    scheduler: TestCoroutineScheduler,
    settings: AppSettings,
    sources: List<StreamSource>,
) {
    val library = FakeLibrary()
    val playback = FakePlayback(sources)
    val host = FakeHost()
    // The addon repository plays no part in playback resolution; the fixture one
    // satisfies the graph without adding a second thing to keep in sync.
    val graph = AppGraph(
        FakeContent(),
        library,
        FakeSettings(settings),
        playback,
        FixtureAppGraph().addons,
    )
    val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    val session by lazy { PlaybackSession(graph, scope, host) }
}

/**
 * The session gets a scope detached from the test's own job, cancelled in the
 * finally. Once playback starts, the progress ticker re-arms a delay forever: as a
 * child of the test job it would keep runTest waiting, and advanceUntilIdle would
 * spin virtual time against it. Tests therefore drive it with runCurrent(), which
 * drains everything queued at the current instant and stops short of the ticker's
 * first ten-second delay.
 */
private fun playbackTest(
    settings: AppSettings = AppSettings(),
    sources: List<StreamSource> = oneSource,
    body: suspend TestScope.(Harness) -> Unit,
) = runTest {
    val harness = Harness(testScheduler, settings, sources)
    try {
        body(harness)
    } finally {
        harness.scope.cancel()
    }
}

class PlaybackSessionTest {

    // Mutation applied to verify: made the no-entry branch return season 2
    // → test failed on the expected season.
    @Test
    fun `a series never watched starts at season one episode one`() = playbackTest { h ->
        h.session.open(series(listOf(MediaSeason(1, "Season 1", episodeCount = 7))))
        runCurrent()

        assertEquals(1, h.playback.requestedSeason)
        assertEquals(1, h.playback.requestedEpisode)
    }

    // Mutation applied to verify: skipped the episodeCompleted check and always
    // advanced → test failed, it asked for episode 8 instead of 7.
    @Test
    fun `an unfinished episode resumes rather than advancing`() = playbackTest { h ->
        h.library.entriesList.value = LibraryState.Ready(listOf(entry(season = 3, episode = 7)))

        h.session.open(series(listOf(MediaSeason(3, "Season 3", episodeCount = 13))))
        runCurrent()

        assertEquals(3, h.playback.requestedSeason)
        assertEquals(7, h.playback.requestedEpisode)
    }

    // Mutation applied to verify: dropped the `episodesInSeason > lastEpisode`
    // branch → test failed, it replayed episode 7 instead of moving to 8.
    @Test
    fun `a finished episode advances within the season`() = playbackTest { h ->
        h.library.entriesList.value = LibraryState.Ready(listOf(entry(season = 3, episode = 7)))
        h.library.episodeStates = mapOf((3 to 7) to true)

        h.session.open(series(listOf(MediaSeason(3, "Season 3", episodeCount = 13))))
        runCurrent()

        assertEquals(3, h.playback.requestedSeason)
        assertEquals(8, h.playback.requestedEpisode)
    }

    // Mutation applied to verify: removed the nextSeason branch → test failed,
    // it stayed on season 3 instead of rolling over.
    @Test
    fun `the last episode of a season rolls into the next`() = playbackTest { h ->
        h.library.entriesList.value = LibraryState.Ready(listOf(entry(season = 3, episode = 13)))
        h.library.episodeStates = mapOf((3 to 13) to true)

        h.session.open(
            series(
                listOf(
                    MediaSeason(3, "Season 3", episodeCount = 13),
                    MediaSeason(4, "Season 4", episodeCount = 13),
                ),
            ),
        )
        runCurrent()

        assertEquals(4, h.playback.requestedSeason)
        assertEquals(1, h.playback.requestedEpisode)
    }

    // An explicit pick from the episode browser must beat any resume logic.
    // Mutation applied to verify: ignored the explicit arguments and always
    // resolved from the library → test failed, it asked for S3E7.
    @Test
    fun `an explicitly chosen episode wins over the resume point`() = playbackTest { h ->
        h.library.entriesList.value = LibraryState.Ready(listOf(entry(season = 3, episode = 7)))

        h.session.open(series(emptyList()), season = 1, episode = 2)
        runCurrent()

        assertEquals(1, h.playback.requestedSeason)
        assertEquals(2, h.playback.requestedEpisode)
    }

    // Mutation applied to verify: passed season/episode through for movies
    // → test failed, the movie request carried coordinates.
    @Test
    fun `a movie asks for no season or episode`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()

        assertEquals(null, h.playback.requestedSeason)
        assertEquals(null, h.playback.requestedEpisode)
    }

    // AppSettings.defaultVolume is a 0..1 fraction and mpv's is 0..100; the ×100
    // is the whole point of this test.
    // Mutation applied to verify: passed defaultVolume through unscaled
    // → test failed, volume arrived as 0.8 instead of 80.
    @Test
    fun `default volume is scaled from the settings fraction to mpv's range`() =
        playbackTest(settings = AppSettings(defaultVolume = 0.8)) { h ->
            h.session.open(movie())
            runCurrent()

            assertEquals(80.0, h.host.volumeSet)
        }

    // Mutation applied to verify: ignored rememberPosition and always resumed
    // → test failed, playback started at 610 seconds instead of 0.
    @Test
    fun `a stored position is ignored when rememberPosition is off`() =
        playbackTest(settings = AppSettings(rememberPosition = false)) { h ->
            h.library.storedProgress = WatchProgress(
                id = "p",
                libraryEntryId = "e",
                tmdbId = 550,
                mediaType = DomainMediaType.Movie,
                positionSeconds = 610.0,
                durationSeconds = 7000.0,
            )

            h.session.open(movie())
            runCurrent()

            assertEquals(0.0, h.host.loadedFrom)
        }

    // Mutation applied to verify: removed the rememberPosition condition so the
    // stored point always applied → test failed, playback started at 0.
    @Test
    fun `a stored position resumes when rememberPosition is on`() = playbackTest { h ->
        h.library.storedProgress = WatchProgress(
            id = "p",
            libraryEntryId = "e",
            tmdbId = 550,
            mediaType = DomainMediaType.Movie,
            positionSeconds = 610.0,
            durationSeconds = 7000.0,
        )

        h.session.open(movie())
        runCurrent()

        assertEquals(610.0, h.host.loadedFrom)
    }

    // Mutation applied to verify: dropped the `progress.completed` early return
    // → test failed, a finished film resumed at 6900 instead of restarting.
    @Test
    fun `a finished title restarts rather than resuming at the credits`() = playbackTest { h ->
        h.library.storedProgress = WatchProgress(
            id = "p",
            libraryEntryId = "e",
            tmdbId = 550,
            mediaType = DomainMediaType.Movie,
            positionSeconds = 6900.0,
            durationSeconds = 7000.0,
            completed = true,
        )

        h.session.open(movie())
        runCurrent()

        assertEquals(0.0, h.host.loadedFrom)
    }

    // Mutation applied to verify: raised COMPLETED_FRACTION above 1.0 → test
    // failed, the saved record was not marked completed.
    @Test
    fun `closing past ninety percent records the title as completed`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()

        h.host.report(position = 950.0, duration = 1000.0)
        h.session.close()
        runCurrent()

        val saved = assertNotNull(h.library.recorded.lastOrNull(), "no progress was recorded")
        assertTrue(saved.completed, "95% through should count as completed")
        assertEquals(950.0, saved.positionSeconds)
    }

    // Mutation applied to verify: dropped the `>= COMPLETED_FRACTION` comparison
    // in favour of a constant true → test failed, half-watched counted as done.
    @Test
    fun `closing halfway does not mark the title completed`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()

        h.host.report(position = 500.0, duration = 1000.0)
        h.session.close()
        runCurrent()

        val saved = assertNotNull(h.library.recorded.lastOrNull(), "no progress was recorded")
        assertTrue(!saved.completed, "halfway is not completed")
    }

    // Mutation applied to verify: removed the `position <= 0.0` guard → test
    // failed, a zero position was written over the stored resume point.
    @Test
    fun `closing before playback starts records nothing`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()

        // mpv has reported no duration yet — nothing worth persisting.
        h.session.close()
        runCurrent()

        assertTrue(h.library.recorded.isEmpty(), "was: ${h.library.recorded}")
    }

    // Mutation applied to verify: removed the size check so a single source also
    // opened the picker → test failed, the phase was Choosing not Playing.
    @Test
    fun `a lone source plays without asking`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()

        assertTrue(h.session.phase is PlaybackPhase.Playing, "was: ${h.session.phase}")
        assertNotNull(h.host.loadedUrl)
    }

    // Mutation applied to verify: auto-played the first of several sources
    // → test failed, the phase was Playing not Choosing.
    @Test
    fun `several sources ask the viewer to choose`() = playbackTest(
        sources = listOf(
            StreamSource(name = "A", url = "https://example.com/a.mkv", sizeBytes = 100),
            StreamSource(name = "B", url = "https://example.com/b.mkv", sizeBytes = 900),
        ),
    ) { h ->
        h.session.open(movie())
        runCurrent()

        val phase = h.session.phase
        assertTrue(phase is PlaybackPhase.Choosing, "was: $phase")
        // Ranked, so the larger file leads.
        assertEquals("B", phase.sources.first().name)
    }

    // Mutation applied to verify: kept sources with neither url nor hash
    // → test failed, an unplayable entry was offered as a choice.
    @Test
    fun `sources with nothing to play are discarded`() = playbackTest(
        sources = listOf(
            StreamSource(name = "Broken", url = "", infoHash = ""),
            StreamSource(name = "Good", url = "https://example.com/a.mkv"),
        ),
    ) { h ->
        h.session.open(movie())
        runCurrent()

        // Only one survived the filter, so it plays outright.
        assertTrue(h.session.phase is PlaybackPhase.Playing, "was: ${h.session.phase}")
    }

    // Mutation applied to verify: reported Failed only on an exception, not on an
    // empty list → test failed, the phase stayed Resolving.
    @Test
    fun `no sources reports why instead of hanging`() = playbackTest(sources = emptyList()) { h ->
        h.session.open(movie())
        runCurrent()

        val phase = h.session.phase
        assertTrue(phase is PlaybackPhase.Failed, "was: $phase")
        assertTrue(phase.message.contains("addon"), "should point at addons: ${phase.message}")
    }

    // The "choose a source" entry point exists to show what is on offer, so it
    // must ask even when asking looks pointless.
    // Mutation applied to verify: let the size==1 shortcut run before the
    // forcePicker branch → test failed, it played instead of asking.
    @Test
    fun `forcing the picker asks even for a single source`() = playbackTest { h ->
        h.session.open(movie(), forcePicker = true)
        runCurrent()

        assertTrue(h.session.phase is PlaybackPhase.Choosing, "was: ${h.session.phase}")
        assertEquals(null, h.host.loadedUrl, "nothing should have started playing")
    }

    // Mutation applied to verify: ignored autoSelectStream → test failed, the
    // picker opened for a viewer who asked never to be asked.
    @Test
    fun `autoSelectStream plays the top candidate without asking`() = playbackTest(
        settings = AppSettings(autoSelectStream = true),
        sources = listOf(
            StreamSource(name = "A", url = "https://example.com/a.mkv", sizeBytes = 100),
            StreamSource(name = "B", url = "https://example.com/b.mkv", sizeBytes = 900),
        ),
    ) { h ->
        h.session.open(movie())
        runCurrent()

        val phase = h.session.phase
        assertTrue(phase is PlaybackPhase.Playing, "was: $phase")
        // Ranked first, so the larger file is the one that plays.
        assertEquals("B", phase.source.name)
    }

    // autoSelectStream must not override an explicit request to choose.
    // Mutation applied to verify: ordered the autoSelectStream branch before the
    // forcePicker branch → test failed, it auto-played.
    @Test
    fun `forcing the picker overrides autoSelectStream`() = playbackTest(
        settings = AppSettings(autoSelectStream = true),
        sources = listOf(
            StreamSource(name = "A", url = "https://example.com/a.mkv"),
            StreamSource(name = "B", url = "https://example.com/b.mkv"),
        ),
    ) { h ->
        h.session.open(movie(), forcePicker = true)
        runCurrent()

        assertTrue(h.session.phase is PlaybackPhase.Choosing, "was: ${h.session.phase}")
    }

    // Mutation applied to verify: dropped forcePicker from reopenSources → test
    // failed, the lone source replayed instead of the list reappearing.
    @Test
    fun `reopening sources returns to the list mid-playback`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()
        assertTrue(h.session.phase is PlaybackPhase.Playing, "setup: ${h.session.phase}")

        h.session.reopenSources()
        runCurrent()

        assertTrue(h.session.phase is PlaybackPhase.Choosing, "was: ${h.session.phase}")
    }

    // Reopening keeps the episode it was already on rather than re-deriving one.
    // Mutation applied to verify: passed null season/episode from reopenSources
    // → test failed, resolution fell back to S1E1.
    @Test
    fun `reopening sources stays on the same episode`() = playbackTest { h ->
        h.session.open(series(emptyList()), season = 2, episode = 4)
        runCurrent()

        h.session.reopenSources()
        runCurrent()

        assertEquals(2, h.playback.requestedSeason)
        assertEquals(4, h.playback.requestedEpisode)
    }

    // Mutation applied to verify: dropped the generation check before applying the
    // resolved result → test failed, the abandoned first open still drove playback.
    @Test
    fun `closing mid-resolve abandons the result`() = playbackTest { h ->
        h.session.open(movie())
        h.session.close()
        runCurrent()

        assertEquals(null, h.session.phase)
        assertEquals(null, h.host.loadedUrl)
    }
}
