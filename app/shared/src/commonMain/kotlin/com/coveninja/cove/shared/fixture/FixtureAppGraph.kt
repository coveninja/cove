package com.coveninja.cove.shared.fixture

import com.coveninja.cove.shared.data.*
import com.coveninja.cove.shared.model.*
import com.coveninja.cove.shared.network.WatchProgressRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

// The catalog the app renders before any network code is wired up. Values are plausible
// but obviously fake.
//
// Deliberately larger and more varied than the ten titles this started as: Explore builds
// per-genre rails and a sorted, paged grid out of whatever the catalog holds, and a corpus
// of ten titles sharing three genres cannot exercise any of that — every rail comes out
// either empty or identical, which makes a broken layout indistinguishable from a working
// one. Genre ids are real TMDB ids so they resolve through the same table the live backend
// feeds, and popularity is spread so sorting visibly reorders.
private val fixtureMovies = listOf(
    Media(id = 550,    title = "Fight Club",               mediaType = MediaType.Movie, voteAverage = 8.4, popularity = 61.0, releaseDate = "1999-10-15", posterPath = "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg", genreIds = listOf(18, 53)),
    Media(id = 278,    title = "The Shawshank Redemption", mediaType = MediaType.Movie, voteAverage = 8.7, popularity = 88.0, releaseDate = "1994-09-23", posterPath = "/q6y0Go1tsGEsmtFryDOJo3dEmqu.jpg", genreIds = listOf(18, 80)),
    Media(id = 238,    title = "The Godfather",            mediaType = MediaType.Movie, voteAverage = 8.7, popularity = 92.0, releaseDate = "1972-03-14", posterPath = "/3bhkrj58Vtu7enYsLMId5rcSyj2.jpg", genreIds = listOf(18, 80)),
    Media(id = 424,    title = "Schindler's List",         mediaType = MediaType.Movie, voteAverage = 8.6, popularity = 47.0, releaseDate = "1993-12-15", posterPath = "/sF1U4EUQS8YHUYjNl3pMGNIQyr0.jpg", genreIds = listOf(18, 36, 10752)),
    Media(id = 680,    title = "Pulp Fiction",             mediaType = MediaType.Movie, voteAverage = 8.5, popularity = 74.0, releaseDate = "1994-09-10", posterPath = "/dM2w364MScsjFf8pfMbaWUcWrR.jpg", genreIds = listOf(53, 80)),
    Media(id = 13,     title = "Forrest Gump",             mediaType = MediaType.Movie, voteAverage = 8.5, popularity = 69.0, releaseDate = "1994-07-06", posterPath = "/arw2vcBveWOVZr6pxd9XTd1TdQa.jpg", genreIds = listOf(35, 18, 10749)),
    Media(id = 27205,  title = "Inception",                mediaType = MediaType.Movie, voteAverage = 8.4, popularity = 96.0, releaseDate = "2010-07-15", posterPath = "/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg", genreIds = listOf(28, 878, 12)),
    Media(id = 157336, title = "Interstellar",             mediaType = MediaType.Movie, voteAverage = 8.4, popularity = 99.0, releaseDate = "2014-11-05", posterPath = "/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg", genreIds = listOf(12, 18, 878)),
    Media(id = 155,    title = "The Dark Knight",          mediaType = MediaType.Movie, voteAverage = 8.5, popularity = 94.0, releaseDate = "2008-07-16", posterPath = "/qJ2tW6WMUDux911r6m7haRef0WH.jpg", genreIds = listOf(18, 28, 80, 53)),
    Media(id = 603,    title = "The Matrix",               mediaType = MediaType.Movie, voteAverage = 8.2, popularity = 83.0, releaseDate = "1999-03-30", posterPath = "/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg", genreIds = listOf(28, 878)),
    Media(id = 129,    title = "Spirited Away",            mediaType = MediaType.Movie, voteAverage = 8.5, popularity = 78.0, releaseDate = "2001-07-20", posterPath = "/39wmItIWsg5sZMyRUHLkWBcuVCM.jpg", genreIds = listOf(16, 10751, 14)),
    Media(id = 496243, title = "Parasite",                 mediaType = MediaType.Movie, voteAverage = 8.5, popularity = 81.0, releaseDate = "2019-05-30", posterPath = "/7IiTTgloJzvGI1TAYymCfbfl3vT.jpg", genreIds = listOf(35, 53, 18)),
    Media(id = 122,    title = "The Return of the King",   mediaType = MediaType.Movie, voteAverage = 8.5, popularity = 86.0, releaseDate = "2003-12-01", posterPath = "/rCzpDGLbOoPwLjy3OAm5NUPOTrC.jpg", genreIds = listOf(12, 14, 28)),
    Media(id = 76341,  title = "Mad Max: Fury Road",       mediaType = MediaType.Movie, voteAverage = 7.6, popularity = 72.0, releaseDate = "2015-05-13", posterPath = "/8tZYtuWezp8JbcsvHYO0O46tFbo.jpg", genreIds = listOf(28, 12, 878)),
    Media(id = 372058, title = "Your Name.",               mediaType = MediaType.Movie, voteAverage = 8.5, popularity = 64.0, releaseDate = "2016-08-26", posterPath = "/q719jXXEzOoYaps6babgKnONONX.jpg", genreIds = listOf(16, 10749, 18)),
    Media(id = 694,    title = "The Shining",              mediaType = MediaType.Movie, voteAverage = 8.2, popularity = 58.0, releaseDate = "1980-05-23", posterPath = "/b6ko0IKC8MdYBBPkkA1aBPLe2yz.jpg", genreIds = listOf(27, 53)),
    Media(id = 429,    title = "The Good, the Bad and the Ugly", mediaType = MediaType.Movie, voteAverage = 8.5, popularity = 41.0, releaseDate = "1966-12-23", posterPath = "/bX2xnavhMYjWDoZp1VM6VnU1xwe.jpg", genreIds = listOf(37)),
    Media(id = 12477,  title = "Grave of the Fireflies",   mediaType = MediaType.Movie, voteAverage = 8.5, popularity = 36.0, releaseDate = "1988-04-16", posterPath = "/k9tv1rXZbOhH7eiCk378x61kNQ1.jpg", genreIds = listOf(16, 18, 10752)),
    Media(id = 1891,   title = "The Empire Strikes Back",  mediaType = MediaType.Movie, voteAverage = 8.4, popularity = 67.0, releaseDate = "1980-05-20", posterPath = "/7BuH8itoSrLExs2YZSsM01Qk2no.jpg", genreIds = listOf(12, 28, 878)),
    Media(id = 807,    title = "Se7en",                    mediaType = MediaType.Movie, voteAverage = 8.4, popularity = 76.0, releaseDate = "1995-09-22", posterPath = "/6yoghtyTpznpBik8EngEmJskVUO.jpg", genreIds = listOf(80, 9648, 53)),
)

