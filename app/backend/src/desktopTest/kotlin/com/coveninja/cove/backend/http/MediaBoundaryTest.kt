package com.coveninja.cove.backend.http

import com.coveninja.cove.backend.addons.AddonStream
import com.coveninja.cove.backend.addons.AddonUrlPolicy
import com.coveninja.cove.backend.torrent.TorrentPlaybackEngine
import com.coveninja.cove.backend.torrent.TorrentProgress
import com.coveninja.cove.backend.torrent.TorrentResource
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.headersOf
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeFully
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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

    @Test
    fun `direct playback reaches the client while the upstream body is still arriving`() = runBlocking {
        // The proxy must stream the upstream body, not receive it. `httpClient.get()` finishes
        // the call before it returns, and Ktor's SaveBody plugin has replayed the whole body
        // through a ByteChannelReplay by then — resident before any of it is forwarded, and here
        // the body is the video, since mpv opens every stream with `Range: bytes=0-`. Nothing in
        // this test measures memory; it pins the observable half of the same property. The
        // upstream is held open after the first 64 KiB, so a proxy that waits for a complete
        // response forwards nothing and this test hangs until the timeout fires.
        val head = ByteArray(64 * 1024) { 'a'.code.toByte() }
        val tail = ByteArray(16 * 1024) { 'b'.code.toByte() }
        val body = ByteChannel(autoFlush = true)
        val upstream = HttpClient(MockEngine {
            respond(
                body,
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf("video/mp4"),
                    HttpHeaders.ContentLength to listOf((head.size + tail.size).toString()),
                ),
            )
        })
        val boundary = boundary(upstream)
        boundary.registerStreams(
            listOf(
                AddonStream(
                    url = "https://video.test/big.mp4",
                    headers = mapOf(HttpHeaders.Referrer to "https://provider.test/"),
                ),
            ),
        )

        // A real socket, not testApplication: its in-memory transport does not hand the client
        // a response until the server has finished producing one, which is exactly the thing
        // under test here.
        val server = embeddedServer(ServerCIO, port = 0) { mediaRoutes(boundary) }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(ClientCIO)
        val headRead = CompletableDeferred<Unit>()
        // Fed from its own coroutine so the upstream can stay open across the assertion below.
        val writer = launch(Dispatchers.IO) {
            body.writeFully(head)
            body.flush()
            headRead.await() // the response must be flowing while the upstream is still open
            body.writeFully(tail)
            body.flushAndClose()
        }
        try {
            withTimeout(STREAMING_TIMEOUT_MILLIS) {
                client.prepareGet("http://127.0.0.1:$port/play?url=https%3A%2F%2Fvideo.test%2Fbig.mp4")
                    .execute { response ->
                        assertEquals(HttpStatusCode.OK, response.status)
                        val incoming = response.bodyAsChannel()
                        assertContentEquals(head, incoming.readByteArray(head.size))
                        headRead.complete(Unit)
                        assertContentEquals(tail, incoming.readByteArray(tail.size))
                    }
            }
            writer.join()
        } finally {
            headRead.complete(Unit)
            client.close()
            server.stop(0, 0)
        }
    }

    @Test
    fun `a proxied body outlives the client's request timeout`() = runBlocking {
        // The client the proxy borrows is the addon client, and it installs a request timeout.
        // That is not a per-read deadline: Ktor enforces it by cancelling the whole call's
        // execution context, so it applies to a body being read a chunk at a time just as much
        // as to a JSON manifest. mpv fills its demuxer cache and then stops reading for minutes
        // at a time, so every proxied episode sat on an open call far past the deadline and was
        // cut off mid-body — surfacing only when the player next wanted bytes it had not read,
        // which is why it read as seeking breaking the stream rather than as a proxy hanging up.
        val head = ByteArray(64 * 1024) { 'a'.code.toByte() }
        val tail = ByteArray(16 * 1024) { 'b'.code.toByte() }

        // A real upstream rather than a MockEngine: the cancellation under test kills the
        // engine's response channel, and only a real engine has one to kill.
        val upstream = embeddedServer(ServerCIO, port = 0) {
            routing {
                get("/slow.mp4") {
                    call.respondBytesWriter(
                        contentType = ContentType.parse("video/mp4"),
                        contentLength = (head.size + tail.size).toLong(),
                    ) {
                        writeFully(head)
                        flush()
                        // Longer than the deadline by enough that a scheduling delay cannot
                        // make the two orders of events swap.
                        delay(PROXY_REQUEST_TIMEOUT_MILLIS * 3)
                        writeFully(tail)
                        flush()
                    }
                }
            }
        }
        upstream.start(wait = false)
        val upstreamPort = upstream.engine.resolvedConnectors().first().port
        val upstreamUrl = "http://127.0.0.1:$upstreamPort/slow.mp4"

        val boundary = boundary(
            HttpClient(ClientCIO) {
                install(HttpTimeout) { requestTimeoutMillis = PROXY_REQUEST_TIMEOUT_MILLIS }
            },
        )
        // Header-bearing, or the boundary redirects instead of proxying and nothing is proved.
        boundary.registerStreams(
            listOf(
                AddonStream(
                    url = upstreamUrl,
                    headers = mapOf(HttpHeaders.Referrer to "https://provider.test/"),
                ),
            ),
        )
        val server = embeddedServer(ServerCIO, port = 0) { mediaRoutes(boundary) }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(ClientCIO)
        try {
            withTimeout(STREAMING_TIMEOUT_MILLIS) {
                client.prepareGet("http://127.0.0.1:$port/play?url=${upstreamUrl.encodeURLParameter()}")
                    .execute { response ->
                        val incoming = response.bodyAsChannel()
                        assertContentEquals(head, incoming.readByteArray(head.size))
                        // The whole assertion: without the streaming override the call has been
                        // cancelled by now and this reads a truncated body instead.
                        assertContentEquals(tail, incoming.readByteArray(tail.size))
                    }
            }
        } finally {
            client.close()
            server.stop(0, 0)
            upstream.stop(0, 0)
        }
    }

    @Test
    fun `a stream that is being played keeps its registration past the idle window`() = runBlocking {
        var now = 0L
        val boundary = boundary(HttpClient(MockEngine { error("upstream should not be called") }), { now })
        boundary.registerStreams(listOf(AddonStream(url = "https://video.test/movie.mkv")))
        val play = "/play?url=https%3A%2F%2Fvideo.test%2Fmovie.mkv"

        testApplication {
            application { mediaRoutes(boundary) }
            val client = createClient { followRedirects = false }

            // Twenty minutes in: a mid-stream reconnect, which on an addon stream is an
            // ordinary event rather than a failure — ffmpeg re-opens the URL on any read error.
            now = 20 * 60 * 1_000L
            assertEquals(HttpStatusCode.TemporaryRedirect, client.get(play).status)

            // Forty minutes in. Measured from the listing this is long past the window, which
            // is where a film longer than half an hour used to die on a 403 of our own — and
            // why retrying it could never work, since only a fresh listing re-registered it.
            now = 40 * 60 * 1_000L
            assertEquals(HttpStatusCode.TemporaryRedirect, client.get(play).status)
        }
    }

    @Test
    fun `a stream nobody played is forgotten once its window lapses`() = runBlocking {
        var now = 0L
        val boundary = boundary(HttpClient(MockEngine { error("upstream should not be called") }), { now })
        boundary.registerStreams(listOf(AddonStream(url = "https://video.test/movie.mkv")))

        testApplication {
            application { mediaRoutes(boundary) }
            val client = createClient { followRedirects = false }
            now = 31 * 60 * 1_000L
            // The registry is what stops /play fetching arbitrary URLs, so renewing on use
            // must not amount to never expiring at all.
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/play?url=https%3A%2F%2Fvideo.test%2Fmovie.mkv").status,
            )
        }
    }

    @Test
    fun `a registration survives a read that outlasts the window without reopening`() = runBlocking {
        // The other half of the failure, and the half renewal-on-lookup cannot reach: a film
        // served as one uninterrupted request comes through lookup exactly once, at the start.
        // Nothing renews it for the next two hours, so without the pin the entry expires
        // underneath the reader and the first range request after it — a seek, or the
        // reconnect at the end of a long read — is refused.
        var now = 0L
        val head = ByteArray(64 * 1024) { 'a'.code.toByte() }
        val tail = ByteArray(16 * 1024) { 'b'.code.toByte() }
        val body = ByteChannel(autoFlush = true)
        var upstreamCalls = 0
        val upstream = HttpClient(MockEngine {
            // Only the first request is the long read. The second is the probe below, and it
            // needs a body of its own — sharing the channel would have the two compete for it.
            if (upstreamCalls++ == 0) {
                respond(
                    body,
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf("video/mp4"),
                        HttpHeaders.ContentLength to listOf((head.size + tail.size).toString()),
                    ),
                )
            } else {
                respond("ok", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("video/mp4")))
            }
        })
        val boundary = boundary(upstream) { now }
        boundary.registerStreams(
            listOf(
                AddonStream(
                    url = "https://video.test/big.mp4",
                    headers = mapOf(HttpHeaders.Referrer to "https://provider.test/"),
                ),
            ),
        )

        val server = embeddedServer(ServerCIO, port = 0) { mediaRoutes(boundary) }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(ClientCIO)
        val headRead = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.IO) {
            body.writeFully(head)
            body.flush()
            headRead.await()
            body.writeFully(tail)
            body.flushAndClose()
        }
        try {
            withTimeout(STREAMING_TIMEOUT_MILLIS) {
                client.prepareGet("http://127.0.0.1:$port/play?url=https%3A%2F%2Fvideo.test%2Fbig.mp4")
                    .execute { response ->
                        val incoming = response.bodyAsChannel()
                        assertContentEquals(head, incoming.readByteArray(head.size))

                        // Still reading, and now well past the window the listing bought.
                        now = 45 * 60 * 1_000L
                        val second = HttpClient(ClientCIO) { followRedirects = false }
                        try {
                            assertEquals(
                                HttpStatusCode.OK,
                                second.get(
                                    "http://127.0.0.1:$port/play?url=https%3A%2F%2Fvideo.test%2Fbig.mp4",
                                ).status,
                            )
                        } finally {
                            second.close()
                        }

                        headRead.complete(Unit)
                        assertContentEquals(tail, incoming.readByteArray(tail.size))
                    }
            }
            writer.join()
        } finally {
            headRead.complete(Unit)
            client.close()
            server.stop(0, 0)
        }
    }

    private fun boundary(
        client: HttpClient,
        nowMillis: () -> Long = System::currentTimeMillis,
    ) = MediaBoundary(
        httpClient = client,
        imageCacheDirectory = Files.createTempDirectory("cove-images"),
        publicUrlPolicy = AddonUrlPolicy { },
        allowLanStreamSources = { false },
        nowMillis = nowMillis,
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

    private companion object {
        // Generous: this bounds a hang, it does not time an operation.
        const val STREAMING_TIMEOUT_MILLIS = 30_000L

        /** Short enough to keep the test quick, long enough to survive a slow CI machine. */
        const val PROXY_REQUEST_TIMEOUT_MILLIS = 400L
    }
}
