package com.coveninja.cove.backend.tracker

import com.coveninja.cove.shared.model.TrackerProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

class TrackerException(message: String) : RuntimeException(message)

data class TrackerHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    fun requireStatus(expected: Int, provider: TrackerProvider, operation: String) {
        if (status != expected) {
            throw TrackerException("${provider.label} $operation returned HTTP $status")
        }
    }

    fun requireSuccess(provider: TrackerProvider, operation: String) {
        if (status !in 200..299) {
            throw TrackerException("${provider.label} $operation returned HTTP $status")
        }
    }
}

/**
 * Every tracker request, throttled and retried the same way.
 *
 * Both APIs publish a write rate limit and both answer 429 with `Retry-After`, so the
 * throttle sits here rather than in either service: a write waits out [minimumWriteIntervalMillis]
 * since the last one, and a 429 is retried exactly once after the header's delay. Reads go
 * straight out — it is writes that are limited, and holding the mutex over a paged pull
 * would serialise a sync behind its own history.
 *
 * [decorate] is the only per-provider part: Trakt wants `trakt-api-key`/`trakt-api-version`,
 * Simkl wants `simkl-api-key` plus app-name/app-version query parameters.
 */
class TrackerHttp(
    private val provider: TrackerProvider,
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val clock: Clock,
    private val minimumWriteIntervalMillis: Long,
    private val decorate: HttpRequestBuilder.(accessToken: String) -> Unit,
) {
    private val writeMutex = Mutex()
    private var lastWriteMillis = 0L

    suspend fun get(path: String, accessToken: String = ""): TrackerHttpResponse =
        send(HttpMethod.Get, path, null, accessToken)

    suspend fun write(
        method: HttpMethod,
        path: String,
        body: JsonElement?,
        accessToken: String = "",
    ): TrackerHttpResponse = writeMutex.withLock {
        val elapsed = clock.millis() - lastWriteMillis
        if (lastWriteMillis > 0 && elapsed < minimumWriteIntervalMillis) {
            delay(minimumWriteIntervalMillis - elapsed)
        }
        var response = send(method, path, body, accessToken)
        lastWriteMillis = clock.millis()
        if (response.status == 429) {
            val retrySeconds = response.headers[HttpHeaders.RetryAfter]
                ?.toLongOrNull()?.coerceIn(1, 60) ?: 1
            delay(retrySeconds * 1_000)
            response = send(method, path, body, accessToken)
            lastWriteMillis = clock.millis()
        }
        response
    }

    fun fail(operation: String, detail: String): Nothing =
        throw TrackerException("${provider.label} $operation $detail")

    private suspend fun send(
        method: HttpMethod,
        path: String,
        body: JsonElement?,
        accessToken: String,
    ): TrackerHttpResponse {
        val response = httpClient.request("${baseUrl.trimEnd('/')}$path") {
            this.method = method
            decorate(accessToken)
            if (accessToken.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $accessToken")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return TrackerHttpResponse(
            response.status.value,
            response.headers.entries().associate { it.key to it.value.joinToString(",") },
            response.bodyAsText(),
        )
    }
}
