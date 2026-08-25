package com.coveninja.cove.backend.simkl

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
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Simkl needs no client secret: Cove links through the PIN flow, which is the one Simkl
 * documents for clients that cannot keep one. [appVersion] is not decoration — Simkl
 * requires `app-name` and `app-version` on every request and rejects those without them.
 */
data class SimklConfig(
    val clientId: String,
    val appVersion: String = "dev",
    val baseUrl: String = "https://api.simkl.com",
)

/**
 * Simkl's half of [TrackerService].
 *
 * Three things differ from Trakt in ways that shape the code rather than just the URLs.
 * Its tokens never expire and it issues no refresh token, so the base's refresh hooks stay
 * at their defaults and the `refresh_token` column carries the numeric account id that
 * `/users/{id}/stats` needs by path. It holds a **20-second per-user lock** on scrobbles
 * and rejects anything inside it, while Cove's progress ticker fires every ten — hence the
 * debounce. And `/sync/all-items` answers with `imdb`/`tvdb` ids and no TMDB id at all,
 * so a pull has to resolve every title through [MediaCatalog.findByImdbId] before the
 * library, which is TMDB-keyed throughout, can take it.
 */
class SimklService(
    private val config: SimklConfig,
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
    provider = TrackerProvider.Simkl,
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
    scrobbleDebounceMillis = SCROBBLE_LOCK_MILLIS,
    startBackgroundSync = startBackgroundSync,
) {
    override val isConfigured: Boolean
        get() = config.clientId.isNotBlank()

    override fun HttpRequestBuilder.decorate(accessToken: String) {
        header("simkl-api-key", config.clientId)
        header(HttpHeaders.UserAgent, "cove/${config.appVersion}")
        parameter("client_id", config.clientId)
        parameter("app-name", APP_NAME)
        parameter("app-version", config.appVersion)
    }

    override fun scrobbleEnabled(settings: AppSettings) = settings.simklScrobbleEnabled

    override fun syncEnabled(settings: AppSettings) = settings.simklSyncEnabled

    override suspend fun startDeviceFlow(): TrackerDeviceCode {
        requireConfigured()
        val response = http.get("/oauth/pin")
        response.requireStatus(200, provider, "oauth/pin")
        val code = CoveJson.decodeFromString<SimklPinResponse>(response.body)
        require(
            code.userCode.isNotBlank() && code.verificationUrl.isNotBlank() && code.expiresIn > 0,
        ) {
            "Simkl returned an incomplete PIN code"
        }
        val resolved = TrackerDeviceCode(
            deviceCode = code.deviceCode.ifBlank { code.userCode },
            userCode = code.userCode,
            verificationUrl = code.verificationUrl,
            expiresIn = code.expiresIn,
            interval = code.interval,
        )
        val profileId = session.profileId.value
        links.start(profileId, resolved) { pollOnce(resolved, profileId) }
        return resolved
    }

    /**
     * Simkl polls by the code the viewer can see, not the device code, and answers 200
     * either way — pending and authorised are told apart by the body's `result`, never by
     * the status. Treating a non-OK result as an error would end the flow on the first
     * poll, which is the one that is always still pending.
     */
    override suspend fun pollOnce(code: TrackerDeviceCode, profileId: String): LinkPoll {
        requireConfigured()
        val userCode = code.userCode.ifBlank { code.deviceCode }
        val response = http.get("/oauth/pin/${userCode.encodeURLParameter()}")
        if (response.status == 404) return LinkPoll.Invalid
        if (response.status !in 200..299) return LinkPoll.Pending
        val body = runCatching { CoveJson.decodeFromString<SimklPinPoll>(response.body) }
            .getOrNull() ?: return LinkPoll.Pending
        if (!body.result.equals("OK", ignoreCase = true) || body.accessToken.isBlank()) {
            return when {
                body.message.contains("pending", ignoreCase = true) -> LinkPoll.Pending
                body.message.contains("expired", ignoreCase = true) -> LinkPoll.Expired
                body.message.isBlank() -> LinkPoll.Pending
                else -> LinkPoll.Denied
            }
        }

        val account = fetchAccount(body.accessToken)
        save(
            profileId,
            TrackerSession(
                accessToken = body.accessToken,
                // Not a refresh token: Simkl issues none. See the column note in Cove.sq.
                refreshToken = account.second,
                expiresAt = 0,
                username = account.first,
            ),
        )
        links.authorized(profileId)
        return LinkPoll.Authorized
    }

    override suspend fun sendScrobble(token: TrackerSession, request: TrackerScrobbleRequest) {
        val payload = buildJsonObject {
            if (request.mediaType == "movie") {
                put("movie", buildJsonObject {
                    put("ids", buildJsonObject { put("tmdb", request.tmdbId.toString()) })
                })
            } else {
                put("show", buildJsonObject {
                    put("ids", buildJsonObject { put("tmdb", request.tmdbId.toString()) })
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
        // 404 is "Simkl does not know this title", which is not a Cove failure — the same
        // allowance the Trakt path makes.
        if (response.status != 404) response.requireSuccess(provider, "scrobble/${request.action}")
    }

    override suspend fun pull(token: TrackerSession, cursor: Instant?): TrackerPull {
        val activities = http.get("/sync/activities", token.accessToken)
        activities.requireSuccess(provider, "sync/activities")
        val newest = runCatching {
            CoveJson.parseToJsonElement(activities.body).jsonObject["all"]
                ?.jsonPrimitive?.content?.toInstantOrNull()
        }.getOrNull()
        if (cursor != null && newest != null && newest <= cursor) return TrackerPull(fetched = false)

        val from = cursor?.toString()?.let { "&date_from=${it.encodeURLParameter()}" }.orEmpty()
        val response = http.get(
            "/sync/all-items?extended=full&episode_watched_at=yes$from",
            token.accessToken,
        )
        response.requireSuccess(provider, "sync/all-items")
        val items = CoveJson.decodeFromString<SimklAllItems>(response.body)

        val history = mutableListOf<ExternalHistoryItem>()
        val watchlist = mutableListOf<ExternalWatchlistItem>()
        resolveAll(items).forEach { (entry, media) ->
            if (entry.status == PLAN_TO_WATCH) {
                watchlist += ExternalWatchlistItem(
                    tmdbId = media.tmdbId,
                    mediaType = media.type,
                    title = entry.title(),
                    posterPath = media.posterPath,
                    listedAt = entry.addedToWatchlistAt.ifBlank { entry.lastWatchedAt },
                )
                return@forEach
            }
            if (media.type == MediaType.Movie) {
                val watchedAt = entry.lastWatchedAt.ifBlank { return@forEach }
                history += ExternalHistoryItem(
                    tmdbId = media.tmdbId,
                    mediaType = MediaType.Movie,
                    title = entry.title(),
                    posterPath = media.posterPath,
                    watchedAt = watchedAt,
                )
                return@forEach
            }
            entry.seasons.forEach { season ->
                season.episodes.forEach { episode ->
                    val watchedAt = episode.watchedAt.ifBlank { entry.lastWatchedAt }
                    if (watchedAt.isBlank()) return@forEach
                    history += ExternalHistoryItem(
                        tmdbId = media.tmdbId,
                        mediaType = MediaType.Tv,
                        season = season.number,
                        episode = episode.number,
                        title = entry.title(),
                        posterPath = media.posterPath,
                        watchedAt = watchedAt,
                    )
                }
            }
        }
        return TrackerPull(history = history, watchlist = watchlist)
    }

    override suspend fun push(token: TrackerSession, cursor: Instant?) {
        pushHistory(token.accessToken, cursor)
        pushWatchlist(token.accessToken, cursor)
    }

    /**
     * All-time totals, or null for anything at all going wrong — an unlinked account, a
     * revoked token, a Simkl outage and a malformed response should all read as "no Simkl
     * section" rather than a broken insights page.
     */
    override suspend fun stats(): TrackerStats? {
        val profileId = session.profileId.value
        val token = runCatching { ensureValidToken(profileId) }.getOrNull() ?: return null
        val accountId = token.refreshToken.takeIf { it.isNotBlank() } ?: return null
        val response = runCatching {
            http.write(HttpMethod.Post, "/users/$accountId/stats", null, token.accessToken)
        }.getOrNull() ?: return null
        if (response.status != 200) return null
        return runCatching {
            val root = CoveJson.parseToJsonElement(response.body).jsonObject
            val movies = root["movies"]?.jsonObject
            val tv = root["tv"]?.jsonObject
            val anime = root["anime"]?.jsonObject

            fun number(section: JsonObject?, vararg path: String): Long {
                var node = section
                path.dropLast(1).forEach { node = node?.get(it)?.jsonObject }
                return node?.get(path.last())?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            }

            // Simkl counts shows and anime in separate buckets; Cove has one "shows"
            // figure, so they are summed rather than one of them silently dropped.
            fun showBuckets(key: String): Long = BUCKETS.sumOf { bucket ->
                number(tv, bucket, key) + number(anime, bucket, key)
            }

            TrackerStats(
                provider = provider.key,
                moviesWatched = number(movies, "completed", "count").toInt(),
                movieMinutes = number(movies, "total_mins"),
                showsWatched = showBuckets("count").toInt(),
                episodesWatched = showBuckets("watched_episodes_count").toInt(),
                episodeMinutes = number(tv, "total_mins") + number(anime, "total_mins"),
                ratings = 0,
            )
        }.getOrNull()?.takeUnless { it.isEmpty }
    }

    private suspend fun fetchAccount(accessToken: String): Pair<String, String> {
        val response = runCatching {
            http.write(HttpMethod.Post, "/users/settings", null, accessToken)
        }.getOrNull() ?: return "" to ""
        if (response.status != 200) return "" to ""
        return runCatching {
            val root = CoveJson.parseToJsonElement(response.body).jsonObject
            val name = root["user"]?.jsonObject?.get("name")?.jsonPrimitive?.content.orEmpty()
            val id = root["account"]?.jsonObject?.get("id")?.jsonPrimitive?.content.orEmpty()
            name to id
        }.getOrDefault("" to "")
    }

    /**
     * Every entry Cove can key on, paired with the TMDB media it resolved to.
     *
     * Bounded concurrency rather than a plain `map`: a first sync of a long-standing
     * account is the one case where this fans out, and firing a thousand TMDB lookups at
     * once would rate-limit the catalog the rest of the app is also using.
     */
    private suspend fun resolveAll(items: SimklAllItems): List<Pair<SimklEntry, ResolvedMedia>> {
        val gate = Semaphore(RESOLVE_CONCURRENCY)
        return coroutineScope {
            items.all().map { entry ->
                async {
                    val type = entry.mediaType() ?: return@async null
                    val imdb = entry.ref()?.ids?.imdb.orEmpty()
                    if (imdb.isBlank()) return@async null
                    val resolved = gate.withPermit { resolveImdb(imdb, type) } ?: return@async null
                    entry to resolved
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun resolveImdb(imdbId: String, type: MediaType): ResolvedMedia? {
        val cached = database.coveQueries
            .selectExternalId(IMDB_SOURCE, imdbId, type.wireName)
            .executeAsOneOrNull()
        if (cached != null) {
            val poster = runCatching { catalog.media(cached.toInt(), type).posterPath.orEmpty() }
                .getOrDefault("")
            return ResolvedMedia(cached.toInt(), type, poster)
        }
        val media = runCatching { catalog.findByImdbId(imdbId, type) }.getOrNull() ?: return null
        if (media.id <= 0) return null
        database.coveQueries.upsertExternalId(IMDB_SOURCE, imdbId, type.wireName, media.id.toLong())
        return ResolvedMedia(media.id, type, media.posterPath.orEmpty())
    }

    private suspend fun pushHistory(accessToken: String, cursor: Instant?) {
        val completed = library.snapshotForSync().progress.filter { progress ->
            progress.completed &&
                (cursor == null || progress.watchedAt.toInstantOrNull()?.let { it > cursor } == true)
        }
        if (completed.isEmpty()) return
        val movies = completed.filter { it.mediaType == MediaType.Movie }.map { progress ->
            buildJsonObject {
                put("watched_at", progress.watchedAt)
                put("ids", buildJsonObject { put("tmdb", progress.tmdbId.toString()) })
            }
        }
        val shows = completed.filter {
            it.mediaType == MediaType.Tv && it.season != null && it.episode != null
        }.groupBy { it.tmdbId }.map { (tmdbId, progress) ->
            buildJsonObject {
                put("ids", buildJsonObject { put("tmdb", tmdbId.toString()) })
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

    /**
     * Simkl has no separate watchlist: the plan-to-watch bucket is a status on the item,
     * and `to` sits on each entry rather than on the envelope.
     */
    private suspend fun pushWatchlist(accessToken: String, cursor: Instant?) {
        val entries = library.snapshotForSync().entries.filter { entry ->
            entry.status == LibraryStatus.WatchLater &&
                (cursor == null || entry.addedAt.toInstantOrNull()?.let { it > cursor } == true)
        }
        if (entries.isEmpty()) return
        fun rows(type: MediaType) = entries.filter { it.mediaType == type }.map { entry ->
            buildJsonObject {
                put("to", PLAN_TO_WATCH)
                put("title", entry.title)
                put("ids", buildJsonObject { put("tmdb", entry.tmdbId.toString()) })
            }
        }
        val response = http.write(
            HttpMethod.Post,
            "/sync/add-to-list",
            buildJsonObject {
                put("movies", JsonArray(rows(MediaType.Movie)))
                put("shows", JsonArray(rows(MediaType.Tv)))
            },
            accessToken,
        )
        response.requireSuccess(provider, "push watchlist")
    }

    private data class ResolvedMedia(
        val tmdbId: Int,
        val type: MediaType,
        val posterPath: String,
    )

    private companion object {
        const val APP_NAME = "cove"
        const val PLAN_TO_WATCH = "plantowatch"
        const val IMDB_SOURCE = "imdb"
        const val RESOLVE_CONCURRENCY = 8
        const val SCROBBLE_LOCK_MILLIS = 20_000L
        val BUCKETS = listOf("watching", "hold", "completed", "dropped")
    }
}

@Serializable
private data class SimklPinResponse(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_url") val verificationUrl: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
    val interval: Int = 5,
)

@Serializable
private data class SimklPinPoll(
    val result: String = "",
    val message: String = "",
    @SerialName("access_token") val accessToken: String = "",
)

@Serializable
private data class SimklIds(
    val simkl: Long = 0,
    val imdb: String = "",
    val tvdb: String = "",
)

@Serializable
private data class SimklMediaRef(
    val title: String = "",
    val poster: String = "",
    val ids: SimklIds = SimklIds(),
)

@Serializable
private data class SimklEpisode(
    val number: Int = 0,
    @SerialName("watched_at") val watchedAt: String = "",
)

@Serializable
private data class SimklSeason(
    val number: Int = 0,
    val episodes: List<SimklEpisode> = emptyList(),
)

@Serializable
private data class SimklEntry(
    val status: String = "",
    @SerialName("last_watched_at") val lastWatchedAt: String = "",
    @SerialName("added_to_watchlist_at") val addedToWatchlistAt: String = "",
    @SerialName("anime_type") val animeType: String = "",
    val show: SimklMediaRef? = null,
    val movie: SimklMediaRef? = null,
    val seasons: List<SimklSeason> = emptyList(),
    /** Set by [SimklAllItems.all]; Simkl says which bucket an entry came from by position. */
    @kotlinx.serialization.Transient var bucket: String = "",
) {
    fun ref(): SimklMediaRef? = movie ?: show

    fun title(): String = ref()?.title.orEmpty()

    /**
     * Anime is its own bucket on Simkl, and mostly maps onto TMDB's TV side; the exception
     * is an anime film, which Simkl marks with `anime_type`. An entry that carries only
     * `mal`/`anidb` ids never reaches here — [SimklService.resolveAll] drops it, because a
     * half-resolved title would put the wrong show in somebody's library.
     */
    fun mediaType(): MediaType? = when {
        movie != null -> MediaType.Movie
        bucket == "anime" && animeType.equals("movie", ignoreCase = true) -> MediaType.Movie
        show != null -> MediaType.Tv
        else -> null
    }
}

@Serializable
private data class SimklAllItems(
    val movies: List<SimklEntry> = emptyList(),
    val shows: List<SimklEntry> = emptyList(),
    val anime: List<SimklEntry> = emptyList(),
) {
    fun all(): List<SimklEntry> =
        movies.onEach { it.bucket = "movies" } +
            shows.onEach { it.bucket = "shows" } +
            anime.onEach { it.bucket = "anime" }
}
