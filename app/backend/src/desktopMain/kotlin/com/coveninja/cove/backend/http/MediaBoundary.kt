package com.coveninja.cove.backend.http

import com.coveninja.cove.backend.backendScope
import com.coveninja.cove.backend.addons.AddonStream
import com.coveninja.cove.backend.addons.AddonUrlPolicy
import com.coveninja.cove.backend.torrent.TorrentPlaybackEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.net.URI
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MediaBoundary(
    private val httpClient: HttpClient,
    imageCacheDirectory: Path,
    private val publicUrlPolicy: AddonUrlPolicy,
    private val allowLanStreamSources: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val torrentEngine: TorrentPlaybackEngine? = null,
) : RouteMediaBoundary, AutoCloseable {
    private val streams = StreamRegistry(nowMillis = nowMillis)
    private val images = TmdbImageCache(httpClient, imageCacheDirectory)
    private val scope = backendScope("media boundary")
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
        // Listing sources is the moment before one is played, so the peer session
        // comes up now rather than on the click: its DHT bootstrap is otherwise the
        // first thing a torrent play waits through, with nothing on screen. Only
        // when a torrent is actually on offer — a title with no torrent sources
        // starts no session at all.
        if (accepted.any { it.infoHash.isNotBlank() }) {
            torrentEngine?.let { engine -> scope.launch { runCatching { engine.warmUp() } } }
        }
        return accepted
    }

    override suspend fun playDirect(call: ApplicationCall, url: String) {
        requireHttpUrl(url)
        val registered = streams.lookup(url)
            ?: return call.respond(HttpStatusCode.Forbidden, mapOf("error" to "unknown stream url; list streams first"))
        if (registered.headers.isEmpty()) {
            call.response.header(HttpHeaders.Location, url)
            call.respond(HttpStatusCode.TemporaryRedirect)
            return
        }

        val requestHeaders = registered.headers.toMutableMap().also { headers ->
            call.request.headers[HttpHeaders.Range]?.let { headers[HttpHeaders.Range] = it }
        }
        publicGet(url, requestHeaders, allowLanStreamSources()) { upstream ->
            val contentType = upstream.headers[HttpHeaders.ContentType]
                ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                ?: ContentType.Application.OctetStream
            val forwarded = listOf(
                HttpHeaders.AcceptRanges,
                HttpHeaders.ContentRange,
                HttpHeaders.ETag,
                HttpHeaders.LastModified,
            )
            for (name in forwarded) {
                upstream.headers[name]?.let { call.response.header(name, it) }
            }
            // The response producer does not necessarily run inside respondBytesWriter — under
            // some engines it is invoked later, once the engine is ready to write the body — and
            // the upstream body dies with this block. So the block waits for the copy either way:
            // where the producer is synchronous the deferred is already complete by the time
            // respondBytesWriter returns, and where it is deferred this is what keeps the socket
            // it reads from open.
            val copied = CompletableDeferred<Unit>()
            call.respondBytesWriter(
                contentType = contentType,
                status = upstream.status,
                contentLength = upstream.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
            ) {
                try {
                    upstream.bodyAsChannel().copyTo(this)
                    copied.complete(Unit)
                } catch (failure: Throwable) {
                    copied.completeExceptionally(failure)
                    throw failure
                }
            }
            copied.await()
        }
    }

    override suspend fun image(call: ApplicationCall, size: String, file: String) {
        val cached = images.get(size, file)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=604800, immutable")
        call.respondBytes(cached.bytes, cached.contentType)
    }

    override suspend fun playTorrent(
        call: ApplicationCall,
        hash: String,
        season: Int?,
        episode: Int?,
        fileIndex: Int?,
    ) {
        val engine = torrentEngine ?: error("torrent playback is unavailable")
        // The player only ever says "Failed to open <url>", which is the same
        // sentence whether the hash was rejected, the swarm was empty or the file
        // never appeared. The reason is worth one line on the way past.
        val resource = try {
            engine.open(hash, season, episode, fileIndex)
        } catch (error: Throwable) {
            System.err.println(
                "Cove torrent: open failed for $hash — ${error::class.simpleName}: ${error.message}",
            )
            throw error
        }
        val range = parseRange(call.request.headers[HttpHeaders.Range], resource.length)
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "inline; filename=\"${resource.name.replace("\"", "")}\"",
        )
        if (range.partial) {
            call.response.header(HttpHeaders.ContentRange, "bytes ${range.start}-${range.endInclusive}/${resource.length}")
        }
        call.respondBytesWriter(
            contentType = ContentType.parse(resource.contentType),
            status = if (range.partial) HttpStatusCode.PartialContent else HttpStatusCode.OK,
            contentLength = range.endInclusive - range.start + 1,
        ) {
            // Same reasoning as the open above, for the half of the work that happens after the
            // 206 is already on the wire. Ktor reports a producer failure to its own logger
            // rather than to the caller, so without this the connection simply dies and the
            // sentence the viewer sees is the only trace left.
            try {
                engine.stream(hash, season, episode, fileIndex, range.start, range.endInclusive, this)
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

    override fun torrentProgress(hash: String) = torrentEngine?.progress(hash)

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
                            publicGet(stream.url, headers, allowLanStreamSources()) { response ->
                                val alive = response.status.value in 200..399
                                val length = response.headers[HttpHeaders.ContentRange]
                                    ?.substringAfterLast('/')?.toLongOrNull()
                                    ?: response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                                    ?: 0
                                // Leaving this block discards the body, which is the point: a
                                // probe reads headers only, and `bytes=0-0` is a request rather
                                // than a promise — a server free to ignore it sends the file.
                                ProbeStreamResult(stream.url, alive, length)
                            }
                        }.getOrElse { ProbeStreamResult(stream.url, false) }
                    } ?: ProbeStreamResult(stream.url, false)
                }
            }.awaitAll()
        })
    }

    override suspend fun subtitle(call: ApplicationCall, url: String) {
        requireHttpUrl(url)
        val bytes = publicGet(url, emptyMap(), allowLan = false) { response ->
            check(response.status.isSuccess()) { "subtitle upstream returned HTTP ${response.status.value}" }
            response.bodyAsChannel().readAtMost(MAX_SUBTITLE_BYTES, "subtitle exceeds 10 MiB limit")
        }
        val content = bytes.decodeToString()
        call.respondText(
            if (content.trimStart().startsWith("WEBVTT")) content else srtToVtt(content),
            ContentType.parse("text/vtt; charset=utf-8"),
        )
    }

    override fun prefetchTorrent(hash: String, season: Int?, episode: Int?, fileIndex: Int?): Boolean {
        require(Regex("^[A-Fa-f0-9]{40}$").matches(hash)) { "invalid torrent info hash" }
        if (!prefetchInFlight.compareAndSet(false, true)) return false
        val engine = torrentEngine ?: run {
            prefetchInFlight.set(false)
            error("torrent playback is unavailable")
        }
        scope.launch {
            try {
                engine.prefetch(hash, season, episode, fileIndex)
            } finally {
                prefetchInFlight.set(false)
            }
        }
        return true
    }

    override suspend fun speedTest(call: ApplicationCall) {
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
        torrentEngine?.close()
    }

    companion object {
        private const val SPEED_TEST_BYTES = 25 * 1024 * 1024
        private const val MAX_SUBTITLE_BYTES = 10 * 1024 * 1024
        private const val MAX_REDIRECTS = 6
        private val SENSITIVE_REDIRECT_HEADERS = setOf(
            "authorization",
            "cookie",
            "proxy-authorization",
        )
    }

    /**
     * Follows the redirect chain by hand — every hop re-validated, credentials dropped when the
     * authority changes — and hands the final response to [consume] while its body is still on
     * the wire.
     *
     * The response cannot simply be returned instead. `httpClient.get()` finishes the call before
     * it returns, and finishing it means Ktor's SaveBody plugin has already replayed the body
     * through a `ByteChannelReplay` — the whole thing resident before any of it is used. On
     * [playDirect] that body is the video, because mpv opens every stream with `Range: bytes=0-`,
     * so the proxy buffered an entire episode into heap with no ceiling and no timeout.
     * `prepareGet(...).execute {}` is the only supported way out: as of Ktor 3.5 the per-request
     * `skipSavingBody()` is a no-op that logs and says so. It is also why the response exists
     * only inside the block — Ktor cancels the body the moment the block returns, which is what
     * [consume] has to be finished with before it does.
     */
    private suspend fun <T> publicGet(
        initialUrl: String,
        headers: Map<String, String>,
        allowLan: Boolean,
        consume: suspend (HttpResponse) -> T,
    ): T {
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
            val hop = httpClient.prepareGet(current) {
                requestHeaders.forEach { (name, value) -> header(name, value) }
            }.execute { response ->
                val location = response.headers[HttpHeaders.Location]
                if (response.status.value in 300..399 && location != null) {
                    // The body of a redirect is discarded by leaving this block.
                    UpstreamHop.Redirect(location)
                } else {
                    // A 3xx carrying no Location is not a redirect anyone can follow, so it
                    // reaches the consumer like any other final response.
                    UpstreamHop.Consumed(consume(response))
                }
            }
            when (hop) {
                is UpstreamHop.Consumed -> return hop.value
                is UpstreamHop.Redirect -> {
                    if (redirectCount == MAX_REDIRECTS - 1) error("too many redirects")
                    current = URI(current).resolve(hop.location).toString()
                }
            }
        }
        error("too many redirects")
    }
}

