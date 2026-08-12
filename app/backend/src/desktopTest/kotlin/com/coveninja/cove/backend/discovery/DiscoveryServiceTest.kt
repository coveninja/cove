package com.coveninja.cove.backend.discovery

import com.coveninja.cove.backend.addons.DesktopAddonUrlPolicy
import com.coveninja.cove.backend.content.TmdbClient
import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.migration.LegacyMigration
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.time.Clock as KotlinClock
import kotlin.time.Instant as KotlinInstant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class DiscoveryServiceTest {
    @Test
    fun `finished highly rated titles shape ranking and insights`() = runTest {
        fixture { library, service ->
            library.add(10, MediaType.Movie, "Loved", "/loved.jpg", 8.0)
            library.setStatus(10, MediaType.Movie, LibraryStatus.Finished)
            library.setRating(10, MediaType.Movie, 5.0)

            val recommendations = service.recommend("movie", 2)

            assertEquals(listOf(100, 101), recommendations.map { it.id })
            assertEquals(28, service.topGenres(MediaType.Movie, 1).single().id)
            assertEquals(7, service.topPeople(1).single().id)
            assertEquals(1, service.insights().signalsUsed)
            assertEquals(10, service.favorites(1).single().tmdbId)
        }
    }

    @Test
    fun `custom algorithm endpoint rejects private network targets before I O`() = runTest {
        fixture { _, service ->
            val result = service.testCustomAlgorithm("http://127.0.0.1:8080/rank")

            assertFalse(result.ok)
            assertTrue(result.error.isNotBlank())
        }
    }

    private suspend fun fixture(
        test: suspend (LocalLibraryRepository, DiscoveryService) -> Unit,
    ) {
        val dir = Files.createTempDirectory("cove-discovery")
        val clock = Clock.fixed(Instant.parse(FIXED_NOW), ZoneOffset.UTC)
        // DiscoveryService moved to commonMain and so takes kotlin.time.Clock, while the
        // migration and the repositories still take java.time's. Both are pinned to the
        // same moment: the recency decay in signalWeight compares them against each other.
        val discoveryClock = object : KotlinClock {
            override fun now(): KotlinInstant = KotlinInstant.parse(FIXED_NOW)
        }
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir, clock) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            var id = 0
            val now = { clock.instant().toString() }
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
            val settings = LocalSettingsRepository(store.database, session, scope, now) { "token" }
            val library = LocalLibraryRepository(store.database, session, scope, { "id-${++id}" }, now)
            val tmdbHttp = HttpClient(MockEngine { request ->
                val body = when {
                    request.url.encodedPath.endsWith("/movie/10") -> DETAILS
                    request.url.encodedPath.endsWith("/discover/movie") -> CANDIDATES
                    else -> error("unexpected TMDB request: ${request.url}")
                }
                respond(
                    body,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }) {
                install(ContentNegotiation) { json(CoveJson) }
            }
            val customHttp = HttpClient(MockEngine { error("private URL must not be requested") }) {
                install(ContentNegotiation) { json(CoveJson) }
            }
            try {
                test(
                    library,
                    DiscoveryService(
                        store.database,
                        session,
                        settings,
                        TmdbClient(tmdbHttp, "key", baseUrl = "https://tmdb.test/3"),
                        customHttp,
                        DesktopAddonUrlPolicy,
                        discoveryClock,
                    ),
                )
            } finally {
                customHttp.close()
                tmdbHttp.close()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val FIXED_NOW = "2026-08-08T12:00:00Z"

        const val DETAILS = """{
            "title":"Loved",
            "genres":[{"id":28,"name":"Action"}],
            "keywords":{"keywords":[{"id":99,"name":"hero"}]},
            "credits":{"cast":[{"id":7,"name":"Actor"}],"crew":[]},
            "production_companies":[{"id":2,"name":"Studio"}]
        }"""

        const val CANDIDATES = """{"results":[
            {"id":101,"title":"Comedy","poster_path":"/101.jpg","vote_average":8.0,"popularity":80.0,"genre_ids":[35]},
            {"id":100,"title":"Action","poster_path":"/100.jpg","vote_average":7.0,"popularity":20.0,"genre_ids":[28]}
        ]}"""
    }
}