private val fixtureTv = listOf(
    Media(id = 1396,  name = "Breaking Bad",       mediaType = MediaType.Tv, voteAverage = 8.9, popularity = 97.0, firstAirDate = "2008-01-20", posterPath = "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg", genreIds = listOf(18, 80)),
    Media(id = 60625, name = "Rick and Morty",     mediaType = MediaType.Tv, voteAverage = 8.7, popularity = 91.0, firstAirDate = "2013-12-02", posterPath = "/cvhNj9eoRBe5SxjCbQTkh05UP5K.jpg", genreIds = listOf(16, 35, 10765)),
    Media(id = 66732, name = "Stranger Things",    mediaType = MediaType.Tv, voteAverage = 8.6, popularity = 95.0, firstAirDate = "2016-07-15", posterPath = "/49WJfeN0moxb9IPfGn8AIqMGskD.jpg", genreIds = listOf(18, 9648, 10765)),
    Media(id = 1399,  name = "Game of Thrones",    mediaType = MediaType.Tv, voteAverage = 8.4, popularity = 89.0, firstAirDate = "2011-04-17", posterPath = "/u3bZgnGQ9T01sWNhyveQz0wH0Hl.jpg", genreIds = listOf(10765, 18, 10759)),
    Media(id = 1398,  name = "The Sopranos",       mediaType = MediaType.Tv, voteAverage = 8.7, popularity = 62.0, firstAirDate = "1999-01-10", posterPath = "/rTc7ZXdroqjkKivFPvCPX0Ru7uw.jpg", genreIds = listOf(18, 80)),
    Media(id = 94605, name = "Arcane",             mediaType = MediaType.Tv, voteAverage = 8.7, popularity = 84.0, firstAirDate = "2021-11-06", posterPath = "/fqldf2t8ztc9aiwn3k6mlX3tvRT.jpg", genreIds = listOf(16, 10765, 10759)),
    Media(id = 87108, name = "Chernobyl",          mediaType = MediaType.Tv, voteAverage = 8.7, popularity = 55.0, firstAirDate = "2019-05-06", posterPath = "/hlLXt2tOPT6RRnjiUmoxyG1LTFi.jpg", genreIds = listOf(18, 10768)),
    Media(id = 46648, name = "True Detective",     mediaType = MediaType.Tv, voteAverage = 8.3, popularity = 58.0, firstAirDate = "2014-01-12", posterPath = "/aowr4xpLP5sRi9r6vt7sZH1L0kg.jpg", genreIds = listOf(18, 80, 9648)),
    Media(id = 71912, name = "The Witcher",        mediaType = MediaType.Tv, voteAverage = 7.8, popularity = 79.0, firstAirDate = "2019-12-20", posterPath = "/7vjaCdMw15FEbXyLQTVa04URsPm.jpg", genreIds = listOf(10765, 18, 10759)),
    Media(id = 82856, name = "The Mandalorian",    mediaType = MediaType.Tv, voteAverage = 8.4, popularity = 82.0, firstAirDate = "2019-11-12", posterPath = "/eU1i6eHXlzMOlEq0ku1Rzq7Y4wA.jpg", genreIds = listOf(10765, 10759, 37)),
    Media(id = 76479, name = "The Boys",           mediaType = MediaType.Tv, voteAverage = 8.4, popularity = 87.0, firstAirDate = "2019-07-25", posterPath = "/2zmTngn1tYC1AvfnrFLhxeD82hz.jpg", genreIds = listOf(10765, 10759)),
    Media(id = 1668,  name = "Friends",            mediaType = MediaType.Tv, voteAverage = 8.4, popularity = 71.0, firstAirDate = "1994-09-22", posterPath = "/f496cm9enuEsZkSPzCwnTESEK5s.jpg", genreIds = listOf(35)),
    Media(id = 2316,  name = "The Office",         mediaType = MediaType.Tv, voteAverage = 8.6, popularity = 74.0, firstAirDate = "2005-03-24", posterPath = "/7DJKHzAi83BmQrWLrYYOqcoKfhR.jpg", genreIds = listOf(35)),
    Media(id = 85937, name = "Demon Slayer",       mediaType = MediaType.Tv, voteAverage = 8.6, popularity = 77.0, firstAirDate = "2019-04-06", posterPath = "/xUfRZu2mi8jH6SzQEJGP6tjBuYj.jpg", genreIds = listOf(16, 10759, 10765)),
    Media(id = 456,   name = "The Simpsons",       mediaType = MediaType.Tv, voteAverage = 8.0, popularity = 66.0, firstAirDate = "1989-12-17", posterPath = "/qcr9bBY6MVeLzriKCmJOv1562vJ.jpg", genreIds = listOf(16, 35, 10751)),
    Media(id = 63174, name = "Lucifer",            mediaType = MediaType.Tv, voteAverage = 8.5, popularity = 60.0, firstAirDate = "2016-01-25", posterPath = "/4EYPN5mVIhSp3zoHz1eMKcCm1Xg.jpg", genreIds = listOf(80, 10765)),
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

// Ratings are on the 1..5 star scale the rating control writes — see `MediaActions.setRating`,
// which passes a star count, and the taste weighting that centres on 2.5. A 0..10 value here
// fills every star at once and reads as "loved it" for a title that was dropped.
private val fixtureEntries = listOf(
    fixtureEntry(
        1, 550, MediaType.Movie, "Fight Club", LibraryStatus.Finished, 8.8,
        addedDaysAgo = 40, watchedDaysAgo = 30, rating = 5.0,
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
        addedDaysAgo = 60, watchedDaysAgo = 50, rating = 2.0,
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
        addedDaysAgo = 70, watchedDaysAgo = 65, rating = 4.0,
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

// The five people the fixture credits name, so tapping a face in the cast row opens
// somebody rather than an empty sheet. Ids match the MediaCastMember/MediaCrewMember
// ids handed out by details() below. No profile paths: a made-up one would 404, and
// leaving them blank exercises the card's initial-letter fallback.
private class FixturePerson(
    val name: String,
    val department: String,
    val birthday: String,
    val placeOfBirth: String,
    val job: String? = null,
)

private val fixturePeople = mapOf(
    1 to FixturePerson("Alex Morgan", "Acting", "1984-03-11", "Portland, Oregon, USA"),
    2 to FixturePerson("Jamie Chen", "Acting", "1991-07-02", "Vancouver, British Columbia, Canada"),
    3 to FixturePerson("Sam Rivera", "Acting", "1978-11-19", "San Juan, Puerto Rico"),
    4 to FixturePerson("Jordan Lee", "Directing", "1969-01-30", "Seoul, South Korea", job = "Director"),
    5 to FixturePerson("Taylor Brooks", "Writing", "1975-06-08", "Dublin, Ireland", job = "Writer"),
)

private val fixtureCharacters =
    listOf("Lead", "Supporting", "Guest", "Herself", "Narrator", "Younger self")

// ── Private repository implementations ──────────────────────────────────────

private class FixtureContentRepository : ContentRepository {
    override val presentationLocale: StateFlow<String> = MutableStateFlow("en")
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
            results = (fixtureMovies + fixtureTv).filter { it.displayTitle.lowercase().contains(q) },
            // Matched on the name alone: the fixture people exist to exercise the row and
            // the sheet, not to stand in for TMDB's index.
            people = fixturePeople
                .filterValues { it.name.lowercase().contains(q) }
                .keys
                .map(::fixturePersonDetails),
        )
    }

    override suspend fun media(id: Int, type: MediaType): Media =
        (fixtureMovies + fixtureTv).first { it.id == id && it.mediaType == type }

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

    override suspend fun person(id: Int): PersonDetails = fixturePersonDetails(id)

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

/**
 * One fixture person, whole enough to fill the person sheet: a bio, vitals, and a slice of
 * the fixture catalog as their filmography. Shared by person() and by search, so a person
 * found by searching and the same person opened from a cast row are the same person.
 */
private fun fixturePersonDetails(id: Int): PersonDetails {
    val person = fixturePeople[id]
        ?: FixturePerson("Fixture Person $id", "Acting", "1980-01-01", "Nowhere, Earth")
    // Every third title, offset by the id, so two people never have the same
    // filmography and the sheet's sorting and dedupe have something to chew on.
    val titles = (fixtureMovies + fixtureTv)
        .filterIndexed { index, _ -> (index + id) % 3 == 0 }
    val credits = titles.mapIndexed { index, title ->
        PersonCredit(
            id = title.id,
            title = title.title,
            name = title.name,
            mediaType = title.mediaType,
            posterPath = title.posterPath,
            backdropPath = title.backdropPath,
            releaseDate = title.releaseDate,
            firstAirDate = title.firstAirDate,
            voteAverage = title.voteAverage,
            genreIds = title.genreIds,
            popularity = title.popularity,
            character = if (person.job == null) {
                fixtureCharacters[(index + id) % fixtureCharacters.size]
            } else {
                null
            },
            job = person.job,
            episodeCount = if (title.mediaType == MediaType.Tv) (index % 9) + 1 else 0,
        )
    }

    return PersonDetails(
        id = id,
        name = person.name,
        biography = "${person.name} is fixture content for developing Cove's person sheet " +
            "without a running backend. They have appeared in ${credits.size} of the " +
            "titles this catalog knows about, and in nothing at all outside it.",
        birthday = person.birthday,
        placeOfBirth = person.placeOfBirth,
        knownForDepartment = person.department,
        alsoKnownAs = listOf(person.name.split(' ').first()),
        popularity = 40.0 + id,
        combinedCredits = if (person.job == null) {
            PersonCredits(cast = credits)
        } else {
            PersonCredits(crew = credits)
        },
    )
}

/**
 * Browsing and taste over the fixture catalog.
 *
 * Everything is derived from the same fixture list rather than canned per-rail arrays, so a
 * sort really sorts, a genre filter really narrows, and paging really runs out. That last
 * one matters: infinite scroll that never reaches the end looks identical to infinite
 * scroll that is broken, and only a finite corpus tells them apart.
 */
private class FixtureDiscoveryRepository : DiscoveryRepository {

    private val catalog = fixtureMovies + fixtureTv

    override suspend fun genres(type: MediaType): List<MediaGenre> =
        catalog.filter { it.mediaType == type }
            .flatMap(Media::genreIds)
            .distinct()
            .mapNotNull { id -> FIXTURE_GENRE_NAMES[id]?.let { MediaGenre(id, it) } }
            .sortedBy(MediaGenre::name)

    override suspend fun browse(query: BrowseQuery): List<Media> {
        val matching = catalog
            .filter { it.mediaType == query.type }
            .filter { query.genreId == null || query.genreId in it.genreIds }
        val ordered = when (query.sort) {
            CatalogSort.Popularity -> matching.sortedByDescending(Media::popularity)
            CatalogSort.Rating -> matching.sortedByDescending(Media::voteAverage)
            CatalogSort.Newest -> matching.sortedByDescending { it.displayDate.orEmpty() }
            CatalogSort.Oldest -> matching.sortedBy { it.displayDate.orEmpty() }
            CatalogSort.Title -> matching.sortedBy { it.displayTitle.lowercase() }
        }
        return ordered.drop((query.page - 1).coerceAtLeast(0) * FIXTURE_PAGE_SIZE)
            .take(FIXTURE_PAGE_SIZE)
    }

    // The fixture library is what fixtureEntries says it is, so "taste" is simply the
    // genres those saved titles carry — enough to make the personalized rails appear and
    // be visibly different from the popularity rail.
    override suspend fun recommended(type: MediaType, limit: Int): List<Media> {
        val savedIds = fixtureEntries.map { it.tmdbId }.toSet()
        val tasteGenres = catalog.filter { it.id in savedIds }.flatMap(Media::genreIds).toSet()
        return catalog.filter { it.mediaType == type && it.id !in savedIds }
            .sortedByDescending { item -> item.genreIds.count { it in tasteGenres } }
            .take(limit)
    }

    override suspend fun topGenres(type: MediaType, limit: Int): List<MediaGenre> {
        val savedIds = fixtureEntries.map { it.tmdbId }.toSet()
        return catalog.filter { it.id in savedIds && it.mediaType == type }
            .flatMap(Media::genreIds)
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .mapNotNull { (id, _) -> FIXTURE_GENRE_NAMES[id]?.let { MediaGenre(id, it) } }
            .take(limit)
    }

    override suspend fun similarTo(type: MediaType, tmdbId: Int, limit: Int): List<Media> {
        val source = catalog.firstOrNull { it.id == tmdbId } ?: return emptyList()
        return catalog.filter { it.mediaType == type && it.id != tmdbId }
            .sortedByDescending { item -> item.genreIds.count { it in source.genreIds } }
            .take(limit)
    }

    override suspend fun favorites(limit: Int): List<FavoriteTitle> =
        fixtureEntries.filter { it.status == LibraryStatus.Finished || it.rating != null }
            .sortedByDescending { it.rating ?: 0.0 }
            .take(limit)
            .map { FavoriteTitle(it.tmdbId, it.mediaType, it.title) }
}

/**
 * Insights with enough history behind them to judge the page by.
 *
 * Everything here is *derived* from one generated day-by-day calendar rather than written
 * out as a list of impressive-looking totals. Hand-picked numbers drift out of agreement
 * the moment one of them is edited — a streak that the calendar contradicts, a monthly bar
 * chart that does not add up to the headline total — and a chart that disagrees with itself
 * is worse than no chart, because it looks like a rendering bug.
 *
 * The generator is seeded, so the page looks the same on every launch and a visual change
 * is attributable to the code rather than to new random data.
 */
private class FixtureInsightsRepository : InsightsRepository {
    override suspend fun activity(range: InsightsRange): ActivityStats =
        fixtureActivityFor(range)
    override suspend fun taste(): DiscoveryInsights = fixtureTaste
    // Plausible rather than derived: Trakt totals come from an account this fixture has no
    // model of, and the point of showing them is that they disagree with the local figures.
    override suspend fun trakt(): TraktStats = TraktStats(
        moviesWatched = 412,
        movieMinutes = 48_960,
        showsWatched = 96,
        episodesWatched = 4_201,
        episodeMinutes = 151_236,
        ratings = 318,
    )
}

/**
 * Original languages for the handful of fixture titles that are not English.
 *
 * The fixture catalog does not carry a language field, and inventing a spread would make
 * the chart meaningless. Naming the ones that are genuinely foreign — and defaulting the
 * rest to English — keeps the breakdown true to the catalog it is drawn from.
 */
private val FIXTURE_LANGUAGES = mapOf(
    129 to "ja", 372058 to "ja", 12477 to "ja", 496243 to "ko", 429 to "it",
)


/**
 * The generated history, narrowed to one year.
 *
 * Recomputed from the calendar rather than regenerated, so the ranges stay consistent with
 * each other and with the all-time figures. The hour distribution is scaled rather than
 * re-derived: the generator applies one fixed evening-weighted shape to every day, so a
 * year's hours are that same shape at a smaller size — which is exactly what scaling gives,
 * and is why this shortcut is honest here and would not be against real data.
 */
private fun fixtureActivityFor(range: InsightsRange): ActivityStats {
    val all = fixtureActivity
    if (range == InsightsRange.AllTime) return all

    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val year = if (range == InsightsRange.ThisYear) today.year else today.year - 1
    val calendar = all.calendar.filterKeys { it.startsWith("$year-") }
    val total = calendar.values.sum()
    if (total <= 0L) return all.copy(calendar = emptyMap(), totalSeconds = 0)

    val byDayOfWeek = MutableList(7) { 0L }
    calendar.forEach { (value, seconds) ->
        val date = runCatching { kotlinx.datetime.LocalDate.parse(value) }.getOrNull()
            ?: return@forEach
        byDayOfWeek[date.dayOfWeek.isoDayNumber % 7] += seconds
    }
    val scale = total.toDouble() / all.totalSeconds.coerceAtLeast(1L)
    val activeDays = calendar.size.coerceAtLeast(1)

    return all.copy(
        totalSeconds = total,
        avgSecondsPerActiveDay = total / activeDays,
        byDayOfWeek = byDayOfWeek,
        byHourOfDay = all.byHourOfDay.map { (it * scale).toLong() },
        calendar = calendar,
    )
}

/** Roughly fourteen months, so this year and the whole of last year both have shape. */
private const val FIXTURE_ACTIVITY_DAYS = 430

/**
 * How a day's watching is spread across the clock.
 *
 * Weighted towards the evening with a small lunchtime bump and a dead zone from 3am to
 * 7am — the point is that the viewing clock reads as a person's habits rather than as
 * noise, since a flat distribution would make that chart look broken.
 */
private val FIXTURE_HOUR_WEIGHTS = listOf(
    3, 1, 1, 0, 0, 0, 0, 1, 2, 3, 4, 5,
    7, 8, 6, 5, 6, 9, 14, 20, 26, 30, 22, 12,
)

// Lazy, not eager. Top-level properties initialise in file order, and this derives from
// FIXTURE_GENRE_NAMES and friends declared further down — reading them from an eager
// initialiser here gets null and takes the whole file's class initialiser down with it.
// Deferring also keeps 430 days of generation off the startup path for hosts that never
// open the insights page.
private val fixtureActivity: ActivityStats by lazy { buildFixtureActivity() }

private fun buildFixtureActivity(): ActivityStats {
    val random = kotlin.random.Random(seed = 20260817)
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val thisYear = today.year

    // Only days with time on them go in, which is what the real table stores — the gaps
    // are what give the heatmap texture and make the streak counters mean something.
    val calendar = linkedMapOf<String, Long>()
    val byDayOfWeek = MutableList(7) { 0L }
    val byMonthThisYear = MutableList(12) { 0L }
    val byMonthLastYear = MutableList(12) { 0L }
    val byYear = linkedMapOf<String, Long>()

    for (offset in FIXTURE_ACTIVITY_DAYS downTo 0) {
        val date = today.plus(-offset, DateTimeUnit.DAY)
        // Sunday is 7 in ISO; the arrays here are Sunday-first, hence the modulo.
        val weekdayIndex = date.dayOfWeek.isoDayNumber % 7
        val weekend = weekdayIndex == 0 || weekdayIndex == 6
        // Skipped days are the norm midweek and rare at weekends.
        if (random.nextInt(100) < (if (weekend) 18 else 42)) continue

        val minutes = if (weekend) 60 + random.nextInt(180) else 25 + random.nextInt(110)
        val seconds = minutes * 60L
        calendar[date.toString()] = seconds
        byDayOfWeek[weekdayIndex] += seconds
        byYear[date.year.toString()] = (byYear[date.year.toString()] ?: 0L) + seconds
        when (date.year) {
            thisYear -> byMonthThisYear[date.month.number - 1] += seconds
            thisYear - 1 -> byMonthLastYear[date.month.number - 1] += seconds
        }
    }

    val total = calendar.values.sum()
    val weightSum = FIXTURE_HOUR_WEIGHTS.sum()
    val byHourOfDay = FIXTURE_HOUR_WEIGHTS.map { weight -> total * weight / weightSum }

    val activeDays = calendar.keys.sorted()
    val topTitles = fixtureEntries
        .filter { it.lastWatchedAt != null }
        .mapIndexed { index, entry ->
            ActivityTitle(
                tmdbId = entry.tmdbId,
                mediaType = entry.mediaType.wireName,
                // Descending by construction so the leaderboard is visibly ranked.
                seconds = (14 - index * 2).coerceAtLeast(2) * 3_600L,
                title = entry.title,
                posterPath = entry.posterPath,
            )
        }
        .sortedByDescending(ActivityTitle::seconds)

    return ActivityStats(
        totalSeconds = total,
        totalTitles = fixtureEntries.size,
        currentStreak = fixtureStreakEndingToday(calendar.keys, today),
        longestStreak = fixtureLongestStreak(activeDays),
        avgSecondsPerActiveDay = if (activeDays.isEmpty()) 0 else total / activeDays.size,
        titlesThisYear = topTitles.size,
        thisYearSeconds = byYear[thisYear.toString()] ?: 0L,
        lastYearSeconds = byYear[(thisYear - 1).toString()] ?: 0L,
        byYear = byYear,
        byMonthThisYear = byMonthThisYear,
        byMonthLastYear = byMonthLastYear,
        byDayOfWeek = byDayOfWeek,
        byHourOfDay = byHourOfDay,
        calendar = calendar,
        titlesWatchedThisYear = topTitles,
        rewatched = fixtureEntries
            .filter { it.lastWatchedAt != null }
            .take(4)
            .mapIndexed { index, entry ->
                TitlePlayCount(
                    tmdbId = entry.tmdbId,
                    mediaType = entry.mediaType.wireName,
                    plays = 4 - index,
                    title = entry.title,
                    posterPath = entry.posterPath,
                )
            }
            .filter { it.plays > 1 },
    )
}

/** Consecutive active days counting back from today; zero if today itself is a gap. */
private fun fixtureStreakEndingToday(active: Set<String>, today: kotlinx.datetime.LocalDate): Int {
    var streak = 0
    var cursor = today
    while (active.contains(cursor.toString())) {
        streak++
        cursor = cursor.plus(-1, DateTimeUnit.DAY)
    }
    return streak
}

/** Longest run of consecutive days anywhere in the history. */
private fun fixtureLongestStreak(sortedDates: List<String>): Int {
    var longest = 0
    var run = 0
    var previous: kotlinx.datetime.LocalDate? = null
    for (value in sortedDates) {
        val date = kotlinx.datetime.LocalDate.parse(value)
        run = if (previous != null && previous.plus(1, DateTimeUnit.DAY) == date) run + 1 else 1
        if (run > longest) longest = run
        previous = date
    }
    return longest
}

/**
 * A taste profile that agrees with the fixture library.
 *
 * Genre scores are counted off the saved titles' own genre ids rather than invented, so the
 * bars line up with what the fixture library actually contains. Keywords, people and
 * studios have no fixture source to derive from, so those are written out — plausible for
 * this library, and varied enough in magnitude that the ranked bars are visibly ranked.
 */
private val fixtureTaste: DiscoveryInsights by lazy { buildFixtureTaste() }

private fun buildFixtureTaste(): DiscoveryInsights {
    val catalog = fixtureMovies + fixtureTv
    val savedById = fixtureEntries.associateBy { it.tmdbId }

    fun genreScores(type: MediaType): List<DiscoveryTaste> =
        catalog.filter { it.mediaType == type && it.id in savedById }
            .flatMap { media ->
                val entry = savedById.getValue(media.id)
                // The same shape the real profile uses: status sets the base weight and a
                // rating pushes it either way, so a dropped title lands negative.
                val weight = when (entry.status) {
                    LibraryStatus.Finished -> 2.0
                    LibraryStatus.Watching -> 1.0
                    LibraryStatus.WatchLater -> 0.25
                    LibraryStatus.Dropped -> -2.0
                } + (entry.rating?.let { (it - 2.5) * 1.5 } ?: 0.0)
                media.genreIds.map { it to weight }
            }
            .groupBy({ it.first }, { it.second })
            .map { (id, weights) -> DiscoveryTaste(id, FIXTURE_GENRE_NAMES[id].orEmpty(), weights.sum()) }
            .filter { it.name.isNotEmpty() }
            .sortedByDescending(DiscoveryTaste::score)

    val movieGenres = genreScores(MediaType.Movie)
    val tvGenres = genreScores(MediaType.Tv)
    val disliked = (movieGenres + tvGenres).filter { it.score < 0 }.sortedBy(DiscoveryTaste::score)

    fun contributors(positive: Boolean) = fixtureEntries
        .map { entry ->
            val weight = when (entry.status) {
                LibraryStatus.Finished -> 2.4
                LibraryStatus.Watching -> 1.2
                LibraryStatus.WatchLater -> 0.3
                LibraryStatus.Dropped -> -2.1
            }
            ContributingTitle(
                tmdbId = entry.tmdbId,
                mediaType = entry.mediaType.wireName,
                title = entry.title,
                posterPath = entry.posterPath,
                weight = weight,
            )
        }
        .filter { if (positive) it.weight > 0 else it.weight < 0 }
        .sortedByDescending { if (positive) it.weight else -it.weight }

    val engaged = catalog.filter { it.id in savedById }
    val decades = engaged
        .mapNotNull { (it.releaseDate ?: it.firstAirDate)?.take(4)?.toIntOrNull() }
        .filter { it in 1870..2200 }
        .groupingBy { it / 10 * 10 }.eachCount()
        .entries.sortedWith(
            compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key },
        )
        .map { DecadeCount(it.key, it.value) }
    val languages = engaged
        .groupingBy { FIXTURE_LANGUAGES[it.id] ?: "en" }.eachCount()
        .entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
        )
        .map { LanguageCount(it.key, it.value) }

    return DiscoveryInsights(
        decades = decades,
        languages = languages,
        // A believable mix of feature films and hour-long drama episodes.
        averageRuntimeMinutes = 78,
        topMovieGenres = movieGenres.filter { it.score > 0 }.take(8),
        topTvGenres = tvGenres.filter { it.score > 0 }.take(8),
        dislikedGenres = disliked.take(6),
        topKeywords = listOf(
            DiscoveryTaste(1, "dystopia", 9.4),
            DiscoveryTaste(2, "heist", 7.8),
            DiscoveryTaste(3, "time travel", 6.9),
            DiscoveryTaste(4, "anti-hero", 6.1),
            DiscoveryTaste(5, "found family", 5.2),
            DiscoveryTaste(6, "slow burn", 4.4),
            DiscoveryTaste(7, "one-shot", 3.6),
            DiscoveryTaste(8, "unreliable narrator", 2.8),
            DiscoveryTaste(9, "small town", 2.1),
        ),
        topPeople = listOf(
            DiscoveryTaste(101, "Bryan Cranston", 8.6),
            DiscoveryTaste(102, "Denis Villeneuve", 7.4),
            DiscoveryTaste(103, "Roger Deakins", 6.2),
            DiscoveryTaste(104, "Tilda Swinton", 5.5),
            DiscoveryTaste(105, "Hans Zimmer", 4.8),
            DiscoveryTaste(106, "Greta Lee", 3.9),
        ),
        signalsUsed = fixtureEntries.size,
        topStudios = listOf(
            StudioEntry(201, "A24", 6),
            StudioEntry(202, "HBO", 5),
            StudioEntry(203, "Warner Bros.", 4),
            StudioEntry(204, "Studio Ghibli", 3),
            StudioEntry(205, "AMC", 2),
            StudioEntry(206, "Netflix", 2),
        ),
        topContributors = contributors(positive = true),
        negativeContributors = contributors(positive = false),
    )
}

