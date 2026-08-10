package com.coveninja.cove.backend.http

import com.coveninja.cove.backend.auth.ClientSessionStore
import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.migration.LegacyMigration
import com.coveninja.cove.backend.platform.DeviceSettingsService
import com.coveninja.cove.backend.quality.QualityEntry
import com.coveninja.cove.backend.quality.QualityLookup
import com.coveninja.cove.backend.updater.UpdateService
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalProfileRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.CatalogSort
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaDetails
import com.coveninja.cove.shared.model.MediaGenre
import com.coveninja.cove.shared.model.MediaImages
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.MediaVideos
import com.coveninja.cove.shared.model.TvEpisode
import com.coveninja.cove.shared.model.TvSeason
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.shared.network.CoveJson
import com.coveninja.cove.shared.network.SearchResultsDto
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.builtins.serializer

class CoreRoutesTest {
    @Test
    fun versionedAndLegacyRoutesShareServicesButOnlyLegacyIsDeprecated() {
        fixture { services ->
            testApplication {
                application { configureCoreRoutes(services) }

                val legacy = client.get("/api/ping")
                assertEquals(HttpStatusCode.OK, legacy.status)
                assertEquals("true", legacy.headers["Deprecation"])
                assertTrue(legacy.bodyAsText().contains("kotlin"))

                val versioned = client.get("/api/v1/ping")
                assertEquals(HttpStatusCode.OK, versioned.status)
                assertEquals(null, versioned.headers["Deprecation"])
            }
        }
    }

