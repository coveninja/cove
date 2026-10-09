package com.coveninja.cove.backend.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException

/**
 * Everything both media boundaries do to a proxied body, and everything they say about it.
 *
 * Lives in jvmSharedMain because the desktop host and the Android one proxy identically and
 * used to do it in two copies that drifted: the request timeout that truncated every stream
 * was fixed in one of them first, and the account of what went wrong existed in neither.
 */

/** How a range request should be answered, given what the upstream actually said. */
internal data class ProxiedRange(
    val status: HttpStatusCode,
    val contentLength: Long?,
    val contentRange: String?,
    /** Bytes of the upstream body to discard before the first one the player sees. */
    val skipBytes: Long,
) {
    /** One short phrase for the log line: what we told the player we were sending. */
    val answered: String
        get() = buildString {
            append(status.value)
            contentRange?.let { append(' ').append(it) }
            if (skipBytes > 0) append(" (skipped $skipBytes)")
        }
}

/**
 * Turns an upstream answer into the response the player gets.
 *
 * The case worth the arithmetic is an upstream that *ignores* a `Range` and answers 200 with
 * the whole file. ffmpeg reads that as a stream rather than a file: `h->is_streamed` goes
 * true, which costs seeking outright — a seek past mpv's demuxer cache has no byte range to
 * ask for — and makes any reconnect re-request from byte zero. Serving the same bytes from
 * the requested offset and labelling them 206 is the difference between a seekable film and
 * one that ends when the viewer touches the bar.
 *
 * The skip is bounded because it is paid in bandwidth: past [maxSkipBytes] the upstream's own
 * 200 goes through untouched rather than quietly downloading a gigabyte nobody will see.
 * Anything else — no range asked for, a proper 206, an error — is passed through as it came.
 */
internal fun proxiedRangeResponse(
    requestedRange: String?,
    upstreamStatus: HttpStatusCode,
    upstreamContentLength: Long?,
    upstreamContentRange: String?,
    maxSkipBytes: Long = MAX_RANGE_SKIP_BYTES,
): ProxiedRange {
    val passThrough = ProxiedRange(
        status = upstreamStatus,
        contentLength = upstreamContentLength,
        contentRange = upstreamContentRange,
        skipBytes = 0,
    )
    if (requestedRange == null || upstreamStatus != HttpStatusCode.OK) return passThrough

    val requested = parseRequestedRange(requestedRange) ?: return passThrough
    val total = upstreamContentLength?.takeIf { it > 0 } ?: return passThrough
    // A suffix range ("bytes=-500") needs no skip arithmetic we can trust against a body we
    // are streaming forward, and no player asks for one mid-film.
    val start = requested.first ?: return passThrough
    if (start <= 0 || start >= total) return passThrough
    if (start > maxSkipBytes) return passThrough
    val end = (requested.last ?: (total - 1)).coerceIn(start, total - 1)

    return ProxiedRange(
        status = HttpStatusCode.PartialContent,
        contentLength = end - start + 1,
        contentRange = "bytes $start-$end/$total",
        skipBytes = start,
    )
}

/** First and last byte of a single `bytes=` range; null for anything else. */
private fun parseRequestedRange(header: String): RequestedRange? {
    if (!header.startsWith("bytes=") || ',' in header) return null
    val spec = header.removePrefix("bytes=")
    val separator = spec.indexOf('-')
    if (separator < 0) return null
    val first = spec.substring(0, separator).trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
    val last = spec.substring(separator + 1).trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
    if (first == null && last == null) return null
    return RequestedRange(first, last)
}

private data class RequestedRange(val first: Long?, val last: Long?)

/**
 * Copies a proxied body, discarding [skipBytes] first and stopping after [limitBytes].
 *
 * The limit is what makes the normalised 206 honest: the upstream is sending the whole file,
 * and a response that promised `Content-Length` for one slice of it must not keep writing
 * past the end of that slice.
 */
