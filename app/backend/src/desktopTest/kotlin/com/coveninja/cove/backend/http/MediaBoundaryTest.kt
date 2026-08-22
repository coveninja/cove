package com.coveninja.cove.backend.http

import com.coveninja.cove.backend.addons.AddonStream
import com.coveninja.cove.backend.addons.AddonUrlPolicy
import com.coveninja.cove.backend.torrent.TorrentPlaybackEngine
import com.coveninja.cove.backend.torrent.TorrentProgress
import com.coveninja.cove.backend.torrent.TorrentResource
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MediaBoundaryTest {
    @Test
    fun `only registered direct streams redirect`() = runBlocking {
        val boundary = boundary(HttpClient(MockEngine { error("upstream should not be called") }))
        boundary.registerStreams(listOf(AddonStream(url = "https://video.test/movie.mkv")))

        testApplication {
            application { mediaRoutes(boundary) }
            val noRedirectClient = createClient { followRedirects = false }
            val known = noRedirectClient.get("/play?url=https%3A%2F%2Fvideo.test%2Fmovie.mkv")
            assertEquals(HttpStatusCode.TemporaryRedirect, known.status)
            assertEquals("https://video.test/movie.mkv", known.headers[HttpHeaders.Location])

            val unknown = noRedirectClient.get("/play?url=https%3A%2F%2Fvideo.test%2Funknown.mkv")
            assertEquals(HttpStatusCode.Forbidden, unknown.status)
        }
    }

    @Test
    fun `header-bearing streams are proxied with range semantics`() = runBlocking {
        var referer = ""
        var range = ""
        val upstream = HttpClient(MockEngine { request ->
            referer = request.headers[HttpHeaders.Referrer].orEmpty()
            range = request.headers[HttpHeaders.Range].orEmpty()
            respond(
                content = "bytes",
                status = HttpStatusCode.PartialContent,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("video/mp4"),
                    HttpHeaders.ContentRange to listOf("bytes 0-4/5"),
                    HttpHeaders.ContentLength to listOf("5"),
                ),
            )
        })
        val boundary = boundary(upstream)
        boundary.registerStreams(
            listOf(
                AddonStream(
                    url = "https://video.test/movie.mp4",
                    headers = mapOf(HttpHeaders.Referrer to "https://provider.test/"),
                ),
            ),
        )

        testApplication {
            application { mediaRoutes(boundary) }
            val response = client.get("/play?url=https%3A%2F%2Fvideo.test%2Fmovie.mp4") {
                headers.append(HttpHeaders.Range, "bytes=0-4")
            }
            assertEquals(HttpStatusCode.PartialContent, response.status)
            assertEquals("bytes", response.bodyAsText())
            assertEquals("bytes 0-4/5", response.headers[HttpHeaders.ContentRange])
        }
        assertEquals("https://provider.test/", referer)
        assertEquals("bytes=0-4", range)
    }

    @Test
    fun `TMDB images are cached after the first request`() = runBlocking {
        var fetches = 0
        val bytes = byteArrayOf(1, 2, 3, 4)
        val upstream = HttpClient(MockEngine {
            fetches++
            respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        })
        val boundary = boundary(upstream)

        testApplication {
            application { mediaRoutes(boundary) }
            assertContentEquals(bytes, client.get("/img/w500/poster.jpg").body<ByteArray>())
            client.get("/img/w500/poster.jpg")
        }
        assertEquals(1, fetches)
    }

    @Test
    fun `torrent playback honors a single HTTP byte range`() = runBlocking {
        val payload = "0123456789".encodeToByteArray()
        var streamed = false
        val engine = object : TorrentPlaybackEngine {
            override suspend fun open(hash: String, season: Int?, episode: Int?, fileIndex: Int?) =
                TorrentResource("resource", "movie.mkv", payload.size.toLong(), "video/x-matroska")

            override suspend fun write(
                resource: TorrentResource,
                start: Long,
                endInclusive: Long,
                output: ByteWriteChannel,
            ) {
                error("the delayed response producer must reopen through stream")
            }

            override suspend fun stream(
                hash: String,
                season: Int?,
                episode: Int?,
                fileIndex: Int?,
                start: Long,
                endInclusive: Long,
                output: ByteWriteChannel,
            ) {
                streamed = true
                val slice = payload.copyOfRange(start.toInt(), endInclusive.toInt() + 1)
                output.writeFully(slice)
            }

            override fun progress(hash: String) = TorrentProgress(hash, 0, 4, 10, 100, 2, false)
            override fun close() = Unit
        }
        val boundary = MediaBoundary(
            httpClient = HttpClient(MockEngine { error("upstream should not be called") }),
            imageCacheDirectory = Files.createTempDirectory("cove-images"),
            publicUrlPolicy = AddonUrlPolicy { },
            allowLanStreamSources = { false },
            torrentEngine = engine,
        )

        testApplication {
            application {
                routing {
                    get("/torrent") { boundary.playTorrent(call, "a".repeat(40), null, null, null) }
                }
            }
            val response = client.get("/torrent") { headers.append(HttpHeaders.Range, "bytes=2-5") }
            assertEquals(HttpStatusCode.PartialContent, response.status)
            assertEquals("bytes 2-5/10", response.headers[HttpHeaders.ContentRange])
            assertEquals("2345", response.bodyAsText())
        }
        assertTrue(streamed)
    }

    @Test
    fun `stream probes validate every redirect before following it`() = runBlocking {
        var requests = 0
        val upstream = HttpClient(MockEngine {
            requests++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://127.0.0.1/private"))
        })
        val boundary = MediaBoundary(
            httpClient = upstream,
            imageCacheDirectory = Files.createTempDirectory("cove-images"),
            publicUrlPolicy = AddonUrlPolicy { url ->
                require("127.0.0.1" !in url) { "private redirect" }
            },
            allowLanStreamSources = { false },
        )
        boundary.registerStreams(listOf(AddonStream(url = "https://video.test/movie.mkv")))

        val result = boundary.probe(ProbeStreamsRequest(listOf(ProbeStreamRequest("https://video.test/movie.mkv"))))

        assertFalse(result.results.single().alive)
        assertEquals(1, requests)
    }

    @Test
    fun `cross origin redirects do not receive authentication headers`() = runBlocking {
        var leakedAuthorization = ""
        var leakedCookie = ""
        val upstream = HttpClient(MockEngine { request ->
            if (request.url.host == "video.test") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.test/movie.mp4"))
            } else {
                leakedAuthorization = request.headers[HttpHeaders.Authorization].orEmpty()
                leakedCookie = request.headers[HttpHeaders.Cookie].orEmpty()
                respond("bytes", HttpStatusCode.OK)
            }
        }) { followRedirects = false }
        val boundary = boundary(upstream)
        boundary.registerStreams(listOf(AddonStream(
            url = "https://video.test/movie.mp4",
            headers = mapOf(
                HttpHeaders.Authorization to "Bearer secret",
                HttpHeaders.Cookie to "session=secret",
            ),
        )))

        testApplication {
            application { mediaRoutes(boundary) }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/play?url=https%3A%2F%2Fvideo.test%2Fmovie.mp4").status,
            )
        }
        assertEquals("", leakedAuthorization)
        assertEquals("", leakedCookie)
    }

    @Test
    fun `subtitle proxy converts srt to webvtt`() = runBlocking {
        val upstream = HttpClient(MockEngine {
            respond("1\n00:00:01,250 --> 00:00:03,000\nHello\n", HttpStatusCode.OK)
        })
        val boundary = boundary(upstream)

        testApplication {
            application { mediaRoutes(boundary) }
            val response = client.get("/subtitle?url=https%3A%2F%2Fsubs.test%2Fmovie.srt")
            assertTrue(response.bodyAsText().startsWith("WEBVTT"))
            assertTrue(response.bodyAsText().contains("00:00:01.250 --> 00:00:03.000"))
        }
    }

    private fun boundary(client: HttpClient) = MediaBoundary(
        httpClient = client,
        imageCacheDirectory = Files.createTempDirectory("cove-images"),
        publicUrlPolicy = AddonUrlPolicy { },
        allowLanStreamSources = { false },
    )

    private fun io.ktor.server.application.Application.mediaRoutes(boundary: MediaBoundary) {
        install(ContentNegotiation) { json(CoveJson) }
        routing {
            get("/play") { boundary.playDirect(call, requireNotNull(call.request.queryParameters["url"])) }
            get("/img/{size}/{file}") {
                boundary.image(call, requireNotNull(call.parameters["size"]), requireNotNull(call.parameters["file"]))
            }
            get("/subtitle") {
                boundary.subtitle(call, requireNotNull(call.request.queryParameters["url"]))
            }
        }
    }
}
