package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.network.CoveApi
import com.coveninja.cove.shared.network.CoveApiConfig
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun MockRequestHandleScope.jsonResponse(body: String) = respond(
    content = body,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private fun repositoryWith(
    onRequest: (HttpRequestData) -> Unit = {},
    body: String = "[]",
): LivePlaybackRepository {
    val client = HttpClient(MockEngine { request ->
        onRequest(request)
        jsonResponse(body)
    }) {
        install(ContentNegotiation) { json(CoveJson) }
    }
    return LivePlaybackRepository(CoveApi(client, CoveApiConfig("http://127.0.0.1:6969")))
}

class LivePlaybackRepositoryTest {

    @Test
    fun `tv stream lookup sends season and episode`() = runTest {
        var captured: HttpRequestData? = null
        val repository = repositoryWith(onRequest = { captured = it })

        repository.streams(tmdbId = 1396, type = MediaType.Tv, season = 3, episode = 7)

        val parameters = assertNotNull(captured, "MockEngine received no request").url.parameters
        assertEquals("1396", parameters["id"])
        assertEquals("tv", parameters["type"])
        assertEquals("3", parameters["season"])
        assertEquals("7", parameters["episode"])
    }

    @Test
    fun `movie stream lookup omits season and episode`() = runTest {
        var captured: HttpRequestData? = null
        val repository = repositoryWith(onRequest = { captured = it })

        repository.streams(tmdbId = 550, type = MediaType.Movie)

        val parameters = assertNotNull(captured, "MockEngine received no request").url.parameters
        assertEquals("550", parameters["id"])
        assertEquals("movie", parameters["type"])
        assertEquals(null, parameters["season"])
        assertEquals(null, parameters["episode"])
    }

    @Test
    fun `playUrl prefers a direct url over an info hash`() {
        val repository = repositoryWith()
        val source = StreamSource(
            url = "https://cdn.example.com/movie.mkv",
            infoHash = "a".repeat(40),
        )

        val url = repository.playUrl(source)

        assertTrue(url.startsWith("http://127.0.0.1:6969/api/play?url="), "was: $url")
        assertTrue("hash=" !in url, "direct playback must not carry a hash: $url")
    }

    @Test
    fun `playUrl carries season episode and file index for a torrent`() {
        val repository = repositoryWith()
        val source = StreamSource(infoHash = "b".repeat(40), fileIdx = 4)

        val url = repository.playUrl(source, season = 2, episode = 5)

        assertTrue(url.contains("hash=${"b".repeat(40)}"), "was: $url")
        assertTrue(url.contains("season=2"), "was: $url")
        assertTrue(url.contains("episode=5"), "was: $url")
        assertTrue(url.contains("fileIdx=4"), "was: $url")
    }

    // A direct URL already points at one file, and the backend's playDirect ignores
    // these, so appending them would only make the registry lookup key noisier.
    @Test
    fun `playUrl omits episode coordinates for a direct url`() {
        val repository = repositoryWith()
        val source = StreamSource(url = "https://cdn.example.com/s02e05.mkv")

        val url = repository.playUrl(source, season = 2, episode = 5)

        assertTrue("season=" !in url, "was: $url")
        assertTrue("episode=" !in url, "was: $url")
    }

    @Test
    fun `playUrl rejects a source with nothing to play`() {
        val repository = repositoryWith()

        assertFailsWith<IllegalArgumentException> {
            repository.playUrl(StreamSource(name = "Broken", url = "", infoHash = ""))
        }
    }

    @Test
    fun `unavailable playback reports why rather than failing later`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            UnavailablePlaybackRepository.streams(550, MediaType.Movie)
        }
        assertTrue(
            error.message.orEmpty().contains("HTTP host"),
            "message should name the missing host, was: ${error.message}",
        )
    }
}
