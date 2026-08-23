package com.coveninja.cove.backend.playback

import com.coveninja.cove.backend.backendScope
import com.coveninja.cove.backend.addons.AddonStream
import com.coveninja.cove.backend.addons.AddonUrlPolicy
import com.coveninja.cove.backend.http.ProbeStreamResult
import com.coveninja.cove.backend.http.ProbeStreamsRequest
import com.coveninja.cove.backend.http.ProbeStreamsResponse
import com.coveninja.cove.backend.http.RouteMediaBoundary
import com.coveninja.cove.backend.torrent.TorrentPlaybackEngine
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The loopback-only URL boundary needed by libmpv.
 *
 * Addons are queried in-process on Android, but media must still be represented as
 * an HTTP URL: direct streams may need registered request headers, torrents need a
 * seekable Range endpoint, and external subtitles must be validated and converted.
 */
internal class AndroidPlaybackMediaHost private constructor(
    private val httpClient: HttpClient,
    private val publicUrlPolicy: AddonUrlPolicy,
    private val allowLanStreamSources: () -> Boolean,
    private val torrentEngine: TorrentPlaybackEngine,
    private val server: EmbeddedServer<*, *>,
    val baseUrl: String,
) : RouteMediaBoundary, AutoCloseable {
    private val streams = StreamRegistry()
    private val scope = backendScope("playback")
    private val prefetchInFlight = AtomicBoolean()

    override suspend fun registerStreams(candidates: List<AddonStream>): List<AddonStream> {
        val accepted = candidates.filter { stream ->
            if (stream.url.isBlank()) return@filter stream.infoHash.isNotBlank()
            runCatching {
                requireHttpUrl(stream.url)
                if (!allowLanStreamSources()) publicUrlPolicy.validate(stream.url)
            }.isSuccess
        }
        streams.remember(accepted)
        return accepted
    }

    fun directUrl(url: String): String = "$baseUrl/play?url=${url.encodeURLParameter()}"

    fun torrentUrl(hash: String, season: Int?, episode: Int?, fileIndex: Int?): String {
        val query = buildList {
            add("hash=$hash")
            season?.let { add("season=$it") }
            episode?.let { add("episode=$it") }
            fileIndex?.let { add("fileIdx=$it") }
        }
        return "$baseUrl/play?${query.joinToString("&")}" 
    }

    fun subtitleUrl(url: String): String = "$baseUrl/subtitle?url=${url.encodeURLParameter()}"

    suspend fun aliveUrls(urls: List<String>): Set<String> {
        if (urls.isEmpty()) return emptySet()
        return coroutineScope {
            urls.take(MAX_PROBED).map { url ->
                async {
                    val registered = streams.lookup(url) ?: return@async null
                    withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) {
                        runCatching {
                            val headers = registered.headers.toMutableMap()
                            headers[HttpHeaders.Range] = "bytes=0-0"
                            val response = publicGet(url, headers, allowLanStreamSources())
                            val alive = response.status.value in 200..399
                            response.bodyAsChannel().cancel(CancellationException("probe complete"))
                            url.takeIf { alive }
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull().toSet()
        }
    }

    override fun torrentProgress(hash: String) = torrentEngine.progress(hash)

    override suspend fun probe(request: ProbeStreamsRequest): ProbeStreamsResponse {
        require(request.streams.size in 1..10) { "streams must contain 1-10 entries" }
        val timeout = (request.timeoutMs.takeIf { it > 0 } ?: 700).coerceIn(100, 800)
        return ProbeStreamsResponse(coroutineScope {
            request.streams.map { stream ->
                async {
                    val registered = streams.lookup(stream.url)
                        ?: return@async ProbeStreamResult(stream.url, false)
                    withTimeoutOrNull(timeout.toLong()) {
                        runCatching {
                            val headers = registered.headers.toMutableMap()
                            headers[HttpHeaders.Range] = "bytes=0-0"
                            val response = publicGet(stream.url, headers, allowLanStreamSources())
                            val alive = response.status.value in 200..399
                            val length = response.headers[HttpHeaders.ContentRange]
                                ?.substringAfterLast('/')?.toLongOrNull()
                                ?: response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                                ?: 0
                            response.bodyAsChannel().cancel(CancellationException("probe complete"))
                            ProbeStreamResult(stream.url, alive, length)
                        }.getOrElse { ProbeStreamResult(stream.url, false) }
                    } ?: ProbeStreamResult(stream.url, false)
                }
            }.awaitAll()
        })
    }

    override suspend fun playDirect(call: io.ktor.server.application.ApplicationCall, url: String) {
        requireHttpUrl(url)
        val registered = streams.lookup(url)
            ?: return call.respondText(
                "unknown stream url; list streams first",
                status = HttpStatusCode.Forbidden,
            )
        if (registered.headers.isEmpty()) {
            call.response.header(HttpHeaders.Location, url)
            call.respond(HttpStatusCode.TemporaryRedirect)
            return
        }

        val requestHeaders = registered.headers.toMutableMap().also { headers ->
            call.request.header(HttpHeaders.Range)?.let { headers[HttpHeaders.Range] = it }
        }
        val upstream = publicGet(url, requestHeaders, allowLanStreamSources())
        val contentType = upstream.headers[HttpHeaders.ContentType]
            ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            ?: ContentType.Application.OctetStream
        for (name in FORWARDED_RESPONSE_HEADERS) {
            upstream.headers[name]?.let { call.response.header(name, it) }
        }
        call.respondBytesWriter(
            contentType = contentType,
            status = upstream.status,
            contentLength = upstream.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
        ) {
            upstream.bodyAsChannel().copyTo(this)
        }
    }

    override suspend fun playTorrent(
        call: io.ktor.server.application.ApplicationCall,
        hash: String,
        season: Int?,
        episode: Int?,
        fileIndex: Int?,
    ) {
        // The player only ever says "The selected stream could not be opened", which is the
        // same sentence whether the hash was rejected, the swarm was empty or the file never
        // appeared. The reason is worth one line on the way past — see the stream producer
        // below for why nothing else records it.
        val resource = try {
            torrentEngine.open(hash, season, episode, fileIndex)
        } catch (failure: Throwable) {
            System.err.println(
                "Cove torrent: open failed for $hash — ${failure::class.simpleName}: ${failure.message}",
            )
            throw failure
        }
        val range = parseRange(call.request.header(HttpHeaders.Range), resource.length)
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "inline; filename=\"${resource.name.replace("\"", "")}\"",
        )
        if (range.partial) {
            call.response.header(
                HttpHeaders.ContentRange,
                "bytes ${range.start}-${range.endInclusive}/${resource.length}",
            )
        }
        call.respondBytesWriter(
            contentType = ContentType.parse(resource.contentType),
            status = if (range.partial) HttpStatusCode.PartialContent else HttpStatusCode.OK,
            contentLength = range.endInclusive - range.start + 1,
        ) {
            // A failure here arrives after the 206 has already gone out, so it cannot become an
            // error response — the connection just dies and the player reports a stream it could
            // not open. Ktor hands the cause to an SLF4J logger, and this process binds no
            // provider ("No SLF4J providers were found" at startup), so by default the one
            // account of what went wrong is discarded. That is the difference between a torrent
            // bug that can be read off a log and one that can only be guessed at.
            try {
                torrentEngine.stream(hash, season, episode, fileIndex, range.start, range.endInclusive, this)
            } catch (cancellation: CancellationException) {
                throw cancellation // the viewer closing the player is not a fault
            } catch (failure: Throwable) {
                // Every seek ends one response and opens another with a fresh Range, so the
                // player dropping a connection mid-write is the ordinary case rather than a
                // fault, and a line per seek would bury the failures worth reading. Ask the
                // channel instead of matching exception types: a hangup surfaces as any of
                // ClosedWriteChannelException, ClosedByteChannelException or a plain IOException
                // carrying "Broken pipe", and a list of those would quietly rot.
                if (isClosedForWrite) throw failure
                System.err.println(
                    "Cove torrent: stream failed for $hash after the response started — " +
                        "${failure::class.simpleName}: ${failure.message}",
                )
                throw failure
            }
        }
    }

    override suspend fun subtitle(call: io.ktor.server.application.ApplicationCall, url: String) {
        requireHttpUrl(url)
        val response = publicGet(url, emptyMap(), allowLan = false)
        check(response.status.isSuccess()) { "subtitle upstream returned HTTP ${response.status.value}" }
        val bytes = response.bodyAsChannel().readAtMost(MAX_SUBTITLE_BYTES)
        val content = bytes.decodeToString()
        call.respondText(
            if (content.trimStart().startsWith("WEBVTT")) content else srtToVtt(content),
            ContentType.parse("text/vtt; charset=utf-8"),
        )
    }

    override suspend fun image(
        call: io.ktor.server.application.ApplicationCall,
        size: String,
        file: String,
    ) {
        require(size in setOf("w185", "w300", "w500", "w780", "w1280", "original")) {
            "invalid image size"
        }
        require(Regex("^[A-Za-z0-9._-]+$").matches(file)) { "invalid image file" }
        val response = publicGet("https://image.tmdb.org/t/p/$size/$file", emptyMap(), allowLan = false)
        require(response.status.isSuccess()) { "TMDB image returned HTTP ${response.status.value}" }
        val bytes = response.body<ByteArray>()
        require(bytes.size <= MAX_IMAGE_BYTES) { "TMDB image exceeds 25 MiB" }
        call.response.header(HttpHeaders.CacheControl, "public, max-age=604800, immutable")
        call.respondBytes(bytes, imageContentType(file))
    }

    private suspend fun publicGet(
        initialUrl: String,
        headers: Map<String, String>,
        allowLan: Boolean,
    ): HttpResponse {
        var current = initialUrl
        val initialAuthority = URI(initialUrl).normalizedAuthority()
        repeat(MAX_REDIRECTS) { redirectCount ->
            requireHttpUrl(current)
            if (!allowLan) publicUrlPolicy.validate(current)
            val requestHeaders = if (URI(current).normalizedAuthority() == initialAuthority) {
                headers
            } else {
                headers.filterKeys { it.lowercase() !in SENSITIVE_REDIRECT_HEADERS }
            }
            val response = httpClient.get(current) {
                requestHeaders.forEach { (name, value) -> header(name, value) }
            }
            if (response.status.value !in 300..399) return response
            val location = response.headers[HttpHeaders.Location] ?: return response
            response.bodyAsChannel().cancel(CancellationException("following redirect"))
            if (redirectCount == MAX_REDIRECTS - 1) error("too many redirects")
            current = URI(current).resolve(location).toString()
        }
        error("too many redirects")
    }

    override fun prefetchTorrent(
        hash: String,
        season: Int?,
        episode: Int?,
        fileIndex: Int?,
    ): Boolean {
        require(Regex("^[A-Fa-f0-9]{40}$").matches(hash)) { "invalid torrent info hash" }
        if (!prefetchInFlight.compareAndSet(false, true)) return false
        scope.launch {
            try {
                torrentEngine.prefetch(hash, season, episode, fileIndex)
            } finally {
                prefetchInFlight.set(false)
            }
        }
        return true
    }

    override suspend fun speedTest(call: io.ktor.server.application.ApplicationCall) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondBytesWriter(
            contentType = ContentType.Application.OctetStream,
            contentLength = SPEED_TEST_BYTES.toLong(),
        ) {
            val chunk = ByteArray(1024 * 1024)
            repeat(SPEED_TEST_BYTES / chunk.size) { writeFully(chunk) }
        }
    }

    override fun close() {
        scope.cancel()
        server.stop(500, 2_000)
        torrentEngine.close()
    }

    companion object {
        private const val HOST = "127.0.0.1"
        private const val MAX_REDIRECTS = 6
        private const val MAX_PROBED = 10
        private const val PROBE_TIMEOUT_MILLIS = 800L
        private const val MAX_SUBTITLE_BYTES = 10 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
        private const val SPEED_TEST_BYTES = 25 * 1024 * 1024
        private val SENSITIVE_REDIRECT_HEADERS = setOf("authorization", "cookie", "proxy-authorization")
        private val FORWARDED_RESPONSE_HEADERS = listOf(
            HttpHeaders.AcceptRanges,
            HttpHeaders.ContentRange,
            HttpHeaders.ETag,
            HttpHeaders.LastModified,
        )

        fun start(
            httpClient: HttpClient,
            publicUrlPolicy: AddonUrlPolicy,
            allowLanStreamSources: () -> Boolean,
            torrentEngine: TorrentPlaybackEngine,
        ): AndroidPlaybackMediaHost {
            lateinit var host: AndroidPlaybackMediaHost
            val server = embeddedServer(CIO, host = HOST, port = 0) {
                install(StatusPages) {
                    exception<IllegalArgumentException> { call, error ->
                        call.respondText(error.message ?: "invalid playback request", status = HttpStatusCode.BadRequest)
                    }
                    exception<IllegalStateException> { call, error ->
                        call.respondText(error.message ?: "playback failed", status = HttpStatusCode.ServiceUnavailable)
                    }
                }
                routing {
                    get("/play") {
                        val url = call.request.queryParameters["url"]
                        val hash = call.request.queryParameters["hash"]
                        when {
                            url != null -> host.playDirect(call, url)
                            hash != null -> host.playTorrent(
                                call,
                                hash,
                                call.request.queryParameters["season"]?.toIntOrNull(),
                                call.request.queryParameters["episode"]?.toIntOrNull(),
                                call.request.queryParameters["fileIdx"]?.toIntOrNull(),
                            )
                            else -> throw IllegalArgumentException("missing hash or url")
                        }
                    }
                    get("/subtitle") {
                        host.subtitle(
                            call,
                            call.request.queryParameters["url"]
                                ?: throw IllegalArgumentException("missing url"),
                        )
                    }
                }
            }
            server.start(wait = false)
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            host = AndroidPlaybackMediaHost(
                httpClient,
                publicUrlPolicy,
                allowLanStreamSources,
                torrentEngine,
                server,
                "http://$HOST:$port",
            )
            return host
        }
    }
}

