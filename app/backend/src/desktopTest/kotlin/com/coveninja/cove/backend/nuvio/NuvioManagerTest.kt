package com.coveninja.cove.backend.nuvio

import com.coveninja.cove.backend.addons.BasicAddonUrlPolicy
import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.migration.LegacyMigration
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.shared.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class NuvioManagerTest {
    @Test
    fun repoAndScraperAreOptInAndOnlyEnabledCodeReachesSandbox() = runTest {
        val http = HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("manifest.json") -> """{"scrapers":[{
                    "id":"one","name":"One","filename":"one.js","supportedTypes":["movie"]
                }]}"""
                request.url.encodedPath.endsWith("one.js") ->
                    "module.exports.getStreams = () => [{url:'https://video.example/movie'}]"
                else -> error("unexpected ${request.url}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        })
        val invocations = mutableListOf<NuvioInvocation>()
        val sandbox = object : NuvioSandbox {
            override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> {
                invocations += invocation
                return listOf(NuvioScrapedStream(name = "1080p", url = "https://video.example/movie"))
            }
        }
        val dir = Files.createTempDirectory("cove-nuvio")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dir) { "primary" }.importIfNeeded()
            val manager = NuvioManager(
                database.database,
                ActiveProfileSession(database.database),
                http,
                { "2026-08-08T00:00:00Z" },
                sandbox,
                BasicAddonUrlPolicy,
            )

            val repo = manager.add("https://github.com/owner/plugins")
            assertFalse(repo.enabled)
            assertFalse(repo.scrapers.single().enabled)
            assertEquals(emptyList(), manager.streams(
                MediaType.Movie, 42, "tt42", "Movie", 2026, null, null,
            ))

            manager.setRepoEnabled(repo.id, true)
            manager.setScraperEnabled(repo.id, "one", true)
            val streams = manager.streams(
                MediaType.Movie, 42, "tt42", "Movie", 2026, null, null,
            )
            assertEquals("https://video.example/movie", streams.single().url)
            assertEquals("one", invocations.single().scraperId)
            assertTrue(manager.repos().single().scrapers.single().code.isNotBlank())
        }
        http.close()
    }

    @Test
    fun disablingAScraperDropsItsSourceAndReEnablingFetchesItAgain() = runTest {
        var codeFetches = 0
        val http = HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("manifest.json") -> """{"scrapers":[{
                    "id":"one","name":"One","filename":"one.js","supportedTypes":["movie"]
                }]}"""
                request.url.encodedPath.endsWith("one.js") -> {
                    codeFetches++
                    "module.exports.getStreams = () => []"
                }
                else -> error("unexpected ${'$'}{request.url}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        })
        val sandbox = object : NuvioSandbox {
            override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> = emptyList()
        }
        val dir = Files.createTempDirectory("cove-nuvio")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dir) { "primary" }.importIfNeeded()
            val manager = NuvioManager(
                database.database,
                ActiveProfileSession(database.database),
                http,
                { "2026-08-19T00:00:00Z" },
                sandbox,
                BasicAddonUrlPolicy,
            )

            val repo = manager.add("https://github.com/owner/plugins")
            manager.setRepoEnabled(repo.id, true)
            manager.setScraperEnabled(repo.id, "one", true)
            assertEquals(1, codeFetches)
            assertTrue(manager.repos().single().scrapers.single().code.isNotBlank())

            // The whole store is one SQLite row, and on Android a row has a size ceiling it
            // must not reach. Retaining the source of every scraper the viewer ever tried is
            // what walks it towards that ceiling, so switching one off releases its code.
            manager.setScraperEnabled(repo.id, "one", false)
            assertEquals("", manager.repos().single().scrapers.single().code)

            // Which is only safe because the enable path treats blank code as "never
            // fetched" and goes back to the repo for it.
            manager.setScraperEnabled(repo.id, "one", true)
            assertEquals(2, codeFetches)
            assertTrue(manager.repos().single().scrapers.single().code.isNotBlank())
        }
        http.close()
    }

    @Test
    fun scraperRuntimeFailureIsNotCachedAsAHealthyEmptyResult() = runTest {
        val http = pluginRepositoryClient()
        var invocations = 0
        val sandbox = object : NuvioSandbox {
            override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> {
                invocations += 1
                if (invocations == 1) error("unsupported pending promise")
                return listOf(NuvioScrapedStream(name = "Recovered", url = "https://video.example/recovered"))
            }
        }
        val dir = Files.createTempDirectory("cove-nuvio")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dir) { "primary" }.importIfNeeded()
            val manager = enabledManager(database, http, sandbox)

            assertTrue(manager.streams(
                MediaType.Movie, 42, "tt42", "Movie", 2026, null, null,
            ).isEmpty())
            assertEquals(
                "https://video.example/recovered",
                manager.streams(MediaType.Movie, 42, "tt42", "Movie", 2026, null, null).single().url,
            )
            assertEquals(2, invocations)
        }
        http.close()
    }

    @Test
    fun successfulEmptyScraperResultRemainsCacheable() = runTest {
        val http = pluginRepositoryClient()
        var invocations = 0
        val sandbox = object : NuvioSandbox {
            override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> {
                invocations += 1
                return emptyList()
            }
        }
        val dir = Files.createTempDirectory("cove-nuvio")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dir) { "primary" }.importIfNeeded()
            val manager = enabledManager(database, http, sandbox)

            repeat(2) {
                assertTrue(manager.streams(
                    MediaType.Movie, 42, "tt42", "Movie", 2026, null, null,
                ).isEmpty())
            }
            assertEquals(1, invocations)
        }
        http.close()
    }

    @Test
    fun oneScraperTimeoutDoesNotDiscardAnotherScrapersStreams() = runTest {
        val http = HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("manifest.json") -> """{"scrapers":[
                    {"id":"slow","name":"Slow","filename":"slow.js","supportedTypes":["movie"]},
                    {"id":"fast","name":"Fast","filename":"fast.js","supportedTypes":["movie"]}
                ]}"""
                request.url.encodedPath.endsWith("slow.js") ||
                    request.url.encodedPath.endsWith("fast.js") ->
                    "module.exports.getStreams = () => []"
                else -> error("unexpected ${request.url}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        })
        val sandbox = object : NuvioSandbox {
            override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> {
                if (invocation.scraperId == "slow") {
                    withTimeout(10) { delay(1_000) }
                }
                return listOf(
                    NuvioScrapedStream(
                        name = invocation.scraperId,
                        url = "https://video.example/${invocation.scraperId}",
                    ),
                )
            }
        }
        val dir = Files.createTempDirectory("cove-nuvio")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dir) { "primary" }.importIfNeeded()
            val manager = NuvioManager(
                database.database,
                ActiveProfileSession(database.database),
                http,
                { "2026-08-21T00:00:00Z" },
                sandbox,
                BasicAddonUrlPolicy,
            )
            val repo = manager.add("https://github.com/owner/plugins")
            manager.setRepoEnabled(repo.id, true)
            manager.setScraperEnabled(repo.id, "slow", true)
            manager.setScraperEnabled(repo.id, "fast", true)

            val streams = manager.streams(
                MediaType.Movie, 42, "tt42", "Movie", 2026, null, null,
            )

            assertEquals(listOf("fast"), streams.map { it.name })
        }
        http.close()
    }

    @Test
    fun aggregateTimeoutKeepsStreamsFromScrapersThatAlreadyFinished() = runTest {
        val http = HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("manifest.json") -> """{"scrapers":[
                    {"id":"slow","name":"Slow","filename":"slow.js","supportedTypes":["movie"]},
                    {"id":"fast","name":"Fast","filename":"fast.js","supportedTypes":["movie"]}
                ]}"""
                request.url.encodedPath.endsWith("slow.js") ||
                    request.url.encodedPath.endsWith("fast.js") ->
                    "module.exports.getStreams = () => []"
                else -> error("unexpected ${request.url}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        })
        val sandbox = object : NuvioSandbox {
            override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> {
                if (invocation.scraperId == "slow") delay(30_000)
                return listOf(
                    NuvioScrapedStream(
                        name = invocation.scraperId,
                        url = "https://video.example/${invocation.scraperId}",
                    ),
                )
            }
        }
        val dir = Files.createTempDirectory("cove-nuvio")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dir) { "primary" }.importIfNeeded()
            val manager = NuvioManager(
                database.database,
                ActiveProfileSession(database.database),
                http,
                { "2026-08-21T00:00:00Z" },
                sandbox,
                BasicAddonUrlPolicy,
            )
            val repo = manager.add("https://github.com/owner/plugins")
            manager.setRepoEnabled(repo.id, true)
            manager.setScraperEnabled(repo.id, "slow", true)
            manager.setScraperEnabled(repo.id, "fast", true)

            val streams = manager.streams(
                MediaType.Movie, 42, "tt42", "Movie", 2026, null, null,
            )

            assertEquals(listOf("fast"), streams.map { it.name })
        }
        http.close()
    }

    @Test
    fun concurrentRequestsForTheSameTitleShareOneScraperRun() = runTest {
        val http = pluginRepositoryClient()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var invocations = 0
        val sandbox = object : NuvioSandbox {
            override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> {
                invocations += 1
                entered.complete(Unit)
                release.await()
                return listOf(NuvioScrapedStream(name = "Shared", url = "https://video.example/shared"))
            }
        }
        val dir = Files.createTempDirectory("cove-nuvio")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dir) { "primary" }.importIfNeeded()
            val manager = enabledManager(database, http, sandbox)

            val first = async {
                manager.streams(MediaType.Movie, 42, "tt42", "Movie", 2026, null, null)
            }
            entered.await()
            val second = async {
                manager.streams(MediaType.Movie, 42, "tt42", "Movie", 2026, null, null)
            }
            yield()
            assertEquals(1, invocations)

            release.complete(Unit)
            assertEquals(first.await(), second.await())
            assertEquals(1, invocations)
        }
        http.close()
    }

    private suspend fun enabledManager(
        database: DesktopDatabase,
        http: HttpClient,
        sandbox: NuvioSandbox,
    ): NuvioManager {
        val manager = NuvioManager(
            database.database,
            ActiveProfileSession(database.database),
            http,
            { "2026-08-21T00:00:00Z" },
            sandbox,
            BasicAddonUrlPolicy,
        )
        val repo = manager.add("https://github.com/owner/plugins")
        manager.setRepoEnabled(repo.id, true)
        manager.setScraperEnabled(repo.id, "one", true)
        return manager
    }

    private fun pluginRepositoryClient() = HttpClient(MockEngine { request ->
        val body = when {
            request.url.encodedPath.endsWith("manifest.json") -> """{"scrapers":[{
                "id":"one","name":"One","filename":"one.js","supportedTypes":["movie"]
            }]}"""
            request.url.encodedPath.endsWith("one.js") -> "module.exports.getStreams = () => []"
            else -> error("unexpected ${request.url}")
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
    })
}