/** Small enough that scrolling the fixture grid reaches a second page and then the end. */
private const val FIXTURE_PAGE_SIZE = 12

private val FIXTURE_GENRE_NAMES = mapOf(
    12 to "Adventure", 14 to "Fantasy", 16 to "Animation", 18 to "Drama",
    27 to "Horror", 28 to "Action", 35 to "Comedy", 36 to "History",
    37 to "Western", 53 to "Thriller", 80 to "Crime", 878 to "Science Fiction",
    9648 to "Mystery", 10749 to "Romance", 10751 to "Family", 10752 to "War",
    10759 to "Action & Adventure", 10765 to "Sci-Fi & Fantasy", 10768 to "War & Politics",
)

private class FixtureLibraryRepository : LibraryRepository {
    private val _entries = MutableStateFlow<LibraryState>(LibraryState.Ready(fixtureEntries))
    override val entries: StateFlow<LibraryState> = _entries
    private val watchedEpisodes = mutableMapOf<Triple<Int, Int, Int>, Boolean>()
    private val savedProgress = fixtureProgress.associateBy {
        progressKey(it.tmdbId, it.mediaType, it.season, it.episode)
    }.toMutableMap()
    private val _watchProgress = MutableStateFlow(
        savedProgress.values.sortedByDescending { it.watchedAt },
    )
    override val watchProgress: StateFlow<List<WatchProgress>> = _watchProgress

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
        _watchProgress.value = savedProgress.values.sortedByDescending { it.watchedAt }
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
private fun fixtureCatalog(id: String, name: String, type: String) = AddonCatalogDescriptor(
    addonId = "fixture.provider",
    addonName = "Fixture Provider",
    addonUrl = "https://example.com/manifest.json",
    type = type,
    catalogId = id,
    name = name,
)

private class FixtureAddonRepository : AddonRepository {
    override val supportsNuvio: Boolean = true

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
            // Two, one per type, so a fixture run exercises the switch on Explore as well
            // as the row itself — the shelves are filtered by the format toggle, and a
            // single movie catalog would look broken the moment someone picked Series.
            catalogs = listOf(
                fixtureCatalog("popular", "Popular", "movie"),
                fixtureCatalog("top", "Top rated", "series"),
            ),
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

