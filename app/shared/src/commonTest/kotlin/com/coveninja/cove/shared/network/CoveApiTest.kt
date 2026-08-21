package com.coveninja.cove.shared.network

import com.coveninja.cove.shared.model.MediaType
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

// Deliberately the production config: a divergent local Json here is how a
// real serialization bug stayed hidden.
private val testJson = CoveJson

// respond() is an extension on MockRequestHandleScope (note: "Handle", not
// "Handler" — the class name in Ktor 3.5.1). jsonResponse must be an extension
// on that same type so it can delegate to respond() inside MockEngine lambdas.
private fun MockRequestHandleScope.jsonResponse(body: String) = respond(
    content = body,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

class CoveApiTest {

    // ── Auth headers ─────────────────────────────────────────────────────────

    // Mutation applied to verify: removed the `tokenProvider()?.let { header(...) }`
    // line → test failed because Authorization header was absent.
    @Test
    fun `auth headers are present when providers return values`() = runTest {
        var capturedHeaders: Headers? = null
        val client = HttpClient(MockEngine { request ->
            capturedHeaders = request.headers
            jsonResponse("[]")
        }) {
            install(ContentNegotiation) { json(testJson) }
        }
        val api = CoveApi(
            client,
            CoveApiConfig(),
            tokenProvider = { "tok123" },
            deviceTokenProvider = { "dev456" },
        )
        api.discover("movie")

        assertNotNull(capturedHeaders, "MockEngine did not receive a request")
        assertEquals("Bearer tok123", capturedHeaders!![HttpHeaders.Authorization])
        assertEquals("dev456", capturedHeaders!!["X-Cove-Token"])
    }

    // Mutation applied to verify: changed `tokenProvider()?.let { ... }` to always
    // add a header with empty string → test failed because Authorization was
    // present (with "") when it should have been absent.
    @Test
    fun `auth headers are absent when providers return null`() = runTest {
        var capturedHeaders: Headers? = null
        val client = HttpClient(MockEngine { request ->
            capturedHeaders = request.headers
            jsonResponse("[]")
        }) {
            install(ContentNegotiation) { json(testJson) }
        }
        val api = CoveApi(
            client,
            CoveApiConfig(),
            tokenProvider = { null },
            deviceTokenProvider = { null },
        )
        api.discover("movie")

        assertNotNull(capturedHeaders)
        // Must be absent, not present with an empty value.
        assertNull(capturedHeaders!![HttpHeaders.Authorization], "Authorization should be absent")
        assertNull(capturedHeaders!!["X-Cove-Token"], "X-Cove-Token should be absent")
    }

    // ── TV stream query parameters ───────────────────────────────────────────

    // Mutation applied to verify: removed `season?.let { parameter("season", it) }`
    // → test failed because "season=2" was absent from the captured URL.
    @Test
    fun `streams with type=tv includes season and episode query params`() = runTest {
        var capturedUrl: String? = null
        val client = HttpClient(MockEngine { request ->
            capturedUrl = request.url.fullPath
            jsonResponse("[]")
        }) {
            install(ContentNegotiation) { json(testJson) }
        }
        val api = CoveApi(client, CoveApiConfig())
        api.streams(id = 1396, type = MediaType.Tv, season = 2, episode = 3)

        assertNotNull(capturedUrl)
        assertTrue(capturedUrl!!.contains("type=tv"),   "Expected type=tv in '$capturedUrl'")
        assertTrue(capturedUrl!!.contains("season=2"),  "Expected season=2 in '$capturedUrl'")
        assertTrue(capturedUrl!!.contains("episode=3"), "Expected episode=3 in '$capturedUrl'")
    }

    // Mutation applied to verify: changed movie branch to always append season
    // and episode params → test failed because "season" appeared for a movie request.
    @Test
    fun `streams with type=movie omits season and episode params`() = runTest {
        var capturedUrl: String? = null
        val client = HttpClient(MockEngine { request ->
            capturedUrl = request.url.fullPath
            jsonResponse("[]")
        }) {
            install(ContentNegotiation) { json(testJson) }
        }
        val api = CoveApi(client, CoveApiConfig())
        api.streams(id = 550, type = MediaType.Movie)

        assertNotNull(capturedUrl)
        assertTrue(capturedUrl!!.contains("type=movie"), "Expected type=movie in '$capturedUrl'")
        assertFalse(capturedUrl!!.contains("season"),    "season should be absent for movies")
        assertFalse(capturedUrl!!.contains("episode"),   "episode should be absent for movies")
    }

    @Test
    fun `streams request outlives the backend Nuvio aggregate timeout`() = runTest {
        var capturedTimeoutMillis: Long? = null
        val client = HttpClient(MockEngine { request ->
            capturedTimeoutMillis = request
                .getCapabilityOrNull(HttpTimeoutCapability)
                ?.requestTimeoutMillis
            jsonResponse("[]")
        }) {
            install(ContentNegotiation) { json(testJson) }
            install(HttpTimeout) { requestTimeoutMillis = 20_000 }
        }
        val api = CoveApi(client, CoveApiConfig())

        api.streams(id = 969681, type = MediaType.Movie)

        assertEquals(30_000, capturedTimeoutMillis)
    }

    // ── Error mapping ────────────────────────────────────────────────────────

    // Confirms requireSuccess() throws on non-2xx so repositories can catch and
    // emit Failed rather than crashing the UI with an unhandled exception.
    // Mutation applied to verify: removed `requireSuccess()` call from discover()
    // → test failed because no RuntimeException was thrown.
    @Test
    fun `500 response from discover throws RuntimeException with status in message`() = runTest {
        val client = HttpClient(MockEngine {
            respond("Internal Server Error", HttpStatusCode.InternalServerError)
        }) {
            install(ContentNegotiation) { json(testJson) }
        }
        val api = CoveApi(client, CoveApiConfig())

        val ex = assertFailsWith<RuntimeException> { api.discover("movie") }
        assertTrue(
            ex.message?.contains("500") == true,
            "Expected 500 in exception message, got: ${ex.message}",
        )
    }
}
