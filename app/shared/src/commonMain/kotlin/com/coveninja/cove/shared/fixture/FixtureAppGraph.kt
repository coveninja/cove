package com.coveninja.cove.shared.fixture

import com.coveninja.cove.shared.data.*
import com.coveninja.cove.shared.model.*
import com.coveninja.cove.shared.network.WatchProgressRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

private val fixtureEntries = listOf(
    LibraryEntry(
        id = "00000000-0000-0000-0000-000000000001",
        tmdbId = 550, mediaType = MediaType.Movie, title = "Fight Club",
        status = LibraryStatus.Finished, voteAverage = 8.8,
    ),
    LibraryEntry(
        id = "00000000-0000-0000-0000-000000000002",
        tmdbId = 1396, mediaType = MediaType.Tv, title = "Breaking Bad",
        status = LibraryStatus.Watching, voteAverage = 8.9,
        lastWatchedSeason = 3, lastWatchedEpisode = 7,
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
    private val savedProgress = mutableMapOf<String, WatchProgress>()

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

fun FixtureAppGraph(): AppGraph = AppGraph(
    content  = FixtureContentRepository(),
    library  = FixtureLibraryRepository(),
    settings = FixtureSettingsRepository(),
    playback = FixturePlaybackRepository(),
    addons   = FixtureAddonRepository(),
)
