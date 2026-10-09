package com.coveninja.cove.backend.http

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a provider's answer becomes the one the player gets.
 *
 * The case this exists for is a provider that ignores a `Range` and sends the whole file with a
 * 200. ffmpeg then treats the input as a stream: `h->is_streamed` goes true, which costs seeking
 * outright and makes every reconnect re-request from byte zero — the `Will reconnect at 0` line
 * in the desktop log. Every case here was confirmed to fail against a broken implementation
 * before its comment was written.
 */
class ProxiedMediaTest {
    private val answered = HttpStatusCode.PartialContent

    @Test
    fun `a range the upstream ignored is served from the requested offset`() {
        val proxied = proxiedRangeResponse(
            requestedRange = "bytes=1000-",
            upstreamStatus = HttpStatusCode.OK,
            upstreamContentLength = 5_000,
            upstreamContentRange = null,
        )

        // Fails if the 200 is passed through: the player would be handed the start of the film
        // as though it were the part it asked for, and would stop believing the stream seekable.
        assertEquals(answered, proxied.status)
        assertEquals("bytes 1000-4999/5000", proxied.contentRange)
        assertEquals(4_000, proxied.contentLength)
        assertEquals(1_000, proxied.skipBytes)
    }

    @Test
    fun `a closed range the upstream ignored keeps its own end`() {
        val proxied = proxiedRangeResponse(
            requestedRange = "bytes=1000-1999",
            upstreamStatus = HttpStatusCode.OK,
            upstreamContentLength = 5_000,
            upstreamContentRange = null,
        )

        // Fails if the end is taken from the file rather than from the request, which would
        // promise a thousand bytes and send four thousand.
        assertEquals("bytes 1000-1999/5000", proxied.contentRange)
        assertEquals(1_000, proxied.contentLength)
    }

    @Test
    fun `a proper 206 is passed through untouched`() {
        val proxied = proxiedRangeResponse(
            requestedRange = "bytes=1000-",
            upstreamStatus = HttpStatusCode.PartialContent,
            upstreamContentLength = 4_000,
            upstreamContentRange = "bytes 1000-4999/5000",
        )

        // The overwhelmingly common case, and the one that must cost nothing. Fails if the
        // normalisation runs anyway: it would skip a thousand bytes of a body that already
        // starts where it should, putting the picture a thousand bytes into the wrong place.
        assertEquals(HttpStatusCode.PartialContent, proxied.status)
        assertEquals("bytes 1000-4999/5000", proxied.contentRange)
        assertEquals(0, proxied.skipBytes)
    }

    @Test
    fun `a request for the whole file is passed through`() {
        val proxied = proxiedRangeResponse(
            requestedRange = null,
            upstreamStatus = HttpStatusCode.OK,
            upstreamContentLength = 5_000,
            upstreamContentRange = null,
        )

        assertEquals(HttpStatusCode.OK, proxied.status)
        assertEquals(0, proxied.skipBytes)
    }

    @Test
    fun `a range starting at zero needs no rewriting`() {
        val proxied = proxiedRangeResponse(
            requestedRange = "bytes=0-",
            upstreamStatus = HttpStatusCode.OK,
            upstreamContentLength = 5_000,
            upstreamContentRange = null,
        )

        // mpv opens every stream this way, so this is the shape of the first request of every
        // film. Fails if it is normalised: a 206 for the whole file is a worse answer than the
        // 200 it already was, and the skip arithmetic has nothing to do.
        assertEquals(HttpStatusCode.OK, proxied.status)
        assertEquals(0, proxied.skipBytes)
    }

    @Test
    fun `a jump further than the cap is left to the upstream`() {
        val proxied = proxiedRangeResponse(
            requestedRange = "bytes=900-",
            upstreamStatus = HttpStatusCode.OK,
            upstreamContentLength = 5_000,
            upstreamContentRange = null,
            maxSkipBytes = 500,
        )

        // The skip is paid in bandwidth nobody watches. Fails without the cap, which would have
        // the proxy quietly download the first gigabyte of a remux to satisfy one seek.
        assertEquals(HttpStatusCode.OK, proxied.status)
        assertEquals(0, proxied.skipBytes)
    }

    @Test
    fun `an unusable answer is never rewritten`() {
        // No length to measure against, a range past the end of the file, a suffix range, and an
        // error status. Fails on any arithmetic that assumes it can always construct a 206: each
        // of these would otherwise promise bytes the body cannot supply.
        assertEquals(
            HttpStatusCode.OK,
            proxiedRangeResponse("bytes=1000-", HttpStatusCode.OK, null, null).status,
        )
        assertEquals(
            0,
            proxiedRangeResponse("bytes=9000-", HttpStatusCode.OK, 5_000, null).skipBytes,
        )
        assertEquals(
            0,
            proxiedRangeResponse("bytes=-500", HttpStatusCode.OK, 5_000, null).skipBytes,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            proxiedRangeResponse("bytes=1000-", HttpStatusCode.Forbidden, 5_000, null).status,
        )
    }
}
