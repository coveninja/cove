package com.coveninja.cove.backend.http

import com.coveninja.cove.backend.backendScope
import com.coveninja.cove.backend.addons.AddonStream
import com.coveninja.cove.backend.addons.AddonUrlPolicy
import com.coveninja.cove.shared.data.PlaybackRepository
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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
        // Checked side by side rather than one after another: each check is a DNS lookup, and
        // a list of debrid links on a slow resolver took long enough in sequence to time the
        // whole listing out. A host that cannot be checked in time is dropped, as one that
        // cannot be resolved always was.
        val checks = Semaphore(URL_CHECK_CONCURRENCY)
        val verdicts = coroutineScope {
            candidates.map { stream ->
                async {
                    if (stream.url.isBlank()) return@async stream.infoHash.isNotBlank()
                    checks.withPermit {
                        withTimeoutOrNull(URL_CHECK_TIMEOUT_MILLIS) {
                            runCatching {
                                requireHttpUrl(stream.url)
                                if (!allowLanStreamSources()) publicUrlPolicy.validate(stream.url)
                            }.isSuccess
                        } ?: false
                    }
                }
            }.awaitAll()
        }
        val accepted = candidates.filterIndexed { index, _ -> verdicts[index] }
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

    /**
     * Proxies a direct stream, header-bearing or not.
     *
     * The not-bearing case used to answer a 307 straight to the provider's URL, which is
     * most addon streams, and the saving was real: mpv read from the CDN and nothing was
     * copied through loopback. What it cost was everything about that read. A desktop log
     * from a film seventy minutes in says `[ffmpeg] https: Will reconnect at 0`, and that is
     * the whole of what was knowable — the reconnect, the rewind to byte zero, and whether
     * the CDN even honours ranges all happened where we could neither see nor influence
     * them. Proxying puts a response we control in front of the player: a range we asked for
     * and labelled honestly (see [proxiedRangeResponse]), an upstream failure that becomes
     * our retry rather than ffmpeg's rewind, and one line in the log per request.
     */
    override suspend fun playDirect(call: ApplicationCall, url: String) {
        requireHttpUrl(url)
        val registered = streams.lookup(url)
            ?: return call.respond(HttpStatusCode.Forbidden, mapOf("error" to "unknown stream url; list streams first"))

        val requestedRange = call.request.headers[HttpHeaders.Range]
        val requestHeaders = registered.headers.toMutableMap().also { headers ->
            requestedRange?.let { headers[HttpHeaders.Range] = it }
        }
        // Held for the length of the read, not the length of the listing that produced it.
        // A film served as one uninterrupted request never comes back through lookup, so
        // without this the entry ages out underneath the reader and the next range request
        // — the reconnect — is refused by our own 403.
        streams.pin(url)
        try {
            publicGet(url, requestHeaders, allowLanStreamSources(), streaming = true) { upstream ->
                val contentType = upstream.headers[HttpHeaders.ContentType]
                    ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                    ?: ContentType.Application.OctetStream
                val answer = proxiedRangeResponse(
                    requestedRange = requestedRange,
                    upstreamStatus = upstream.status,
                    upstreamContentLength = upstream.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
                    upstreamContentRange = upstream.headers[HttpHeaders.ContentRange],
                )
                for (name in FORWARDED_MEDIA_HEADERS) {
                    upstream.headers[name]?.let { call.response.header(name, it) }
                }
                answer.contentRange?.let { call.response.header(HttpHeaders.ContentRange, it) }
                // The response producer does not necessarily run inside respondBytesWriter — under
                // some engines it is invoked later, once the engine is ready to write the body — and
                // the upstream body dies with this block. So the block waits for the copy either way:
                // where the producer is synchronous the deferred is already complete by the time
                // respondBytesWriter returns, and where it is deferred this is what keeps the socket
                // it reads from open.
                val copied = CompletableDeferred<Unit>()
                call.respondBytesWriter(
                    contentType = contentType,
                    status = answer.status,
                    contentLength = answer.contentLength,
                ) {
                    try {
                        logMediaWrite(
                            source = "url=$url",
                            requestedRange = requestedRange,
                            answered = answer.answered,
                            playerHungUp = { isClosedForWrite },
                        ) {
                            copyProxiedBody(
                                source = upstream.bodyAsChannel(),
                                skipBytes = answer.skipBytes,
                                limitBytes = answer.contentLength,
                            )
                        }
                        copied.complete(Unit)
                    } catch (failure: Throwable) {
                        logTruncatedMediaBody(url, failure, isClosedForWrite)
                        copied.completeExceptionally(failure)
                        throw failure
                    }
                }
                copied.await()
            }
        } finally {
            streams.unpin(url)
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
            logMediaWrite(
                source = "torrent=$hash file=${resource.id.substringAfterLast(':')}",
                requestedRange = call.request.headers[HttpHeaders.Range],
                answered = "${if (range.partial) 206 else 200} " +
                    "bytes ${range.start}-${range.endInclusive}/${resource.length}",
                playerHungUp = { isClosedForWrite },
            ) {
                engine.stream(hash, season, episode, fileIndex, range.start, range.endInclusive, this)
            }
        }
    }

    override fun torrentProgress(hash: String) = torrentEngine?.progress(hash)

    override suspend fun probe(request: ProbeStreamsRequest): ProbeStreamsResponse {
        require(request.streams.size in 1..PlaybackRepository.MAX_PROBED_URLS) {
            "streams must contain 1-${PlaybackRepository.MAX_PROBED_URLS} entries"
        }
        // The old 800 ms ceiling was under a cold TLS handshake to a distant CDN, so a healthy
        // source could be judged dead for being far away.
        val timeout = (request.timeoutMs.takeIf { it > 0 } ?: 2_000).coerceIn(100, 3_000)
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
                                val alive = response.status.value in 200..399 &&
                                    looksLikePlayableContentType(response.headers[HttpHeaders.ContentType])
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
        /**
         * Whether the body being fetched is a media stream, and so must outlive the client's
         * request timeout. Set by [playDirect] and nothing else: a subtitle, an image or a
         * liveness probe is a small body that should still fail fast. See [mediaStreamTimeouts].
         */
        streaming: Boolean = false,
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
                if (streaming) mediaStreamTimeouts()
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
    /** Requests currently reading this stream; a pinned entry never expires. */
    val pins: Int = 0,
)

