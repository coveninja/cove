package com.coveninja.cove.backend.addons

import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.backend.db.DesktopDatabase
import com.coveninja.cove.backend.migration.LegacyMigration
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.shared.model.CatalogSort
import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaDetails
import com.coveninja.cove.shared.model.MediaGenre
import com.coveninja.cove.shared.model.MediaImages
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.MediaVideos
import com.coveninja.cove.shared.model.PersonDetails
import com.coveninja.cove.shared.model.TvEpisode
import com.coveninja.cove.shared.model.TvSeason
import com.coveninja.cove.shared.network.SearchResultsDto
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

private const val MANIFEST = """
{"id":"provider.one","name":"Provider One","resources":["stream","catalog"],
 "types":["movie"],
 "catalogs":[
   {"type":"movie","id":"popular","name":"Popular"},
   {"type":"movie","id":"search","name":"Search","extra":[{"name":"search","isRequired":true}]}
 ]}
"""

class AddonCatalogServiceTest {

    /**
     * The row is drawn from resolved media, and drawing it twice must not re-resolve it —
     * every entry costs a metadata request, which is the whole reason the cache exists.
     */
    @Test
    fun `resolves a catalog page once and serves the repeat from cache`() = runBlocking {
        val requests = mutableListOf<String>()
        val http = mockAddon(requests)
        val dir = Files.createTempDirectory("cove-catalog")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val manager = AddonManager(
                store.database,
                ActiveProfileSession(store.database),
                http,
                { "now" },
            )
            manager.add("https://addon.test/manifest.json")
            val catalog = RecordingResolver()
            val service = AddonCatalogService(manager, catalog)

            val first = service.page("provider.one", null, "movie", "popular", skip = 0, limit = 20)
            assertEquals(listOf(11, 22), first.medias.map(Media::id))
            assertEquals(1, requests.count { "/catalog/movie/popular" in it })
            assertEquals(2, catalog.resolved)

            val second = service.page("provider.one", null, "movie", "popular", skip = 0, limit = 20)
            assertEquals(first, second)
            assertEquals(
                1,
                requests.count { "/catalog/movie/popular" in it },
                "a cached page must not reach the addon again",
            )
            assertEquals(2, catalog.resolved, "a cached page must not re-resolve its entries")
        }
        http.close()
    }

    /**
     * Every addon mutation bumps the profile's addon store version, which is folded into
     * the cache key. Without that the viewer would disable a catalog and keep seeing its
     * row for the next quarter of an hour.
     */
    @Test
    fun `an addon mutation invalidates the cached page`() = runBlocking {
        val requests = mutableListOf<String>()
        val http = mockAddon(requests)
        val dir = Files.createTempDirectory("cove-catalog-bust")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val manager = AddonManager(
                store.database,
                ActiveProfileSession(store.database),
                http,
                // A mutation has to move the version, so the clock has to move with it.
                object : () -> String {
                    private var tick = 0
                    override fun invoke(): String = "now-${tick++}"
                },
            )
            manager.add("https://addon.test/manifest.json")
            val service = AddonCatalogService(manager, RecordingResolver())

            service.page("provider.one", null, "movie", "popular", skip = 0, limit = 20)
            assertEquals(1, requests.count { "/catalog/movie/popular" in it })

            manager.setCatalogEnabled("provider.one", null, "movie/popular", enabled = false)

            service.page("provider.one", null, "movie", "popular", skip = 0, limit = 20)
            assertEquals(
                2,
                requests.count { "/catalog/movie/popular" in it },
                "toggling a catalog must not leave the previous page cached",
            )
        }
        http.close()
    }

    /**
     * `nextSkip` counts what the source handed over, not what survived resolution. Paging
     * by the survivors would ask for the dropped entries again on every page and never
     * advance past a run of them.
     */
    @Test
    fun `paging advances past entries that could not be resolved`() = runBlocking {
        val requests = mutableListOf<String>()
        // Three entries, one of them keyed on a scheme nothing can resolve.
        val http = mockAddon(
            requests,
            catalogBody = """
                {"metas":[
                  {"id":"tmdb:11","type":"movie","name":"One"},
                  {"id":"kitsu:999","type":"movie","name":"Unresolvable"},
                  {"id":"tmdb:22","type":"movie","name":"Two"}
                ]}
            """.trimIndent(),
        )
        val dir = Files.createTempDirectory("cove-catalog-skip")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val manager = AddonManager(
                store.database,
                ActiveProfileSession(store.database),
                http,
                { "now" },
            )
            manager.add("https://addon.test/manifest.json")
            val service = AddonCatalogService(manager, RecordingResolver())

            val page = service.page("provider.one", null, "movie", "popular", skip = 0, limit = 20)
            assertEquals(listOf(11, 22), page.medias.map(Media::id), "unresolvable entries are dropped")
            assertEquals(3, page.nextSkip, "skip advances by consumed entries, not resolved ones")
        }
        http.close()
    }

    /**
     * A catalog that cannot answer without the viewer typing something first is not a row.
     * The manifest above offers one such catalog alongside an ordinary one.
     */
    @Test
    fun `only catalogs that need no input are offered as rows`() = runBlocking {
        val http = mockAddon(mutableListOf())
        val dir = Files.createTempDirectory("cove-catalog-eligible")
        DesktopDatabase.inMemory().use { store ->
            LegacyMigration(store.database, dir) { "primary" }.importIfNeeded()
            val manager = AddonManager(
                store.database,
                ActiveProfileSession(store.database),
                http,
                { "now" },
            )
            manager.add("https://addon.test/manifest.json")
            val service = AddonCatalogService(manager, RecordingResolver())

            val catalogs = service.catalogs()
            assertEquals(listOf("popular"), catalogs.map { it.catalogId })
            assertEquals("movie/popular", catalogs.single().key)
            assertEquals("Provider One", catalogs.single().addonName)

            // ...and one switched off is not offered either.
            manager.setCatalogEnabled("provider.one", null, "movie/popular", enabled = false)
            assertTrue(service.catalogs().isEmpty(), "a disabled catalog is not a row")
        }
        http.close()
    }
}

