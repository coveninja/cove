package com.coveninja.cove.shared.fixture

import com.coveninja.cove.shared.data.*
import com.coveninja.cove.shared.model.*
import com.coveninja.cove.shared.network.WatchProgressRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

// Six movies and four TV titles that the app renders before any network code
// is wired up. Values are plausible but obviously fake.
private val fixtureMovies = listOf(
    Media(id = 550,   title = "Fight Club",              mediaType = MediaType.Movie, voteAverage = 8.8, releaseDate = "1999-10-15", posterPath = "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg"),
    Media(id = 278,   title = "The Shawshank Redemption",mediaType = MediaType.Movie, voteAverage = 8.7, releaseDate = "1994-09-23", posterPath = "/q6y0Go1tsGEsmtFryDOJo3dEmqu.jpg"),
    Media(id = 238,   title = "The Godfather",           mediaType = MediaType.Movie, voteAverage = 8.7, releaseDate = "1972-03-14", posterPath = "/3bhkrj58Vtu7enYsLMId5rcSyj2.jpg"),
    Media(id = 424,   title = "Schindler's List",        mediaType = MediaType.Movie, voteAverage = 8.6, releaseDate = "1993-12-15", posterPath = "/sF1U4EUQS8YHUYjNl3pMGNIQyr0.jpg"),
    Media(id = 680,   title = "Pulp Fiction",            mediaType = MediaType.Movie, voteAverage = 8.5, releaseDate = "1994-09-10", posterPath = "/dM2w364MScsjFf8pfMbaWUcWrR.jpg"),
    Media(id = 13,    title = "Forrest Gump",            mediaType = MediaType.Movie, voteAverage = 8.5, releaseDate = "1994-07-06", posterPath = "/arw2vcBveWOVZr6pxd9XTd1TdQa.jpg"),
)

private val fixtureTv = listOf(
    Media(id = 1396, name = "Breaking Bad",        mediaType = MediaType.Tv, voteAverage = 8.9, firstAirDate = "2008-01-20", posterPath = "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg"),
    Media(id = 60625,name = "Rick and Morty",      mediaType = MediaType.Tv, voteAverage = 8.7, firstAirDate = "2013-12-02", posterPath = "/cvhNj9eoRBe5SxjCbQTkh05UP5K.jpg"),
    Media(id = 66732,name = "Stranger Things",     mediaType = MediaType.Tv, voteAverage = 8.6, firstAirDate = "2016-07-15", posterPath = "/49WJfeN0moxb9IPfGn8AIqMGskD.jpg"),
    Media(id = 1399, name = "Game of Thrones",     mediaType = MediaType.Tv, voteAverage = 8.4, firstAirDate = "2011-04-17", posterPath = "/u3bZgnGQ9T01sWNhyveQz0wH0Hl.jpg"),
)

// Dates are generated relative to the day the app runs, not hardcoded: My List sorts by
// them and the calendar groups by them, so a fixed 2026 date would leave both looking
// broken forever after that week passes.
private fun daysAgo(days: Int): String =
    (Clock.System.now() - days.days).toString()

private fun dayOffset(days: Int): String =
    Clock.System.todayIn(TimeZone.currentSystemDefault()).plus(days, DateTimeUnit.DAY).toString()

private fun posterOf(tmdbId: Int): String =
    (fixtureMovies + fixtureTv).firstOrNull { it.id == tmdbId }?.posterPath.orEmpty()

private fun fixtureEntry(
    index: Int,
    tmdbId: Int,
    mediaType: MediaType,
    title: String,
    status: LibraryStatus,
    voteAverage: Double,
    addedDaysAgo: Int,
    watchedDaysAgo: Int? = null,
    rating: Double? = null,
    lastWatchedSeason: Int? = null,
    lastWatchedEpisode: Int? = null,
    lastAiredSeason: Int? = null,
    lastAiredEpisode: Int? = null,
): LibraryEntry = LibraryEntry(
    id = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
    tmdbId = tmdbId,
    mediaType = mediaType,
    title = title,
    posterPath = posterOf(tmdbId),
    status = status,
    rating = rating,
    voteAverage = voteAverage,
    lastWatchedAt = watchedDaysAgo?.let { daysAgo(it) },
    lastWatchedSeason = lastWatchedSeason,
    lastWatchedEpisode = lastWatchedEpisode,
    lastAiredSeason = lastAiredSeason,
    lastAiredEpisode = lastAiredEpisode,
    addedAt = daysAgo(addedDaysAgo),
    updatedAt = daysAgo(watchedDaysAgo ?: addedDaysAgo),
)