/**
 * The URLs `/play` will proxy, and the headers to send with each.
 *
 * The entry is what makes an addon's URL playable at all, so its lifetime is the lifetime
 * of the playback that uses it — and neither of the two ways playback holds one is a clock.
 * A reconnect comes back through [lookup] hours after the list was fetched (ffmpeg re-opens
 * the URL on any mid-stream read error, which on these sources is routine), and a single
 * uninterrupted read holds one request open for the whole film without ever calling [lookup]
 * again. An absolute TTL measured from the listing fails both: it expired a stream that was
 * playing, `/play` answered 403, and every retry of that URL answered 403 for ever after,
 * because nothing but a fresh listing could put the entry back.
 *
 * So expiry is idle time. [lookup] renews, [pin] holds an entry for as long as a request is
 * reading it, and only a stream nobody has touched for [ttlMillis] is forgotten — which is
 * still the bound that matters, since this map is what stops `/play` fetching arbitrary URLs.
 */
private class StreamRegistry(
    private val ttlMillis: Long = 30 * 60 * 1_000L,
    private val nowMillis: () -> Long,
) {
    private val entries = ConcurrentHashMap<String, RegisteredStream>()

    fun remember(streams: List<AddonStream>) {
        val now = nowMillis()
        entries.entries.removeIf { it.value.pins == 0 && it.value.expiresAt <= now }
        streams.asSequence().filter { it.url.isNotBlank() }.forEach { stream ->
            entries.compute(stream.url) { _, existing ->
                RegisteredStream(
                    headers = stream.headers.toMap(),
                    expiresAt = now + ttlMillis,
                    pins = existing?.pins ?: 0,
                )
            }
        }
    }

    /** The entry for [url], renewing its lease. Null once it has gone unused for the TTL. */
    fun lookup(url: String): RegisteredStream? = entries.computeIfPresent(url) { _, entry ->
        if (entry.pins == 0 && entry.expiresAt <= nowMillis()) null
        else entry.copy(expiresAt = nowMillis() + ttlMillis)
    }

    /** Holds [url] for as long as a request is reading it. Balanced by [unpin]. */
    fun pin(url: String) {
        entries.computeIfPresent(url) { _, entry -> entry.copy(pins = entry.pins + 1) }
    }

    /** Releases a [pin], restarting the entry's idle clock from now. */
    fun unpin(url: String) {
        entries.computeIfPresent(url) { _, entry ->
            entry.copy(
                expiresAt = nowMillis() + ttlMillis,
                pins = (entry.pins - 1).coerceAtLeast(0),
            )
        }
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

private const val URL_CHECK_CONCURRENCY = 8
private const val URL_CHECK_TIMEOUT_MILLIS = 5_000L