    override suspend fun catalogs(): List<AddonCatalogDescriptor> = addons
        .filter(Addon::enabled)
        .flatMap(Addon::catalogs)
        .filter(AddonCatalogDescriptor::enabled)

    override suspend fun catalogPage(
        addonId: String,
        type: String,
        catalogId: String,
        skip: Int,
        limit: Int,
    ): AddonCatalogPage {
        val pool = if (type == "series") fixtureTv else fixtureMovies
        // Reversed against the discover feed so the row is visibly its own list rather
        // than a second copy of Trending — the whole point of drawing it is that an
        // addon chose this order.
        val ordered = pool.reversed()
        return AddonCatalogPage(
            medias = ordered.drop(skip).take(limit),
            nextSkip = skip + minOf(limit, (ordered.size - skip).coerceAtLeast(0)),
        )
    }

    override suspend fun setCatalogEnabled(addonId: String, catalogKey: String, enabled: Boolean) {
        addons.replaceAll { addon ->
            if (addon.id != addonId) addon
            else addon.copy(
                catalogs = addon.catalogs.map {
                    if (it.key == catalogKey) it.copy(enabled = enabled) else it
                },
            )
        }
        reload()
    }

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

/**
 * A signed-out account that accepts anything.
 *
 * Sign-in has no server to refuse it, so this exists to make the signed-in half
 * of the settings page reachable in fixtures mode — the state changes, the sync
 * status stamps a time, and signing out puts it back.
 */
private class FixtureAccountRepository : AccountRepository {
    private val _account = MutableStateFlow<AccountState>(AccountState.SignedOut)
    override val account: StateFlow<AccountState> = _account

