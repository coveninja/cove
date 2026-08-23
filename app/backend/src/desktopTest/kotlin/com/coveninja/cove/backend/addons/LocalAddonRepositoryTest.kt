package com.coveninja.cove.backend.addons

import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.migration.LegacyMigration
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalProfileRepository
import com.coveninja.cove.shared.data.AddonsState
import com.coveninja.cove.shared.model.AppSettings
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LocalAddonRepositoryTest {
    @Test
    fun `provider addon lifecycle is available without an HTTP host`() = runTest {
        val http = providerHttpClient()
        val directory = Files.createTempDirectory("cove-local-addon-repository")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, directory) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val repository = LocalAddonRepository(
                addons = AddonManager(store.database, session, http, { "now" }),
                activeProfileIds = session.profileId,
                scope = scope,
            )

            advanceUntilIdle()
            val initial = assertIs<AddonsState.Ready>(repository.state.value)
            assertEquals(listOf("cove.justwatch", "cove.introdb"), initial.addons.map { it.id })
            assertFalse(repository.supportsNuvio)

            repository.addAddon("  https://addon.test/manifest.json\n")

            val installed = assertIs<AddonsState.Ready>(repository.state.value)
                .addons.single { it.source == "stremio" }
            assertEquals("provider.one", installed.id)
            assertEquals("https://addon.test", installed.url)
            assertEquals("Provider One", installed.displayName)
            assertNull(repository.lastError.value)

            repository.setAddonEnabled(installed.id, false)
            assertFalse(assertIs<AddonsState.Ready>(repository.state.value)
                .addons.single { it.id == installed.id }.enabled)

            repository.removeAddon(installed.id)
            assertTrue(assertIs<AddonsState.Ready>(repository.state.value)
                .addons.none { it.source == "stremio" })
            scope.cancel()
        }
        http.close()
    }

    @Test
    fun `active profile changes reload the visible addon list`() = runTest {
        val http = providerHttpClient()
        val directory = Files.createTempDirectory("cove-local-addon-profiles")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, directory) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            var sequence = 0
            val profiles = LocalProfileRepository(
                store.database,
                session,
                { "profile-${++sequence}" },
                { "now" },
            )
            val repository = LocalAddonRepository(
                addons = AddonManager(store.database, session, http, { "now" }),
                activeProfileIds = session.profileId,
                scope = scope,
            )
            advanceUntilIdle()
            repository.addAddon("https://addon.test")

            val child = profiles.create("Child")
            profiles.activate(child.id)
            advanceUntilIdle()
            assertTrue(assertIs<AddonsState.Ready>(repository.state.value)
                .addons.none { it.source == "stremio" })

            profiles.activate("primary")
            advanceUntilIdle()
            assertEquals("provider.one", assertIs<AddonsState.Ready>(repository.state.value)
                .addons.single { it.source == "stremio" }.id)
            scope.cancel()
        }
        http.close()
    }

    // The two fields AddonManagerTest cannot see, because both are added on the
    // way out of the backend rather than in it.
    // Mutation applied to verify: dropped `managed = managed` from toSharedModel
    // → test failed, the inherited addon reached the UI as an ordinary editable
    // row; dropped `addons.sharing()` from AddonsState.Ready → test failed with
    // the default AddonSharing() and no primary to name.
    @Test
    fun `an inherited addon reaches the UI marked and explained`() = runTest {
        val http = providerHttpClient()
        val directory = Files.createTempDirectory("cove-local-addon-shared")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, directory) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            var sequence = 0
            val profiles = LocalProfileRepository(
                store.database,
                session,
                { "profile-${++sequence}" },
                { "now" },
            )
            val repository = LocalAddonRepository(
                addons = AddonManager(store.database, session, http, { "now" }),
                activeProfileIds = session.profileId,
                scope = scope,
            )
            advanceUntilIdle()
            repository.addAddon("https://addon.test")
            store.database.coveQueries.upsertSettings(
                "primary",
                CoveJson.encodeToString(AppSettings(addonsFollowPrimary = true)),
                "now",
            )

            val child = profiles.create("Child")
            profiles.activate(child.id)
            advanceUntilIdle()

            val state = assertIs<AddonsState.Ready>(repository.state.value)
            val inherited = state.addons.single { it.source == "stremio" }
            assertEquals("provider.one", inherited.id)
            assertTrue(inherited.managed, "the UI has no other way to know it is read-only")
            assertTrue(state.sharing.enabled)
            assertFalse(state.sharing.editable)
            // Named, because the locked card is titled after it.
            assertEquals("Primary", state.sharing.primaryName)

            profiles.activate("primary")
            advanceUntilIdle()
            val own = assertIs<AddonsState.Ready>(repository.state.value)
            assertFalse(own.addons.single { it.source == "stremio" }.managed)
            assertTrue(own.sharing.editable, "only the primary is offered the switch")
            scope.cancel()
        }
        http.close()
    }

    @Test
    fun `bad manifest input is reported without discarding the current list`() = runTest {
        val http = providerHttpClient()
        val directory = Files.createTempDirectory("cove-local-addon-errors")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, directory) { "primary" }.importIfNeeded()
            val session = ActiveProfileSession(store.database)
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val repository = LocalAddonRepository(
                addons = AddonManager(store.database, session, http, { "now" }),
                activeProfileIds = session.profileId,
                scope = scope,
            )
            advanceUntilIdle()
            val before = assertIs<AddonsState.Ready>(repository.state.value)

            repository.addAddon("http://localhost/manifest.json")

            assertEquals(before, repository.state.value)
            assertTrue(repository.lastError.value.orEmpty().contains("public host"))
            scope.cancel()
        }
        http.close()
    }

    @Test
    fun `bare addon sync payload from an older mobile build is imported`() = runTest {
        val http = providerHttpClient()
        val directory = Files.createTempDirectory("cove-local-addon-mobile-upgrade")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, directory) { "primary" }.importIfNeeded()
            store.database.coveQueries.upsertLegacyPayload(
                profile_id = "primary",
                kind = "addons",
                json = """[{"id":"synced.provider","url":"https://synced.test","manifest":{"id":"synced.provider","name":"Synced Provider","resources":["stream"]},"kind":"provider"}]""",
                updated_at = "2026-08-11T12:00:00Z",
            )
            val session = ActiveProfileSession(store.database)
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val repository = LocalAddonRepository(
                addons = AddonManager(store.database, session, http, { "now" }),
                activeProfileIds = session.profileId,
                scope = scope,
            )

            advanceUntilIdle()

            val synced = assertIs<AddonsState.Ready>(repository.state.value)
                .addons.single { it.source == "stremio" }
            assertEquals("synced.provider", synced.id)
            assertEquals("Synced Provider", synced.displayName)
            scope.cancel()
        }
        http.close()
    }

    private fun providerHttpClient() = HttpClient(MockEngine { request ->
        require(request.url.encodedPath.endsWith("/manifest.json")) {
            "unexpected URL ${request.url}"
        }
        respond(
            """{"id":"provider.one","name":"Provider One","resources":["stream"]}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })
}
