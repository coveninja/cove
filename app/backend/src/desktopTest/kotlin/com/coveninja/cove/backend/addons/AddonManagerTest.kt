package com.coveninja.cove.backend.addons

import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.migration.LegacyMigration
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalProfileRepository
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    // --- "Primary profile drives addons" ------------------------------------
    //
    // The policy is a field on the *primary's* settings row, so these tests write
    // it there directly rather than through the active profile's repository —
    // which is the same asymmetry the feature has in the app.

    @Test
    fun `a secondary profile inherits the primary's stremio addons`() = runBlocking {
        val http = HttpClient(MockEngine {
            respond(
                """{"id":"provider.one","name":"Provider One","resources":["stream"]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val dir = Files.createTempDirectory("cove-addons-shared")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            var id = 0
            val profiles = LocalProfileRepository(store.database, session, { "id-${++id}" }, { "now" })
            val manager = AddonManager(store.database, session, http, { "now" })
            manager.add("https://addon.test")
            setAddonPolicy(store, "primary", follow = true)

            val child = profiles.create("Child")
            profiles.activate(child.id)

            val inherited = manager.entries().single { it.source == "stremio" }
            assertEquals("provider.one", inherited.id)
            assertTrue(inherited.managed, "the primary's addon must arrive read-only")
            // Officials are seeded into every profile and keep their own switches,
            // so none of them may arrive as a copy of the primary's row.
            assertTrue(manager.entries().none { it.source == "official" && it.managed })
            // Officials first, then the shared list, then anything of its own.
            assertEquals(
                listOf("cove.justwatch", "cove.introdb", "provider.one"),
                manager.entries().map { it.id },
            )

            val sharing = manager.sharing()
            assertTrue(sharing.enabled)
            assertFalse(sharing.editable, "only the primary may change the policy")
            assertEquals("Primary", sharing.primaryName)

            profiles.activate("primary")
            assertTrue(manager.sharing().editable)
            // The primary's own row is never marked: it owns it.
            assertFalse(manager.entries().single { it.source == "stremio" }.managed)
        }
        http.close()
    }

    @Test
    fun `a secondary profile inherits nothing while the policy is off`() = runBlocking {
        val http = HttpClient(MockEngine {
            respond(
                """{"id":"provider.one","name":"Provider One","resources":["stream"]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val dir = Files.createTempDirectory("cove-addons-unshared")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            var id = 0
            val profiles = LocalProfileRepository(store.database, session, { "id-${++id}" }, { "now" })
            val manager = AddonManager(store.database, session, http, { "now" })
            manager.add("https://addon.test")

            val child = profiles.create("Child")
            profiles.activate(child.id)

            // Default off: sharing a household's providers is opt-in.
            assertEquals(emptyList(), manager.entries().filter { it.source == "stremio" })
            assertFalse(manager.sharing().enabled)

            // And switching it off again takes them away.
            setAddonPolicy(store, "primary", follow = true)
            assertEquals(1, manager.entries().count { it.managed })
            setAddonPolicy(store, "primary", follow = false)
            assertEquals(emptyList(), manager.entries().filter { it.managed })
        }
        http.close()
    }

    @Test
    fun `a secondary cannot change an inherited addon but keeps its own`() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            val host = request.url.host.substringBefore('.')
            respond(
                """{"id":"$host","name":"${host.uppercase()}","resources":["stream"]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val dir = Files.createTempDirectory("cove-addons-locked")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            var id = 0
            val profiles = LocalProfileRepository(store.database, session, { "id-${++id}" }, { "now" })
            val manager = AddonManager(store.database, session, http, { "now" })
            manager.add("https://shared.test/manifest.json")
            setAddonPolicy(store, "primary", follow = true)

            val child = profiles.create("Child")
            profiles.activate(child.id)
            manager.add("https://mine.test/manifest.json")

            // Every mutation says the policy is holding rather than "addon not
            // found", which for an addon sitting in the list would read as a bug.
            listOf<suspend () -> Unit>(
                { manager.setEnabled("shared", null, false) },
                { manager.remove("shared", null) },
                { manager.refresh("shared", null) },
                { manager.setCatalogEnabled("shared", null, "movie/top", true) },
            ).forEach { mutation ->
                val failure = assertFailsWith<IllegalStateException> { mutation() }
                assertEquals("this addon is managed by the primary profile", failure.message)
            }
            // A URL that exists nowhere still reports the ordinary failure.
            assertEquals(
                "addon not found",
                assertFailsWith<IllegalStateException> { manager.remove("nobody", null) }.message,
            )

            // Untouched by all of that, and the child's own addon is still fully its own.
            assertTrue(manager.entries().single { it.id == "shared" }.enabled)
            manager.setEnabled("mine", null, false)
            assertFalse(manager.entries().single { it.id == "mine" }.enabled)
            manager.remove("mine", null)
            assertEquals(listOf("shared"), manager.entries().filter { it.source == "stremio" }.map { it.id })
        }
        http.close()
    }

    // The regression this feature could most easily cause: a secondary pushing
    // the primary's addons to Supabase under its own profile carries a newer
    // timestamp than every other device, so the next pull duplicates them across
    // the account — and a later removal on the primary never propagates.
    @Test
    fun `an inherited addon is never pushed under the secondary's own profile`() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            val host = request.url.host.substringBefore('.')
            respond(
                """{"id":"$host","name":"${host.uppercase()}","resources":["stream"]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val dir = Files.createTempDirectory("cove-addons-sync")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            var id = 0
            val profiles = LocalProfileRepository(store.database, session, { "id-${++id}" }, { "now" })
            val manager = AddonManager(store.database, session, http, { "now" })
            manager.add("https://shared.test/manifest.json")
            setAddonPolicy(store, "primary", follow = true)

            val child = profiles.create("Child")
            profiles.activate(child.id)
            manager.add("https://mine.test/manifest.json")

            val snapshot = manager.snapshotForSync()
            assertEquals(
                listOf("mine"),
                snapshot.entries.filter { it.source == "stremio" }.map { it.id },
            )
            assertTrue(snapshot.entries.none { it.managed })
        }
        http.close()
    }

    // Every mutation on AddonManager clears the whole stream cache, so nothing the
    // primary does to its own addons can leave a secondary stale. Switching the
    // policy is the exception: it is a settings write that never reaches this
    // class, and only the cache key notices it.
    @Test
    fun `a secondary resolves streams through the inherited provider`() = runBlocking {
        val requested = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            requested += request.url.toString()
            val body = when {
                request.url.encodedPath.endsWith("/manifest.json") ->
                    """{"id":"provider.one","name":"Provider One","resources":["stream"],"types":["movie"]}"""
                "/stream/movie/" in request.url.encodedPath ->
                    """{"streams":[{"name":"1080p","infoHash":"abc"}]}"""
                else -> error("unexpected URL ${request.url}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val dir = Files.createTempDirectory("cove-addons-streams")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            var id = 0
            val profiles = LocalProfileRepository(store.database, session, { "id-${++id}" }, { "now" })
            val manager = AddonManager(store.database, session, http, { "now" })
            manager.add("https://addon.test/manifest.json")

            val child = profiles.create("Child")
            profiles.activate(child.id)
            // Caches an empty answer while the policy is off, which is what the
            // switch below has to be able to displace.
            assertEquals(emptyList(), manager.streams(MediaType.Movie, "tt1"))

            setAddonPolicy(store, "primary", follow = true)

            assertEquals("Provider One", manager.streams(MediaType.Movie, "tt1").single().addonName)
            assertTrue(requested.any { "/stream/movie/" in it })

            // And back: the shared provider has to stop answering at once too.
            setAddonPolicy(store, "primary", follow = false)
            assertEquals(emptyList(), manager.streams(MediaType.Movie, "tt1"))

            // A change the primary makes to a shared addon reaches the child as
            // well — by a different route, since that one does clear the cache.
            setAddonPolicy(store, "primary", follow = true)
            assertEquals(1, manager.streams(MediaType.Movie, "tt1").size)
            profiles.activate("primary")
            manager.setEnabled("provider.one", null, false)
            profiles.activate(child.id)
            assertEquals(emptyList(), manager.streams(MediaType.Movie, "tt1"))
        }
        http.close()
    }

    @Test
    fun `an addon both profiles installed stays the secondary's own`() = runBlocking {
        val http = HttpClient(MockEngine {
            respond(
                """{"id":"provider.one","name":"Provider One","resources":["stream"]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val dir = Files.createTempDirectory("cove-addons-both")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            var id = 0
            val profiles = LocalProfileRepository(store.database, session, { "id-${++id}" }, { "now" })
            val manager = AddonManager(store.database, session, http, { "now" })
            manager.add("https://addon.test/manifest.json")
            setAddonPolicy(store, "primary", follow = true)

            val child = profiles.create("Child")
            profiles.activate(child.id)
            manager.add("https://addon.test/manifest.json")

            // One row, and it is the child's: turning the policy off later must
            // not take away an addon this profile installed for itself.
            val own = manager.entries().single { it.source == "stremio" }
            assertFalse(own.managed)
            manager.setEnabled("provider.one", null, false)
            assertFalse(manager.entries().single { it.source == "stremio" }.enabled)
        }
        http.close()
    }

    /** Writes the sharing policy onto [profileId]'s own settings row. */
    private fun setAddonPolicy(store: DesktopDatabase, profileId: String, follow: Boolean) {
        store.database.coveQueries.upsertSettings(
            profileId,
            CoveJson.encodeToString(AppSettings(addonsFollowPrimary = follow)),
            "now",
        )
    }
}
