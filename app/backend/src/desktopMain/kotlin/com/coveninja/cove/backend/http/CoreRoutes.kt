package com.coveninja.cove.backend.http

import com.coveninja.cove.backend.addons.AddonManager
import com.coveninja.cove.backend.addons.AddonCatalogPage
import com.coveninja.cove.backend.activity.ActivityService
import com.coveninja.cove.backend.auth.AuthService
import com.coveninja.cove.backend.auth.ClientSessionStore
import com.coveninja.cove.backend.auth.RegistrationOutcome
import com.coveninja.cove.backend.auth.SupabaseException
import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.backend.content.TmdbClient
import com.coveninja.cove.backend.calendar.CalendarService
import com.coveninja.cove.backend.platform.DeviceSettingsService
import com.coveninja.cove.backend.discovery.AlgorithmTestRequest
import com.coveninja.cove.backend.discovery.DiscoveryService
import com.coveninja.cove.backend.quality.QualityLookup
import com.coveninja.cove.backend.quality.QualityEntry
import com.coveninja.cove.backend.prefetch.PrefetchService
import com.coveninja.cove.backend.nuvio.NuvioManager
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalProfileRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.backend.store.BulkProgressRequest
import com.coveninja.cove.backend.trakt.TraktException
import com.coveninja.cove.backend.trakt.TraktPollRequest
import com.coveninja.cove.backend.trakt.TraktScrobbleRequest
import com.coveninja.cove.backend.trakt.TraktService
import com.coveninja.cove.backend.updater.UpdateService
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.CatalogSort
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.network.AddLibraryRequest
import com.coveninja.cove.shared.network.DismissLibraryRequest
import com.coveninja.cove.shared.network.PatchRatingRequest
import com.coveninja.cove.shared.network.PatchStatusRequest
import com.coveninja.cove.shared.network.ProfilesResponseDto
import com.coveninja.cove.shared.network.WatchProgressRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest

data class CoreRouteServices(
    val profiles: LocalProfileRepository,
    val settings: LocalSettingsRepository,
    val library: LocalLibraryRepository,
    val catalog: MediaCatalog? = null,
    val addons: AddonManager? = null,
    val nuvio: NuvioManager? = null,
    val media: MediaBoundary? = null,
    val auth: AuthService? = null,
    val clientSessions: ClientSessionStore? = null,
    val activity: ActivityService? = null,
    val calendar: CalendarService? = null,
    val trakt: TraktService? = null,
    val deviceSettings: DeviceSettingsService? = null,
    val discovery: DiscoveryService? = null,
    val quality: QualityLookup? = null,
    val updater: UpdateService? = null,
    val prefetch: PrefetchService? = null,
)

enum class ApiAccess {
    Loopback,
    Remote,
}

fun Application.configureCoreRoutes(
    services: CoreRouteServices,
    access: ApiAccess = ApiAccess.Loopback,
) {
    if (access == ApiAccess.Remote) {
        intercept(ApplicationCallPipeline.Plugins) {
            val current = services.settings.current()
            val supplied = call.request.headers["X-Cove-Token"]
                ?: call.request.queryParameters["token"]
                ?: ""
            if (!current.remoteAccessEnabled ||
                current.remoteAccessToken.isBlank() ||
                !constantTimeEquals(current.remoteAccessToken, supplied)
            ) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("unauthorized: missing or invalid X-Cove-Token"),
                )
                finish()
            }
        }
    }
    install(ContentNegotiation) { json(com.coveninja.cove.shared.network.CoveJson) }
    install(CORS) {
        allowOrigins { origin ->
            runCatching { URI(origin).host }.getOrNull() in LOOPBACK_ORIGINS
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Cove-Token")
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "invalid request"))
        }
        exception<IllegalStateException> { call, error ->
            val status = if (error.message?.contains("not found", ignoreCase = true) == true ||
                error.message?.startsWith("unknown ") == true
            ) {
                HttpStatusCode.NotFound
            } else {
                HttpStatusCode.Conflict
            }
            call.respond(status, ErrorResponse(error.message ?: "request could not be completed"))
        }
        exception<SupabaseException> { call, error ->
            val status = if (error.statusCode in 400..499) {
                HttpStatusCode.fromValue(error.statusCode)
            } else {
                HttpStatusCode.BadGateway
            }
            call.respond(status, ErrorResponse(error.message ?: "Supabase request failed"))
        }
        exception<TraktException> { call, error ->
            call.respond(HttpStatusCode.BadGateway, ErrorResponse(error.message ?: "Trakt request failed"))
        }
        exception<RequestTooLargeException> { call, error ->
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse(error.message ?: "request body too large"))
        }
    }
    install(SSE)

    routing {
        route("/api") { coreRoutes(services, legacy = true) }
        route("/api/v1") { coreRoutes(services, legacy = false) }
    }
}