    @Test
    fun settingsProfilesAndLibraryRoundTripThroughV1() {
        fixture { services ->
            testApplication {
                application { configureCoreRoutes(services) }

                val settings = AppSettings(defaultVolume = 0.35, remoteAccessEnabled = true)
                val update = client.put("/api/v1/settings") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody(CoveJson.encodeToString(AppSettings.serializer(), settings))
                }
                assertEquals(HttpStatusCode.OK, update.status)
                val saved = CoveJson.decodeFromString(AppSettings.serializer(), update.bodyAsText())
                assertEquals(0.35, saved.defaultVolume)
                assertEquals("***", saved.remoteAccessToken)
                assertTrue(client.post("/api/v1/settings/reveal-token").bodyAsText().contains("token"))

                val mpv = "hwdec=auto-safe\n"
                assertEquals(HttpStatusCode.NoContent, client.put("/api/v1/settings/mpv-conf") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody(CoveJson.encodeToString(String.serializer(), mpv))
                }.status)
                assertEquals(mpv, CoveJson.decodeFromString(
                    String.serializer(),
                    client.get("/api/v1/settings/mpv-conf").bodyAsText(),
                ))

                val createdProfile = client.post("/api/v1/profiles") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"name":"Child"}""")
                }
                assertEquals(HttpStatusCode.Created, createdProfile.status)
                assertTrue(createdProfile.bodyAsText().contains("Child"))

                val add = client.post("/api/v1/library") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"tmdb_id":42,"media_type":"movie","title":"Movie","status":"watch_later"}""")
                }
                assertEquals(HttpStatusCode.Created, add.status)
                assertEquals("Movie", CoveJson.decodeFromString(LibraryEntry.serializer(), add.bodyAsText()).title)

                val progress = client.post("/api/v1/library/progress") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody(
                        """{"tmdb_id":42,"media_type":"movie","title":"Movie","position_seconds":30.0,"duration_seconds":90.0,"completed":false}""",
                    )
                }
                assertEquals(HttpStatusCode.OK, progress.status)
                assertEquals(
                    30.0,
                    CoveJson.decodeFromString(WatchProgress.serializer(), progress.bodyAsText()).positionSeconds,
                )
                val fetchedProgress = client.get("/api/v1/library/progress?tmdb_id=42&media_type=movie")
                assertEquals(30.0, CoveJson.decodeFromString(WatchProgress.serializer(), fetchedProgress.bodyAsText()).positionSeconds)

                val detail = client.get("/api/v1/library/42/movie")
                assertTrue(detail.bodyAsText().contains("position_seconds"))

                val listed = client.get("/api/v1/library")
                assertTrue(listed.bodyAsText().contains("Movie"))
                val removed = client.delete("/api/v1/library/42/movie")
                assertEquals(HttpStatusCode.NoContent, removed.status)
                assertFalse(client.get("/api/v1/library").bodyAsText().contains("Movie"))
                assertEquals(
                    30.0,
                    CoveJson.decodeFromString(
                        WatchProgress.serializer(),
                        client.get("/api/v1/library/progress?tmdb_id=42&media_type=movie").bodyAsText(),
                    ).positionSeconds,
                )
            }
        }
    }

    @Test
    fun clientSessionCompatibilityRouteIsDurableBoundedAndNeverCached() {
        fixture { services ->
            testApplication {
                application { configureCoreRoutes(services) }

                val missing = client.get("/api/v1/client-session")
                assertEquals(HttpStatusCode.NotFound, missing.status)
                assertEquals("no-store", missing.headers[HttpHeaders.CacheControl])

                val saved = client.post("/api/v1/client-session") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("""{"access_token":"secret","profile_id":"primary"}""")
                }
                assertEquals(HttpStatusCode.NoContent, saved.status)
                val loaded = client.get("/api/v1/client-session")
                assertEquals(HttpStatusCode.OK, loaded.status)
                assertTrue(loaded.bodyAsText().contains("secret"))
                assertEquals("no-store", loaded.headers[HttpHeaders.CacheControl])

                val oversized = client.post("/api/v1/client-session") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody("\"${"x".repeat((1 shl 20) + 1)}\"")
                }
                assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
                assertTrue(client.get("/api/v1/client-session").bodyAsText().contains("secret"))

                assertEquals(
                    HttpStatusCode.NoContent,
                    client.delete("/api/v1/client-session").status,
                )
                assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/client-session").status)
            }
        }
    }

    @Test
    fun remoteSurfaceFailsClosedAndAcceptsOnlyTheCurrentToken() {
        fixture { services ->
            testApplication {
                application { configureCoreRoutes(services, ApiAccess.Remote) }

                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/ping").status)
                services.settings.update(
                    services.settings.current().copy(remoteAccessEnabled = true),
                )
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/ping").status)
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/ping") {
                    header("X-Cove-Token", "wrong")
                }.status)
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/ping") {
                    header("X-Cove-Token", "token")
                }.status)
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/ping?token=token").status)

                services.settings.update(
                    services.settings.current().copy(remoteAccessEnabled = false),
                )
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/ping") {
                    header("X-Cove-Token", "token")
                }.status)
            }
        }
    }

    @Test
    fun compatibilityQualityAndUpdaterRoutesKeepStableWireContracts() {
        fixture { base ->
            val services = base.copy(
                quality = QualityLookup { flowOf(QualityEntry("movie:42", "1080p")) },
                updater = UpdateService("v0.31.3"),
            )
            testApplication {
                application { configureCoreRoutes(services) }

                val quality = client.get("/api/v1/quality/batch?ids=movie:42")
                assertEquals(HttpStatusCode.OK, quality.status)
                assertTrue(quality.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/x-ndjson"))
                assertEquals("{\"id\":\"movie:42\",\"quality\":\"1080p\"}\n", quality.bodyAsText())

                val update = client.get("/api/v1/update/check")
                assertEquals(HttpStatusCode.OK, update.status)
                assertTrue(update.bodyAsText().contains("\"current_version\":\"v0.31.3\""))
                assertEquals(HttpStatusCode.Conflict, client.post("/api/v1/update/apply").status)
            }
        }
    }

    // Browsing is the one catalog route whose whole job is translating a query string into
    // catalog arguments, so what is worth asserting is the translation, not the payload.
    @Test
    fun browseMapsQueryParametersOntoTheCatalog() {
        val catalog = RecordingCatalog()
        fixture(catalog) { services ->
            testApplication {
                application { configureCoreRoutes(services) }

                assertEquals(
                    HttpStatusCode.OK,
                    client.get("/api/v1/browse?type=tv&genre=18&sort=rating&page=3").status,
                )
                assertEquals(MediaType.Tv, catalog.lastType)
                assertEquals(18, catalog.lastGenre)
                assertEquals(CatalogSort.Rating, catalog.lastSort)
                assertEquals(3, catalog.lastPage)

                // An unreadable or absent sort falls back rather than failing the request:
                // a client asking for an order this build does not have should still get
                // titles back, not a 400.
                assertEquals(
                    HttpStatusCode.OK,
                    client.get("/api/v1/browse?type=movie&sort=nonsense").status,
                )
                assertEquals(MediaType.Movie, catalog.lastType)
                assertEquals(CatalogSort.Popularity, catalog.lastSort)
                assertEquals(null, catalog.lastGenre)
                assertEquals(1, catalog.lastPage)

                // The genre vocabulary moved out of the TmdbClient-only block when it
                // joined MediaCatalog, so it has to answer for a plain catalog too.
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/genres?type=tv").status)
            }
        }
    }

    private fun fixture(
        catalog: com.coveninja.cove.backend.content.MediaCatalog? = null,
        test: (CoreRouteServices) -> Unit,
    ) {
        val dir = Files.createTempDirectory("cove-routes")
        DesktopDatabase.inMemory().use { database ->
            LegacyMigration(database.database, dir) { "primary" }.importIfNeeded()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val session = ActiveProfileSession(database.database)
                var id = 0
                val ids = { "id-${++id}" }
                test(
                    CoreRouteServices(
                        profiles = LocalProfileRepository(database.database, session, ids) { "now" },
                        settings = LocalSettingsRepository(database.database, session, scope, { "now" }) { "token" },
                        library = LocalLibraryRepository(database.database, session, scope, ids) { "now" },
                        clientSessions = ClientSessionStore(database.database) { "now" },
                        deviceSettings = DeviceSettingsService(dir),
                        catalog = catalog,
                    ),
                )
            } finally {
                scope.cancel()
            }
        }
    }
}