internal suspend fun ByteWriteChannel.copyProxiedBody(
    source: ByteReadChannel,
    skipBytes: Long,
    limitBytes: Long?,
): Long {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var remainingSkip = skipBytes
    while (remainingSkip > 0) {
        val wanted = minOf(remainingSkip, buffer.size.toLong()).toInt()
        val read = source.readAvailable(buffer, 0, wanted)
        if (read <= 0) return 0
        remainingSkip -= read
    }

    var written = 0L
    while (true) {
        val room = limitBytes?.let { it - written } ?: Long.MAX_VALUE
        if (room <= 0) break
        val wanted = minOf(room, buffer.size.toLong()).toInt()
        val read = source.readAvailable(buffer, 0, wanted)
        if (read == -1) break
        if (read == 0) continue
        writeFully(buffer, 0, read)
        // Per chunk, and load-bearing. A ByteWriteChannel buffers what is written to it, so
        // without this the player gets nothing until the buffer fills or the response ends —
        // which for a proxy whose whole job is to forward an episode as it arrives means the
        // picture does not start until the upstream finishes sending it.
        flush()
        written += read
    }
    return written
}

/**
 * Runs a media write and leaves one line behind saying how it went.
 *
 * Nothing else records this. A failure here arrives after the 200 or 206 is already on the
 * wire, so it cannot become an error response — the connection simply dies and the player
 * reports a stream it could not open. Ktor hands the cause to an SLF4J logger and neither
 * host binds a provider, so before this the account of a seek that killed a stream was
 * whatever the viewer could remember. The line is one per request rather than per chunk:
 * mpv opens a new one on every seek, so the file reads as a list of the seeks that happened
 * and what each of them got.
 *
 * [playerHungUp] separates the ordinary case from the fault — every seek ends the previous
 * response mid-write — and is asked of the channel rather than matched against exception
 * types, which surface as any of ClosedWriteChannelException, ClosedByteChannelException or
 * a plain IOException carrying "Broken pipe".
 */
internal suspend fun <T> logMediaWrite(
    source: String,
    requestedRange: String?,
    answered: String,
    playerHungUp: () -> Boolean,
    startedAtMillis: Long = System.currentTimeMillis(),
    write: suspend () -> T,
): T {
    var written: Long? = null
    var outcome = "complete"
    try {
        val result = write()
        written = result as? Long
        return result
    } catch (cancellation: CancellationException) {
        outcome = "cancelled"
        throw cancellation
    } catch (failure: Throwable) {
        outcome = if (playerHungUp()) {
            "player hung up"
        } else {
            "failed: ${failure::class.simpleName}: ${failure.message}"
        }
        throw failure
    } finally {
        System.err.println(
            "Cove media: $source range=${requestedRange ?: "whole"} -> $answered" +
                (written?.let { " wrote=$it" } ?: "") +
                " in ${System.currentTimeMillis() - startedAtMillis}ms $outcome",
        )
    }
}

/**
 * Records a proxied body that ended before the upstream said it would.
 *
 * Kept beside [logMediaWrite] because it is the other half of the same account: this one
 * names the upstream that stopped sending, where that one names what the player received.
 */
internal fun logTruncatedMediaBody(url: String, failure: Throwable, playerHungUp: Boolean) {
    if (playerHungUp) return
    System.err.println(
        "Cove media: proxied body for $url ended early — " +
            "${failure::class.simpleName}: ${failure.message}",
    )
}

/** The headers a proxied response carries over from the upstream verbatim. */
internal val FORWARDED_MEDIA_HEADERS = listOf(
    HttpHeaders.AcceptRanges,
    HttpHeaders.ETag,
    HttpHeaders.LastModified,
)

/**
 * How far into a body the proxy will read and throw away to satisfy a range the upstream
 * ignored. A seek within an episode is comfortably inside this; a jump to the end of a
 * 4K remux is not, and is better served by the upstream's own 200.
 */
private const val MAX_RANGE_SKIP_BYTES = 64L * 1024 * 1024

private const val COPY_BUFFER_BYTES = 64 * 1024
