package com.coveninja.cove.backend.addons

import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.migration.LegacyMigration
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalProfileRepository
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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AddonManagerTest {
    @Test
    fun `installs persists and fans out provider streams in registration order`() = runBlocking {
        val requested = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            requested += request.url.toString()
            val body = when {
                request.url.encodedPath.endsWith("/manifest.json") ->
                    """{"id":"provider.one","name":"Provider One","resources":["stream"],"types":["movie","series"]}"""
                "/stream/series/" in request.url.encodedPath ->
                    """{"streams":[{"name":"1080p","infoHash":"abc","behaviorHints":{"videoSize":1234}}]}"""
                else -> error("unexpected URL ${request.url}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val dir = Files.createTempDirectory("cove-addons")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            val errors = mutableListOf<Throwable>()
            val manager = AddonManager(
                store.database,
                session,
                http,
                { "now" },
                onProviderError = { _, error -> errors += error },
            )

            val installed = manager.add(" https://addon.test/manifest.json ")
            assertEquals("https://addon.test", installed.url)
            assertEquals(AddonKind.Provider, installed.kind)
            assertEquals(installed, manager.entries().single { it.source == "stremio" })
            assertEquals(listOf("cove.justwatch", "cove.introdb"), manager.entries()
                .filter { it.source == "official" }.map { it.id })

            val streams = manager.streams(MediaType.Tv, "tt123:1:2")
            assertEquals(1, streams.size, "requests=$requested errors=${errors.map { it.stackTraceToString() }}")
            assertEquals("Provider One", streams.single().addonName)
            assertEquals(1234, streams.single().sizeBytes)
            assertTrue(requested.any { "/stream/series/" in it })
            manager.streams(MediaType.Tv, "tt123:1:2")
            assertEquals(1, requested.count { "/stream/series/" in it })

            manager.setEnabled("provider.one", null, false)
            assertEquals(emptyList(), manager.streams(MediaType.Tv, "tt123:1:2"))
            assertEquals(emptyList(), manager.streams(MediaType.Movie, "tt123"))
            manager.remove(null, "https://addon.test")
            assertEquals(emptyList(), manager.entries().filter { it.source == "stremio" })
        }
        http.close()
    }

    // Official integrations are gated on the same enabled flag, so they have to be
    // switchable. setEnabled used to carry a copy of remove()'s source guard —
    // message and all — which turned every built-in toggle into an HTTP 400.
    // Mutation applied to verify: restored `require(existing.source == "stremio")`
    // in setEnabled → test failed with IllegalArgumentException.
    @Test
    fun `official addons can be switched off`() = runBlocking {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        val dir = Files.createTempDirectory("cove-addons-official")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val manager = AddonManager(
                store.database,
                ActiveProfileSession(store.database),
                http,
                { "now" },
            )

            assertTrue(manager.entries().first { it.id == "cove.justwatch" }.enabled)

            manager.setEnabled("cove.justwatch", null, false)

            assertEquals(false, manager.entries().first { it.id == "cove.justwatch" }.enabled)
            // The others are untouched by a single toggle.
            assertTrue(manager.entries().first { it.id == "cove.introdb" }.enabled)
        }
        http.close()
    }

    // selectAddons orders by rowid, so the storage write has to preserve it.
    // Mutation applied to verify: reverted upsertAddon to INSERT OR REPLACE
    // → test failed, the toggled addon had moved to the end of the list.
    @Test
    fun `toggling an addon does not reorder the list`() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            val id = request.url.host.substringBefore('.')
            respond(
                """{"id":"$id","name":"${id.uppercase()}","resources":["stream"]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val dir = Files.createTempDirectory("cove-addons-order")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val manager = AddonManager(
                store.database,
                ActiveProfileSession(store.database),
                http,
                { "now" },
            )
            manager.add("https://alpha.test/manifest.json")
            manager.add("https://bravo.test/manifest.json")
            manager.add("https://charlie.test/manifest.json")

            val before = manager.entries().map { it.id }
            manager.setEnabled("alpha", null, false)
            val after = manager.entries().map { it.id }

            assertEquals(before, after, "toggling must not move an addon")
        }
        http.close()
    }

    @Test
    fun `addon state is isolated per active profile`() = runBlocking {
        val http = HttpClient(MockEngine {
            respond(
                """{"id":"provider.one","name":"Provider One","resources":["stream"]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val dir = Files.createTempDirectory("cove-addons-profiles")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            var id = 0
            val profiles = LocalProfileRepository(store.database, session, { "id-${++id}" }, { "now" })
            val manager = AddonManager(store.database, session, http, { "now" })
            manager.add("https://addon.test")

            val child = profiles.create("Child")
            profiles.activate(child.id)
            assertEquals(emptyList(), manager.entries().filter { it.source == "stremio" })
            profiles.activate("primary")
            assertEquals("provider.one", manager.entries().single { it.source == "stremio" }.id)
        }
        http.close()
    }
}
