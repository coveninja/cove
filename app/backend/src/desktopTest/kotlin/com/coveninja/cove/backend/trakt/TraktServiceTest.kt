package com.coveninja.cove.backend.trakt

import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.CatalogSort
import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaDetails
import com.coveninja.cove.shared.model.MediaGenre
import com.coveninja.cove.shared.model.MediaImages
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.MediaVideos
import com.coveninja.cove.shared.model.PersonDetails
import com.coveninja.cove.shared.model.TvEpisode
import com.coveninja.cove.shared.model.TvSeason
import com.coveninja.cove.shared.network.CoveJson
import com.coveninja.cove.shared.network.SearchResultsDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TraktServiceTest {
    @Test
    fun deviceFlowPersistsProfileTokenAndScrobbleUsesRequiredHeaders() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = mockClient { path, headers, body ->
            requests += RecordedRequest(path, headers["trakt-api-key"].orEmpty(), headers[HttpHeaders.Authorization].orEmpty(), body)
            when (path) {
                "/oauth/device/code" -> json(
                    """{"device_code":"device","user_code":"ABCD","verification_url":"https://trakt.test/activate","expires_in":600,"interval":5}""",
                )
                "/oauth/device/token" -> json(
                    """{"access_token":"access","refresh_token":"refresh","expires_in":7200}""",
                )
                "/users/me" -> json("""{"username":"cove-user"}""")
                "/scrobble/start" -> json("{}")
                else -> error("unexpected Trakt path $path")
            }
        }
        backend(client).use { fixture ->
            val service = fixture.service
            assertEquals("ABCD", service.startDeviceFlow().userCode)
            assertEquals("authorized", service.poll("device").status)
            assertEquals("cove-user", service.status().username)

            assertTrue(service.scrobbleNow(
                TraktScrobbleRequest("start", 42, "movie", progress = 12.5),
            ))

            val scrobble = requests.single { it.path == "/scrobble/start" }
            assertEquals("client", scrobble.apiKey)
            assertEquals("Bearer access", scrobble.authorization)
            assertTrue(scrobble.body.contains("\"tmdb\":42"))
            assertTrue(scrobble.body.contains("\"progress\":12.5"))
        }
    }

    @Test
    fun syncPullsHistoryAndPushesLocalWatchlistAdditively() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = mockClient { path, headers, body ->
            requests += RecordedRequest(path, headers["trakt-api-key"].orEmpty(), headers[HttpHeaders.Authorization].orEmpty(), body)
            when {
                path == "/sync/last_activities" -> json(
                    """{"movies":{"watched_at":"2026-08-08T10:00:00Z","watchlisted_at":""},"shows":{"watchlisted_at":""},"episodes":{"watched_at":""}}""",
                )
                path.startsWith("/sync/history?") -> json(
                    """[{"watched_at":"2026-08-08T09:00:00Z","type":"movie","movie":{"title":"Remote movie","ids":{"tmdb":22}}}]""",
                    headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        "X-Pagination-Page-Count" to listOf("1"),
                    ),
                )
                path == "/sync/watchlist" && body.isBlank() -> json("[]")
                path == "/sync/history" -> json("{}")
                path == "/sync/watchlist" -> json("{}")
                else -> error("unexpected Trakt path $path")
            }
        }
        backend(client, traktSyncEnabled = true).use { fixture ->
            fixture.database.coveQueries.upsertTraktSession(
                "p1", "access", "refresh", Instant.parse("2027-01-01T00:00:00Z").epochSecond, "user", "",
            )
            fixture.library.add(11, MediaType.Tv, "Local show", "/local.jpg", 0.0)

            assertTrue(fixture.service.syncNow().completed)

            val entries = (fixture.library.entries.value as LibraryState.Ready).entries
            assertTrue(entries.any { it.tmdbId == 22 && it.title == "Remote movie" })
            assertTrue(entries.any { it.tmdbId == 11 })
            val watchlistPush = requests.last { it.path == "/sync/watchlist" }
            assertTrue(watchlistPush.body.contains("\"tmdb\":11"))
            assertEquals("Bearer access", watchlistPush.authorization)
            assertTrue(fixture.service.status().connected)
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.backend(
        client: HttpClient,
        traktSyncEnabled: Boolean = false,
    ): Fixture {
        val handle = DesktopDatabase.inMemory()
        val q = handle.database.coveQueries
        q.insertProfile("p1", "Primary", 1, null, "")
        q.setActiveProfile("p1")
        val session = ActiveProfileSession(handle.database)
        val settings = LocalSettingsRepository(
            handle.database,
            session,
            backgroundScope,
            { "2026-08-08T12:00:00Z" },
            { "token" },
        )
        val library = LocalLibraryRepository(
            handle.database,
            session,
            backgroundScope,
            { "id-${q.selectLibraryEntries("p1").executeAsList().size}-${q.selectWatchProgress("p1").executeAsList().size}" },
            { "2026-08-08T12:00:00Z" },
        )
        if (traktSyncEnabled) {
            settings.update((settings.settings.value as SettingsState.Ready).settings.copy(traktSyncEnabled = true))
        }
        val serviceScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val service = TraktService(
            TraktConfig("client", "secret", "https://trakt.test"),
            handle.database,
            session,
            settings,
            library,
            FakeTraktCatalog,
            client,
            serviceScope,
            Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC),
            minimumWriteIntervalMillis = 0,
            startBackgroundSync = false,
        )
        return Fixture(handle, client, service, library, serviceScope)
    }
}