private fun mockAddon(
    requests: MutableList<String>,
    catalogBody: String = """{"metas":[
        {"id":"tmdb:11","type":"movie","name":"One"},
        {"id":"tmdb:22","type":"movie","name":"Two"}
    ]}""",
) = HttpClient(MockEngine { request ->
    requests += request.url.toString()
    val body = when {
        request.url.encodedPath.endsWith("/manifest.json") -> MANIFEST
        "/catalog/" in request.url.encodedPath -> catalogBody
        else -> error("unexpected URL ${request.url}")
    }
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
})

/** Counts resolutions so the cache can be shown to be doing something. */
private class RecordingResolver : MediaCatalog {
    var resolved = 0
        private set

    override suspend fun media(id: Int, type: MediaType): Media {
        resolved += 1
        return Media(id = id, title = "Title $id", mediaType = type)
    }

    override suspend fun discover(type: MediaType, limit: Int) = emptyList<Media>()
    override suspend fun searchMulti(query: String) = SearchResultsDto()
    override suspend fun details(id: Int, type: MediaType) = MediaDetails()
    override suspend fun images(id: Int, type: MediaType) = MediaImages()
    override suspend fun videos(id: Int, type: MediaType) = MediaVideos()
    override suspend fun similar(id: Int, type: MediaType) = emptyList<Media>()
    override suspend fun seasons(id: Int) = emptyList<TvSeason>()
    override suspend fun episodes(id: Int, season: Int) = emptyList<TvEpisode>()
    override suspend fun imdbId(id: Int, type: MediaType) = "tt$id"
    override suspend fun person(id: Int) = PersonDetails(id = id)
    override suspend fun genres(type: MediaType) = emptyList<MediaGenre>()
    override suspend fun discoverFiltered(
        type: MediaType,
        genreId: Int?,
        keywordId: Int?,
        personId: Int?,
        sort: CatalogSort,
        page: Int,
    ) = emptyList<Media>()
}
