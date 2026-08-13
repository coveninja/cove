package com.coveninja.cove.backend.content

import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TmdbClientTest {
    @Test
    fun `discover localizes fills English gaps and stamps media type`() = runTest {
        val requests = mutableListOf<String>()
        val client = testClient { url ->
            requests += url
            if (url.contains("language=tr-TR")) {
                """{"results":[{"id":7,"title":"","overview":"Yerel ozet","poster_path":"/poster.jpg","popularity":5.0}]}"""
            } else {
                """{"results":[{"id":7,"title":"English title","overview":"English overview","poster_path":"/poster.jpg","popularity":5.0}]}"""
            }
        }

        val result = TmdbClient(client, "secret", { "tr_TR" }, "https://tmdb.test/3")
            .discover(MediaType.Movie)

        assertEquals("English title", result.single().title)
        assertEquals("Yerel ozet", result.single().overview)
        assertEquals(MediaType.Movie, result.single().mediaType)
        assertEquals(2, requests.size)
        assertTrue(requests.all { "api_key=secret" in it })
        assertTrue(requests.any { "language=tr-TR" in it })
        assertTrue(requests.any { "language=en-US" in it })
    }

    @Test
    fun `images request localized and English image languages and expose CDN URLs`() = runTest {
        var requestUrl = ""
        val client = testClient { url ->
            requestUrl = url
            """{"backdrops":[{"file_path":"/back.jpg"}],"logos":[],"posters":[{"file_path":"/poster.jpg"}]}"""
        }

        val result = TmdbClient(client, "secret", { "pt-BR" }, "https://tmdb.test/3")
            .images(8, MediaType.Tv)

        assertTrue("language=pt-BR" in requestUrl)
        assertTrue("include_image_language=pt%2Cen%2Cnull" in requestUrl)
        assertEquals("https://image.tmdb.org/t/p/original/back.jpg", result.backdrops.single().url)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", result.posters.single().url)
    }

    @Test
    fun `videos fall back to English and build YouTube embed URL`() = runTest {
        val client = testClient { url ->
            if (url.contains("language=de-DE")) """{"results":[]}""" else {
                """{"results":[{"name":"Trailer","key":"abc123","site":"YouTube","type":"Trailer"}]}"""
            }
        }

        val result = TmdbClient(client, "secret", { "de" }, "https://tmdb.test/3")
            .videos(9, MediaType.Movie)

        assertEquals("https://www.youtube.com/embed/abc123", result.results.single().embedUrl)
    }

    @Test
    fun `non-success response reports endpoint without leaking API key`() = runTest {
        val client = HttpClient(MockEngine { respond("nope", HttpStatusCode.Unauthorized) })
        val error = kotlin.runCatching {
            TmdbClient(client, "top-secret", baseUrl = "https://tmdb.test/3")
                .media(10, MediaType.Movie)
        }.exceptionOrNull()

        assertTrue(error is TmdbException)
        assertTrue("401" in error.message.orEmpty())
        assertTrue("top-secret" !in error.message.orEmpty())
    }

    @Test
    fun `blank override follows supported system locale`() {
        assertEquals("tr", resolveAppLocale("", "tr-TR"))
        assertEquals("ja", resolveAppLocale("  ", "ja_JP"))
        assertEquals("de", resolveAppLocale("de", "tr-TR"))
        assertEquals("en", resolveAppLocale("", "fr-FR"))
    }

    private fun testClient(response: (String) -> String) = HttpClient(MockEngine { request ->
        respond(
            content = response(request.url.toString()),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }) {
        install(ContentNegotiation) { json(CoveJson) }
    }
}