private data class RecordedRequest(
    val path: String,
    val apiKey: String,
    val authorization: String,
    val body: String,
)

private class Fixture(
    private val handle: DesktopDatabase,
    private val client: HttpClient,
    val service: TraktService,
    val library: LocalLibraryRepository,
    private val serviceScope: CoroutineScope,
) : AutoCloseable {
    val database get() = handle.database
    override fun close() {
        serviceScope.cancel()
        client.close()
        handle.close()
    }
}

private fun mockClient(
    handler: suspend MockRequestHandleScope.(path: String, headers: io.ktor.http.Headers, body: String) -> HttpResponseData,
): HttpClient = HttpClient(MockEngine) {
    engine {
        addHandler { request ->
            val body = (request.body as? OutgoingContent.ByteArrayContent)
                ?.bytes()?.decodeToString().orEmpty()
            handler(
                request.url.encodedPath + request.url.encodedQuery
                    .takeIf(String::isNotBlank)?.let { "?$it" }.orEmpty(),
                request.headers,
                body,
            )
        }
    }
    install(ContentNegotiation) { json(CoveJson) }
}

private fun MockRequestHandleScope.json(
    body: String,
    headers: io.ktor.http.Headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
) = respond(body, HttpStatusCode.OK, headers)

private object FakeTraktCatalog : MediaCatalog {
    override suspend fun media(id: Int, type: MediaType) = Media(id, posterPath = "/$id.jpg", mediaType = type)
    override suspend fun discover(type: MediaType, limit: Int) = emptyList<Media>()
    override suspend fun searchMulti(query: String) = SearchResultsDto()
    override suspend fun details(id: Int, type: MediaType) = MediaDetails()
    override suspend fun images(id: Int, type: MediaType) = MediaImages()
    override suspend fun videos(id: Int, type: MediaType) = MediaVideos()
    override suspend fun similar(id: Int, type: MediaType) = emptyList<Media>()
    override suspend fun seasons(id: Int) = emptyList<TvSeason>()
    override suspend fun episodes(id: Int, season: Int) = emptyList<TvEpisode>()
    override suspend fun imdbId(id: Int, type: MediaType) = "tt$id"
    override suspend fun person(id: Int) = PersonDetails(id = id)
    override suspend fun genres(type: MediaType) = emptyList<MediaGenre>()
    override suspend fun discoverFiltered(
        type: MediaType,
        genreId: Int?,
        keywordId: Int?,
        personId: Int?,
        sort: CatalogSort,
        page: Int,
    ) = emptyList<Media>()
}