    private val _syncStatus = MutableStateFlow(SyncStatus())
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus

    override suspend fun signIn(email: String, password: String): AuthOutcome {
        if (email.isBlank() || password.isBlank()) {
            return AuthOutcome.Failure("Enter an email address and a password.")
        }
        _account.value = AccountState.SignedIn(email = email.trim(), userId = "fixture-user")
        syncNow()
        return AuthOutcome.Success
    }

    override suspend fun register(email: String, password: String, profileName: String) =
        signIn(email, password)

    override suspend fun confirmRegistration(
        email: String,
        token: String,
        password: String,
        profileName: String,
    ) = signIn(email, password)

    override suspend fun sendOtp(email: String): AuthOutcome =
        if (email.isBlank()) AuthOutcome.Failure("Enter an email address.") else AuthOutcome.Success

    override suspend fun verifyOtp(email: String, token: String): AuthOutcome =
        if (token.isBlank()) AuthOutcome.Failure("Enter the code from your email.") else {
            _account.value = AccountState.SignedIn(email.trim(), "fixture-user")
            syncNow()
            AuthOutcome.Success
        }

    override suspend fun signOut() {
        _account.value = AccountState.SignedOut
        _syncStatus.value = SyncStatus()
    }

    override suspend fun syncNow() {
        _syncStatus.value = SyncStatus(lastSyncedAt = Clock.System.now())
    }
}

private class FixtureProfileRepository : ProfileRepository {
    private var counter = 0
    private val _profiles = MutableStateFlow<ProfilesState>(
        ProfilesState.Ready(
            profiles = listOf(
                Profile(id = "fixture-primary", name = "Cove", isPrimary = true),
                Profile(id = "fixture-guest", name = "Guest"),
            ),
            activeProfileId = "fixture-primary",
        ),
    )
    override val profiles: StateFlow<ProfilesState> = _profiles

