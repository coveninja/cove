package com.coveninja.cove.backend.trakt

import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.ExternalHistoryItem
import com.coveninja.cove.backend.store.ExternalWatchlistItem
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.backend.tracker.LinkPoll
import com.coveninja.cove.backend.tracker.TrackerDeviceCode
import com.coveninja.cove.backend.tracker.TrackerPull
import com.coveninja.cove.backend.tracker.TrackerScrobbleRequest
import com.coveninja.cove.backend.tracker.TrackerService
import com.coveninja.cove.backend.tracker.TrackerSession
import com.coveninja.cove.backend.tracker.toInstantOrNull
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.TrackerProvider
import com.coveninja.cove.shared.model.TrackerStats
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class TraktConfig(
    val clientId: String,
    val clientSecret: String,
    val baseUrl: String = "https://api.trakt.tv",
)

/** Trakt's half of [TrackerService]: its OAuth device flow, its sync shape, its stats. */
class TraktService(
    private val config: TraktConfig,
    database: CoveDatabase,
    session: ActiveProfileSession,
    settings: LocalSettingsRepository,
    library: LocalLibraryRepository,
    catalog: MediaCatalog,
    httpClient: HttpClient,
    scope: CoroutineScope,
    clock: Clock = Clock.systemUTC(),
    minimumWriteIntervalMillis: Long = 1_100,
    startBackgroundSync: Boolean = true,
) : TrackerService(
    provider = TrackerProvider.Trakt,
    database = database,
    session = session,
    settings = settings,
    library = library,
    catalog = catalog,
    httpClient = httpClient,
    scope = scope,
    clock = clock,
    baseUrl = config.baseUrl,
    minimumWriteIntervalMillis = minimumWriteIntervalMillis,
    startBackgroundSync = startBackgroundSync,
) {
    override val isConfigured: Boolean
        get() = config.clientId.isNotBlank() && config.clientSecret.isNotBlank()

    override fun HttpRequestBuilder.decorate(accessToken: String) {
        header("trakt-api-key", config.clientId)
        header("trakt-api-version", "2")
    }

    override fun scrobbleEnabled(settings: AppSettings) = settings.traktScrobbleEnabled

    override fun syncEnabled(settings: AppSettings) = settings.traktSyncEnabled

    override suspend fun startDeviceFlow(): TrackerDeviceCode {
        requireConfigured()
        val response = http.write(
            HttpMethod.Post,
            "/oauth/device/code",
            buildJsonObject { put("client_id", config.clientId) },
        )
        response.requireStatus(200, provider, "device/code")
        val code = CoveJson.decodeFromString<TrackerDeviceCode>(response.body)
        require(
            code.deviceCode.isNotBlank() && code.userCode.isNotBlank() &&
                code.verificationUrl.isNotBlank() && code.expiresIn > 0,
        ) {
            "Trakt returned an incomplete device code"
        }
        val profileId = session.profileId.value
        links.start(profileId, code) { pollOnce(code, profileId) }
        return code
    }

    override suspend fun pollOnce(code: TrackerDeviceCode, profileId: String): LinkPoll {
        requireConfigured()
        val response = http.write(
            HttpMethod.Post,
            "/oauth/device/token",
            buildJsonObject {
                put("code", code.deviceCode)
                put("client_id", config.clientId)
                put("client_secret", config.clientSecret)
            },
        )
        val outcome = when (response.status) {
            400 -> LinkPoll.Pending
            404, 409 -> LinkPoll.Invalid
            410 -> LinkPoll.Expired
            418 -> LinkPoll.Denied
            429 -> LinkPoll.SlowDown
            200 -> LinkPoll.Authorized
            else -> http.fail("device/token", "returned HTTP ${response.status}")
        }
        if (outcome != LinkPoll.Authorized) return outcome

        val token = CoveJson.decodeFromString<TraktTokenResponse>(response.body)
        require(
            token.accessToken.isNotBlank() && token.refreshToken.isNotBlank() && token.expiresIn > 0,
        ) {
            "Trakt returned an incomplete OAuth token"
        }
        save(
            profileId,
            TrackerSession(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                expiresAt = clock.instant().plusSeconds(token.expiresIn.toLong()).epochSecond,
                username = fetchUsername(token.accessToken),
            ),
        )
        links.authorized(profileId)
        return LinkPoll.Authorized
    }

    override fun needsRefresh(current: TrackerSession): Boolean =
        current.expiresAt - clock.instant().epochSecond <= REFRESH_MARGIN_SECONDS

    override suspend fun refreshIfNeeded(profileId: String, current: TrackerSession): TrackerSession {
        require(current.refreshToken.isNotBlank()) { "Trakt refresh token is missing" }
        val response = http.write(
            HttpMethod.Post,
            "/oauth/token",
            buildJsonObject {
                put("refresh_token", current.refreshToken)
                put("client_id", config.clientId)
                put("client_secret", config.clientSecret)
                put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                put("grant_type", "refresh_token")
            },
        )
        response.requireStatus(200, provider, "refresh token")
        val refreshed = CoveJson.decodeFromString<TraktTokenResponse>(response.body)
        require(refreshed.accessToken.isNotBlank() && refreshed.expiresIn > 0) {
            "Trakt returned an incomplete refreshed token"
        }
        return current.copy(
            accessToken = refreshed.accessToken,
            refreshToken = refreshed.refreshToken.ifBlank { current.refreshToken },
            expiresAt = clock.instant().plusSeconds(refreshed.expiresIn.toLong()).epochSecond,
        ).also { save(profileId, it) }
    }

    override fun revokeRemote(accessToken: String) {
        scope.launch {
            runCatching {
                http.write(
                    HttpMethod.Post,
                    "/oauth/revoke",
                    buildJsonObject {
                        put("token", accessToken)
                        put("client_id", config.clientId)
                        put("client_secret", config.clientSecret)
                    },
                )
            }
        }
    }

    override suspend fun sendScrobble(token: TrackerSession, request: TrackerScrobbleRequest) {
        val payload = buildJsonObject {
            if (request.mediaType == "movie") {
                put("movie", buildJsonObject {
                    put("ids", buildJsonObject { put("tmdb", request.tmdbId) })
                })
            } else {
                put("show", buildJsonObject {
                    put("ids", buildJsonObject { put("tmdb", request.tmdbId) })
                })
                put("episode", buildJsonObject {
                    put("season", requireNotNull(request.season))
                    put("number", requireNotNull(request.episode))
                })
            }
            put("progress", request.progress)
        }
        val response = http.write(
            HttpMethod.Post,
            "/scrobble/${request.action}",
            payload,
            token.accessToken,
        )
        if (response.status != 404) response.requireSuccess(provider, "scrobble/${request.action}")
    }

    override suspend fun pull(token: TrackerSession, cursor: Instant?): TrackerPull {
        val activities = http.get("/sync/last_activities", token.accessToken)
        activities.requireSuccess(provider, "sync/last_activities")
        val last = CoveJson.decodeFromString<TraktLastActivities>(activities.body)
        val needsPull = cursor == null || last.newestWatchedOrWatchlisted().any { it > cursor }
        if (!needsPull) return TrackerPull(fetched = false)
        return TrackerPull(
            history = pullHistory(token.accessToken, cursor),
            watchlist = pullWatchlist(token.accessToken),
        )
    }

    override suspend fun push(token: TrackerSession, cursor: Instant?) {
        pushHistory(token.accessToken, cursor)
        pushWatchlist(token.accessToken, cursor)
    }

    /**
     * Trakt's all-time totals for the linked account, or null if there is nothing to show.
     *
     * Every failure path returns null rather than throwing. This is a decorative extra on a
     * page that is complete without it — an unlinked account, an expired token, a Trakt
     * outage and a malformed response should all read the same way to the caller, which is
     * "no Trakt section", not "the insights page is broken".
     */
    override suspend fun stats(): TrackerStats? {
        val profileId = session.profileId.value
        val token = runCatching { ensureValidToken(profileId) }.getOrNull() ?: return null
        val response = runCatching { http.get("/users/me/stats", token.accessToken) }.getOrNull()
            ?: return null
        if (response.status != 200) return null
        return runCatching {
            val root = CoveJson.parseToJsonElement(response.body).jsonObject
            fun section(name: String) = root[name]?.jsonObject
            fun Int(section: JsonObject?, key: String): Int =
                section?.get(key)?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            fun Long(section: JsonObject?, key: String): Long =
                section?.get(key)?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

            val movies = section("movies")
            val shows = section("shows")
            val episodes = section("episodes")
            TrackerStats(
                provider = provider.key,
                moviesWatched = Int(movies, "watched"),
                movieMinutes = Long(movies, "minutes"),
                showsWatched = Int(shows, "watched"),
                episodesWatched = Int(episodes, "watched"),
                episodeMinutes = Long(episodes, "minutes"),
                ratings = Int(section("ratings"), "total"),
            )
        }.getOrNull()?.takeUnless { it.isEmpty }
    }

    override fun importLegacy(profileId: String) {
        if (sessions.read(profileId) != null) return
        val legacy = database.coveQueries.selectLegacyPayloadRecord(profileId, "trakt")
            .executeAsOneOrNull()?.json ?: return
        runCatching { CoveJson.decodeFromString<LegacyTraktSession>(legacy) }.getOrNull()?.let { old ->
            if (old.accessToken.isNotBlank()) {
                save(
                    profileId,
                    TrackerSession(
                        accessToken = old.accessToken,
                        refreshToken = old.refreshToken,
                        expiresAt = old.expiresAt.toInstantOrNull()?.epochSecond ?: 0,
                        username = old.username,
                        lastSyncAt = old.lastSyncAt,
                    ),
                )
            }
        }
    }

    private suspend fun fetchUsername(accessToken: String): String {
        val response = http.get("/users/me", accessToken)
        if (response.status != 200) return ""
        return runCatching {
            CoveJson.parseToJsonElement(response.body)
                .jsonObject["username"]?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")
    }

    private suspend fun pullHistory(accessToken: String, cursor: Instant?): List<ExternalHistoryItem> {
        val result = mutableListOf<ExternalHistoryItem>()
        var page = 1
        do {
            val start = cursor?.toString()?.let { "&start_at=${it.encodeURLParameter()}" }.orEmpty()
            val response = http.get("/sync/history?limit=100&page=$page$start", accessToken)
            response.requireSuccess(provider, "sync/history page $page")
            val items = CoveJson.decodeFromString<List<TraktHistoryItem>>(response.body)
            items.forEach { item ->
                when (item.type) {
                    "movie" -> item.movie?.takeIf { it.ids.tmdb > 0 }?.let { movie ->
                        result += ExternalHistoryItem(
                            movie.ids.tmdb, MediaType.Movie, title = movie.title, watchedAt = item.watchedAt,
                        )
                    }
                    "episode" -> if ((item.show?.ids?.tmdb ?: 0) > 0 && item.episode != null) {
                        result += ExternalHistoryItem(
                            item.show!!.ids.tmdb,
                            MediaType.Tv,
                            item.episode.season,
                            item.episode.number,
                            item.show.title,
                            watchedAt = item.watchedAt,
                        )
                    }
                }
            }
            val pages = response.headers["X-Pagination-Page-Count"]?.toIntOrNull() ?: 1
            page++
        } while (page <= pages)
        return result
    }

    private suspend fun pullWatchlist(accessToken: String): List<ExternalWatchlistItem> {
        val response = http.get("/sync/watchlist", accessToken)
        response.requireSuccess(provider, "sync/watchlist")
        return CoveJson.decodeFromString<List<TraktWatchlistItem>>(response.body).mapNotNull { item ->
            val (id, type, title) = when (item.type) {
                "movie" -> Triple(item.movie?.ids?.tmdb ?: 0, MediaType.Movie, item.movie?.title.orEmpty())
                "show" -> Triple(item.show?.ids?.tmdb ?: 0, MediaType.Tv, item.show?.title.orEmpty())
                else -> return@mapNotNull null
            }
            if (id <= 0) return@mapNotNull null
            val poster = runCatching { catalog.media(id, type).posterPath.orEmpty() }.getOrDefault("")
            ExternalWatchlistItem(id, type, title, poster, item.listedAt)
        }
    }

    private suspend fun pushHistory(accessToken: String, cursor: Instant?) {
        val snapshot = library.snapshotForSync()
        val completed = snapshot.progress.filter { progress ->
            progress.completed && (cursor == null || progress.watchedAt.toInstantOrNull()?.let { it > cursor } == true)
        }
        if (completed.isEmpty()) return
        val movies = completed.filter { it.mediaType == MediaType.Movie }.map { progress ->
            buildJsonObject {
                put("watched_at", progress.watchedAt)
                put("ids", buildJsonObject { put("tmdb", progress.tmdbId) })
            }
        }
        val shows = completed.filter {
            it.mediaType == MediaType.Tv && it.season != null && it.episode != null
        }.groupBy { it.tmdbId }.map { (tmdbId, progress) ->
            buildJsonObject {
                put("ids", buildJsonObject { put("tmdb", tmdbId) })
                put("seasons", buildJsonArray {
                    progress.groupBy { requireNotNull(it.season) }.forEach { (season, episodes) ->
                        add(buildJsonObject {
                            put("number", season)
                            put("episodes", buildJsonArray {
                                episodes.forEach { episode ->
                                    add(buildJsonObject {
                                        put("number", requireNotNull(episode.episode))
                                        put("watched_at", episode.watchedAt)
                                    })
                                }
                            })
                        })
                    }
                })
            }
        }
        val response = http.write(
            HttpMethod.Post,
            "/sync/history",
            buildJsonObject {
                put("movies", JsonArray(movies))
                put("shows", JsonArray(shows))
            },
            accessToken,
        )
        response.requireSuccess(provider, "push history")
    }

    private suspend fun pushWatchlist(accessToken: String, cursor: Instant?) {
        val entries = library.snapshotForSync().entries.filter { entry ->
            entry.status == LibraryStatus.WatchLater &&
                (cursor == null || entry.addedAt.toInstantOrNull()?.let { it > cursor } == true)
        }
        if (entries.isEmpty()) return
        val response = http.write(
            HttpMethod.Post,
            "/sync/watchlist",
            buildJsonObject {
                put("movies", JsonArray(entries.filter { it.mediaType == MediaType.Movie }.map { entry ->
                    buildJsonObject { put("ids", buildJsonObject { put("tmdb", entry.tmdbId) }) }
                }))
                put("shows", JsonArray(entries.filter { it.mediaType == MediaType.Tv }.map { entry ->
                    buildJsonObject { put("ids", buildJsonObject { put("tmdb", entry.tmdbId) }) }
                }))
            },
            accessToken,
        )
        response.requireSuccess(provider, "push watchlist")
    }

    private companion object {
        const val REFRESH_MARGIN_SECONDS = 3_600L
    }
}

@Serializable
private data class TraktTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
)