private val fixtureEntries = listOf(
    fixtureEntry(
        1, 550, MediaType.Movie, "Fight Club", LibraryStatus.Finished, 8.8,
        addedDaysAgo = 40, watchedDaysAgo = 30, rating = 9.0,
    ),
    // Two aired episodes ahead of where the viewer stopped: this is the entry that
    // exercises the "new episodes" badge.
    fixtureEntry(
        2, 1396, MediaType.Tv, "Breaking Bad", LibraryStatus.Watching, 8.9,
        addedDaysAgo = 20, watchedDaysAgo = 2,
        lastWatchedSeason = 3, lastWatchedEpisode = 7,
        lastAiredSeason = 3, lastAiredEpisode = 9,
    ),
    fixtureEntry(
        3, 60625, MediaType.Tv, "Rick and Morty", LibraryStatus.Watching, 8.7,
        addedDaysAgo = 12, watchedDaysAgo = 1,
        lastWatchedSeason = 8, lastWatchedEpisode = 3,
        lastAiredSeason = 8, lastAiredEpisode = 3,
    ),
    fixtureEntry(
        4, 66732, MediaType.Tv, "Stranger Things", LibraryStatus.WatchLater, 8.6,
        addedDaysAgo = 5,
    ),
    fixtureEntry(
        5, 1399, MediaType.Tv, "Game of Thrones", LibraryStatus.Dropped, 8.4,
        addedDaysAgo = 60, watchedDaysAgo = 50, rating = 6.0,
        lastWatchedSeason = 5, lastWatchedEpisode = 2,
        lastAiredSeason = 8, lastAiredEpisode = 6,
    ),
    fixtureEntry(
        6, 278, MediaType.Movie, "The Shawshank Redemption", LibraryStatus.WatchLater, 8.7,
        addedDaysAgo = 3,
    ),
    fixtureEntry(
        7, 238, MediaType.Movie, "The Godfather", LibraryStatus.Watching, 8.7,
        addedDaysAgo = 8, watchedDaysAgo = 4,
    ),
    fixtureEntry(
        8, 424, MediaType.Movie, "Schindler's List", LibraryStatus.WatchLater, 8.6,
        addedDaysAgo = 15,
    ),
    fixtureEntry(
        9, 680, MediaType.Movie, "Pulp Fiction", LibraryStatus.Finished, 8.5,
        addedDaysAgo = 70, watchedDaysAgo = 65, rating = 8.0,
    ),
    fixtureEntry(
        10, 13, MediaType.Movie, "Forrest Gump", LibraryStatus.Dropped, 8.5,
        addedDaysAgo = 25, watchedDaysAgo = 22,
    ),
)

// Two mid-watch resume points and one finished episode, so resume bars, the
// continue-watching hero, and the "already finished" path all have something to show.
private val fixtureProgress = listOf(
    WatchProgress(
        id = "fixture-progress-movie-238",
        libraryEntryId = "00000000-0000-0000-0000-000000000007",
        tmdbId = 238, mediaType = MediaType.Movie,
        positionSeconds = 3120.0, durationSeconds = 10500.0,
        watchedAt = daysAgo(4),
    ),
    WatchProgress(
        id = "fixture-progress-tv-1396-3-7",
        libraryEntryId = "00000000-0000-0000-0000-000000000002",
        tmdbId = 1396, mediaType = MediaType.Tv, season = 3, episode = 7,
        positionSeconds = 1400.0, durationSeconds = 2820.0,
        watchedAt = daysAgo(2),
    ),
    WatchProgress(
        id = "fixture-progress-tv-60625-8-3",
        libraryEntryId = "00000000-0000-0000-0000-000000000003",
        tmdbId = 60625, mediaType = MediaType.Tv, season = 8, episode = 3,
        positionSeconds = 1300.0, durationSeconds = 1320.0, completed = true,
        watchedAt = daysAgo(1),
    ),
)