    override suspend fun create(name: String): Profile {
        val profile = Profile(id = "fixture-${++counter}", name = name)
        edit { it.copy(profiles = it.profiles + profile) }
        return profile
    }

    override suspend fun rename(id: String, name: String) = edit { state ->
        state.copy(profiles = state.profiles.map { if (it.id == id) it.copy(name = name) else it })
    }

    override suspend fun activate(id: String) = edit { it.copy(activeProfileId = id) }

    override suspend fun delete(id: String) = edit { state ->
        val remaining = state.profiles.filterNot { it.id == id }
        // The last profile is not deletable — there would be nothing to be active.
        if (remaining.isEmpty()) {
            state
        } else {
            state.copy(
                profiles = remaining,
                activeProfileId = remaining.firstOrNull { it.id == state.activeProfileId }?.id
                    ?: remaining.first().id,
            )
        }
    }

    private fun edit(transform: (ProfilesState.Ready) -> ProfilesState.Ready) {
        val current = _profiles.value as? ProfilesState.Ready ?: return
        _profiles.value = transform(current)
    }
}

/** Walks the device flow without Trakt: connecting shows a code, then links. */
private class FixtureTraktRepository : TraktRepository {
    private val _state = MutableStateFlow<TraktState>(TraktState.Unlinked())
    override val state: StateFlow<TraktState> = _state