@Serializable
private data class LegacyTraktSession(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    val username: String = "",
    @SerialName("last_sync_at") val lastSyncAt: String = "",
)

@Serializable
private data class TraktLastActivities(
    val movies: TraktMovieActivities = TraktMovieActivities(),
    val shows: TraktShowActivities = TraktShowActivities(),
    val episodes: TraktEpisodeActivities = TraktEpisodeActivities(),
) {
    fun newestWatchedOrWatchlisted(): List<Instant> = listOfNotNull(
        movies.watchedAt.toInstantOrNull(),
        movies.watchlistedAt.toInstantOrNull(),
        shows.watchlistedAt.toInstantOrNull(),
        episodes.watchedAt.toInstantOrNull(),
    )
}

@Serializable
private data class TraktMovieActivities(
    @SerialName("watched_at") val watchedAt: String = "",
    @SerialName("watchlisted_at") val watchlistedAt: String = "",
)

@Serializable
private data class TraktShowActivities(
    @SerialName("watchlisted_at") val watchlistedAt: String = "",
)

@Serializable
private data class TraktEpisodeActivities(@SerialName("watched_at") val watchedAt: String = "")

@Serializable
private data class TraktIds(val tmdb: Int = 0)

@Serializable
private data class TraktMediaRef(val title: String = "", val ids: TraktIds = TraktIds())

@Serializable
private data class TraktEpisodeRef(
    val season: Int = 0,
    val number: Int = 0,
    val ids: TraktIds = TraktIds(),
)

@Serializable
private data class TraktHistoryItem(
    @SerialName("watched_at") val watchedAt: String = "",
    val type: String = "",
    val movie: TraktMediaRef? = null,
    val show: TraktMediaRef? = null,
    val episode: TraktEpisodeRef? = null,
)

@Serializable
private data class TraktWatchlistItem(
    @SerialName("listed_at") val listedAt: String = "",
    val type: String = "",
    val movie: TraktMediaRef? = null,
    val show: TraktMediaRef? = null,
)
