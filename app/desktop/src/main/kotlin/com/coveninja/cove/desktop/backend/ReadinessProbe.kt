package com.coveninja.cove.desktop.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Signals when the backend is ready to accept requests. */
fun interface ReadinessProbe {
    /**
     * Suspends until the backend is ready.  Returns `true` if ready within
     * the configured deadline, `false` if the deadline expired before a
     * successful response was observed.
     */
    suspend fun awaitReady(): Boolean
}

/**
 * Polls `http://127.0.0.1:[port]/api/ping` until it returns HTTP 200 with
 * `"status":"ok"` in the body, or until [timeoutMillis] elapses.
 *
 * Uses JDK 17's built-in [HttpClient] — no additional dependency needed.
 * Both connect and per-request read timeouts are 250 ms so a stalled
 * connection cannot block more than one poll interval.
 */
class HttpReadinessProbe(
    private val port: Int,
    private val timeoutMillis: Long,
    private val pollIntervalMillis: Long = 100,
) : ReadinessProbe {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(250))
        .build()

    override suspend fun awaitReady(): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val ready = withContext(Dispatchers.IO) {
                try {
                    val req = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:$port/api/ping"))
                        .timeout(Duration.ofMillis(250))
                        .GET()
                        .build()
                    val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
                    resp.statusCode() == 200 && resp.body().contains("\"status\":\"ok\"")
                } catch (_: Exception) {
                    false
                }
            }
            if (ready) return true
            delay(pollIntervalMillis)
        }
        return false
    }
}
