package com.coveninja.cove.backend.http

import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder

/**
 * Lifts the client's whole-call deadline off a request whose body is an episode.
 *
 * Both media boundaries proxy direct streams with the same client the addons use, and that
 * client installs `HttpTimeout { requestTimeoutMillis = 25_000 }`. A request timeout is not a
 * per-read deadline: `applyRequestTimeout` in Ktor's own plugin launches a coroutine that
 * cancels the call's `executionContext` once it expires, and neither the OkHttp nor the CIO
 * engine implements it natively — they map only connect and socket timeouts — so it applies to
 * every non-WebSocket request including one being read a chunk at a time.
 *
 * mpv opens every stream with `Range: bytes=0-`, fills its demuxer cache and then stops
 * reading, so the proxy sits on a full response for minutes at a time by design. Twenty-five
 * seconds in, the upstream call was cancelled underneath it and the response to mpv ended short
 * of its own `Content-Length`. Nothing failed loudly: playback ran on out of the cache, and the
 * truncation only surfaced when the player next needed bytes it had not already read — which is
 * why it read as "seeking ahead breaks the stream" rather than as a proxy that hangs up.
 *
 * The connection is still bounded, just by the right things. [MEDIA_CONNECT_TIMEOUT_MILLIS]
 * still fails an upstream that will not answer, and [MEDIA_SOCKET_TIMEOUT_MILLIS] still fails
 * one that stops sending mid-body. Backpressure does not trip the socket timeout: the engine's
 * body producer blocks writing into a bounded channel rather than on a socket read, so no read
 * is outstanding during the minutes mpv spends not consuming.
 */
internal fun HttpRequestBuilder.mediaStreamTimeouts() {
    timeout {
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        connectTimeoutMillis = MEDIA_CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = MEDIA_SOCKET_TIMEOUT_MILLIS
    }
}

/** An upstream that has not answered by now is not going to serve a video. */
internal const val MEDIA_CONNECT_TIMEOUT_MILLIS = 15_000L

/** Inactivity mid-body, which is a dead upstream rather than a slow one. */
internal const val MEDIA_SOCKET_TIMEOUT_MILLIS = 60_000L
