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
import com.coveninja.cove.shared.model.PersonDetails
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.model.SubtitleSource
import com.coveninja.cove.shared.model.TorrentProgress
import com.coveninja.cove.shared.model.TvEpisode
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.shared.network.WatchProgressRequest
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaSeason
import com.coveninja.cove.ui.model.MediaVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.coveninja.cove.shared.model.Media as DomainMedia
import com.coveninja.cove.shared.model.MediaType as DomainMediaType
import com.coveninja.cove.ui.model.MediaType as UiMediaType

// ── Fakes ────────────────────────────────────────────────────────────────────

private class FakeLibrary : LibraryRepository {
    val entriesList = MutableStateFlow<LibraryState>(LibraryState.Ready(emptyList()))
    override val entries: StateFlow<LibraryState> = entriesList
    private val progressList = MutableStateFlow<List<WatchProgress>>(emptyList())
    override val watchProgress: StateFlow<List<WatchProgress>> = progressList

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
        ).also { progressList.value = listOf(it) }
    }
}

private class FakePlayback(var sources: List<StreamSource>) : PlaybackRepository {
    var requestedSeason: Int? = null
    var requestedEpisode: Int? = null
    var streamRequests = 0
    var refreshRequests = 0

    override suspend fun streams(
        tmdbId: Int,
        type: DomainMediaType,
        season: Int?,
        episode: Int?,
        refresh: Boolean,
    ): List<StreamSource> {
        streamRequests++
        if (refresh) refreshRequests++
        requestedSeason = season
        requestedEpisode = episode
        return sources
    }

    override suspend fun timestamps(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
    ): MediaTimestamps = MediaTimestamps.None

    var offeredSubtitles: List<SubtitleSource> = emptyList()

    /** Null means "probe answered with everything alive". */
    var deadUrls: Set<String> = emptySet()
    var probedUrls: List<String>? = null

    override suspend fun aliveUrls(urls: List<String>): Set<String> {
        probedUrls = urls
        return urls.toSet() - deadUrls
    }

    override suspend fun torrentProgress(hash: String): TorrentProgress? = null

    override suspend fun subtitles(
        tmdbId: Int,
        type: DomainMediaType,
        season: Int?,
        episode: Int?,
    ): List<SubtitleSource> = offeredSubtitles

    override fun playUrl(source: StreamSource, season: Int?, episode: Int?): String =
        "http://127.0.0.1:6969/api/play?url=${source.url}"
}

private class FakeContent : ContentRepository {
    override val presentationLocale: StateFlow<String> = MutableStateFlow("en")
    override val home: StateFlow<HomeState> = MutableStateFlow(HomeState.Ready(emptyList()))
    override val explore: StateFlow<ExploreState> =
        MutableStateFlow(ExploreState.Ready(emptyList(), emptyList()))
    override val searchResults: StateFlow<SearchState> = MutableStateFlow(SearchState.Idle)
    override suspend fun search(query: String) = Unit
    override suspend fun media(id: Int, type: DomainMediaType): DomainMedia =
        throw UnsupportedOperationException()
    override suspend fun details(media: DomainMedia): ContentDetails =
        throw UnsupportedOperationException()

    override suspend fun person(id: Int): PersonDetails = throw UnsupportedOperationException()

    override suspend fun episodes(id: Int, season: Int): List<TvEpisode> = emptyList()
}

private class FakeSettings(settings: AppSettings) : SettingsRepository {
    override val settings: StateFlow<SettingsState> = MutableStateFlow(SettingsState.Ready(settings))
    override suspend fun update(settings: AppSettings) = Unit
}

private class FakeHost : VideoPlayerHost {
    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status
    override var videoCodecCapabilities: VideoCodecCapabilities = VideoCodecCapabilities()

    var loadedUrl: String? = null
    var loadedFrom: Double? = null
    val loads = mutableListOf<Pair<String, Double>>()
    var volumeSet: Double? = null

    fun report(position: Double, duration: Double) {
        _status.value = _status.value.copy(positionSeconds = position, durationSeconds = duration)
    }

    fun reportPlayback(
        position: Double,
        duration: Double,
        interrupted: Boolean = false,
        ended: Boolean = false,
    ) {
        _status.value = _status.value.copy(
            hasMedia = true,
            fileLoaded = true,
            positionSeconds = position,
            durationSeconds = duration,
            interrupted = interrupted,
            endReached = ended,
        )
    }

    /** What the player would report once mpv has taken a sub-add. */
    fun reportSubtitleTracks(vararg tracks: MediaTrack) {
        _status.value = _status.value.copy(subtitleTracks = tracks.toList())
    }

    fun reportError(message: String) {
        _status.value = _status.value.copy(
            hasMedia = false,
            fileLoaded = false,
            interrupted = false,
            endReached = false,
            error = message,
        )
    }