// Spread either side of today so the backlog strip, "airs today" and "coming up" all
// render. Kinds mirror what CalendarService emits: "available" is watchable now.
private val fixtureCalendar = listOf(
    CalendarItem(
        date = dayOffset(-6), kind = CalendarItem.KIND_AVAILABLE,
        tmdbId = 1396, mediaType = MediaType.Tv.wireName, title = "Breaking Bad",
        posterPath = posterOf(1396),
        seasonNumber = 3, episodeNumber = 8, episodeName = "Fly", waitingCount = 2,
    ),
    CalendarItem(
        date = dayOffset(-1), kind = CalendarItem.KIND_AVAILABLE,
        tmdbId = 60625, mediaType = MediaType.Tv.wireName, title = "Rick and Morty",
        posterPath = posterOf(60625),
        seasonNumber = 8, episodeNumber = 4, episodeName = "Cronenberg Redux",
        waitingCount = 1,
    ),
    CalendarItem(
        date = dayOffset(0), kind = CalendarItem.KIND_EPISODE,
        tmdbId = 66732, mediaType = MediaType.Tv.wireName, title = "Stranger Things",
        posterPath = posterOf(66732),
        seasonNumber = 5, episodeNumber = 1, episodeName = "The Crawl",
    ),
    CalendarItem(
        date = dayOffset(1), kind = CalendarItem.KIND_EPISODE,
        tmdbId = 1396, mediaType = MediaType.Tv.wireName, title = "Breaking Bad",
        posterPath = posterOf(1396),
        seasonNumber = 3, episodeNumber = 10, episodeName = "Fly",
    ),
    CalendarItem(
        date = dayOffset(7), kind = CalendarItem.KIND_EPISODE,
        tmdbId = 66732, mediaType = MediaType.Tv.wireName, title = "Stranger Things",
        posterPath = posterOf(66732),
        seasonNumber = 5, episodeNumber = 2, episodeName = "The Vanishing Point",
    ),
    CalendarItem(
        date = dayOffset(12), kind = CalendarItem.KIND_MOVIE,
        tmdbId = 278, mediaType = MediaType.Movie.wireName,
        title = "The Shawshank Redemption", posterPath = posterOf(278),
    ),
    CalendarItem(
        date = dayOffset(34), kind = CalendarItem.KIND_EPISODE,
        tmdbId = 60625, mediaType = MediaType.Tv.wireName, title = "Rick and Morty",
        posterPath = posterOf(60625),
        seasonNumber = 8, episodeNumber = 5, episodeName = "Morty's Menagerie",
    ),
)

private val fixtureSettings = AppSettings(
    defaultVolume = 1.0,
    rememberPosition = true,
    showStreamDetails = true,
    discoveryAlgorithm = "smart",
)

// ── Private repository implementations ──────────────────────────────────────

private class FixtureContentRepository : ContentRepository {
    override val home: StateFlow<HomeState> =
        MutableStateFlow(HomeState.Ready(fixtureMovies + fixtureTv))

    override val explore: StateFlow<ExploreState> =
        MutableStateFlow(ExploreState.Ready(movies = fixtureMovies, tv = fixtureTv))

    // Starts Idle; search() transitions to Ready so the SearchScreen can show results.
    private val _searchResults = MutableStateFlow<SearchState>(SearchState.Idle)
    override val searchResults: StateFlow<SearchState> = _searchResults

    override suspend fun search(query: String) {
        if (query.isBlank()) { _searchResults.value = SearchState.Idle; return }
        val q = query.lowercase()
        _searchResults.value = SearchState.Ready(
            (fixtureMovies + fixtureTv).filter { it.displayTitle.lowercase().contains(q) }
        )
    }

