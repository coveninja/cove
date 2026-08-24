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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    @Test
    fun aWedgedScraperDoesNotHoldTheRequestOpen() = runTest {
        val http = scraperClient("wedged", "fast")
        val sandbox = object : NuvioSandbox {
            override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> {
                // A scraper blocked on a pipe read or inside a native call does not observe
                // cancellation, which is exactly what the Android sandbox could do. NonCancellable
                // is the only faithful way to model that from a coroutine.
                if (invocation.scraperId == "wedged") {
                    withContext(NonCancellable) { delay(WEDGED_MILLIS) }
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
            val manager = managerFor(database, http, sandbox, listOf("wedged", "fast"))

            val streams = manager.streams(MediaType.Movie, 42, "tt42", "Movie", 2026, null, null)

            assertEquals(listOf("fast"), streams.map { it.name })
            // The point of the test: the request comes back on its own budget rather than
            // whenever the wedged scraper happens to let go.
            assertTrue(currentTime < WEDGED_MILLIS, "returned only after ${currentTime} ms")
            drainDetachedBatch()
        }
        http.close()
    }

    @Test
    fun aScraperStillQueuedWhenTheBudgetRunsOutNeverReachesTheSandbox() = runTest {
        val wedges = (1..12).map { "wedge$it" }
        val http = scraperClient(*(wedges + "late").toTypedArray())
        var lateRan = false
        val sandbox = object : NuvioSandbox {
            override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> {
                if (invocation.scraperId == "late") lateRan = true
                else withContext(NonCancellable) { delay(WEDGED_MILLIS) }
                return emptyList()
            }
        }
        val dir = Files.createTempDirectory("cove-nuvio")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dir) { "primary" }.importIfNeeded()
            val manager = managerFor(database, http, sandbox, wedges + "late")

            manager.streams(MediaType.Movie, 42, "tt42", "Movie", 2026, null, null)

            // Every permit is held by a wedged scraper for longer than the whole budget, so the
            // thirteenth is cancelled where it waits instead of being started once the budget is
            // already spent — the shape that left two thirds of a large set unaccounted for.
            assertFalse(lateRan)
            assertTrue(currentTime < WEDGED_MILLIS, "returned only after ${currentTime} ms")
            drainDetachedBatch()
        }
        http.close()
    }

    @Test
    fun aScraperThatAnsweredIsNotRunAgainForTheSameTitle() = runTest {
        val http = scraperClient("steady", "flaky")
        val runs = mutableMapOf<String, Int>()
        var flakyFails = true
        val sandbox = object : NuvioSandbox {
            override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> {
                runs[invocation.scraperId] = (runs[invocation.scraperId] ?: 0) + 1
                if (invocation.scraperId == "flaky" && flakyFails) error("provider is down")
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
            val manager = managerFor(database, http, sandbox, listOf("steady", "flaky"))

            assertEquals(
                listOf("steady"),
                manager.streams(MediaType.Movie, 42, "tt42", "Movie", 2026, null, null).map { it.name },
            )
            flakyFails = false
            val second = manager.streams(MediaType.Movie, 42, "tt42", "Movie", 2026, null, null)

            // One scraper failing used to discard the whole run's cache, so every play paid for
            // the entire fan-out again. Only the one that failed is retried now.
            assertEquals(1, runs["steady"])
            assertEquals(2, runs["flaky"])
            assertEquals(listOf("steady", "flaky"), second.map { it.name })
        }
        http.close()
    }

    @Test
    fun aStragglerDoesNotHoldTheAnswerBack() = runTest {
        val fast = listOf("f1", "f2", "f3")
        val http = scraperClient(*(fast + "slow").toTypedArray())
        val sandbox = object : NuvioSandbox {
            override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> {
                if (invocation.scraperId == "slow") delay(STRAGGLER_MILLIS)
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
            val manager = managerFor(database, http, sandbox, fast + "slow")

            val streams = manager.streams(MediaType.Movie, 42, "tt42", "Movie", 2026, null, null)

            // The fan-out is already parallel, so the whole wait used to be whatever the slowest
            // scraper cost. Answers that have stopped arriving are enough to act on.
            assertEquals(fast, streams.map { it.name })
            assertTrue(currentTime < STRAGGLER_MILLIS, "waited for the straggler: ${currentTime} ms")
            drainDetachedBatch()
        }
        http.close()
    }

    @Test
    fun theStragglerKeepsRunningIntoTheCache() = runTest {
        val fast = listOf("f1", "f2", "f3")
        val http = scraperClient(*(fast + "slow").toTypedArray())
        var slowRuns = 0
        val sandbox = object : NuvioSandbox {
            override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> {
                if (invocation.scraperId == "slow") {
                    slowRuns += 1
                    delay(STRAGGLER_MILLIS)
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
            val manager = managerFor(database, http, sandbox, fast + "slow")

            manager.streams(MediaType.Movie, 42, "tt42", "Movie", 2026, null, null)
            // Returning early must not cancel the rest, or it would trade the wait for lost
            // sources. The batch runs on and each outcome writes its own cache entry.
            advanceTimeBy(STRAGGLER_MILLIS + 1_000)
            val second = manager.streams(MediaType.Movie, 42, "tt42", "Movie", 2026, null, null)

            assertEquals(1, slowRuns, "the straggler was cancelled and had to run again")
            assertEquals(fast + "slow", second.map { it.name })
            drainDetachedBatch()
        }
        http.close()
    }

    @Test
    fun aRunWithNothingToShowYetIsStillWaitedFor() = runTest {
        val http = scraperClient("only")
        val sandbox = object : NuvioSandbox {
            override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> {
                delay(SLOW_BUT_ONLY_MILLIS)
                return listOf(NuvioScrapedStream(name = "only", url = "https://video.example/only"))
            }
        }
        val dir = Files.createTempDirectory("cove-nuvio")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dir) { "primary" }.importIfNeeded()
            val manager = managerFor(database, http, sandbox, listOf("only"))

            val streams = manager.streams(MediaType.Movie, 42, "tt42", "Movie", 2026, null, null)

            // Quiet is not the same as done. With nothing gathered there is nothing to return
            // early with, so a lone slow source must still be waited for.
            assertEquals(listOf("only"), streams.map { it.name })
        }
        http.close()
    }

    /**
     * Runs out whatever the manager deliberately left running.
     *
     * A batch that outlives its request is the point of the early return, but runTest does not
     * own those coroutines and does not wait for them, so anything they raise at teardown is
     * reported against the *next* test in the JVM as UncaughtExceptionsBeforeTest — which is
     * confusing enough to be worth spending a line per test to avoid.
     */
    private suspend fun TestScope.drainDetachedBatch() = advanceUntilIdle()

    private suspend fun managerFor(
        database: DesktopDatabase,
        http: HttpClient,
        sandbox: NuvioSandbox,
        scraperIds: List<String>,
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
        scraperIds.forEach { manager.setScraperEnabled(repo.id, it, true) }
        return manager
    }

    private fun scraperClient(vararg scraperIds: String) = HttpClient(MockEngine { request ->
        val body = when {
            request.url.encodedPath.endsWith("manifest.json") -> scraperIds.joinToString(
                separator = ",",
                prefix = """{"scrapers":[""",
                postfix = "]}",
            ) { """{"id":"$it","name":"$it","filename":"$it.js","supportedTypes":["movie"]}""" }
            scraperIds.any { request.url.encodedPath.endsWith("/$it.js") } ->
                "module.exports.getStreams = () => []"
            else -> error("unexpected ${request.url}")
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
    })

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

    private companion object {
        /** Longer than the aggregate budget, so a run that waits for it is unmistakable. */
        const val WEDGED_MILLIS = 60_000L

        /** Well past the quiet window, but inside the aggregate budget. */
        const val STRAGGLER_MILLIS = 8_000L

        /** Slow enough to be past the quiet window, so only the results floor keeps it waited for. */
        const val SLOW_BUT_ONLY_MILLIS = 5_000L
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
