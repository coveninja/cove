package com.coveninja.cove.shared.network

import com.coveninja.cove.shared.model.*
import io.ktor.client.*
import io.ktor.client.call.*
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
    ): List<StreamSource> =
        httpClient.get("${config.baseUrl}/api/streams") {
            applyAuthHeaders()
            parameter("id", id)
            parameter("type", type.wireName)
            season?.let { parameter("season", it) }
            episode?.let { parameter("episode", it) }
        }.requireSuccess().body()

    // Pure URL builder — no HTTP. mpv / the player module opens this directly.
    fun playUrl(hash: String? = null, url: String? = null): String {
        require(hash != null || url != null) { "Either hash or url must be provided" }
        return buildString {
            append(config.baseUrl)
            append("/api/play?")
            if (hash != null) {
                append("hash=")
                append(hash)
                if (url != null) append("&")
            }
            if (url != null) {
                append("url=")
                append(url.encodeURLParameter())
            }
        }
    }

    // ── Library ─────────────────────────────────────────────────────────────

    suspend fun library(status: LibraryStatus? = null): List<LibraryEntry> =
        httpClient.get("${config.baseUrl}/api/library") {
            applyAuthHeaders()
            status?.let { parameter("status", it.wireName) }
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

    // ── Profiles ────────────────────────────────────────────────────────────

    suspend fun profiles(): ProfilesResponseDto =
        httpClient.get("${config.baseUrl}/api/profiles") {
            applyAuthHeaders()
        }.requireSuccess().body()

    // ── Updater ─────────────────────────────────────────────────────────────

    suspend fun checkUpdate(): UpdateCheckDto =
        httpClient.get("${config.baseUrl}/api/update/check") {
            applyAuthHeaders()
        }.requireSuccess().body()

    // Triggers download + restart; the process exits 42 so the shell re-execs.
    suspend fun applyUpdate() {
        httpClient.post("${config.baseUrl}/api/update/apply") {
            applyAuthHeaders()
        }.requireSuccess()
    }
}
