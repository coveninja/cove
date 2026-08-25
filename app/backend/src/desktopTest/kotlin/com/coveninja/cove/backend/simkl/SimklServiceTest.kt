package com.coveninja.cove.backend.simkl

import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.backend.tracker.TrackerScrobbleRequest
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.data.SettingsState
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
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SimklServiceTest {
    @Test
    fun pinFlowPersistsProfileTokenAndScrobbleCarriesSimklCredentials() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = mockClient { path, headers, body ->
            requests += RecordedRequest(
                path,
                headers["simkl-api-key"].orEmpty(),
                headers[HttpHeaders.Authorization].orEmpty(),
                body,
            )
            when {
                path.startsWith("/oauth/pin/") -> json(
                    """{"result":"OK","access_token":"access"}""",
                )
                path.startsWith("/oauth/pin") -> json(
                    """{"result":"OK","device_code":"dev","user_code":"ABCD",""" +
                        """"verification_url":"https://simkl.com/pin/","expires_in":900,"interval":5}""",
                )
                path.startsWith("/users/settings") -> json(
                    """{"user":{"name":"cove-user"},"account":{"id":4242,"type":"free"}}""",
                )
                path.startsWith("/scrobble/start") -> json("{}")
                else -> error("unexpected Simkl path $path")
            }
        }
        backend(client).use { fixture ->
            val service = fixture.service
            assertEquals("ABCD", service.startDeviceFlow().userCode)
            assertEquals("authorized", service.poll("ABCD").status)
            assertEquals("cove-user", service.status().username)
            // The account id rides in the refresh_token column because Simkl issues no
            // refresh token; /users/{id}/stats needs it by path. Mutation check: store ""
            // there and this reads empty.
            assertEquals(
                "4242",
                fixture.database.coveQueries.selectTrackerSession("p1", "simkl")
                    .executeAsOne().refresh_token,
            )

            assertTrue(
                service.scrobbleNow(TrackerScrobbleRequest("start", 42, "movie", progress = 12.5)),
            )

            val scrobble = requests.single { it.path.startsWith("/scrobble/start") }
            assertEquals("client", scrobble.apiKey)
            assertEquals("Bearer access", scrobble.authorization)
            // Mutation check: drop either parameter from decorate() and one of these fails.
            // Simkl rejects requests without app-name/app-version outright.
            assertTrue(scrobble.path.contains("app-name=cove"), scrobble.path)
            assertTrue(scrobble.path.contains("app-version=1.2.3"), scrobble.path)
            assertTrue(scrobble.body.contains("\"tmdb\":\"42\""), scrobble.body)
            assertTrue(scrobble.body.contains("\"progress\":12.5"), scrobble.body)
        }
    }

    /**
     * The whole reason the Simkl pull differs from Trakt's: `/sync/all-items` answers with
     * IMDB ids and no TMDB id at all, and the library is TMDB-keyed throughout.
     */
    @Test
    fun syncResolvesImdbIdsOntoTmdbAndPushesLocalWatchlistAdditively() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val client = mockClient { path, headers, body ->
            requests += RecordedRequest(
                path,
                headers["simkl-api-key"].orEmpty(),
                headers[HttpHeaders.Authorization].orEmpty(),
                body,
            )
            when {
                path.startsWith("/sync/activities") -> json("""{"all":"2026-08-08T10:00:00Z"}""")
                path.startsWith("/sync/all-items") -> json(
                    """{"movies":[{"status":"completed","last_watched_at":"2026-08-08T09:00:00Z",""" +
                        """"movie":{"title":"Remote movie","ids":{"simkl":1,"imdb":"tt0022"}}}],""" +
                        """"shows":[{"status":"watching","last_watched_at":"2026-08-08T09:30:00Z",""" +
                        """"show":{"title":"Remote show","ids":{"simkl":2,"imdb":"tt0033"}},""" +
                        """"seasons":[{"number":1,"episodes":[{"number":2,""" +
                        """"watched_at":"2026-08-08T09:30:00Z"}]}]}],""" +
                        // Anime with no IMDB id: Simkl knows it, TMDB cannot be asked about
                        // it, so it is dropped rather than half-resolved onto a wrong title.
                        """"anime":[{"status":"completed","last_watched_at":"2026-08-08T08:00:00Z",""" +
                        """"show":{"title":"Anime only","ids":{"simkl":3,"mal":"1"}},""" +
                        """"seasons":[{"number":1,"episodes":[{"number":1,""" +
                        """"watched_at":"2026-08-08T08:00:00Z"}]}]}]}""",
                )
                path.startsWith("/sync/history") -> json("{}")
                path.startsWith("/sync/add-to-list") -> json("""{"added":{},"not_found":{}}""")
                else -> error("unexpected Simkl path $path")
            }
        }
        backend(client, simklSyncEnabled = true).use { fixture ->
            fixture.database.coveQueries.upsertTrackerSession(
                "p1", "simkl", "access", "4242", 0, "user", "",
            )
            fixture.library.add(11, MediaType.Tv, "Local show", "/local.jpg", 0.0)

            assertTrue(fixture.service.syncNow().completed)

            val entries = (fixture.library.entries.value as LibraryState.Ready).entries
            // tt0022 → 22 and tt0033 → 33 come from FakeSimklCatalog's tt<id> convention.
            // Mutation check: return null from findByImdbId and neither arrives.
            assertTrue(entries.any { it.tmdbId == 22 && it.title == "Remote movie" }, "$entries")
            assertTrue(entries.any { it.tmdbId == 33 && it.title == "Remote show" }, "$entries")
            // The local entry is untouched: the pull is additive, never a replace.
            assertTrue(entries.any { it.tmdbId == 11 }, "$entries")
            // Anime with only a MAL id has no TMDB counterpart and must not invent one.
            assertFalse(entries.any { it.title == "Anime only" }, "$entries")

            // The credentials have to survive a path that already carries a query string:
            // decorate() adds its parameters to a URL /sync/all-items has already filled in,
            // and Simkl answers 403 to a request that reaches it without them.
            val pull = requests.last { it.path.startsWith("/sync/all-items") }
            assertTrue(pull.path.contains("client_id=client"), pull.path)
            assertTrue(pull.path.contains("extended=full"), pull.path)
            assertEquals("client", pull.apiKey)

            val listPush = requests.last { it.path.startsWith("/sync/add-to-list") }
            assertTrue(listPush.body.contains("\"tmdb\":\"11\""), listPush.body)
            // `to` sits on each item, not on the envelope — Simkl ignores it otherwise.
            assertTrue(listPush.body.contains("\"to\":\"plantowatch\""), listPush.body)
            assertEquals("Bearer access", listPush.authorization)
            assertTrue(fixture.service.status().connected)
        }
    }

    /** The IMDB→TMDB answers are permanent, so a second sync must not re-ask TMDB. */
    @Test
    fun resolvedImdbIdsAreCachedAcrossSyncs() = runTest {
        val client = mockClient { path, _, _ ->
            when {
                path.startsWith("/sync/activities") -> json("""{"all":"2026-08-08T10:00:00Z"}""")
                path.startsWith("/sync/all-items") -> json(
                    """{"movies":[{"status":"completed","last_watched_at":"2026-08-08T09:00:00Z",""" +
                        """"movie":{"title":"Remote movie","ids":{"simkl":1,"imdb":"tt0022"}}}]}""",
                )
                path.startsWith("/sync/history") -> json("{}")
                path.startsWith("/sync/add-to-list") -> json("{}")
                else -> error("unexpected Simkl path $path")
            }
        }
        val lookups = AtomicInteger()
        backend(client, simklSyncEnabled = true, lookups = lookups).use { fixture ->
            fixture.database.coveQueries.upsertTrackerSession(
                "p1", "simkl", "access", "4242", 0, "user", "",
            )
            assertTrue(fixture.service.syncNow().completed)
            assertEquals(1, lookups.get())

            // Clearing the cursor forces a second full pull of the same payload.
            fixture.database.coveQueries.upsertTrackerSession(
                "p1", "simkl", "access", "4242", 0, "user", "",
            )
            assertTrue(fixture.service.syncNow().completed)
            // Mutation check: skip the external_id_map read in resolveImdb and this is 2.
            assertEquals(1, lookups.get())
        }
    }

    /**
     * Simkl holds a 20-second per-user lock, while the progress ticker behind these fires
     * every ten. A "stop" is never dropped, because it is the event that marks an item
     * watched.
     */
    @Test
    fun repeatStartScrobblesAreDebouncedButStopIsNot() = runTest {
        val sent = mutableListOf<String>()
        val client = mockClient { path, _, _ ->
            if (path.startsWith("/scrobble/")) sent += path.substringBefore('?')
            json("{}")
        }
        backend(client).use { fixture ->
            fixture.database.coveQueries.upsertTrackerSession(
                "p1", "simkl", "access", "4242", 0, "user", "",
            )
            val start = TrackerScrobbleRequest("start", 42, "movie", progress = 10.0)
            assertTrue(fixture.service.scrobbleNow(start))
            // Mutation check: set scrobbleDebounceMillis to 0 and this becomes true.
            assertFalse(fixture.service.scrobbleNow(start.copy(progress = 20.0)))
            assertTrue(fixture.service.scrobbleNow(start.copy(action = "stop", progress = 95.0)))
            assertEquals(listOf("/scrobble/start", "/scrobble/stop"), sent)
        }
    }

    /** A build with no client id is a valid, silently-disabled configuration. */
    @Test
    fun unconfiguredBuildRefusesToLinkAndReportsNoStats() = runTest {
        val client = mockClient { path, _, _ -> error("unexpected Simkl request to $path") }
        backend(client, clientId = "").use { fixture ->
            assertFalse(fixture.service.isConfigured)
            assertFalse(fixture.service.status().connected)
            assertFalse(fixture.service.enqueueSync())
            assertNull(fixture.service.stats())
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.backend(
        client: HttpClient,
        simklSyncEnabled: Boolean = false,
        clientId: String = "client",
        lookups: AtomicInteger = AtomicInteger(),
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
            {
                "id-${q.selectLibraryEntries("p1").executeAsList().size}-" +
                    "${q.selectWatchProgress("p1").executeAsList().size}"
            },
            { "2026-08-08T12:00:00Z" },
        )
        if (simklSyncEnabled) {
            settings.update(
                (settings.settings.value as SettingsState.Ready).settings.copy(simklSyncEnabled = true),
            )
        }
        val serviceScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val service = SimklService(
            SimklConfig(clientId, "1.2.3", "https://simkl.test"),
            handle.database,
            session,
            settings,
            library,
            FakeSimklCatalog(lookups),
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
    val service: SimklService,
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
    handler: suspend MockRequestHandleScope.(
        path: String,
        headers: io.ktor.http.Headers,
        body: String,
    ) -> HttpResponseData,
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
    headers: io.ktor.http.Headers =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
) = respond(body, HttpStatusCode.OK, headers)

/**
 * Answers `tt0042` with TMDB 42, and counts how often it was asked.
 *
 * A blank id resolves to something rather than to null on purpose: it is the service's
 * own guard that has to drop an entry Simkl gave no IMDB id for, and a fake that refused
 * blanks would pass whether that guard existed or not.
 */
private class FakeSimklCatalog(private val lookups: AtomicInteger) : MediaCatalog {
    override suspend fun findByImdbId(imdbId: String, type: MediaType): Media? {
        lookups.incrementAndGet()
        if (imdbId.isBlank()) return Media(999, posterPath = "/999.jpg", mediaType = type)
        val id = imdbId.removePrefix("tt").trimStart('0').toIntOrNull() ?: return null
        return Media(id, posterPath = "/$id.jpg", mediaType = type)
    }

    override suspend fun media(id: Int, type: MediaType) =
        Media(id, posterPath = "/$id.jpg", mediaType = type)
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