private val LOOPBACK_ORIGINS = setOf("localhost", "127.0.0.1", "::1")

private fun constantTimeEquals(expected: String, supplied: String): Boolean =
    MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))

private fun Route.coreRoutes(services: CoreRouteServices, legacy: Boolean) {
    get("/ping") {
        call.markLegacy(legacy)
        call.respond(mapOf("status" to "ok", "backend" to "kotlin"))
    }

    services.clientSessions?.let { sessions ->
        get("/client-session") {
            call.markLegacy(legacy)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val json = sessions.get()
            if (json == null) call.respond(HttpStatusCode.NotFound)
            else call.respondText(json, ContentType.Application.Json)
        }
        post("/client-session") {
            call.markLegacy(legacy)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            sessions.save(call.receiveTextLimited())
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/client-session") {
            call.markLegacy(legacy)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            sessions.clear()
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/auth") {
        val auth = services.auth
        post("/register") {
            call.markLegacy(legacy)
            if (auth == null) return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("Supabase not configured"),
            )
            val request = call.receiveJson<RegisterRequest>()
            when (val result = auth.register(request.email, request.password, request.profileName)) {
                RegistrationOutcome.ConfirmationRequired -> call.respond(
                    mapOf("confirmation_required" to true),
                )
                is RegistrationOutcome.Complete -> call.respond(result.session)
            }
        }
        post("/register/confirm") {
            call.markLegacy(legacy)
            if (auth == null) return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("Supabase not configured"),
            )
            val request = call.receiveJson<ConfirmRegistrationRequest>()
            call.respond(auth.confirmRegistration(
                request.email,
                request.token,
                request.password,
                request.profileName,
            ))
        }
        post("/login") {
            call.markLegacy(legacy)
            if (auth == null) return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("Supabase not configured"),
            )
            val request = call.receiveJson<LoginRequest>()
            call.respond(auth.login(request.email, request.password))
        }
        post("/otp") {
            call.markLegacy(legacy)
            if (auth == null) return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("Supabase not configured"),
            )
            auth.sendOtp(call.receiveJson<EmailRequest>().email)
            call.respond(mapOf("status" to "ok"))
        }
        post("/verify-otp") {
            call.markLegacy(legacy)
            if (auth == null) return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("Supabase not configured"),
            )
            val request = call.receiveJson<VerifyOtpRequest>()
            call.respond(auth.verifyOtp(request.email, request.token))
        }
        post("/refresh") {
            call.markLegacy(legacy)
            if (auth == null) return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("Supabase not configured"),
            )
            call.respond(auth.refresh())
        }
        post("/logout") {
            call.markLegacy(legacy)
            if (auth != null) auth.logout()
            call.respond(mapOf("status" to "ok"))
        }
        get("/me") {
            call.markLegacy(legacy)
            if (auth == null) return@get call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("Supabase not configured"),
            )
            call.respond(auth.me())
        }
        post("/sync") {
            call.markLegacy(legacy)
            if (auth == null) return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("Supabase not configured"),
            )
            val bearer = call.request.headers[HttpHeaders.Authorization]
                ?.takeIf { it.startsWith("Bearer ") }
                ?.removePrefix("Bearer ")
                .orEmpty()
            val result = auth.synchronize(bearer)
            call.respond(SyncResponse("ok", result.libraryGeneration, result.pushError))
        }
    }

    route("/trakt") {
        val trakt = services.trakt
        suspend fun ApplicationCall.requireTrakt(): TraktService? {
            if (trakt == null || !trakt.isConfigured) {
                respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Trakt integration not configured (credentials missing)"),
                )
                return null
            }
            return trakt
        }
        post("/device-code") {
            call.markLegacy(legacy)
            call.requireTrakt()?.let { call.respond(it.startDeviceFlow()) }
        }
        post("/poll") {
            call.markLegacy(legacy)
            val service = call.requireTrakt() ?: return@post
            call.respond(service.poll(call.receiveJson<TraktPollRequest>().deviceCode))
        }
        get("/status") {
            call.markLegacy(legacy)
            call.requireTrakt()?.let { call.respond(it.status()) }
        }
        post("/unlink") {
            call.markLegacy(legacy)
            val service = call.requireTrakt() ?: return@post
            service.unlink()
            call.respond(HttpStatusCode.NoContent)
        }
        post("/scrobble") {
            call.markLegacy(legacy)
            val service = call.requireTrakt() ?: return@post
            val accepted = service.enqueueScrobble(call.receiveJson<TraktScrobbleRequest>())
            call.respond(if (accepted) HttpStatusCode.Accepted else HttpStatusCode.NoContent)
        }
        post("/sync") {
            call.markLegacy(legacy)
            val service = call.requireTrakt() ?: return@post
            service.enqueueSync()
            call.respond(HttpStatusCode.Accepted)
        }
    }

    services.catalog?.let { catalog ->
        get("/discover") {
            call.markLegacy(legacy)
            val typeName = call.request.queryParameters["type"] ?: "movie"
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val discovery = services.discovery
            if (discovery == null) {
                call.respond(catalog.discover(typeName.toMediaType(default = MediaType.Movie), limit))
            } else {
                call.respond(discovery.recommend(
                    typeName,
                    limit,
                    call.request.queryParameters["profile"] == "kid",
                ))
            }
        }
        // Browsing, as distinct from /discover: this reaches the whole catalog, including
        // titles already in the library, and never runs candidates through a taste profile.
        // /discover/genre is not a substitute — it excludes everything already saved, which
        // is right for a recommendation and wrong for "show me every sci-fi film".
        // Alongside /browse rather than inside the TmdbClient block: the genre vocabulary is
        // part of MediaCatalog now, and a genre id in a /browse query is unreadable without it.
        get("/genres") {
            call.markLegacy(legacy)
            call.respond(catalog.genres(call.request.queryParameters["type"].toMediaType()))
        }
        get("/browse") {
            call.markLegacy(legacy)
            val parameters = call.request.queryParameters
            call.respond(
                catalog.discoverFiltered(
                    type = parameters["type"].toMediaType(default = MediaType.Movie),
                    genreId = parameters["genre"]?.toIntOrNull()?.takeIf { it > 0 },
                    sort = CatalogSort.fromWire(parameters["sort"]),
                    page = parameters["page"]?.toIntOrNull() ?: 1,
                ),
            )
        }
        get("/search/multi") {
            call.markLegacy(legacy)
            val query = call.request.queryParameters["q"]
                ?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("search query is required")
            call.respond(catalog.searchMulti(query))
        }
        get("/media") {
            call.markLegacy(legacy)
            val (id, type) = call.queryMediaKey()
            call.respond(catalog.media(id, type))
        }
        get("/details") {
            call.markLegacy(legacy)
            val (id, type) = call.queryMediaKey()
            call.respond(catalog.details(id, type))
        }
        get("/images") {
            call.markLegacy(legacy)
            val (id, type) = call.queryMediaKey()
            call.respond(catalog.images(id, type))
        }
        get("/videos") {
            call.markLegacy(legacy)
            val (id, type) = call.queryMediaKey()
            call.respond(catalog.videos(id, type))
        }
        get("/similar") {
            call.markLegacy(legacy)
            val (id, type) = call.queryMediaKey()
            call.respond(catalog.similar(id, type))
        }
        get("/tv/episodes") {
            call.markLegacy(legacy)
            val id = call.request.queryParameters["id"]?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("invalid media id")
            val season = call.request.queryParameters["season"]?.toIntOrNull()
                ?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("invalid season")
            call.respond(catalog.episodes(id, season))
        }
        get("/tv/seasons") {
            call.markLegacy(legacy)
            val id = call.request.queryParameters["id"]?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("invalid media id")
            call.respond(catalog.seasons(id))
        }
        if (catalog is TmdbClient) {
            get("/keywords") {
                call.markLegacy(legacy)
                val query = call.request.queryParameters["q"]
                    ?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("keyword query is required")
                call.respond(catalog.keywordSuggestions(query))
            }
            get("/search") {
                call.markLegacy(legacy)
                val query = call.request.queryParameters["q"]
                    ?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("search query is required")
                call.respond(catalog.searchTitles(query))
            }
            get("/person") {
                call.markLegacy(legacy)
                val id = call.request.queryParameters["id"]?.toIntOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("invalid person id")
                call.respond(catalog.person(id))
            }
            get("/provider") {
                call.markLegacy(legacy)
                val id = call.request.queryParameters["id"]?.toIntOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("invalid provider id")
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 40).coerceIn(1, 100)
                call.respond(catalog.providerTitles(id, limit))
            }
            get("/logos") {
                call.markLegacy(legacy)
                val (id, type) = call.queryMediaKey()
                call.respond(catalog.images(id, type).logos)
            }
            get("/imdb") {
                call.markLegacy(legacy)
                val id = call.request.queryParameters["id"]?.toIntOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("invalid media id")
                val type = call.request.queryParameters["type"].toMediaType(default = MediaType.Tv)
                call.respond(mapOf("imdb_id" to catalog.imdbId(id, type)))
            }
        }

        services.addons?.let { addons ->
            get("/streams") {
                call.markLegacy(legacy)
                val (id, type) = call.queryMediaKey()
                val stremioId = buildString {
                    append(catalog.imdbId(id, type))
                    if (type == MediaType.Tv) {
                        val season = call.request.queryParameters["season"]?.toIntOrNull()
                            ?.takeIf { it > 0 }
                            ?: throw IllegalArgumentException("season is required for tv streams")
                        val episode = call.request.queryParameters["episode"]?.toIntOrNull()
                            ?.takeIf { it > 0 }
                            ?: throw IllegalArgumentException("episode is required for tv streams")
                        append(":$season:$episode")
                    }
                }
                val streams = coroutineScope {
                    val addonStreams = async { addons.streams(type, stremioId) }
                    val nuvioStreams = services.nuvio?.let { nuvio ->
                        async {
                            val metadata = catalog.media(id, type)
                            nuvio.streams(
                                type,
                                id,
                                stremioId.substringBefore(':'),
                                metadata.displayTitle,
                                metadata.displayDate?.take(4)?.toIntOrNull() ?: 0,
                                call.request.queryParameters["season"]?.toIntOrNull(),
                                call.request.queryParameters["episode"]?.toIntOrNull(),
                            )
                        }
                    }
                    addonStreams.await() + nuvioStreams?.await().orEmpty()
                }
                call.respond(services.media?.registerStreams(streams) ?: streams)
            }
            get("/subtitles") {
                call.markLegacy(legacy)
                val (id, type) = call.queryMediaKey()
                val imdbId = catalog.imdbId(id, type)
                val seasonRaw = call.request.queryParameters["season"]
                val episodeRaw = call.request.queryParameters["episode"]
                val stremioId = if (type == MediaType.Tv && (seasonRaw != null || episodeRaw != null)) {
                    val season = seasonRaw?.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("invalid season")
                    val episode = episodeRaw?.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("invalid episode")
                    "$imdbId:$season:$episode"
                } else {
                    imdbId
                }
                call.respond(addons.subtitles(type, stremioId))
            }
        }
    }

    services.discovery?.let { discovery ->
        get("/discover/genres") {
            call.markLegacy(legacy)
            call.respond(discovery.topGenres(
                call.request.queryParameters["type"].toMediaType(),
                (call.request.queryParameters["limit"]?.toIntOrNull() ?: 8).coerceIn(1, 50),
            ))
        }
        get("/discover/keywords") {
            call.markLegacy(legacy)
            call.respond(discovery.topKeywords(
                (call.request.queryParameters["limit"]?.toIntOrNull() ?: 12).coerceIn(1, 50),
            ))
        }
        get("/discover/people") {
            call.markLegacy(legacy)
            call.respond(discovery.topPeople(
                (call.request.queryParameters["limit"]?.toIntOrNull() ?: 8).coerceIn(1, 50),
            ))
        }
        get("/discover/genre") {
            call.markLegacy(legacy)
            val genre = call.request.queryParameters["genre"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("invalid genre")
            call.respond(discovery.byGenre(
                call.request.queryParameters["type"].toMediaType(),
                genre,
                call.request.queryParameters["limit"]?.toIntOrNull() ?: 20,
                call.request.queryParameters["profile"] == "kid",
            ))
        }
        get("/discover/keyword") {
            call.markLegacy(legacy)
            val keyword = call.request.queryParameters["keyword"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("invalid keyword")
            call.respond(discovery.byKeyword(
                call.request.queryParameters["type"].toMediaType(),
                keyword,
                call.request.queryParameters["limit"]?.toIntOrNull() ?: 20,
                call.request.queryParameters["profile"] == "kid",
            ))
        }
        get("/discover/person") {
            call.markLegacy(legacy)
            val person = call.request.queryParameters["person"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("invalid person")
            call.respond(discovery.byPerson(
                call.request.queryParameters["type"].toMediaType(),
                person,
                call.request.queryParameters["limit"]?.toIntOrNull() ?: 20,
                call.request.queryParameters["profile"] == "kid",
            ))
        }
        get("/discover/similar-to") {
            call.markLegacy(legacy)
            val id = call.request.queryParameters["tmdb_id"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("invalid tmdb_id")
            call.respond(discovery.similarTo(
                call.request.queryParameters["type"].toMediaType(),
                id,
                call.request.queryParameters["limit"]?.toIntOrNull() ?: 20,
                call.request.queryParameters["profile"] == "kid",
            ))
        }
        get("/discover/favorites") {
            call.markLegacy(legacy)
            call.respond(discovery.favorites(call.request.queryParameters["limit"]?.toIntOrNull() ?: 5))
        }
        get("/discover/insights") {
            call.markLegacy(legacy)
            call.respond(discovery.insights())
        }
        post("/discover/algorithm/test") {
            call.markLegacy(legacy)
            call.respond(discovery.testCustomAlgorithm(call.receiveJson<AlgorithmTestRequest>().url))
        }
    }

    services.quality?.let { quality ->
        get("/quality/batch") {
            call.markLegacy(legacy)
            val ids = call.request.queryParameters["ids"]
                ?: throw IllegalArgumentException("missing ids")
            call.response.header("X-Accel-Buffering", "no")
            call.respondTextWriter(ContentType.parse("application/x-ndjson")) {
                quality.lookup(ids).collect { entry ->
                    write(com.coveninja.cove.shared.network.CoveJson.encodeToString(
                        QualityEntry.serializer(),
                        entry,
                    ))
                    write("\n")
                    flush()
                }
            }
        }
    }

    services.media?.let { media ->
        post("/streams/probe") {
            call.markLegacy(legacy)
            call.respond(media.probe(call.receiveJson<ProbeStreamsRequest>()))
        }
        get("/play") {
            call.markLegacy(legacy)
            val url = call.request.queryParameters["url"]
            val hash = call.request.queryParameters["hash"]
            when {
                url != null -> media.playDirect(call, url)
                hash != null -> media.playTorrent(
                    call,
                    hash,
                    call.optionalNonNegativeQueryInt("season"),
                    call.optionalPositiveQueryInt("episode"),
                    call.optionalNonNegativeQueryInt("fileIdx"),
                )
                else -> throw IllegalArgumentException("missing hash or url")
            }
        }
        get("/torrent/{hash}") {
            call.markLegacy(legacy)
            media.playTorrent(
                call,
                requireNotNull(call.parameters["hash"]),
                call.optionalNonNegativeQueryInt("season"),
                call.optionalPositiveQueryInt("episode"),
                call.optionalNonNegativeQueryInt("fileIdx"),
            )
        }
        get("/progress") {
            call.markLegacy(legacy)
            val hash = call.request.queryParameters["hash"]
                ?: throw IllegalArgumentException("missing hash")
            val progress = media.torrentProgress(hash)
            if (progress == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("torrent is not active"))
            else call.respond(progress)
        }
        post("/prefetch-download") {
            call.markLegacy(legacy)
            val hash = call.request.queryParameters["hash"]
                ?: throw IllegalArgumentException("missing hash")
            val started = media.prefetchTorrent(
                hash,
                call.optionalNonNegativeQueryInt("season"),
                call.optionalPositiveQueryInt("episode"),
                call.optionalNonNegativeQueryInt("fileIdx"),
            )
            call.respond(HttpStatusCode.Accepted, mapOf("started" to started))
        }
        sse("/progress/stream") {
            call.markLegacy(legacy)
            val hash = call.request.queryParameters["hash"]
                ?: throw IllegalArgumentException("missing hash")
            while (true) {
                delay(2_000)
                val progress = media.torrentProgress(hash)
                send(ServerSentEvent(
                    data = progress?.let {
                        com.coveninja.cove.shared.network.CoveJson.encodeToString(
                            com.coveninja.cove.backend.torrent.TorrentProgress.serializer(),
                            it,
                        )
                    } ?: "null",
                ))
            }
        }
        get("/speedtest") {
            call.markLegacy(legacy)
            media.speedTest(call)
        }
        get("/subtitle-proxy") {
            call.markLegacy(legacy)
            val url = call.request.queryParameters["url"]
                ?: throw IllegalArgumentException("missing url")
            media.subtitle(call, url)
        }
        get("/img/{size}/{file}") {
            call.markLegacy(legacy)
            media.image(
                call,
                requireNotNull(call.parameters["size"]),
                requireNotNull(call.parameters["file"]),
            )
        }
    }

    services.addons?.let { addons ->
        get("/addons") {
            call.markLegacy(legacy)
            call.respond(addons.entries())
        }
        post("/addons") {
            call.markLegacy(legacy)
            call.respond(addons.add(call.receiveJson<AddAddonRequest>().url))
        }
        patch("/addons") {
            call.markLegacy(legacy)
            val request = call.receiveJson<ToggleAddonRequest>()
            addons.setEnabled(
                call.request.queryParameters["id"],
                call.request.queryParameters["url"],
                request.enabled,
            )
            call.respondText("", status = HttpStatusCode.NoContent)
        }
        delete("/addons") {
            call.markLegacy(legacy)
            addons.remove(call.request.queryParameters["id"], call.request.queryParameters["url"])
            call.respondText("", status = HttpStatusCode.NoContent)
        }
        post("/addons/refresh") {
            call.markLegacy(legacy)
            call.respond(
                addons.refresh(call.request.queryParameters["id"], call.request.queryParameters["url"]),
            )
        }
        get("/catalogs") {
            call.markLegacy(legacy)
            call.respond(addons.enabledCatalogs())
        }
        patch("/addons/catalog") {
            call.markLegacy(legacy)
            val request = call.receiveJson<ToggleAddonRequest>()
            val catalog = call.request.queryParameters["catalog"]
                ?: throw IllegalArgumentException("catalog is required")
            addons.setCatalogEnabled(
                call.request.queryParameters["id"],
                call.request.queryParameters["url"],
                catalog,
                request.enabled,
            )
            call.respond(HttpStatusCode.NoContent)
        }
        get("/catalog") {
            call.markLegacy(legacy)
            val type = call.request.queryParameters["type"]
                ?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("catalog type is required")
            val catalogId = call.request.queryParameters["catalogId"]
                ?: call.request.queryParameters["catalog"]
                ?: throw IllegalArgumentException("catalog id is required")
            val skip = call.request.queryParameters["skip"]?.toIntOrNull()?.takeIf { it >= 0 } ?: 0
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull()
                ?.takeIf { it > 0 } ?: 20).coerceAtMost(100)
            val raw = addons.catalog(
                call.request.queryParameters["addonId"] ?: call.request.queryParameters["id"],
                call.request.queryParameters["addonUrl"] ?: call.request.queryParameters["url"],
                type,
                catalogId,
                skip,
            )
            val consumed = minOf(raw.size, limit)
            val medias = (services.catalog as? TmdbClient)?.let { tmdb ->
                val semaphore = kotlinx.coroutines.sync.Semaphore(6)
                coroutineScope {
                    raw.take(limit).map { meta ->
                        async { semaphore.withPermit { tmdb.resolveAddonMeta(meta) } }
                    }.map { it.await() }.filterNotNull()
                }
            }.orEmpty()
            call.respond(AddonCatalogPage(medias, skip + consumed))
        }
        get("/timestamps") {
            call.markLegacy(legacy)
            val id = call.request.queryParameters["id"]?.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("invalid media id")
            call.respond(addons.timestamps(
                id,
                call.optionalNonNegativeQueryInt("season"),
                call.optionalNonNegativeQueryInt("episode"),
            ))
        }
        get("/watch-options") {
            call.markLegacy(legacy)
            val (id, type) = call.queryMediaKey()
            call.respond(addons.watchOptions(type, id))
        }
    }

    services.nuvio?.let { nuvio ->
        get("/nuvio/repos") {
            call.markLegacy(legacy)
            call.respond(nuvio.repos())
        }
        post("/nuvio/repos") {
            call.markLegacy(legacy)
            call.respond(nuvio.add(call.receiveJson<AddNuvioRepoRequest>().url))
        }
        patch("/nuvio/repos") {
            call.markLegacy(legacy)
            val id = call.request.queryParameters["id"]
                ?: throw IllegalArgumentException("repo id is required")
            nuvio.setRepoEnabled(id, call.receiveJson<ToggleAddonRequest>().enabled)
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/nuvio/repos") {
            call.markLegacy(legacy)
            val id = call.request.queryParameters["id"]
                ?: throw IllegalArgumentException("repo id is required")
            nuvio.remove(id)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/nuvio/repos/refresh") {
            call.markLegacy(legacy)
            val id = call.request.queryParameters["id"]
                ?: throw IllegalArgumentException("repo id is required")
            nuvio.refresh(id)
            call.respond(HttpStatusCode.NoContent)
        }
        patch("/nuvio/scrapers") {
            call.markLegacy(legacy)
            val repoId = call.request.queryParameters["repoId"]
                ?: throw IllegalArgumentException("repoId is required")
            val scraperId = call.request.queryParameters["scraperId"]
                ?: throw IllegalArgumentException("scraperId is required")
            nuvio.setScraperEnabled(repoId, scraperId, call.receiveJson<ToggleAddonRequest>().enabled)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    get("/settings") {
        call.markLegacy(legacy)
        when (val state = services.settings.settings.value) {
            SettingsState.Loading -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("settings are loading"))
            is SettingsState.Failed -> call.respond(HttpStatusCode.InternalServerError, ErrorResponse(state.message))
            is SettingsState.Ready -> call.respond(services.settings.masked())
        }
    }
    put("/settings") {
        call.markLegacy(legacy)
        services.settings.update(call.receiveJson<AppSettings>())
        call.respond(services.settings.masked())
    }
    post("/settings/reveal-token") {
        call.markLegacy(legacy)
        call.respond(mapOf("token" to services.settings.current().remoteAccessToken))
    }
    services.deviceSettings?.let { device ->
        get("/settings/mpv-conf") {
            call.markLegacy(legacy)
            call.respondText(
                com.coveninja.cove.shared.network.CoveJson.encodeToString(
                    String.serializer(),
                    device.readMpvConfig(),
                ),
                ContentType.Application.Json,
            )
        }
        put("/settings/mpv-conf") {
            call.markLegacy(legacy)
            device.writeMpvConfig(call.receiveJson<String>())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    services.updater?.let { updater ->
        get("/update/check") {
            call.markLegacy(legacy)
            call.respond(updater.check())
        }
        post("/update/apply") {
            call.markLegacy(legacy)
            updater.apply()
        }
    }

    get("/profiles") {
        call.markLegacy(legacy)
        call.respond(services.profiles.response())
    }
    post("/profiles") {
        call.markLegacy(legacy)
        call.respond(HttpStatusCode.Created, services.profiles.create(call.receiveJson<CreateProfileRequest>().name))
    }
    post("/profiles/{id}/activate") {
        call.markLegacy(legacy)
        services.profiles.activate(requireNotNull(call.parameters["id"]))
        val response = services.profiles.response()
        call.respond(response.profiles.first { it.id == response.activeProfileId })
    }
    patch("/profiles/{id}") {
        call.markLegacy(legacy)
        val id = requireNotNull(call.parameters["id"])
        services.profiles.rename(id, call.receiveJson<RenameProfileRequest>().name)
        call.respond(services.profiles.response().profiles.first { it.id == id })
    }
    delete("/profiles/{id}") {
        call.markLegacy(legacy)
        services.profiles.delete(requireNotNull(call.parameters["id"]))
        call.respondText("", status = HttpStatusCode.NoContent)
    }

    get("/library") {
        call.markLegacy(legacy)
        when (val state = services.library.entries.value) {
            LibraryState.Loading -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("library is loading"))
            is LibraryState.Failed -> call.respond(HttpStatusCode.InternalServerError, ErrorResponse(state.message))
            is LibraryState.Ready -> {
                val status = call.request.queryParameters["status"]
                call.respond(if (status == null) state.entries else state.entries.filter { it.status.wireName == status })
            }
        }
    }
    get("/library/progress") {
        call.markLegacy(legacy)
        val id = call.request.queryParameters["tmdb_id"]?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("invalid tmdb id")
        val type = call.request.queryParameters["media_type"].toMediaType()
        val season = call.optionalNonNegativeQueryInt("season")
        val episode = call.optionalPositiveQueryInt("episode")
        val progress = services.library.progress(id, type, season, episode)
        if (progress == null) {
            call.respondText("null", ContentType.Application.Json)
        } else {
            call.respond(progress)
        }
    }
    post("/library/progress/bulk") {
        call.markLegacy(legacy)
        val result = services.library.recordProgressBulk(call.receiveJson<BulkProgressRequest>(2 shl 20))
        services.prefetch?.notifyProgressChanged()
        call.respond(result)
    }
    post("/library/progress") {
        call.markLegacy(legacy)
        val progress = services.library.recordProgress(call.receiveJson<WatchProgressRequest>())
        services.activity?.record(progress)
        services.prefetch?.notifyProgressChanged()
        call.respond(progress)
    }
    services.activity?.let { activity ->
        get("/library/activity") {
            call.markLegacy(legacy)
            call.respond(activity.stats())
        }
    }
    services.calendar?.let { calendar ->
        get("/library/calendar") {
            call.markLegacy(legacy)
            call.respond(calendar.calendar())
        }
    }
    get("/library/stats") {
        call.markLegacy(legacy)
        call.respond(services.library.stats())
    }
    post("/library") {
        call.markLegacy(legacy)
        val request = call.receiveJson<AddLibraryRequest>()
        services.library.add(request.tmdbId, request.mediaType, request.title, request.posterPath, request.voteAverage)
        if (request.status != LibraryStatus.WatchLater) {
            services.library.setStatus(request.tmdbId, request.mediaType, request.status)
        }
        call.respond(HttpStatusCode.Created, services.library.requireEntry(request.tmdbId, request.mediaType))
    }
    delete("/library/dismiss") {
        call.markLegacy(legacy)
        val request = call.receiveJson<DismissLibraryRequest>()
        services.library.setDismissed(request.tmdbId, request.mediaType, false)
        call.respondText("", status = HttpStatusCode.NoContent)
    }
    post("/library/dismiss") {
        call.markLegacy(legacy)
        val request = call.receiveJson<DismissLibraryRequest>()
        services.library.setDismissed(request.tmdbId, request.mediaType, true)
        call.respondText("", status = HttpStatusCode.NoContent)
    }
    get("/library/{id}/{type}") {
        call.markLegacy(legacy)
        val (id, type) = call.mediaKey()
        call.respond(services.library.detail(id, type))
    }
    delete("/library/{id}/{type}") {
        call.markLegacy(legacy)
        val (id, type) = call.mediaKey()
        services.library.remove(id, type)
        call.respondText("", status = HttpStatusCode.NoContent)
    }
    patch("/library/{id}/{type}/status") {
        call.markLegacy(legacy)
        val (id, type) = call.mediaKey()
        val statusName = call.receiveJson<PatchStatusRequest>().status
        val status = LibraryStatus.entries.firstOrNull { it.wireName == statusName }
            ?: throw IllegalArgumentException("invalid library status $statusName")
        services.library.setStatus(id, type, status)
        call.respond(services.library.requireEntry(id, type))
    }
    patch("/library/{id}/{type}/rating") {
        call.markLegacy(legacy)
        val (id, type) = call.mediaKey()
        services.library.setRating(id, type, call.receiveJson<PatchRatingRequest>().rating)
        call.respond(services.library.requireEntry(id, type))
    }
}

private fun ApplicationCall.markLegacy(legacy: Boolean) {
    if (!legacy) return
    response.header("Deprecation", "true")
    response.header("Sunset", "v0.34.0")
    response.header("Link", "</api/v1>; rel=successor-version")
}

private class RequestTooLargeException(message: String) : RuntimeException(message)

private suspend fun ApplicationCall.receiveTextLimited(maxBytes: Int = 1 shl 20): String {
    request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { declared ->
        if (declared > maxBytes) throw RequestTooLargeException("request body exceeds $maxBytes bytes")
    }
    val input = receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (!input.isClosedForRead) {
        val count = input.readAvailable(buffer)
        if (count == -1) break
        if (count == 0) continue
        if (output.size() + count > maxBytes) {
            throw RequestTooLargeException("request body exceeds $maxBytes bytes")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray().decodeToString()
}

private suspend inline fun <reified T> ApplicationCall.receiveJson(maxBytes: Int = 1 shl 20): T {
    val text = receiveTextLimited(maxBytes)
    return runCatching { com.coveninja.cove.shared.network.CoveJson.decodeFromString<T>(text) }
        .getOrElse { throw IllegalArgumentException("invalid JSON body: ${it.message}") }
}

private fun LocalProfileRepository.response(): ProfilesResponseDto =
    when (val state = profiles.value) {
        ProfilesState.Loading -> error("profiles are loading")
        is ProfilesState.Failed -> error(state.message)
        is ProfilesState.Ready -> ProfilesResponseDto(state.profiles, state.activeProfileId)
    }

private fun LocalLibraryRepository.requireEntry(tmdbId: Int, mediaType: MediaType) =
    (entries.value as? LibraryState.Ready)?.entries
        ?.firstOrNull { it.tmdbId == tmdbId && it.mediaType == mediaType }
        ?: error("library entry does not exist")

private fun ApplicationCall.mediaKey(): Pair<Int, MediaType> {
    val id = parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("invalid media id")
    val typeName = parameters["type"]
    val type = MediaType.entries.firstOrNull { it.wireName == typeName }
        ?: throw IllegalArgumentException("invalid media type")
    return id to type
}

private fun ApplicationCall.queryMediaKey(): Pair<Int, MediaType> {
    val id = request.queryParameters["id"]?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("invalid media id")
    return id to request.queryParameters["type"].toMediaType()
}

private fun ApplicationCall.optionalNonNegativeQueryInt(name: String): Int? {
    val raw = request.queryParameters[name] ?: return null
    return raw.toIntOrNull()?.takeIf { it >= 0 }
        ?: throw IllegalArgumentException("invalid $name")
}

private fun ApplicationCall.optionalPositiveQueryInt(name: String): Int? {
    val raw = request.queryParameters[name] ?: return null
    return raw.toIntOrNull()?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("invalid $name")
}

private fun String?.toMediaType(default: MediaType? = null): MediaType =
    MediaType.entries.firstOrNull { it.wireName == this }
        ?: default
        ?: throw IllegalArgumentException("invalid media type")

@Serializable
private data class ErrorResponse(val error: String)

@Serializable
private data class CreateProfileRequest(val name: String)

@Serializable
private data class RenameProfileRequest(val name: String)

@Serializable
private data class AddAddonRequest(val url: String)

@Serializable
private data class ToggleAddonRequest(val enabled: Boolean)

@Serializable
private data class RegisterRequest(
    val email: String = "",
    val password: String = "",
    @kotlinx.serialization.SerialName("profile_name") val profileName: String = "",
)

@Serializable
private data class ConfirmRegistrationRequest(
    val email: String = "",
    val token: String = "",
    val password: String = "",
    @kotlinx.serialization.SerialName("profile_name") val profileName: String = "",
)

@Serializable
private data class LoginRequest(val email: String = "", val password: String = "")

@Serializable
private data class EmailRequest(val email: String = "")

@Serializable
private data class VerifyOtpRequest(val email: String = "", val token: String = "")

@Serializable
private data class AddNuvioRepoRequest(val url: String)

@Serializable
private data class SyncResponse(
    val status: String,
    @kotlinx.serialization.SerialName("library_generation") val libraryGeneration: Long,
    @kotlinx.serialization.SerialName("push_error") val pushError: String,
)