private class RecordingCatalog : com.coveninja.cove.backend.content.MediaCatalog {
    var lastType: MediaType? = null
    var lastGenre: Int? = null
    var lastSort: CatalogSort? = null
    var lastPage: Int? = null

    override suspend fun discoverFiltered(
        type: MediaType,
        genreId: Int?,
        keywordId: Int?,
        personId: Int?,
        sort: CatalogSort,
        page: Int,
    ): List<Media> {
        lastType = type
        lastGenre = genreId
        lastSort = sort
        lastPage = page
        return listOf(Media(id = 1, title = "Recorded", mediaType = type))
    }

    override suspend fun genres(type: MediaType) = listOf(MediaGenre(18, "Drama"))
    override suspend fun discover(type: MediaType, limit: Int) = emptyList<Media>()
    override suspend fun searchMulti(query: String) = SearchResultsDto()
    override suspend fun media(id: Int, type: MediaType) = Media(id, mediaType = type)
    override suspend fun details(id: Int, type: MediaType) = MediaDetails()
    override suspend fun images(id: Int, type: MediaType) = MediaImages()
    override suspend fun videos(id: Int, type: MediaType) = MediaVideos()
    override suspend fun similar(id: Int, type: MediaType) = emptyList<Media>()
    override suspend fun seasons(id: Int) = emptyList<TvSeason>()
    override suspend fun episodes(id: Int, season: Int) = emptyList<TvEpisode>()
    override suspend fun imdbId(id: Int, type: MediaType) = "tt$id"
}