private fun imageContentType(file: String): ContentType =
    when (file.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "png" -> ContentType.Image.PNG
        "webp" -> ContentType.parse("image/webp")
        else -> ContentType.Application.OctetStream
    }

private data class RegisteredStream(val headers: Map<String, String>, val expiresAt: Long)

private class StreamRegistry(
    private val ttlMillis: Long = 30 * 60 * 1_000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val entries = ConcurrentHashMap<String, RegisteredStream>()

    fun remember(streams: List<AddonStream>) {
        val now = nowMillis()
        entries.entries.removeIf { it.value.expiresAt <= now }
        streams.asSequence().filter { it.url.isNotBlank() }.forEach { stream ->
            entries[stream.url] = RegisteredStream(stream.headers.toMap(), now + ttlMillis)
        }
    }

    fun lookup(url: String): RegisteredStream? {
        val entry = entries[url] ?: return null
        if (entry.expiresAt <= nowMillis()) {
            entries.remove(url, entry)
            return null
        }
        return entry
    }
}

private data class ByteRange(val start: Long, val endInclusive: Long, val partial: Boolean)

internal fun parsePlaybackRange(header: String?, length: Long): LongRange =
    parseRange(header, length).let { it.start..it.endInclusive }

private fun parseRange(header: String?, length: Long): ByteRange {
    require(length > 0) { "torrent file is empty" }
    if (header == null) return ByteRange(0, length - 1, partial = false)
    require(header.startsWith("bytes=") && ',' !in header) { "invalid byte range" }
    val spec = header.removePrefix("bytes=")
    val separator = spec.indexOf('-')
    require(separator >= 0) { "invalid byte range" }
    val first = spec.substring(0, separator)
    val last = spec.substring(separator + 1)
    return if (first.isBlank()) {
        val suffix = last.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("invalid byte range")
        ByteRange((length - suffix).coerceAtLeast(0), length - 1, partial = true)
    } else {
        val start = first.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("invalid byte range")
        val end = last.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
        require(start < length && end >= start) { "byte range is outside the file" }
        ByteRange(start, end, partial = true)
    }
}

internal fun convertSrtToVtt(content: String): String = srtToVtt(content)

private fun srtToVtt(content: String): String = buildString {
    append("WEBVTT\n\n")
    content.lineSequence().forEach { line ->
        append(if ("-->" in line) line.replace(Regex("(\\d{2}:\\d{2}:\\d{2}),"), "$1.") else line)
        append('\n')
    }
}

private suspend fun ByteReadChannel.readAtMost(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (!isClosedForRead) {
        val count = readAvailable(buffer)
        if (count == -1) break
        if (count == 0) continue
        require(output.size() + count <= maxBytes) { "subtitle exceeds 10 MiB limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun requireHttpUrl(raw: String) {
    val url = runCatching { Url(raw) }.getOrElse { throw IllegalArgumentException("invalid stream url") }
    require(url.protocol.name == "http" || url.protocol.name == "https") { "invalid stream url" }
    require(runCatching { URI(raw).rawUserInfo }.getOrNull() == null) {
        "stream URL must not contain credentials"
    }
}

private fun URI.normalizedAuthority(): String =
    "${scheme.lowercase()}://${host.orEmpty().lowercase()}:${if (port >= 0) port else if (scheme == "https") 443 else 80}"