    override fun load(url: String, startPositionSeconds: Double) {
        loadedUrl = url
        loadedFrom = startPositionSeconds
        loads += url to startPositionSeconds
        _status.value = PlaybackStatus(
            positionSeconds = startPositionSeconds,
            statusMessage = "Opening stream…",
        )
    }

    override fun setPaused(paused: Boolean) = Unit
    override fun togglePause() = Unit
    override fun seek(seconds: Double) = Unit
    override fun seekRelative(deltaSeconds: Double) = Unit
    override fun setVolume(volume: Double) { volumeSet = volume }
    var mutedSet: Boolean? = null
    override fun setMuted(muted: Boolean) { mutedSet = muted }
    override fun setScaling(scaling: VideoScaling) = Unit
    var speedSet: Double? = null
    override fun setSpeed(speed: Double) { speedSet = speed }
    var appliedPreferences: PlaybackPreferences? = null
    val addedSubtitles = mutableListOf<AddedSubtitle>()
    override fun applyPreferences(preferences: PlaybackPreferences) {
        appliedPreferences = preferences
    }
    override fun addSubtitle(url: String, title: String, language: String, select: Boolean) {
        addedSubtitles += AddedSubtitle(url, title, language, select)
    }
    override fun selectAudioTrack(id: Int) = Unit
    var selectedSubtitleId: Int? = null
    override fun selectSubtitleTrack(id: Int?) { selectedSubtitleId = id }
    override fun stepChapter(delta: Int) = Unit
    override fun stepFrame(delta: Int) = Unit
    override fun setSubtitleDelay(seconds: Double) = Unit
    override fun setAudioDelay(seconds: Double) = Unit
    override fun takeScreenshot() = Unit

    /** Null means ready; the tests set a sentence to refuse. */
    var webVideoProblem: String? = null
    var webVideoInstallAllowed: Boolean? = null
    override suspend fun prepareWebVideo(mayInstallHelper: Boolean): String? {
        webVideoInstallAllowed = mayInstallHelper
        return webVideoProblem
    }

    var stops = 0
    override fun stop() {
        stops++
        loadedUrl = null
        _status.value = PlaybackStatus()
    }

    @Composable
    override fun Surface(modifier: Modifier) = Unit
}

private data class AddedSubtitle(
    val url: String,
    val title: String,
    val language: String,
    val select: Boolean,
)

private fun subtitleTrack(id: Int, title: String) = MediaTrack(
    id = id,
    kind = TrackKind.Subtitle,
    title = title,
    language = null,
    selected = false,
)

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