    override suspend fun details(media: Media): ContentDetails {
        val all = fixtureMovies + fixtureTv
        val isTv = media.mediaType == MediaType.Tv
        val details = MediaDetails(
            title = media.title.orEmpty(),
            name = media.name.orEmpty(),
            posterPath = media.posterPath.orEmpty(),
            overview = media.overview ?: fixtureOverview(media),
            genres = if (isTv) {
                listOf(MediaGenre(18, "Drama"), MediaGenre(80, "Crime"))
            } else {
                listOf(MediaGenre(18, "Drama"), MediaGenre(53, "Thriller"))
            },
            runtime = if (isTv) 0 else 128,
            episodeRunTime = if (isTv) listOf(52) else emptyList(),
            credits = MediaCredits(
                cast = listOf(
                    MediaCastMember(1, "Alex Morgan", "Lead", order = 0),
                    MediaCastMember(2, "Jamie Chen", "Supporting", order = 1),
                    MediaCastMember(3, "Sam Rivera", "Guest", order = 2),
                ),
                crew = listOf(
                    MediaCrewMember(4, "Jordan Lee", "Director"),
                    MediaCrewMember(5, "Taylor Brooks", "Writer"),
                ),
            ),
            originCountry = listOf("US"),
            productionCompanies = listOf(MediaCompany(1, "Cove Pictures")),
            status = if (isTv) "Returning Series" else "Released",
            numberOfSeasons = if (isTv) 2 else 0,
            numberOfEpisodes = if (isTv) 6 else 0,
            seasons = if (isTv) {
                listOf(
                    TvSeason(1, 3, "Season 1", media.posterPath),
                    TvSeason(2, 3, "Season 2", media.posterPath),
                )
            } else {
                emptyList()
            },
        )

        return ContentDetails(
            media = media,
            details = details,
            images = MediaImages(
                backdrops = listOf(
                    MediaImage(filePath = media.backdropPath ?: media.posterPath.orEmpty()),
                ),
                posters = listOf(MediaImage(filePath = media.posterPath.orEmpty())),
            ),
            videos = MediaVideos(),
            similar = all.filter { it.id != media.id }.take(6),
        )
    }

    override suspend fun episodes(id: Int, season: Int): List<TvEpisode> =
        (1..3).map { episode ->
            TvEpisode(
                episodeNumber = episode,
                name = "Episode $episode",
                overview = "A fixture episode for trying Cove's season browser without a backend.",
                airDate = "2026-0${season.coerceIn(1, 9)}-${(episode * 7).toString().padStart(2, '0')}",
                runtime = 52,
            )
        }

    private fun fixtureOverview(media: Media): String =
        "${media.displayTitle} is fixture content for developing Cove's interface without a running backend."
}

private class FixtureLibraryRepository : LibraryRepository {
    private val _entries = MutableStateFlow<LibraryState>(LibraryState.Ready(fixtureEntries))
    override val entries: StateFlow<LibraryState> = _entries
    private val watchedEpisodes = mutableMapOf<Triple<Int, Int, Int>, Boolean>()
    private val savedProgress = fixtureProgress.associateBy {
        progressKey(it.tmdbId, it.mediaType, it.season, it.episode)
    }.toMutableMap()

    private fun readyEntries(): List<LibraryEntry> =
        (_entries.value as? LibraryState.Ready)?.entries.orEmpty()

    override suspend fun add(
        tmdbId: Int,
        mediaType: MediaType,
        title: String,
        posterPath: String,
        voteAverage: Double,
    ) {
        if (readyEntries().any { it.tmdbId == tmdbId && it.mediaType == mediaType }) return
        _entries.value = LibraryState.Ready(
            readyEntries() + LibraryEntry(
                id = "fixture-${mediaType.wireName}-$tmdbId",
                tmdbId = tmdbId,
                mediaType = mediaType,
                title = title,
                posterPath = posterPath,
                status = LibraryStatus.WatchLater,
                voteAverage = voteAverage,
            ),
        )
    }

    override suspend fun remove(tmdbId: Int, mediaType: MediaType) {
        _entries.value = LibraryState.Ready(
            readyEntries().filterNot { it.tmdbId == tmdbId && it.mediaType == mediaType },
        )
    }

    override suspend fun setStatus(tmdbId: Int, mediaType: MediaType, status: LibraryStatus) {
        _entries.value = LibraryState.Ready(
            readyEntries().map { entry ->
                if (entry.tmdbId == tmdbId && entry.mediaType == mediaType) {
                    entry.copy(status = status)
                } else {
                    entry
                }
            },
        )
    }

