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
import kotlinx.coroutines.test.runTest

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
}