private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024

// Reads off the channel so the ceiling is a real one. A Content-Length can be absent or a lie,
// and a limit checked against an already-buffered body has nothing left to protect.
private suspend fun io.ktor.utils.io.ByteReadChannel.readAtMost(maxBytes: Int, message: String): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (!isClosedForRead) {
        val count = readAvailable(buffer)
        if (count == -1) break
        if (count == 0) continue
        require(output.size() + count <= maxBytes) { message }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun srtToVtt(content: String): String = buildString {
    append("WEBVTT\n\n")
    content.lineSequence().forEach { line ->
        append(if ("-->" in line) line.replace(Regex("(\\d{2}:\\d{2}:\\d{2}),"), "$1.") else line)
        append('\n')
    }
}

private data class ByteRange(val start: Long, val endInclusive: Long, val partial: Boolean)

private fun parseRange(header: String?, length: Long): ByteRange {
    require(length > 0) { "torrent file is empty" }
    if (header == null) return ByteRange(0, length - 1, partial = false)
    require(header.startsWith("bytes=") && ',' !in header) { "invalid byte range" }
    val spec = header.removePrefix("bytes=")
    val separator = spec.indexOf('-')
    require(separator >= 0) { "invalid byte range" }
    val first = spec.substring(0, separator)
    val last = spec.substring(separator + 1)
    val range = if (first.isBlank()) {
        val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("invalid byte range")
        ByteRange((length - suffix).coerceAtLeast(0), length - 1, partial = true)
    } else {
        val start = first.toLongOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("invalid byte range")
        val end = last.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
        require(start < length && end >= start) { "byte range is outside the file" }
        ByteRange(start, end, partial = true)
    }
    return range
}

private data class RegisteredStream(
    val headers: Map<String, String>,
    val expiresAt: Long,
)

private class StreamRegistry(
    private val ttlMillis: Long = 30 * 60 * 1_000L,
    private val nowMillis: () -> Long,
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

private data class CachedImage(val bytes: ByteArray, val contentType: ContentType)

private class TmdbImageCache(
    private val httpClient: HttpClient,
    private val directory: Path,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun get(size: String, file: String): CachedImage {
        require(size in setOf("w185", "w300", "w500", "w780", "w1280", "original")) {
            "invalid image size"
        }
        require(Regex("^[A-Za-z0-9._-]+$").matches(file)) { "invalid image file" }
        Files.createDirectories(directory.resolve(size))
        val path = directory.resolve(size).resolve(file).normalize()
        require(path.startsWith(directory.resolve(size).normalize())) { "invalid image path" }
        val bytes = if (Files.isRegularFile(path)) {
            Files.readAllBytes(path)
        } else {
            locks.computeIfAbsent("$size/$file") { Mutex() }.withLock {
                if (Files.isRegularFile(path)) Files.readAllBytes(path) else fetch(size, file, path)
            }
        }
        return CachedImage(bytes, imageContentType(file))
    }

    private suspend fun fetch(size: String, file: String, path: Path): ByteArray {
        val bytes = httpClient.prepareGet("https://image.tmdb.org/t/p/$size/$file").execute { response ->
            require(response.status.isSuccess()) { "TMDB image returned HTTP ${response.status.value}" }
            response.bodyAsChannel().readAtMost(MAX_IMAGE_BYTES, "TMDB image exceeds 25 MiB")
        }
        val temporary = path.resolveSibling("${path.fileName}.tmp-${UUID.randomUUID()}")
        Files.write(temporary, bytes)
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return bytes
    }
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

private fun imageContentType(file: String): ContentType = when (file.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> ContentType.Image.JPEG
    "png" -> ContentType.Image.PNG
    "webp" -> ContentType.parse("image/webp")
    else -> ContentType.Application.OctetStream
}