    override suspend fun setRating(tmdbId: Int, mediaType: MediaType, rating: Double?) {
        _entries.value = LibraryState.Ready(
            readyEntries().map { entry ->
                if (entry.tmdbId == tmdbId && entry.mediaType == mediaType) {
                    entry.copy(rating = rating)
                } else {
                    entry
                }
            },
        )
    }

    override suspend fun setDismissed(tmdbId: Int, mediaType: MediaType, dismissed: Boolean) {
        if (dismissed) remove(tmdbId, mediaType)
    }

    override suspend fun episodeWatchStates(
        tmdbId: Int,
        mediaType: MediaType,
    ): Map<Pair<Int, Int>, Boolean> =
        if (mediaType != MediaType.Tv) {
            emptyMap()
        } else {
            watchedEpisodes
                .filterKeys { (id, _, _) -> id == tmdbId }
                .mapKeys { (key, _) -> key.second to key.third }
        }

    override suspend fun setEpisodeWatched(
        tmdbId: Int,
        title: String,
        posterPath: String,
        voteAverage: Double,
        season: Int,
        episode: Int,
        runtimeMinutes: Int?,
        watched: Boolean,
    ) {
        watchedEpisodes[Triple(tmdbId, season, episode)] = watched
        if (watched && readyEntries().none {
                it.tmdbId == tmdbId && it.mediaType == MediaType.Tv
            }
        ) {
            add(tmdbId, MediaType.Tv, title, posterPath, voteAverage)
        }
    }

    override suspend fun progress(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int?,
        episode: Int?,
    ): WatchProgress? = savedProgress[progressKey(tmdbId, mediaType, season, episode)]

    override suspend fun progressSnapshot(): List<WatchProgress> =
        savedProgress.values.sortedByDescending { it.watchedAt }

    override suspend fun recordProgress(request: WatchProgressRequest): WatchProgress {
        val key = progressKey(request.tmdbId, request.mediaType, request.season, request.episode)
        val progress = WatchProgress(
            id = "fixture-progress-$key",
            libraryEntryId = "fixture-${request.mediaType.wireName}-${request.tmdbId}",
            tmdbId = request.tmdbId,
            mediaType = request.mediaType,
            season = request.season,
            episode = request.episode,
            positionSeconds = request.positionSeconds,
            durationSeconds = request.durationSeconds,
            completed = request.completed,
        )
        savedProgress[key] = progress
        return progress
    }

    private fun progressKey(tmdbId: Int, mediaType: MediaType, season: Int?, episode: Int?): String =
        // Movies ignore season/episode, so they collapse to a single key per title.
        if (mediaType == MediaType.Tv) "${mediaType.wireName}-$tmdbId-$season-$episode"
        else "${mediaType.wireName}-$tmdbId"
}

private class FixtureSettingsRepository : SettingsRepository {
    private val _settings = MutableStateFlow<SettingsState>(SettingsState.Ready(fixtureSettings))
    override val settings: StateFlow<SettingsState> = _settings

    override suspend fun update(settings: AppSettings) {
        _settings.value = SettingsState.Ready(settings)
    }
}

/**
 * Two candidates so the source picker has something to show without a backend.
 * The URLs resolve to nothing — fixtures carry no media — so choosing one gets as
 * far as handing the player a URL and then fails there, which is the intent: the
 * flow up to playback stays walkable with no addons configured.
 */
private class FixturePlaybackRepository : PlaybackRepository {
    override suspend fun streams(
        tmdbId: Int,
        type: MediaType,
        season: Int?,
        episode: Int?,
    ): List<StreamSource> = listOf(
        StreamSource(
            name = "Fixture 1080p",
            title = "fixture.1080p.WEB-DL.mkv",
            url = "http://127.0.0.1:6969/api/play?url=fixture-1080p",
            addonName = "Fixtures",
            sizeBytes = 4_200_000_000,
            cached = true,
        ),
        StreamSource(
            name = "Fixture 720p",
            title = "fixture.720p.WEB.mkv",
            infoHash = "0".repeat(40),
            addonName = "Fixtures",
            sizeBytes = 1_900_000_000,
        ),
    )