    override suspend fun startLink() {
        _state.value = TraktState.Pending(
            userCode = "COVE-1234",
            verificationUrl = "https://trakt.tv/activate",
        )
    }

    override suspend fun cancelLink() {
        _state.value = TraktState.Unlinked()
    }

    override suspend fun unlink() {
        _state.value = TraktState.Unlinked()
    }

    override suspend fun syncNow() = Unit
}

private class FixtureDeviceRepository : DeviceRepository {
    override val available: Boolean = true
    override val appVersion: String = "fixtures"
    private val _performance = MutableStateFlow(DevicePerformanceState())
    override val performance: StateFlow<DevicePerformanceState> = _performance

    private var config = "# mpv configuration\nhwdec=auto-copy\n"

    override suspend fun setLowPerformanceMode(enabled: Boolean) {
        _performance.value = _performance.value.copy(lowPerformanceMode = enabled)
    }

    override suspend fun dismissLowPerformanceRecommendation() {
        _performance.value = _performance.value.copy(recommendationDismissed = true)
    }

    override suspend fun readMpvConfig(): String = config

    override suspend fun writeMpvConfig(content: String) {
        config = content
    }
}

/**
 * A plausible cache, so the storage screen is a screen rather than four zeroes.
 *
 * The sizes are the shape of a real install — one big torrent cache and small everything else —
 * because a fixtures run is what the layout is judged on, and a row reading "0 bytes" hides the
 * wrapping problem that a row reading "18.4 GiB" reveals.
 */
private class FixtureStorageRepository : StorageRepository {
    override val available: Boolean = true

