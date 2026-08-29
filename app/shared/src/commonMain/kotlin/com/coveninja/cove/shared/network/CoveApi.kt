package com.coveninja.cove.shared.network

import com.coveninja.cove.shared.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

// HTTP compatibility client for the embedded Ktor boundary. Takes an already-configured HttpClient so
// the engine choice (OkHttp on desktop, CIO for future targets) stays outside
// this class and MockEngine can be injected in tests.
class CoveApi(
    private val httpClient: HttpClient,
    val config: CoveApiConfig,
    // Omit headers entirely when providers return null — an empty Authorization
    // header would be sent to the backend and rejected.
    private val tokenProvider: () -> String? = { null },
    private val deviceTokenProvider: () -> String? = { null },
) {

    private fun HttpRequestBuilder.applyAuthHeaders() {
        tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        deviceTokenProvider()?.let { header("X-Cove-Token", it) }
    }

    private fun HttpResponse.requireSuccess(): HttpResponse {
        if (!status.isSuccess()) {
            throw RuntimeException("HTTP ${status.value}: ${status.description}")
        }
        return this
    }

    /**
     * Like [requireSuccess], but surfaces the host's `{"error": "…"}` body when
     * there is one. Anything a user typed — credentials, a profile name — gets
     * refused with a reason worth reading, and a bare "HTTP 400" hides it.
     */
    private suspend fun HttpResponse.requireSuccessWithReason(): HttpResponse {
        if (status.isSuccess()) return this
        val reason = runCatching { body<ErrorResponseDto>().error }.getOrNull()
        throw RuntimeException(
            reason?.takeIf(String::isNotBlank) ?: "HTTP ${status.value}: ${status.description}",
        )
    }

    // ── Ping ────────────────────────────────────────────────────────────────

    suspend fun ping(): Boolean =
        httpClient.get("${config.baseUrl}/api/ping") { applyAuthHeaders() }
            .status.isSuccess()

    // ── Discovery / search ──────────────────────────────────────────────────

    suspend fun discover(type: String, limit: Int = 20): List<Media> =
        httpClient.get("${config.baseUrl}/api/discover") {
            applyAuthHeaders()
            parameter("type", type)
            parameter("limit", limit)
        }.requireSuccess().body()

    suspend fun searchMulti(q: String): SearchResultsDto =
        httpClient.get("${config.baseUrl}/api/search/multi") {
            applyAuthHeaders()
            parameter("q", q)
        }.requireSuccess().body()

    /** The provider's genre vocabulary. Ids returned by [browse] are only readable with it. */
    suspend fun genres(type: MediaType): List<MediaGenre> =
        httpClient.get("${config.baseUrl}/api/genres") {
            applyAuthHeaders()
            parameter("type", type.wireName)
        }.requireSuccess().body()

    /**
     * One page of the whole catalog. Unlike [discover] this is impersonal and includes
     * titles already in the library.
     */
    suspend fun browse(
        type: MediaType,
        genreId: Int? = null,
        sort: CatalogSort = CatalogSort.Popularity,
        page: Int = 1,
    ): List<Media> =
        httpClient.get("${config.baseUrl}/api/browse") {
            applyAuthHeaders()
            parameter("type", type.wireName)
            genreId?.let { parameter("genre", it) }
            parameter("sort", sort.wireName)
            parameter("page", page)
        }.requireSuccess().body()

    // ── Personalized discovery ──────────────────────────────────────────────
    //
    // Each of these is backed by a taste profile that costs one metadata request per
    // saved title on a cold cache. They are not first-paint calls.

    suspend fun discoverTopGenres(type: MediaType, limit: Int): List<DiscoveryTasteDto> =
        httpClient.get("${config.baseUrl}/api/discover/genres") {
            applyAuthHeaders()
            parameter("type", type.wireName)
            parameter("limit", limit)
        }.requireSuccess().body()

    suspend fun discoverSimilarTo(type: MediaType, tmdbId: Int, limit: Int): List<Media> =
        httpClient.get("${config.baseUrl}/api/discover/similar-to") {
            applyAuthHeaders()
            parameter("type", type.wireName)
            parameter("tmdb_id", tmdbId)
            parameter("limit", limit)
        }.requireSuccess().body()

    suspend fun discoverFavorites(limit: Int): List<FavoriteSignalDto> =
        httpClient.get("${config.baseUrl}/api/discover/favorites") {
            applyAuthHeaders()
            parameter("limit", limit)
        }.requireSuccess().body()

    // ── Insights ────────────────────────────────────────────────────────────

    /** Pre-aggregated watch-time counters. Cheap on the host — a read of local counters. */
    suspend fun activityStats(range: InsightsRange = InsightsRange.AllTime): ActivityStats =
        httpClient.get("${config.baseUrl}/api/library/activity") {
            applyAuthHeaders()
            parameter("range", range.wireName)
        }.requireSuccess().body()

    /** The whole taste profile in one call. As expensive as the discovery calls above. */
    suspend fun discoverInsights(): DiscoveryInsights =
        httpClient.get("${config.baseUrl}/api/discover/insights") {
            applyAuthHeaders()
        }.requireSuccess().body()

    /**
     * All-time totals from every tracker the host has linked, or empty on 204 — which is
     * what it sends when none is linked or none had anything to say.
     */
    suspend fun trackerStats(): List<TrackerStats> {
        val response = httpClient.get("${config.baseUrl}/api/trackers/stats") {
            applyAuthHeaders()
        }
        if (response.status == HttpStatusCode.NoContent) return emptyList()
        return response.requireSuccess().body()
    }

    // ── Media metadata ──────────────────────────────────────────────────────

    suspend fun media(id: Int, type: MediaType): Media =
        httpClient.get("${config.baseUrl}/api/media") {
            applyAuthHeaders()
            parameter("id", id)
            parameter("type", type.wireName)
        }.requireSuccess().body()

    suspend fun details(id: Int, type: MediaType): MediaDetails =
        httpClient.get("${config.baseUrl}/api/details") {
            applyAuthHeaders()
            parameter("id", id)
            parameter("type", type.wireName)
        }.requireSuccess().body()

    suspend fun images(id: Int, type: MediaType): MediaImages =
        httpClient.get("${config.baseUrl}/api/images") {
            applyAuthHeaders()
            parameter("id", id)
            parameter("type", type.wireName)
        }.requireSuccess().body()

    suspend fun videos(id: Int, type: MediaType): MediaVideos =
        httpClient.get("${config.baseUrl}/api/videos") {
            applyAuthHeaders()
            parameter("id", id)
            parameter("type", type.wireName)
        }.requireSuccess().body()

    suspend fun similar(id: Int, type: MediaType): List<Media> =
        httpClient.get("${config.baseUrl}/api/similar") {
            applyAuthHeaders()
            parameter("id", id)
            parameter("type", type.wireName)
        }.requireSuccess().body()

    // A person carries their own filmography: the host appends combined_credits, so
    // this is the only request behind the person sheet.
    suspend fun person(id: Int): PersonDetails =
        httpClient.get("${config.baseUrl}/api/person") {
            applyAuthHeaders()
            parameter("id", id)
        }.requireSuccess().body()

    // ── TV seasons / episodes ───────────────────────────────────────────────

    suspend fun tvSeasons(id: Int): List<TvSeason> =
        httpClient.get("${config.baseUrl}/api/tv/seasons") {
            applyAuthHeaders()
            parameter("id", id)
        }.requireSuccess().body()

    suspend fun tvEpisodes(id: Int, season: Int): List<TvEpisode> =
        httpClient.get("${config.baseUrl}/api/tv/episodes") {
            applyAuthHeaders()
            parameter("id", id)
            parameter("season", season)
        }.requireSuccess().body()

    // ── Streams ─────────────────────────────────────────────────────────────

    // season and episode are required only when type == Tv.
    suspend fun streams(
        id: Int,
        type: MediaType,
        season: Int? = null,
        episode: Int? = null,
        refresh: Boolean = false,
    ): List<StreamSource> =
        httpClient.get("${config.baseUrl}/api/streams") {
            applyAuthHeaders()
            // Nuvio waits up to 25 seconds so it can return streams from
            // providers that completed before the aggregate deadline. Keep a
            // small transport margin beyond that backend-owned timeout.
            timeout { requestTimeoutMillis = STREAMS_REQUEST_TIMEOUT_MILLIS }
            parameter("id", id)
            parameter("type", type.wireName)
            season?.let { parameter("season", it) }
            episode?.let { parameter("episode", it) }
            // Only ever sent when set, so a backend that predates the parameter sees
            // exactly the request it saw before.
            if (refresh) parameter("refresh", "1")
        }.requireSuccess().body()

    // Pure URL builder — no HTTP. mpv / the player module opens this directly.
    //
    // Every argument given is emitted; nothing is filtered here. season/episode/
    // fileIdx only mean anything to the backend's torrent path (playDirect ignores
    // them), but deciding when they apply is the caller's job — a builder that
    // silently dropped parameters it was handed would be the surprising one.
    // LivePlaybackRepository is where that choice is made.
    fun playUrl(
        hash: String? = null,
        url: String? = null,
        season: Int? = null,
        episode: Int? = null,
        fileIdx: Int? = null,
    ): String {
        require(hash != null || url != null) { "Either hash or url must be provided" }
        val parameters = buildList {
            // hash is hex and url is the only value that can carry reserved
            // characters, so it is the only one that needs encoding.
            hash?.let { add("hash=$it") }
            url?.let { add("url=${it.encodeURLParameter()}") }
            season?.let { add("season=$it") }
            episode?.let { add("episode=$it") }
            fileIdx?.let { add("fileIdx=$it") }
        }
        return "${config.baseUrl}/api/play?${parameters.joinToString("&")}"
    }

    // Intro/recap/credits ranges. season and episode are omitted for films.
    suspend fun timestamps(id: Int, season: Int? = null, episode: Int? = null): MediaTimestamps =
        httpClient.get("${config.baseUrl}/api/timestamps") {
            applyAuthHeaders()
            parameter("id", id)
            season?.let { parameter("season", it) }
            episode?.let { parameter("episode", it) }
        }.requireSuccess().body()

    suspend fun subtitles(
        id: Int,
        type: MediaType,
        season: Int? = null,
        episode: Int? = null,
    ): List<SubtitleSource> =
        httpClient.get("${config.baseUrl}/api/subtitles") {
            applyAuthHeaders()
            parameter("id", id)
            parameter("type", type.wireName)
            season?.let { parameter("season", it) }
            episode?.let { parameter("episode", it) }
        }.requireSuccess().body()

    /**
     * Pure URL builder. The proxy converts SRT to VTT and validates the upstream
     * against the addon URL policy, so subtitles are fetched through it rather
     * than handed to the player directly.
     */
    fun subtitleProxyUrl(url: String): String =
        "${config.baseUrl}/api/subtitle-proxy?url=${url.encodeURLParameter()}"

    /**
     * Checks whether stream URLs still answer. The backend accepts at most ten
     * and caps the per-stream timeout itself.
     */
    suspend fun probeStreams(urls: List<String>, timeoutMs: Int = 2_000): StreamProbeResponse =
        httpClient.post("${config.baseUrl}/api/streams/probe") {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody(StreamProbeBody(urls.map(::StreamProbeRequest), timeoutMs))
        }.requireSuccess().body()

    /** Null when the torrent is not active, which the backend reports as a 404. */
    suspend fun torrentProgress(hash: String): TorrentProgress? {
        val response = httpClient.get("${config.baseUrl}/api/progress") {
            applyAuthHeaders()
            parameter("hash", hash)
        }
        return if (response.status.isSuccess()) response.body() else null
    }

    // ── Addons ──────────────────────────────────────────────────────────────

    suspend fun addons(): List<Addon> =
        httpClient.get("${config.baseUrl}/api/addons") { applyAuthHeaders() }
            .requireSuccess().body()

    suspend fun addAddon(url: String): Addon =
        httpClient.post("${config.baseUrl}/api/addons") {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody(AddAddonRequest(url))
        }.requireSuccess().body()

    suspend fun setAddonEnabled(id: String, enabled: Boolean) {
        httpClient.patch("${config.baseUrl}/api/addons") {
            applyAuthHeaders()
            parameter("id", id)
            contentType(ContentType.Application.Json)
            setBody(ToggleEnabledRequest(enabled))
        }.requireSuccess()
    }

    suspend fun removeAddon(id: String) {
        httpClient.delete("${config.baseUrl}/api/addons") {
            applyAuthHeaders()
            parameter("id", id)
        }.requireSuccess()
    }

    suspend fun refreshAddon(id: String) {
        httpClient.post("${config.baseUrl}/api/addons/refresh") {
            applyAuthHeaders()
            parameter("id", id)
        }.requireSuccess()
    }

    // ── Addon catalogs ──────────────────────────────────────────────────────

    suspend fun addonCatalogs(): List<AddonCatalogDescriptor> =
        httpClient.get("${'$'}{config.baseUrl}/api/catalogs") { applyAuthHeaders() }
            .requireSuccess().body()

    suspend fun addonCatalog(
        addonId: String,
        type: String,
        catalogId: String,
        skip: Int,
        limit: Int,
    ): AddonCatalogPage =
        httpClient.get("${'$'}{config.baseUrl}/api/catalog") {
            applyAuthHeaders()
            parameter("addonId", addonId)
            parameter("type", type)
            parameter("catalogId", catalogId)
            parameter("skip", skip)
            parameter("limit", limit)
        }.requireSuccess().body()

    suspend fun setAddonCatalogEnabled(addonId: String, catalogKey: String, enabled: Boolean) {
        httpClient.patch("${'$'}{config.baseUrl}/api/addons/catalog") {
            applyAuthHeaders()
            parameter("id", addonId)
            parameter("catalog", catalogKey)
            contentType(ContentType.Application.Json)
            setBody(ToggleEnabledRequest(enabled))
        }.requireSuccess()
    }

    // ── Nuvio scraper repositories ──────────────────────────────────────────

    suspend fun nuvioRepos(): List<NuvioRepoSummary> =
        httpClient.get("${config.baseUrl}/api/nuvio/repos") { applyAuthHeaders() }
            .requireSuccess().body()

    suspend fun addNuvioRepo(url: String): NuvioRepoSummary =
        httpClient.post("${config.baseUrl}/api/nuvio/repos") {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody(AddAddonRequest(url))
        }.requireSuccess().body()

    suspend fun setNuvioRepoEnabled(id: String, enabled: Boolean) {
        httpClient.patch("${config.baseUrl}/api/nuvio/repos") {
            applyAuthHeaders()
            parameter("id", id)
            contentType(ContentType.Application.Json)
            setBody(ToggleEnabledRequest(enabled))
        }.requireSuccess()
    }

    suspend fun removeNuvioRepo(id: String) {
        httpClient.delete("${config.baseUrl}/api/nuvio/repos") {
            applyAuthHeaders()
            parameter("id", id)
        }.requireSuccess()
    }

    suspend fun setNuvioScraperEnabled(repoId: String, scraperId: String, enabled: Boolean) {
        httpClient.patch("${config.baseUrl}/api/nuvio/scrapers") {
            applyAuthHeaders()
            parameter("repoId", repoId)
            parameter("scraperId", scraperId)
            contentType(ContentType.Application.Json)
            setBody(ToggleEnabledRequest(enabled))
        }.requireSuccess()
    }

    // ── Library ─────────────────────────────────────────────────────────────

    suspend fun library(status: LibraryStatus? = null): List<LibraryEntry> =
        httpClient.get("${config.baseUrl}/api/library") {
            applyAuthHeaders()
            status?.let { parameter("status", it.wireName) }
        }.requireSuccess().body()

    /** The backend builds this from TMDB, so it is slow by nature — cache the result. */
    suspend fun libraryCalendar(): List<CalendarItem> =
        httpClient.get("${config.baseUrl}/api/library/calendar") {
            applyAuthHeaders()
        }.requireSuccess().body()

    suspend fun addToLibrary(
        tmdbId: Int,
        mediaType: MediaType,
        title: String,
        posterPath: String = "",
        voteAverage: Double = 0.0,
    ): LibraryEntry =
        httpClient.post("${config.baseUrl}/api/library") {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody(
                AddLibraryRequest(
                    tmdbId = tmdbId,
                    mediaType = mediaType,
                    title = title,
                    posterPath = posterPath,
                    voteAverage = voteAverage,
                ),
            )
        }.requireSuccess().body()

    suspend fun libraryDetail(tmdbId: Int, mediaType: MediaType): LibraryDetailDto =
        httpClient.get("${config.baseUrl}/api/library/$tmdbId/${mediaType.wireName}") {
            applyAuthHeaders()
        }.requireSuccess().body()

    suspend fun deleteLibraryEntry(tmdbId: Int, mediaType: MediaType) {
        httpClient.delete("${config.baseUrl}/api/library/$tmdbId/${mediaType.wireName}") {
            applyAuthHeaders()
        }.requireSuccess()
    }

    suspend fun patchLibraryStatus(tmdbId: Int, mediaType: MediaType, status: LibraryStatus): LibraryEntry =
        httpClient.patch("${config.baseUrl}/api/library/$tmdbId/${mediaType.wireName}/status") {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody(PatchStatusRequest(status.wireName))
        }.requireSuccess().body()

    suspend fun patchLibraryRating(
        tmdbId: Int,
        mediaType: MediaType,
        rating: Double?,
    ): LibraryEntry =
        httpClient.patch("${config.baseUrl}/api/library/$tmdbId/${mediaType.wireName}/rating") {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody(PatchRatingRequest(rating))
        }.requireSuccess().body()

    suspend fun setLibraryDismissed(
        tmdbId: Int,
        mediaType: MediaType,
        dismissed: Boolean,
    ) {
        if (dismissed) {
            httpClient.post("${config.baseUrl}/api/library/dismiss") {
                applyAuthHeaders()
                contentType(ContentType.Application.Json)
                setBody(DismissLibraryRequest(tmdbId, mediaType))
            }.requireSuccess()
        } else {
            httpClient.delete("${config.baseUrl}/api/library/dismiss") {
                applyAuthHeaders()
                contentType(ContentType.Application.Json)
                setBody(DismissLibraryRequest(tmdbId, mediaType))
            }.requireSuccess()
        }
    }

    // GET returns null (literal JSON null, status 200) when no progress exists.
    suspend fun libraryProgress(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int? = null,
        episode: Int? = null,
    ): WatchProgress? =
        httpClient.get("${config.baseUrl}/api/library/progress") {
            applyAuthHeaders()
            parameter("tmdb_id", tmdbId)
            parameter("media_type", mediaType.wireName)
            season?.let { parameter("season", it) }
            episode?.let { parameter("episode", it) }
        }.requireSuccess().body()

    suspend fun postLibraryProgress(request: WatchProgressRequest): WatchProgress =
        httpClient.post("${config.baseUrl}/api/library/progress") {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.requireSuccess().body()

    // ── Settings ────────────────────────────────────────────────────────────

    suspend fun settings(): AppSettings =
        httpClient.get("${config.baseUrl}/api/settings") {
            applyAuthHeaders()
        }.requireSuccess().body()

    // PUT is a whole-object replace — any field absent from the body is written
    // as its persisted default value. Callers must supply the complete settings object;
    // LiveSettingsRepository enforces this structurally.
    suspend fun updateSettings(settings: AppSettings): AppSettings =
        httpClient.put("${config.baseUrl}/api/settings") {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody(settings)
        }.requireSuccess().body()

    // ── Auth ────────────────────────────────────────────────────────────────
    // These report the host's own message rather than the bare status line: what
    // comes back is usually Supabase explaining why a sign-in was refused, and
    // that is the text the form has to show.

    suspend fun authMe(): AuthMeResponse =
        httpClient.get("${config.baseUrl}/api/auth/me") {
            applyAuthHeaders()
        }.requireSuccessWithReason().body()

    suspend fun login(email: String, password: String): LoginResponse =
        authPost("login", LoginRequest(email, password))

    suspend fun register(email: String, password: String, profileName: String): RegisterResponse =
        authPost("register", RegisterRequest(email, password, profileName))

    suspend fun confirmRegistration(
        email: String,
        token: String,
        password: String,
        profileName: String,
    ): AuthSessionResponse =
        authPost("register/confirm", ConfirmRegistrationRequest(email, token, password, profileName))

    suspend fun sendOtp(email: String) {
        httpClient.post("${config.baseUrl}/api/auth/otp") {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody(EmailRequest(email))
        }.requireSuccessWithReason()
    }

    suspend fun verifyOtp(email: String, token: String): LoginResponse =
        authPost("verify-otp", VerifyOtpRequest(email, token))

    /** Exchanges the host's stored refresh token for a usable access token. */
    suspend fun refreshSession(): LoginResponse =
        httpClient.post("${config.baseUrl}/api/auth/refresh") {
            applyAuthHeaders()
        }.requireSuccessWithReason().body()

    suspend fun logout() {
        httpClient.post("${config.baseUrl}/api/auth/logout") { applyAuthHeaders() }.requireSuccess()
    }

    /** The access token is the credential here, so it is passed explicitly. */
    suspend fun syncAccount(accessToken: String): SyncResponse =
        httpClient.post("${config.baseUrl}/api/auth/sync") {
            applyAuthHeaders()
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }.requireSuccessWithReason().body()

    private suspend inline fun <reified Request : Any, reified Response> authPost(
        path: String,
        request: Request,
    ): Response = httpClient.post("${config.baseUrl}/api/auth/$path") {
        applyAuthHeaders()
        contentType(ContentType.Application.Json)
        setBody(request)
    }.requireSuccessWithReason().body()

    // ── Profiles ────────────────────────────────────────────────────────────

    suspend fun profiles(): ProfilesResponseDto =
        httpClient.get("${config.baseUrl}/api/profiles") {
            applyAuthHeaders()
        }.requireSuccess().body()

    suspend fun createProfile(name: String): Profile =
        httpClient.post("${config.baseUrl}/api/profiles") {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody(ProfileNameRequest(name))
        }.requireSuccessWithReason().body()

    suspend fun renameProfile(id: String, name: String): Profile =
        httpClient.patch("${config.baseUrl}/api/profiles/$id") {
            applyAuthHeaders()
            contentType(ContentType.Application.Json)
            setBody(ProfileNameRequest(name))
        }.requireSuccessWithReason().body()

    suspend fun activateProfile(id: String): Profile =
        httpClient.post("${config.baseUrl}/api/profiles/$id/activate") {
            applyAuthHeaders()
        }.requireSuccessWithReason().body()

    suspend fun deleteProfile(id: String) {
        httpClient.delete("${config.baseUrl}/api/profiles/$id") {
            applyAuthHeaders()
        }.requireSuccessWithReason()
    }

    // ── Updater ─────────────────────────────────────────────────────────────

    suspend fun checkUpdate(): UpdateCheckDto =
        httpClient.get("${config.baseUrl}/api/update/check") {
            applyAuthHeaders()
        }.requireSuccess().body()

    // Compatibility only. Application updates are never exposed through the
    // HTTP/LAN boundary; the device-local UpdateRepository owns installation.
    suspend fun applyUpdate() {
        httpClient.post("${config.baseUrl}/api/update/apply") {
            applyAuthHeaders()
        }.requireSuccess()
    }
}

private const val STREAMS_REQUEST_TIMEOUT_MILLIS = 30_000L
