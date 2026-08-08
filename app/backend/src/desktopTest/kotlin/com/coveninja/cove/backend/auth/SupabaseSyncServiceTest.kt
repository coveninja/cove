package com.coveninja.cove.backend.auth

import com.coveninja.cove.backend.addons.AddonManager
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

    private suspend fun fixture(
        remote: (HttpRequestData, String) -> String,
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
                    addons,
                    now,
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