    private val _policy = MutableStateFlow(
        TorrentCachePolicy(
            limitBytes = 20L * 1024 * 1024 * 1024,
            downloadAheadBytes = 512L * 1024 * 1024,
            deleteAfterWatching = false,
            maxAgeDays = 30,
        ),
    )
    override val policy: StateFlow<TorrentCachePolicy> = _policy

    private val sizes = mutableMapOf(
        CacheKind.TorrentDownloads to CacheEntry(CacheKind.TorrentDownloads, 18_412_773_376, 7),
        CacheKind.TorrentMetadata to CacheEntry(CacheKind.TorrentMetadata, 61_440, 24),
        CacheKind.Images to CacheEntry(CacheKind.Images, 41_128_755, 812),
        CacheKind.Tools to CacheEntry(CacheKind.Tools, 40_894_464, 1),
    )

    private val _usage = MutableStateFlow<StorageUsageState>(StorageUsageState.Loading)
    override val usage: StateFlow<StorageUsageState> = _usage

    override suspend fun refresh() {
        val entries = sizes.values.sortedByDescending { it.bytes }
        _usage.value = StorageUsageState.Ready(
            StorageUsage(
                entries = entries,
                totalBytes = entries.sumOf { it.bytes },
                freeDiskBytes = 214_748_364_800,
            ),
        )
    }

    override suspend fun setPolicy(policy: TorrentCachePolicy) {
        _policy.value = policy
    }

    override suspend fun clear(kind: CacheKind): ClearResult {
        val freed = sizes[kind]?.bytes ?: 0
        sizes[kind] = CacheEntry(kind, 0, 0)
        refresh()
        return ClearResult(freedBytes = freed, keptInUse = 0)
    }
}

fun FixtureAppGraph(): AppGraph = AppGraph(
    content   = FixtureContentRepository(),
    library   = FixtureLibraryRepository(),
    settings  = FixtureSettingsRepository(),
    playback  = FixturePlaybackRepository(),
    addons    = FixtureAddonRepository(),
    calendar  = FixtureCalendarRepository(),
    discovery = FixtureDiscoveryRepository(),
    insights  = FixtureInsightsRepository(),
    account   = FixtureAccountRepository(),
    profiles  = FixtureProfileRepository(),
    trakt     = FixtureTraktRepository(),
    device    = FixtureDeviceRepository(),
    storage   = FixtureStorageRepository(),
    fixtures  = true,
)
