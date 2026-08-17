package com.coveninja.cove.backend.trakt

import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.ExternalHistoryItem
import com.coveninja.cove.backend.store.ExternalWatchlistItem
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.TraktStats
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

/** Profile-scoped Trakt OAuth, scrobbling, and additive two-way sync. */
class TraktService(
    private val config: TraktConfig,
    private val database: CoveDatabase,
    private val session: ActiveProfileSession,
    private val settings: LocalSettingsRepository,
    private val library: LocalLibraryRepository,
    private val catalog: MediaCatalog,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val minimumWriteIntervalMillis: Long = 1_100,
    startBackgroundSync: Boolean = true,
) {
    private val writeMutex = Mutex()
    private val refreshMutex = Mutex()
    private var lastWriteMillis = 0L
    private val flowJobs = ConcurrentHashMap<String, Job>()
    private val flowStates = ConcurrentHashMap<String, String>()

    val isConfigured: Boolean
        get() = config.clientId.isNotBlank() && config.clientSecret.isNotBlank()

    init {
        importLegacy(session.profileId.value)
        if (startBackgroundSync && isConfigured) {
            scope.launch {
                delay(BACKGROUND_START_DELAY_MILLIS)
                while (isActive) {
                    runCatching { syncNow() }
                    delay(BACKGROUND_INTERVAL_MILLIS)
                }
            }
        }
    }

    suspend fun startDeviceFlow(): TraktDeviceCode {
        requireConfigured()
        val response = writeRequest(
            HttpMethod.Post,
            "/oauth/device/code",
            buildJsonObject { put("client_id", config.clientId) },
        )
        response.requireStatus(200, "device/code")
        val code = CoveJson.decodeFromString<TraktDeviceCode>(response.body)
        require(code.deviceCode.isNotBlank() && code.userCode.isNotBlank() &&
            code.verificationUrl.isNotBlank() && code.expiresIn > 0) {
            "Trakt returned an incomplete device code"
        }
        val profileId = session.profileId.value
        flowJobs.remove(profileId)?.cancel()
        flowStates[profileId] = "pending"
        flowJobs[profileId] = scope.launch {
            val deadline = clock.millis() + code.expiresIn * 1_000L
            var interval = (code.interval.coerceAtLeast(1) + 1) * 1_000L
            while (isActive && clock.millis() < deadline) {
                delay(interval)
                when (runCatching { poll(code.deviceCode, profileId) }.getOrNull()?.status) {
                    "authorized" -> return@launch
                    "expired" -> {
                        flowStates[profileId] = "expired"
                        return@launch
                    }
                    "denied", "invalid" -> {
                        flowStates[profileId] = "denied"
                        return@launch
                    }
                    "slow_down" -> interval = (interval + 5_000).coerceAtMost(30_000)
                }
            }
            if (isActive) flowStates[profileId] = "expired"
        }
        return code
    }

    suspend fun poll(deviceCode: String): TraktPollResponse = poll(deviceCode, session.profileId.value)

    fun status(): TraktStatus {
        val profileId = session.profileId.value
        importLegacy(profileId)
        val stored = stored(profileId)
        return TraktStatus(
            connected = stored != null,
            username = stored?.username.orEmpty(),
            expiresAt = stored?.expiresAt?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)?.toString().orEmpty(),
            flowState = flowStates[profileId] ?: "idle",
        )
    }

    fun unlink() {
        val profileId = session.profileId.value
        flowJobs.remove(profileId)?.cancel()
        flowStates[profileId] = "idle"
        val token = stored(profileId)?.accessToken.orEmpty()
        database.coveQueries.deleteTraktSession(profileId)
        if (token.isNotBlank() && isConfigured) {
            scope.launch {
                runCatching {
                    writeRequest(
                        HttpMethod.Post,
                        "/oauth/revoke",
                        buildJsonObject {
                            put("token", token)
                            put("client_id", config.clientId)
                            put("client_secret", config.clientSecret)
                        },
                    )
                }
            }
        }
    }

    /** Returns false when the feature is disabled or the profile is unlinked. */
    fun enqueueScrobble(request: TraktScrobbleRequest): Boolean {
        validateScrobble(request)
        val enabled = (settings.settings.value as? SettingsState.Ready)
            ?.settings?.traktScrobbleEnabled == true
        val profileId = session.profileId.value
        if (!enabled || stored(profileId) == null || !isConfigured) return false
        scope.launch { runCatching { sendScrobble(profileId, request) } }
        return true
    }

    /** Direct form used by in-process callers; HTTP keeps using the async wrapper above. */
    suspend fun scrobbleNow(request: TraktScrobbleRequest): Boolean {
        validateScrobble(request)
        val enabled = (settings.settings.value as? SettingsState.Ready)
            ?.settings?.traktScrobbleEnabled == true
        val profileId = session.profileId.value
        if (!enabled || stored(profileId) == null || !isConfigured) return false
        sendScrobble(profileId, request)
        return true
    }

    fun enqueueSync(): Boolean {
        val enabled = (settings.settings.value as? SettingsState.Ready)
            ?.settings?.traktSyncEnabled == true
        if (!enabled || !isConfigured || stored(session.profileId.value) == null) return false
        scope.launch { runCatching { syncNow() } }
        return true
    }

    suspend fun syncNow(): TraktSyncResult {
        requireConfigured()
        val enabled = (settings.settings.value as? SettingsState.Ready)
            ?.settings?.traktSyncEnabled == true
        if (!enabled) return TraktSyncResult(false, "disabled")
        val profileId = session.profileId.value
        var token = ensureValidToken(profileId) ?: return TraktSyncResult(false, "not_connected")
        val cursor = token.lastSyncAt.toInstantOrNull()
        val cycleStart = clock.instant()

        val activities = get("/sync/last_activities", token.accessToken)
        activities.requireSuccess("sync/last_activities")
        val last = CoveJson.decodeFromString<TraktLastActivities>(activities.body)
        val needsPull = cursor == null || last.newestWatchedOrWatchlisted().any { it > cursor }

        if (needsPull) {
            val history = pullHistory(token.accessToken, cursor)
            val watchlist = pullWatchlist(token.accessToken)
            library.applyExternal(history, watchlist)
        }
        pushHistory(token.accessToken, cursor)
        pushWatchlist(token.accessToken, cursor)

        // A profile switch during network work must not advance another
        // profile's cursor. The token remains correctly scoped to profileId.
        token = stored(profileId) ?: return TraktSyncResult(false, "unlinked_during_sync")
        save(profileId, token.copy(lastSyncAt = cycleStart.toString()))
        return TraktSyncResult(true)
    }

    private suspend fun poll(deviceCode: String, profileId: String): TraktPollResponse {
        requireConfigured()
        require(deviceCode.isNotBlank()) { "device_code is required" }
        val response = writeRequest(
            HttpMethod.Post,
            "/oauth/device/token",
            buildJsonObject {
                put("code", deviceCode)
                put("client_id", config.clientId)
                put("client_secret", config.clientSecret)
            },
        )
        val status = when (response.status) {
            400 -> "pending"
            404, 409 -> "invalid"
            410 -> "expired"
            418 -> "denied"
            429 -> "slow_down"
            200 -> "authorized"
            else -> throw TraktException("Trakt device/token returned HTTP ${response.status}")
        }
        if (status != "authorized") return TraktPollResponse(status)

        val token = CoveJson.decodeFromString<TraktTokenResponse>(response.body)
        require(token.accessToken.isNotBlank() && token.refreshToken.isNotBlank() && token.expiresIn > 0) {
            "Trakt returned an incomplete OAuth token"
        }
        val username = fetchUsername(token.accessToken)
        save(
            profileId,
            StoredTraktSession(
                token.accessToken,
                token.refreshToken,
                clock.instant().plusSeconds(token.expiresIn.toLong()).epochSecond,
                username,
                "",
            ),
        )
        flowStates[profileId] = "authorized"
        flowJobs.remove(profileId)?.takeIf { it != kotlinx.coroutines.currentCoroutineContext()[Job] }?.cancel()
        return TraktPollResponse(status, username)
    }

    /**
     * Trakt's all-time totals for the linked account, or null if there is nothing to show.
     *
     * Every failure path returns null rather than throwing. This is a decorative extra on a
     * page that is complete without it — an unlinked account, an expired token, a Trakt
     * outage and a malformed response should all read the same way to the caller, which is
     * "no Trakt section", not "the insights page is broken".
     */
    suspend fun stats(): TraktStats? {
        val profileId = session.profileId.value
        val token = runCatching { ensureValidToken(profileId) }.getOrNull() ?: return null
        val response = runCatching { get("/users/me/stats", token.accessToken) }.getOrNull()
            ?: return null
        if (response.status != 200) return null
        return runCatching {
            val root = CoveJson.parseToJsonElement(response.body).jsonObject
            fun section(name: String) = root[name]?.jsonObject
            fun Int(section: kotlinx.serialization.json.JsonObject?, key: String): Int =
                section?.get(key)?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            fun Long(section: kotlinx.serialization.json.JsonObject?, key: String): Long =
                section?.get(key)?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

            val movies = section("movies")
            val shows = section("shows")
            val episodes = section("episodes")
            TraktStats(
                moviesWatched = Int(movies, "watched"),
                movieMinutes = Long(movies, "minutes"),
                showsWatched = Int(shows, "watched"),
                episodesWatched = Int(episodes, "watched"),
                episodeMinutes = Long(episodes, "minutes"),
                ratings = Int(section("ratings"), "total"),
            )
        }.getOrNull()?.takeUnless { it.isEmpty }
    }

    private suspend fun fetchUsername(accessToken: String): String {
        val response = get("/users/me", accessToken)
        if (response.status != 200) return ""
        return runCatching {
            CoveJson.parseToJsonElement(response.body).jsonObject["username"]?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")
    }

    private suspend fun ensureValidToken(profileId: String): StoredTraktSession? {
        var current = stored(profileId) ?: return null
        if (current.expiresAt - clock.instant().epochSecond > 3_600) return current
        return refreshMutex.withLock {
            current = stored(profileId) ?: return@withLock null
            if (current.expiresAt - clock.instant().epochSecond > 3_600) return@withLock current
            require(current.refreshToken.isNotBlank()) { "Trakt refresh token is missing" }
            val response = writeRequest(
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
            response.requireStatus(200, "refresh token")
            val refreshed = CoveJson.decodeFromString<TraktTokenResponse>(response.body)
            require(refreshed.accessToken.isNotBlank() && refreshed.expiresIn > 0) {
                "Trakt returned an incomplete refreshed token"
            }
            current.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken.ifBlank { current.refreshToken },
                expiresAt = clock.instant().plusSeconds(refreshed.expiresIn.toLong()).epochSecond,
            ).also { save(profileId, it) }
        }
    }

    private suspend fun sendScrobble(profileId: String, request: TraktScrobbleRequest) {
        val token = ensureValidToken(profileId) ?: return
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
        val response = writeRequest(
            HttpMethod.Post,
            "/scrobble/${request.action}",
            payload,
            token.accessToken,
        )
        if (response.status != 404) response.requireSuccess("scrobble/${request.action}")
    }

    private suspend fun pullHistory(accessToken: String, cursor: Instant?): List<ExternalHistoryItem> {
        val result = mutableListOf<ExternalHistoryItem>()
        var page = 1
        do {
            val start = cursor?.toString()?.let { "&start_at=${it.encodeURLParameter()}" }.orEmpty()
            val response = get("/sync/history?limit=100&page=$page$start", accessToken)
            response.requireSuccess("sync/history page $page")
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
        val response = get("/sync/watchlist", accessToken)
        response.requireSuccess("sync/watchlist")
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
        val response = writeRequest(
            HttpMethod.Post,
            "/sync/history",
            buildJsonObject {
                put("movies", JsonArray(movies))
                put("shows", JsonArray(shows))
            },
            accessToken,
        )
        response.requireSuccess("push history")
    }

    private suspend fun pushWatchlist(accessToken: String, cursor: Instant?) {
        val entries = library.snapshotForSync().entries.filter { entry ->
            entry.status == LibraryStatus.WatchLater &&
                (cursor == null || entry.addedAt.toInstantOrNull()?.let { it > cursor } == true)
        }
        if (entries.isEmpty()) return
        val response = writeRequest(
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
        response.requireSuccess("push watchlist")
    }

    private suspend fun get(path: String, accessToken: String): TraktHttpResponse =
        request(HttpMethod.Get, path, null, accessToken)

    private suspend fun writeRequest(
        method: HttpMethod,
        path: String,
        body: JsonElement,
        accessToken: String = "",
    ): TraktHttpResponse = writeMutex.withLock {
        val elapsed = clock.millis() - lastWriteMillis
        if (lastWriteMillis > 0 && elapsed < minimumWriteIntervalMillis) {
            delay(minimumWriteIntervalMillis - elapsed)
        }
        var response = request(method, path, body, accessToken)
        lastWriteMillis = clock.millis()
        if (response.status == 429) {
            val retrySeconds = response.headers[HttpHeaders.RetryAfter]
                ?.toLongOrNull()?.coerceIn(1, 60) ?: 1
            delay(retrySeconds * 1_000)
            response = request(method, path, body, accessToken)
            lastWriteMillis = clock.millis()
        }
        response
    }

    private suspend fun request(
        method: HttpMethod,
        path: String,
        body: JsonElement?,
        accessToken: String,
    ): TraktHttpResponse {
        val response = httpClient.request("${config.baseUrl.trimEnd('/')}$path") {
            this.method = method
            header("trakt-api-key", config.clientId)
            header("trakt-api-version", "2")
            if (accessToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $accessToken")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return TraktHttpResponse(
            response.status.value,
            response.headers.entries().associate { it.key to it.value.joinToString(",") },
            response.bodyAsText(),
        )
    }

    private fun stored(profileId: String): StoredTraktSession? {
        importLegacy(profileId)
        return database.coveQueries.selectTraktSession(profileId).executeAsOneOrNull()?.let {
            StoredTraktSession(it.access_token, it.refresh_token, it.expires_at, it.username, it.last_sync_at)
        }
    }

    private fun save(profileId: String, value: StoredTraktSession) {
        database.coveQueries.upsertTraktSession(
            profileId,
            value.accessToken,
            value.refreshToken,
            value.expiresAt,
            value.username,
            value.lastSyncAt,
        )
    }

    private fun importLegacy(profileId: String) {
        if (database.coveQueries.selectTraktSession(profileId).executeAsOneOrNull() != null) return
        val legacy = database.coveQueries.selectLegacyPayloadRecord(profileId, "trakt")
            .executeAsOneOrNull()?.json ?: return
        runCatching { CoveJson.decodeFromString<LegacyTraktSession>(legacy) }.getOrNull()?.let { old ->
            if (old.accessToken.isNotBlank()) {
                save(
                    profileId,
                    StoredTraktSession(
                        old.accessToken,
                        old.refreshToken,
                        old.expiresAt.toInstantOrNull()?.epochSecond ?: 0,
                        old.username,
                        old.lastSyncAt,
                    ),
                )
            }
        }
    }

    private fun requireConfigured() {
        check(isConfigured) { "Trakt integration not configured (credentials missing)" }
    }

    private fun validateScrobble(request: TraktScrobbleRequest) {
        require(request.action in setOf("start", "pause", "stop")) {
            "action must be start, pause, or stop"
        }
        require(request.tmdbId > 0 && request.mediaType in setOf("movie", "tv")) {
            "positive tmdb_id and media_type movie or tv required"
        }
        require(request.progress in 0.0..100.0) { "progress must be between 0 and 100" }
        if (request.mediaType == "tv") {
            require(request.season != null && request.episode != null &&
                request.season >= 0 && request.episode >= 0) {
                "tv scrobbles require non-negative season and episode"
            }
        }
    }

    private data class StoredTraktSession(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val username: String,
        val lastSyncAt: String,
    )

    private data class TraktHttpResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
    ) {
        fun requireStatus(expected: Int, operation: String) {
            if (status != expected) throw TraktException("Trakt $operation returned HTTP $status")
        }

        fun requireSuccess(operation: String) {
            if (status !in 200..299) throw TraktException("Trakt $operation returned HTTP $status")
        }
    }

    companion object {
        private const val BACKGROUND_START_DELAY_MILLIS = 60_000L
        private const val BACKGROUND_INTERVAL_MILLIS = 6 * 60 * 60 * 1_000L
    }
}

class TraktException(message: String) : RuntimeException(message)

@Serializable
data class TraktDeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int = 5,
)

@Serializable
data class TraktPollRequest(@SerialName("device_code") val deviceCode: String)

@Serializable
data class TraktPollResponse(val status: String, val username: String = "")

@Serializable
data class TraktStatus(
    val connected: Boolean,
    val username: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("flow_state") val flowState: String = "idle",
)

@Serializable
data class TraktScrobbleRequest(
    val action: String,
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("media_type") val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
    val progress: Double,
)

@Serializable
data class TraktSyncResult(val completed: Boolean, val reason: String = "")

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

private fun String.toInstantOrNull(): Instant? {
    if (isBlank()) return null
    return runCatching { Instant.parse(this) }.getOrElse {
        runCatching { java.time.OffsetDateTime.parse(this).toInstant() }.getOrNull()
    }
}