    // A plausible intro so the seek bar's segments are visible without a backend.
    override suspend fun timestamps(tmdbId: Int, season: Int?, episode: Int?): MediaTimestamps =
        MediaTimestamps(intro = listOf(TimestampSegment(startMs = 65_000, endMs = 152_000)))

    override suspend fun subtitles(
        tmdbId: Int,
        type: MediaType,
        season: Int?,
        episode: Int?,
    ): List<SubtitleSource> = emptyList()

    override suspend fun aliveUrls(urls: List<String>): Set<String> = urls.toSet()

    override suspend fun torrentProgress(hash: String): TorrentProgress? = null

    override fun playUrl(source: StreamSource, season: Int?, episode: Int?): String =
        source.url ?: "http://127.0.0.1:6969/api/play?hash=${source.infoHash}"
}

/** In-memory, so the addon screens are usable with no backend running. */
private class FixtureAddonRepository : AddonRepository {
    private val addons = mutableListOf(
        Addon(
            id = "fixture.provider",
            url = "https://example.com/manifest.json",
            manifest = AddonManifestSummary(
                id = "fixture.provider",
                name = "Fixture Provider",
                description = "A stand-in provider addon. Supplies the fixture streams.",
                version = "1.0.0",
            ),
            kind = AddonKind.Provider,
        ),
    )
    private val repos = mutableListOf<NuvioRepoSummary>()

    private val _state = MutableStateFlow<AddonsState>(AddonsState.Ready(addons.toList(), repos.toList()))
    override val state: StateFlow<AddonsState> = _state
    override val lastError: StateFlow<String?> = MutableStateFlow(null)

    override suspend fun reload() {
        _state.value = AddonsState.Ready(addons.toList(), repos.toList())
    }

    override suspend fun addAddon(url: String) {
        addons += Addon(
            id = "fixture-${addons.size + 1}",
            url = url,
            manifest = AddonManifestSummary(name = url.substringAfter("://").substringBefore('/')),
        )
        reload()
    }

    override suspend fun setAddonEnabled(id: String, enabled: Boolean) {
        addons.replaceAll { if (it.id == id) it.copy(enabled = enabled) else it }
        reload()
    }

    override suspend fun removeAddon(id: String) {
        addons.removeAll { it.id == id }
        reload()
    }

    override suspend fun refreshAddon(id: String) = reload()

    override suspend fun addNuvioRepo(url: String) {
        repos += NuvioRepoSummary(
            id = "fixture-repo-${repos.size + 1}",
            owner = "fixture",
            repo = url.substringAfterLast('/'),
            url = url,
        )
        reload()
    }

    override suspend fun setNuvioRepoEnabled(id: String, enabled: Boolean) {
        repos.replaceAll { if (it.id == id) it.copy(enabled = enabled) else it }
        reload()
    }

    override suspend fun removeNuvioRepo(id: String) {
        repos.removeAll { it.id == id }
        reload()
    }

    override suspend fun setNuvioScraperEnabled(repoId: String, scraperId: String, enabled: Boolean) {
        repos.replaceAll { repo ->
            if (repo.id != repoId) repo
            else repo.copy(
                scrapers = repo.scrapers.map {
                    if (it.id == scraperId) it.copy(enabled = enabled) else it
                },
            )
        }
        reload()
    }
}

// ── Public factory ───────────────────────────────────────────────────────────

// Serves the canned schedule directly rather than extending BaseCalendarRepository:
// there is no metadata to fan out over here, and the point of the fixture is to be
// instantly Ready so the calendar has something to draw.
private class FixtureCalendarRepository : CalendarRepository {
    override val calendar: StateFlow<CalendarState> =
        MutableStateFlow(CalendarState.Ready(fixtureCalendar, Clock.System.now().toString()))

    override suspend fun refresh(force: Boolean) = Unit
}

fun FixtureAppGraph(): AppGraph = AppGraph(
    content  = FixtureContentRepository(),
    library  = FixtureLibraryRepository(),
    settings = FixtureSettingsRepository(),
    playback = FixturePlaybackRepository(),
    addons   = FixtureAddonRepository(),
    calendar = FixtureCalendarRepository(),
)
