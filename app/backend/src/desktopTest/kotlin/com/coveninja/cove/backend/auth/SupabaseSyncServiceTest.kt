package com.coveninja.cove.backend.auth

import com.coveninja.cove.backend.addons.AddonManager
import com.coveninja.cove.backend.addons.AddonSyncPayload
import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.migration.LegacyMigration
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalProfileRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

class SupabaseSyncServiceTest {
    @Test
    fun pullMergesRemoteDataThenPushesThroughUserRls() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        fixture(
            remote = { request, profileId ->
                requests += request
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    "library_entries" -> if (request.method == HttpMethod.Get) """[{
                        "id":"remote-entry","profile_id":"$profileId","tmdb_id":42,"media_type":"movie",
                        "title":"Remote Movie","status":"watch_later","added_at":"2026-08-01T00:00:00Z",
                        "updated_at":"2026-08-02T00:00:00Z"
                    }]""" else "[]"
                    "profile_settings" -> if (request.method == HttpMethod.Get) """[{"data":{
                        "defaultVolume":0.25,"remoteAccessEnabled":true,"remoteAccessToken":"remote-secret",
                        "allowLanStreamSources":true,"updatedAt":"2026-08-02T00:00:00Z"
                    },"updated_at":"2026-08-02T00:00:00Z"}]""" else "[]"
                    else -> "[]"
                }
            },
        ) { graph ->
            graph.settings.update(AppSettings(remoteAccessEnabled = true, remoteAccessToken = "local-secret"))
            val result = graph.sync.reconcileAndSync("user-1", "jwt")

            assertEquals("", result.pushError)
            assertEquals(
                "Remote Movie",
                assertIs<LibraryState.Ready>(graph.library.entries.value).entries.single().title,
            )
            val merged = assertIs<SettingsState.Ready>(graph.settings.settings.value).settings
            assertEquals(0.25, merged.defaultVolume)
            assertTrue(merged.remoteAccessEnabled)
            assertEquals("local-secret", merged.remoteAccessToken)
            assertEquals(
                "user-1",
                assertIs<ProfilesState.Ready>(graph.profiles.profiles.value).profiles.single().supabaseUid,
            )
            assertTrue(requests.filter { it.method == HttpMethod.Post }.isNotEmpty())
            assertTrue(requests.all { request ->
                !request.url.encodedPath.startsWith("/rest/v1/") ||
                    request.headers[HttpHeaders.Authorization] == "Bearer jwt"
            })
        }
    }

    @Test
    fun freshDeviceAdoptsCanonicalRemoteProfileIdWithoutLosingLocalLibrary() = runTest {
        fixture(
            remote = { request, _ ->
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> if (request.method == HttpMethod.Get) {
                        """[{"id":"remote-primary","user_id":"user-1","name":"Remote Name","is_primary":true,"updated_at":"2026-08-02T00:00:00Z"}]"""
                    } else "[]"
                    else -> "[]"
                }
            },
        ) { graph ->
            graph.library.add(7, MediaType.Movie, "Local Movie")
            graph.sync.reconcileAndSync("user-1", "jwt")

            val state = assertIs<ProfilesState.Ready>(graph.profiles.profiles.value)
            assertEquals("remote-primary", state.activeProfileId)
            assertEquals("Remote Name", state.profiles.single().name)
            assertEquals(
                "Local Movie",
                assertIs<LibraryState.Ready>(graph.library.entries.value).entries.single().title,
            )
            assertEquals(
                "remote-primary",
                graph.database.database.coveQueries.selectLibraryEntries("remote-primary")
                    .executeAsOne().profile_id,
            )
        }
    }

    /**
     * The guarantee that makes syncing from Android safe.
     *
     * That host runs no addon manager, so it has no view of the addon list at
     * all. If a sync from there pushed what it knows — nothing — the desktop's
     * configured providers would be wiped on its next pull, and playback would
     * quietly stop finding sources everywhere.
     *
     * Mutation applied to verify: made the pull drop payload kinds with no
     * participant instead of calling persistOpaque → this failed both assertions,
     * because the blob is then neither kept locally nor pushed back.
     */
    @Test
    fun `a host with no addon manager round-trips the addon blob untouched`() = runTest {
        val posted = mutableMapOf<String, String>()
        fixture(
            remote = { request, profileId ->
                if (request.method == HttpMethod.Post) {
                    posted[request.url.encodedPath.substringAfterLast('/')] =
                        (request.body as TextContent).text
                }
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    // Deliberately not a shape this build can parse: the point is
                    // that an unrecognised blob survives a host that cannot read it.
                    "profile_addons" -> if (request.method == HttpMethod.Get) {
                        """[{"profile_id":"$profileId","data":[{"future_addon_field":"keep me"}],"updated_at":"2026-08-09T00:00:00Z"}]"""
                    } else "[]"
                    else -> "[]"
                }
            },
            withAddonParticipant = false,
        ) { graph ->
            graph.sync.reconcileAndSync("user-1", "jwt")

            val stored = graph.database.database.coveQueries
                .selectLegacyPayloadRecord(
                    (graph.profiles.profiles.value as ProfilesState.Ready).activeProfileId,
                    "addons",
                )
                .executeAsOneOrNull()
            assertTrue(
                stored?.json?.contains("future_addon_field") == true,
                "the unreadable addon blob was not kept locally",
            )
            assertTrue(
                posted["profile_addons"]?.contains("future_addon_field") == true,
                "the addon blob was not pushed back; other devices would lose it",
            )
        }
    }

    /**
     * The other half of the same guarantee: with nothing to say about a payload,
     * say nothing.
     *
     * A host with no participant and no stored blob — a phone signing in for the
     * first time, before any device has pushed addons — must not push an empty
     * list. An empty push is indistinguishable from "I deliberately have no
     * addons", and carries a fresh timestamp that beats every other device's.
     *
     * Mutation applied to verify: made pushPayload() fall back to
     * SyncSnapshot("[]", "") instead of returning → this failed, having posted
     * profile_addons with an empty array.
     */
    @Test
    fun `a host with nothing to say about a payload pushes nothing`() = runTest {
        val posted = mutableListOf<String>()
        fixture(
            remote = { request, profileId ->
                if (request.method == HttpMethod.Post) {
                    posted += request.url.encodedPath.substringAfterLast('/')
                }
                when (request.url.encodedPath.substringAfterLast('/')) {
                    "profiles" -> """[{"id":"$profileId","user_id":"user-1","name":"Primary","is_primary":true,"updated_at":"2026-01-01T00:00:00Z"}]"""
                    else -> "[]"
                }
            },
            withAddonParticipant = false,
        ) { graph ->
            graph.sync.reconcileAndSync("user-1", "jwt")

            assertTrue(
                "profile_addons" !in posted,
                "pushed an addon row this host knows nothing about: $posted",
            )
        }
    }

    private suspend fun fixture(
        remote: (HttpRequestData, String) -> String,
        // False models a host that runs no addon manager — Android — where the
        // addon blob has to survive a sync untouched rather than being replaced.
        withAddonParticipant: Boolean = true,
        test: suspend (TestGraph) -> Unit,
    ) {
        val dataDir = Files.createTempDirectory("cove-supabase-sync")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dataDir) { "local-primary" }.importIfNeeded()
            val profileId = database.database.coveQueries.selectActiveProfileId().executeAsOne()
            val http = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        respond(
                            remote(request, profileId),
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                var id = 0
                val ids = { "id-${++id}" }
                val now = { "2026-08-01T00:00:00Z" }
                val session = ActiveProfileSession(database.database)
                val profiles = LocalProfileRepository(database.database, session, ids, now)
                val library = LocalLibraryRepository(database.database, session, scope, ids, now)
                val settings = LocalSettingsRepository(database.database, session, scope, now) { "device-token" }
                val addons = AddonManager(database.database, session, http, now)
                val client = SupabaseClient(SupabaseConfig("https://project.invalid", "anon"), http)
                val sync = SupabaseSyncService(
                    client,
                    database.database,
                    profiles,
                    library,
                    settings,
                    now,
                    if (withAddonParticipant) listOf(AddonSyncPayload(addons)) else emptyList(),
                )
                test(TestGraph(database, profiles, library, settings, sync))
            } finally {
                scope.cancel()
                http.close()
            }
        }
    }

    private data class TestGraph(
        val database: DesktopDatabase,
        val profiles: LocalProfileRepository,
        val library: LocalLibraryRepository,
        val settings: LocalSettingsRepository,
        val sync: SupabaseSyncService,
    )
}