private fun trailer() = MediaVideo(
    id = "Movie:550-abc",
    title = "Official Trailer",
    thumbnailUrl = null,
    type = "Trailer",
    url = "https://www.youtube.com/watch?v=abc",
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

private val twoSources = listOf(
    StreamSource(name = "A", url = "https://example.com/a.mkv"),
    StreamSource(name = "B", url = "https://example.com/b.mkv"),
)

private fun storedAt(position: Double) = WatchProgress(
    id = "p",
    libraryEntryId = "e",
    tmdbId = 550,
    mediaType = DomainMediaType.Movie,
    positionSeconds = position,
    durationSeconds = 7000.0,
)

private class Harness(
    scheduler: TestCoroutineScheduler,
    settings: AppSettings,
    sources: List<StreamSource>,
    capabilities: VideoCodecCapabilities,
) {
    val library = FakeLibrary()
    val playback = FakePlayback(sources)
    val host = FakeHost()
        .also { it.videoCodecCapabilities = capabilities }
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
    capabilities: VideoCodecCapabilities = VideoCodecCapabilities(),
    body: suspend TestScope.(Harness) -> Unit,
) = runTest {
    val harness = Harness(testScheduler, settings, sources, capabilities)
    try {
        body(harness)
    } finally {
        harness.scope.cancel()
    }
}

class PlaybackSessionTest {

    @Test
    fun `a series never watched starts at season one episode one`() = playbackTest { h ->
        h.session.open(series(listOf(MediaSeason(1, "Season 1", episodeCount = 7))))
        runCurrent()

        assertEquals(1, h.playback.requestedSeason)
        assertEquals(1, h.playback.requestedEpisode)
    }

    @Test
    fun `an unfinished episode resumes rather than advancing`() = playbackTest { h ->
        h.library.entriesList.value = LibraryState.Ready(listOf(entry(season = 3, episode = 7)))

        h.session.open(series(listOf(MediaSeason(3, "Season 3", episodeCount = 13))))
        runCurrent()

        assertEquals(3, h.playback.requestedSeason)
        assertEquals(7, h.playback.requestedEpisode)
    }

    @Test
    fun `a finished episode advances within the season`() = playbackTest { h ->
        h.library.entriesList.value = LibraryState.Ready(listOf(entry(season = 3, episode = 7)))
        h.library.episodeStates = mapOf((3 to 7) to true)

        h.session.open(series(listOf(MediaSeason(3, "Season 3", episodeCount = 13))))
        runCurrent()

        assertEquals(3, h.playback.requestedSeason)
        assertEquals(8, h.playback.requestedEpisode)
    }

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
    @Test
    fun `an explicitly chosen episode wins over the resume point`() = playbackTest { h ->
        h.library.entriesList.value = LibraryState.Ready(listOf(entry(season = 3, episode = 7)))

        h.session.open(series(emptyList()), season = 1, episode = 2)
        runCurrent()

        assertEquals(1, h.playback.requestedSeason)
        assertEquals(2, h.playback.requestedEpisode)
    }

    @Test
    fun `a movie asks for no season or episode`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()

        assertEquals(null, h.playback.requestedSeason)
        assertEquals(null, h.playback.requestedEpisode)
    }

    @Test
    fun `opaque external subtitle ids receive numbered labels per language`() = playbackTest(
        settings = AppSettings(subtitlesEnabled = true),
    ) { h ->
        h.playback.offeredSubtitles = listOf(
            SubtitleSource(
                id = "v3_aHR0cHM6Ly9zdWJzNS5zdHJlbWlvL2VuL2Rvd25sb2FkLzE5NTc3NDUxMTc",
                url = "https://subs.test/en-1.srt",
                lang = "en",
            ),
            SubtitleSource(
                id = "Signs & Songs",
                url = "https://subs.test/en-signs.srt",
                lang = "en-US",
            ),
            SubtitleSource(
                id = "19573745118",
                url = "https://subs.test/en-2.srt",
                lang = "en-GB",
            ),
            SubtitleSource(
                id = "19573745119",
                url = "https://subs.test/es-1.srt",
                lang = "es",
            ),
        )

        h.session.open(movie())
        runCurrent()

        assertEquals(
            listOf("Subtitle 1", "Signs & Songs", "Subtitle 2", "Subtitle 1"),
            h.host.addedSubtitles.map(AddedSubtitle::title),
        )
    }

    // The select flag is the whole difference between the two kinds of external
    // subtitle, and it is also the only automated check that both player hosts are
    // asked for the same thing — neither mpv binding is reachable from here.
    @Test
    fun `a supplied file is selected while fetched ones are only offered`() = playbackTest(
        settings = AppSettings(subtitlesEnabled = true),
    ) { h ->
        h.playback.offeredSubtitles = listOf(
            SubtitleSource(id = "1", url = "https://subs.test/en.srt", lang = "en"),
        )
        h.session.open(movie())
        runCurrent()

        assertTrue(h.session.addUserSubtitle("/home/a/Movie.2024.en.srt"))

        assertEquals(
            listOf("https://subs.test/en.srt" to false, "/home/a/Movie.2024.en.srt" to true),
            h.host.addedSubtitles.map { it.url to it.select },
        )
        // The file names itself, so the menu has something to call it other than an id.
        assertEquals("Movie.2024.en.srt", h.host.addedSubtitles.last().title)
        assertEquals("en", h.host.addedSubtitles.last().language)
    }

    @Test
    fun `a supplied file is loaded again after a reconnect`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()
        assertTrue(h.session.addUserSubtitle("/home/a/Movie.srt"))

        h.host.reportPlayback(position = 400.0, duration = 1000.0)
        runCurrent()
        h.host.reportPlayback(position = 400.0, duration = 1000.0, interrupted = true)
        runCurrent()

        // Once on the drop, once on the reload that followed it.
        assertEquals(
            listOf("/home/a/Movie.srt", "/home/a/Movie.srt"),
            h.host.addedSubtitles.map(AddedSubtitle::url),
        )
        assertTrue(h.host.addedSubtitles.all(AddedSubtitle::select))
    }

    @Test
    fun `another title does not inherit the file`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()
        assertTrue(h.session.addUserSubtitle("/home/a/Movie.srt"))

        h.session.open(series(listOf(MediaSeason(1, "Season 1", episodeCount = 7))))
        runCurrent()

        assertEquals(1, h.host.addedSubtitles.size)
    }

    @Test
    fun `a file that is not a subtitle is refused`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()

        assertFalse(h.session.addUserSubtitle("/home/a/Movie.mkv"))
        assertTrue(h.host.addedSubtitles.isEmpty())
    }

    // Dropping again is what someone does when the first drop looked like it did
    // nothing; two identical entries in the menu is the wrong answer to that.
    @Test
    fun `the same file supplied twice is reselected rather than added again`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()
        assertTrue(h.session.addUserSubtitle("/home/a/Movie.srt"))
        h.host.reportSubtitleTracks(subtitleTrack(id = 3, title = "Movie.srt"))

        assertTrue(h.session.addUserSubtitle("/home/a/Movie.srt"))

        assertEquals(1, h.host.addedSubtitles.size)
        assertEquals(3, h.host.selectedSubtitleId)
    }

    // AppSettings.defaultVolume is a 0..1 fraction and mpv's is 0..100; the ×100
    // is the whole point of this test.
    @Test
    fun `default volume is scaled from the settings fraction to mpv's range`() =
        playbackTest(settings = AppSettings(defaultVolume = 0.8)) { h ->
            h.session.open(movie())
            runCurrent()

            assertEquals(80.0, h.host.volumeSet)
        }

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

    @Test
    fun `play from beginning ignores the resume point`() = playbackTest { h ->
        h.library.storedProgress = WatchProgress(
            id = "p",
            libraryEntryId = "e",
            tmdbId = 550,
            mediaType = DomainMediaType.Movie,
            positionSeconds = 610.0,
            durationSeconds = 7000.0,
        )

        h.session.open(movie(), fromStart = true)
        runCurrent()

        assertEquals(0.0, h.host.loadedFrom)
    }

    // A source that never opened means nothing played, so the request to start over still
    // stands: resuming here would drop the viewer back at the position they just asked to
    // leave, and the failover is exactly when they are least able to argue with it.
    @Test
    fun `a source that fails before anything plays keeps the restart`() = playbackTest(
        sources = twoSources,
    ) { h ->
        h.library.storedProgress = storedAt(610.0)

        h.session.open(movie(), fromStart = true)
        runCurrent()
        h.session.choose((h.session.phase as PlaybackPhase.Choosing).sources.first())
        runCurrent()
        assertEquals(0.0, h.host.loadedFrom)

        assertTrue(h.session.failoverToNextSource(), "expected another source")
        runCurrent()

        assertEquals(0.0, h.host.loadedFrom)
    }

    // Once a real position has been recorded, the resume point is this playthrough — so every
    // later load honours it. A flag left standing would restart a viewer who merely switched
    // source half an hour in.
    @Test
    fun `a restart stops applying once a position has been recorded`() = playbackTest(
        sources = twoSources,
    ) { h ->
        h.library.storedProgress = storedAt(610.0)

        h.session.open(movie(), fromStart = true)
        runCurrent()
        h.session.choose((h.session.phase as PlaybackPhase.Choosing).sources.first())
        runCurrent()
        assertEquals(0.0, h.host.loadedFrom)

        h.host.report(position = 950.0, duration = 7000.0)
        // Well past the progress ticker's interval, so at least one save has run.
        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(h.session.failoverToNextSource(), "expected another source")
        runCurrent()

        assertEquals(610.0, h.host.loadedFrom)
    }

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

    @Test
    fun `the first interruption retries the same source from its retained position`() =
        playbackTest { h ->
            h.session.open(movie())
            runCurrent()
            val initialUrl = assertNotNull(h.host.loadedUrl)

            h.host.reportPlayback(position = 400.0, duration = 1000.0)
            runCurrent()
            h.host.reportPlayback(position = 400.0, duration = 1000.0, interrupted = true)
            runCurrent()

            assertEquals(listOf(initialUrl to 0.0, initialUrl to 400.0), h.host.loads)
            assertTrue(h.session.reconnecting)

            h.host.reportPlayback(position = 401.0, duration = 1000.0)
            runCurrent()
            assertTrue(!h.session.reconnecting)
        }

    @Test
    fun `an interruption that repeats without progress is not retried again`() =
        playbackTest { h ->
            h.session.open(movie())
            runCurrent()
            val initialUrl = assertNotNull(h.host.loadedUrl)

            h.host.reportPlayback(position = 400.0, duration = 1000.0, interrupted = true)
            runCurrent()
            assertEquals(2, h.host.loads.size)

            // Reopened, played nothing, died in the same place: the one case the budget exists
            // to stop, and the only signal that tells it apart is the position not moving.
            h.host.reportPlayback(position = 402.0, duration = 1000.0)
            runCurrent()
            h.host.reportPlayback(position = 402.0, duration = 1000.0, interrupted = true)
            runCurrent()

            assertEquals(2, h.host.loads.size, "a source dying at one offset must not loop")
            assertTrue(!h.session.reconnecting)
            assertTrue(h.session.recoveryFailed)

            h.session.retryCurrentSource()
            runCurrent()
            assertEquals(initialUrl to 402.0, h.host.loads.last())
            assertEquals(3, h.host.loads.size)
            assertTrue(h.session.reconnecting)
            assertTrue(!h.session.recoveryFailed)
        }

    @Test
    fun `a stall after real progress earns another retry, up to the cap`() =
        playbackTest { h ->
            h.session.open(movie())
            runCurrent()

            // Three stalls, each a couple of minutes of watching apart — the ordinary shape of a
            // phone on a weak connection, which used to end on a banner at the second one.
            listOf(200.0, 400.0, 600.0).forEachIndexed { index, position ->
                h.host.reportPlayback(position = position, duration = 1000.0)
                runCurrent()
                h.host.reportPlayback(position = position, duration = 1000.0, interrupted = true)
                runCurrent()

                assertEquals(index + 2, h.host.loads.size, "stall at $position was not retried")
                assertTrue(h.session.reconnecting)
                assertTrue(!h.session.recoveryFailed)
            }

            h.host.reportPlayback(position = 800.0, duration = 1000.0)
            runCurrent()
            h.host.reportPlayback(position = 800.0, duration = 1000.0, interrupted = true)
            runCurrent()

            assertEquals(4, h.host.loads.size, "the cap must hold however well playback recovers")
            assertTrue(!h.session.reconnecting)
            assertTrue(h.session.recoveryFailed)
        }

    @Test
    fun `an interrupted late stream is saved incomplete at its retained position`() =
        playbackTest { h ->
            h.session.open(movie())
            runCurrent()

            h.host.reportPlayback(position = 950.0, duration = 1000.0, interrupted = true)
            runCurrent()

            val saved = assertNotNull(h.library.recorded.lastOrNull())
            assertEquals(950.0, saved.positionSeconds)
            assertTrue(!saved.completed, "an interruption must never complete the title")
        }

    @Test
    fun `a manual retry plays the source's freshly minted url`() =
        playbackTest { h ->
            val release = StreamSource(
                name = "Provider 1080p",
                title = "movie.1080p.WEB.mkv",
                url = "https://debrid.test/first",
                sizeBytes = 4_000,
            )
            h.playback.sources = listOf(release)
            h.session.open(movie())
            runCurrent()
            val initialUrl = assertNotNull(h.host.loadedUrl)

            h.host.reportPlayback(position = 400.0, duration = 1000.0)
            runCurrent()

            // The same release, re-listed at a new address — which is what a provider that
            // mints a link per request answers with, and what the dead URL cannot be matched
            // to. Everything but the address is unchanged.
            h.playback.sources = listOf(release.copy(url = "https://debrid.test/second"))
            h.session.retryCurrentSource()
            runCurrent()

            val (url, position) = h.host.loads.last()
            assertNotEquals(initialUrl, url, "retrying the dead address is the bug")
            assertEquals("http://127.0.0.1:6969/api/play?url=https://debrid.test/second", url)
            assertEquals(400.0, position, "a retry resumes where the stream stopped")
            assertEquals(1, h.playback.refreshRequests, "the cached listing holds the dead link")
        }

    @Test
    fun `a manual retry falls back to the current url when the listing cannot help`() =
        playbackTest { h ->
            h.session.open(movie())
            runCurrent()
            val initialUrl = assertNotNull(h.host.loadedUrl)
            h.host.reportPlayback(position = 400.0, duration = 1000.0)
            runCurrent()

            // Nothing came back — the backend is unreachable, or every provider timed out.
            // Reloading what is in hand is worth more than refusing to do anything.
            h.playback.sources = emptyList()
            h.session.retryCurrentSource()
            runCurrent()

            assertEquals(initialUrl to 400.0, h.host.loads.last())
            assertTrue(h.session.reconnecting)
            assertTrue(!h.session.recoveryFailed)
        }

    @Test
    fun `a reconnect that cannot reopen exposes recovery without looping`() =
        playbackTest { h ->
            h.session.open(movie())
            runCurrent()
            h.host.reportPlayback(position = 400.0, duration = 1000.0, interrupted = true)
            runCurrent()
            assertTrue(h.session.reconnecting)

            h.host.reportError("The selected stream could not be opened.")
            runCurrent()

            assertTrue(!h.session.reconnecting)
            assertTrue(h.session.recoveryFailed)
            assertEquals(2, h.host.loads.size)
        }

    @Test
    fun `closing before playback starts records nothing`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()

        // mpv has reported no duration yet — nothing worth persisting.
        h.session.close()
        runCurrent()

        assertTrue(h.library.recorded.isEmpty(), "was: ${h.library.recorded}")
    }

    @Test
    fun `a lone source plays without asking`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()

        assertTrue(h.session.phase is PlaybackPhase.Playing, "was: ${h.session.phase}")
        assertNotNull(h.host.loadedUrl)
    }

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
        assertEquals("B", phase.sources.first().source.name)
    }

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
    @Test
    fun `forcing the picker asks even for a single source`() = playbackTest { h ->
        h.session.open(movie(), forcePicker = true)
        runCurrent()

        assertTrue(h.session.phase is PlaybackPhase.Choosing, "was: ${h.session.phase}")
        assertEquals(null, h.host.loadedUrl, "nothing should have started playing")
    }

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

    @Test
    fun `auto selection prefers hardware decoding over a higher ranked software source`() =
        playbackTest(
            settings = AppSettings(autoSelectStream = true),
            sources = listOf(
                StreamSource(
                    name = "AV1 4K",
                    title = "Movie.2160p.AV1",
                    url = "https://example.com/av1.mkv",
                    sizeBytes = 900,
                ),
                StreamSource(
                    name = "H264 720p",
                    title = "Movie.720p.x264",
                    url = "https://example.com/h264.mkv",
                    sizeBytes = 100,
                ),
            ),
            capabilities = VideoCodecCapabilities(
                av1 = VideoDecoderSupport.SoftwareOnly,
                h264 = VideoDecoderSupport.Hardware,
            ),
        ) { h ->
            h.session.open(movie())
            runCurrent()

            val phase = h.session.phase
            assertTrue(phase is PlaybackPhase.Playing, "was: $phase")
            assertEquals("H264 720p", phase.source.name)
        }

    @Test
    fun `a lone software-only source opens the picker and remains manually selectable`() =
        playbackTest(
            settings = AppSettings(autoSelectStream = true),
            sources = listOf(
                StreamSource(
                    name = "AV1",
                    title = "Movie.1080p.AV1",
                    url = "https://example.com/av1.mkv",
                ),
            ),
            capabilities = VideoCodecCapabilities(av1 = VideoDecoderSupport.SoftwareOnly),
        ) { h ->
            h.session.open(movie())
            runCurrent()

            val choosing = h.session.phase
            assertTrue(choosing is PlaybackPhase.Choosing, "was: $choosing")
            assertEquals(VideoDecoderSupport.SoftwareOnly, choosing.sources.single().compatibility.support)
            assertEquals(null, h.host.loadedUrl)

            h.session.choose(choosing.sources.single())
            runCurrent()

            assertTrue(h.session.phase is PlaybackPhase.Playing, "was: ${h.session.phase}")
        }

    @Test
    fun `an unsupported source is displayed but cannot be chosen`() = playbackTest(
        settings = AppSettings(autoSelectStream = true),
        sources = listOf(
            StreamSource(
                name = "AV1",
                title = "Movie.1080p.AV1",
                url = "https://example.com/av1.mkv",
            ),
        ),
        capabilities = VideoCodecCapabilities(av1 = VideoDecoderSupport.Unsupported),
    ) { h ->
        h.session.open(movie())
        runCurrent()

        val choosing = h.session.phase
        assertTrue(choosing is PlaybackPhase.Choosing, "was: $choosing")
        val choice = choosing.sources.single()
        assertTrue(!choice.compatibility.selectable)

        h.session.choose(choice)
        runCurrent()

        assertTrue(h.session.phase is PlaybackPhase.Choosing, "was: ${h.session.phase}")
        assertEquals(null, h.host.loadedUrl)
    }

    // autoSelectStream must not override an explicit request to choose.
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
    @Test
    fun `reopening sources stays on the same episode`() = playbackTest { h ->
        h.session.open(series(emptyList()), season = 2, episode = 4)
        runCurrent()

        h.session.reopenSources()
        runCurrent()

        assertEquals(2, h.playback.requestedSeason)
        assertEquals(4, h.playback.requestedEpisode)
    }

    // A probe that rejects everything is far more likely to be wrong than every
    // source being dead at once, so the list
    // survives it.
    @Test
    fun `a probe that kills every source is not believed`() = playbackTest(
        settings = AppSettings(probeStreams = true),
        sources = listOf(
            StreamSource(name = "A", url = "https://example.com/a.mkv"),
            StreamSource(name = "B", url = "https://example.com/b.mkv"),
        ),
    ) { h ->
        h.playback.deadUrls = setOf("https://example.com/a.mkv", "https://example.com/b.mkv")

        h.session.open(movie())
        runCurrent()

        // Asserting the phase alone is not enough: without the fallback the
        // picker still opens, just with nothing in it.
        val phase = h.session.phase
        assertTrue(phase is PlaybackPhase.Choosing, "was: $phase")
        assertEquals(2, phase.sources.size, "every source should have survived")
    }

    // The probe covers a handful of candidates, so what it could not reach must stay on offer.
    // Intersecting with the reached set instead of subtracting the rejected one silently cut
    // every list down to the probe's budget — and when that left nothing, the ifEmpty guard
    // handed back the rejects too, which is how a dead link reached the player.
    @Test
    fun `only a source the probe actually rejected is dropped`() = playbackTest(
        settings = AppSettings(probeStreams = true),
        sources = (1..12).map { StreamSource(name = "S$it", url = "https://example.com/$it.mkv") },
    ) { h ->
        h.playback.deadUrls = setOf("https://example.com/1.mkv")

        h.session.open(movie())
        runCurrent()

        val phase = h.session.phase
        assertTrue(phase is PlaybackPhase.Choosing, "was: $phase")
        val offered = phase.sources.mapNotNull { it.source.url }
        assertTrue("https://example.com/1.mkv" !in offered, "the rejected source was still offered")
        assertEquals(11, offered.size, "sources the probe had no room for were dropped")
    }

    // Ranking first is what puts the source about to be auto-played inside the probe's budget.
    @Test
    fun `the probe checks the highest ranked sources rather than an arbitrary slice`() =
        playbackTest(
            settings = AppSettings(probeStreams = true),
            sources = (1..12).map {
                StreamSource(name = "S$it", url = "https://example.com/$it.mkv", sizeBytes = it * 1_000L)
            },
        ) { h ->
            h.session.open(movie())
            runCurrent()

            val probed = h.playback.probedUrls.orEmpty()
            assertEquals(10, probed.size, "probe budget not filled")
            // Biggest first under the default ranking, so the largest source — the one an
            // automatic pick lands on — must be among those checked.
            assertTrue("https://example.com/12.mkv" in probed, "top-ranked source went unchecked")
        }

    // probeStreams defaults to true, so the setting has to be turned off
    // explicitly here — an earlier version of this test used the default harness
    // settings and asserted the probe had not run, which was simply wrong.
    @Test
    fun `probing only happens when it is switched on`() =
        playbackTest(settings = AppSettings(probeStreams = false)) { h ->
            h.session.open(movie())
            runCurrent()

            assertEquals(null, h.playback.probedUrls, "probe ran with the setting off")
        }

    @Test
    fun `failover walks past sources that already failed`() = playbackTest(
        sources = listOf(
            StreamSource(name = "A", url = "https://example.com/a.mkv", sizeBytes = 300),
            StreamSource(name = "B", url = "https://example.com/b.mkv", sizeBytes = 200),
            StreamSource(name = "C", url = "https://example.com/c.mkv", sizeBytes = 100),
        ),
    ) { h ->
        h.session.open(movie())
        runCurrent()
        h.session.choose((h.session.phase as PlaybackPhase.Choosing).sources.first())
        runCurrent()

        val played = mutableListOf<String>()
        played += (h.session.phase as PlaybackPhase.Playing).source.name.orEmpty()
        repeat(2) {
            assertTrue(h.session.failoverToNextSource(), "expected another source")
            runCurrent()
            played += (h.session.phase as PlaybackPhase.Playing).source.name.orEmpty()
        }

        assertEquals(listOf("A", "B", "C"), played)
    }

    @Test
    fun `automatic failover skips software-only and unsupported sources`() = playbackTest(
        sources = listOf(
            StreamSource(
                name = "Hardware A",
                title = "Movie.1080p.x264",
                url = "https://example.com/a.mkv",
                sizeBytes = 400,
            ),
            StreamSource(
                name = "Software",
                title = "Movie.1080p.AV1",
                url = "https://example.com/software.mkv",
                sizeBytes = 300,
            ),
            StreamSource(
                name = "Unsupported",
                title = "Movie.1080p.VP9",
                url = "https://example.com/unsupported.mkv",
                sizeBytes = 200,
            ),
            StreamSource(
                name = "Hardware B",
                title = "Movie.720p.x264",
                url = "https://example.com/b.mkv",
                sizeBytes = 100,
            ),
        ),
        capabilities = VideoCodecCapabilities(
            h264 = VideoDecoderSupport.Hardware,
            av1 = VideoDecoderSupport.SoftwareOnly,
            vp9 = VideoDecoderSupport.Unsupported,
        ),
    ) { h ->
        h.session.open(movie())
        runCurrent()
        val choosing = h.session.phase as PlaybackPhase.Choosing
        h.session.choose(choosing.sources.first())
        runCurrent()

        assertTrue(h.session.failoverToNextSource())
        runCurrent()

        assertEquals("Hardware B", (h.session.phase as PlaybackPhase.Playing).source.name)
        assertTrue(!h.session.failoverToNextSource())
    }

    @Test
    fun `failover stops when every source has been tried`() = playbackTest { h ->
        h.session.open(movie())
        runCurrent()
        assertTrue(h.session.phase is PlaybackPhase.Playing, "setup: ${h.session.phase}")

        assertTrue(!h.session.failoverToNextSource(), "only one source exists")
    }

    @Test
    fun `closing mid-resolve abandons the result`() = playbackTest { h ->
        h.session.open(movie())
        h.session.close()
        runCurrent()

        assertEquals(null, h.session.phase)
        assertEquals(null, h.host.loadedUrl)
    }

    @Test
    fun `an extra plays its own address without resolving sources`() = playbackTest { h ->
        h.session.openExtra(movie(), trailer())
        runCurrent()

        assertEquals("https://www.youtube.com/watch?v=abc", h.host.loadedUrl)
        assertEquals(0.0, h.host.loadedFrom, "an extra always starts at the beginning")
        assertEquals(0, h.playback.streamRequests, "nothing should have been resolved")
    }

    // Watching two minutes of a trailer is not watching two minutes of the film,
    // and a resume point written here would appear on a title never started.
    @Test
    fun `an extra leaves no watch progress behind`() = playbackTest { h ->
        h.session.openExtra(movie(), trailer())
        runCurrent()
        h.host.report(position = 90.0, duration = 120.0)

        h.session.close()
        runCurrent()

        assertTrue(
            h.library.recorded.isEmpty(),
            "an extra recorded progress: ${h.library.recorded}",
        )
    }

    @Test
    fun `an extra cannot be swapped for the film through the source list`() = playbackTest { h ->
        h.session.openExtra(movie(), trailer())
        runCurrent()

        h.session.reopenSources()
        runCurrent()

        assertEquals(0, h.playback.streamRequests)
        assertEquals("https://www.youtube.com/watch?v=abc", h.host.loadedUrl)
    }

    // The bug this whole path exists to fix was a click that did nothing at all,
    // so the one case with nowhere to go has to say so.
    @Test
    fun `an extra with no address says so rather than doing nothing`() = playbackTest { h ->
        h.session.openExtra(movie(), trailer().copy(url = null))
        runCurrent()

        val phase = h.session.phase
        assertTrue(phase is PlaybackPhase.Failed, "was: $phase")
        assertEquals(null, h.host.loadedUrl)
    }

    // The player header reads off this label, and "Fight Club" alone would not say
    // which of the twenty extras is playing.
    @Test
    fun `the label names the extra that is playing`() {
        assertEquals(
            "Fight Club · Official Trailer",
            PlaybackRequest(movie(), extra = trailer()).label,
        )
    }

    @Test
    fun `plugin artwork exposes only normalized public TMDB images`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            pluginArtworkUrl("http://127.0.0.1:6969/api/img/w185/poster.jpg"),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            pluginArtworkUrl("https://image.tmdb.org/t/p/original/poster.jpg"),
        )
        assertNull(pluginArtworkUrl("https://images.example/private-poster.jpg?token=secret"))
        assertNull(pluginArtworkUrl("http://127.0.0.1:6969/private.jpg"))
    }

    // An extra opens embedded in the sheet it was started from; the film itself
    // always takes the window.
    @Test
    fun `an extra opens embedded and a film opens fullscreen`() = playbackTest { h ->
        h.session.openExtra(movie(), trailer())
        runCurrent()
        assertEquals(PlaybackPresentation.Inline, h.session.presentation)

        h.session.open(movie())
        runCurrent()
        assertEquals(PlaybackPresentation.Fullscreen, h.session.presentation)
    }

    // The failure has to appear in the slot on the page, not by blacking out the
    // window for a trailer that never started.
    @Test
    fun `a failed extra is reported in the page it was started from`() = playbackTest { h ->
        h.session.openExtra(movie(), trailer())
        h.session.expandToFullscreen()

        h.session.failExtra(movie(), trailer(), "no")

        assertEquals(PlaybackPresentation.Inline, h.session.presentation)
    }

    // The handle now outlives the surface it was drawn on, so opening the film
    // over a playing trailer is the only thing that can silence it.
    @Test
    fun `starting a title silences whatever was playing`() = playbackTest(
        sources = emptyList(),
    ) { h ->
        h.session.openExtra(movie(), trailer())
        runCurrent()
        assertEquals("https://www.youtube.com/watch?v=abc", h.host.loadedUrl, "setup")

        h.session.open(movie())

        assertEquals(1, h.host.stops)
        assertEquals(null, h.host.loadedUrl)
    }

    // A page URL needs an extractor in hand before the load, and when there is
    // none the viewer has to be told rather than left with a black box.
    @Test
    fun `an extra that cannot be prepared is not loaded`() = playbackTest { h ->
        h.host.webVideoProblem = "yt-dlp is not installed."

        h.session.openExtra(movie(), trailer())
        runCurrent()

        assertEquals(null, h.host.loadedUrl)
        val phase = h.session.phase
        assertTrue(phase is PlaybackPhase.Failed, "was: $phase")
        assertEquals("yt-dlp is not installed.", phase.message)
    }

    // Downloading a 40 MB helper is the viewer's call, so the setting travels with
    // the request rather than being read inside the player.
    @Test
    fun `the helper setting decides whether the player may fetch one`() = playbackTest(
        settings = AppSettings(manageYtDlp = false),
    ) { h ->
        h.session.openExtra(movie(), trailer())
        runCurrent()

        assertEquals(false, h.host.webVideoInstallAllowed)
    }
}
