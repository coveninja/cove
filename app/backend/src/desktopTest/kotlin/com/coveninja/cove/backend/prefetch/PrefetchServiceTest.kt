package com.coveninja.cove.backend.prefetch

import com.coveninja.cove.backend.addons.AddonManager
import com.coveninja.cove.backend.content.TmdbClient
import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.migration.LegacyMigration
import com.coveninja.cove.backend.nuvio.NuvioManager
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class PrefetchServiceTest {
    @Test
    fun `cycle warms the same provider cache used by playback`() = runTest {
        var streamRequests = 0
        val http = HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/manifest.json") ->
                    """{"id":"provider","name":"Provider","resources":["stream"]}"""
                request.url.encodedPath.endsWith("/external_ids") ->
                    """{"imdb_id":"tt42"}"""
                request.url.encodedPath.endsWith("/movie/42") ->
                    """{"id":42,"title":"Movie","release_date":"2025-01-01","poster_path":"/42.jpg"}"""
                "/stream/movie/tt42.json" in request.url.encodedPath -> {
                    streamRequests++
                    """{"streams":[{"name":"1080p","url":"https://cdn.test/movie.mp4"}]}"""
                }
                else -> error("unexpected request ${request.url}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) { json(CoveJson) }
        }
        val dir = Files.createTempDirectory("cove-prefetch")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            var id = 0
            val now = { "2026-08-08T12:00:00Z" }
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val library = LocalLibraryRepository(store.database, session, scope, { "id-${++id}" }, now)
            val settings = LocalSettingsRepository(store.database, session, scope, now) { "token" }
            val catalog = TmdbClient(http, "key", baseUrl = "https://tmdb.test/3")
            val addons = AddonManager(store.database, session, http, now)
            val nuvio = NuvioManager(store.database, session, http, now)
            addons.add("https://addon.test")
            library.add(42, MediaType.Movie, "Movie")
            library.setStatus(42, MediaType.Movie, LibraryStatus.Watching)
            val service = PrefetchService(
                store.database,
                session,
                settings,
                catalog,
                addons,
                nuvio,
                scope,
            )

            service.runCycle()
            assertEquals(1, streamRequests)
            assertEquals(1, addons.streams(MediaType.Movie, "tt42").size)
            assertEquals(1, streamRequests)

            scope.cancel()
        }
        http.close()
    }
}
