package com.coveninja.cove.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CoveApiClient is a singleton with mutable BASE/token state, so every test
 * restores Local mode and clears the JWT afterwards to avoid order dependence.
 *
 * The token-in-query-string behaviour is deliberate and load-bearing: mpv opens
 * play/image URLs itself and cannot attach headers, so Remote mode has to put
 * the pairing token in the URL.
 */
class CoveApiClientTest {

    @Serializable
    private data class Echo(val value: String)

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun resetClientState() {
        CoveApiClient.applyMode(ServerMode.Local)
        CoveApiClient.setJwt(null)
        server.shutdown()
    }

    private fun useRemote(token: String = "tok123") {
        CoveApiClient.applyMode(
            ServerMode.Remote(server.url("/api").toString().trimEnd('/'), token),
        )
    }

    // ── applyMode ────────────────────────────────────────────────────────────

    @Test
    fun applyModeRemoteSetsBaseUrl() {
        val remoteBase = server.url("/api").toString().trimEnd('/')

        CoveApiClient.applyMode(ServerMode.Remote(remoteBase, "tok"))

        assertEquals(remoteBase, CoveApiClient.BASE)
    }

    @Test
    fun applyModeLocalRestoresDefaultBaseAndClearsToken() {
        useRemote()
        CoveApiClient.applyMode(ServerMode.Local)

        assertEquals(com.coveninja.cove.BuildConfig.BACKEND_URL, CoveApiClient.BASE)
        // Token cleared: playUrl must no longer append one.
        assertFalse(CoveApiClient.playUrl("abc").contains("token="))
    }

    // ── playUrl ──────────────────────────────────────────────────────────────

    @Test
    fun playUrlWrapsBareInfohash() {
        assertEquals("${CoveApiClient.BASE}/play?hash=abc123", CoveApiClient.playUrl("abc123"))
    }

    @Test
    fun playUrlAppendsSeasonAndEpisode() {
        val url = CoveApiClient.playUrl("abc123", season = 2, episode = 5)

        assertEquals("${CoveApiClient.BASE}/play?hash=abc123&season=2&episode=5", url)
    }

    @Test
    fun playUrlOmitsEpisodeWhenOnlySeasonGiven() {
        val url = CoveApiClient.playUrl("abc123", season = 2)

        assertEquals("${CoveApiClient.BASE}/play?hash=abc123&season=2", url)
        assertFalse(url.contains("episode="))
    }

    @Test
    fun playUrlReturnsAbsoluteUrlUnchangedInLocalMode() {
        val direct = "http://example.test/stream.mkv?x=1"

        assertEquals(direct, CoveApiClient.playUrl(direct))
    }

    @Test
    fun playUrlAppendsTokenToInfohashInRemoteMode() {
        useRemote("secret")

        assertTrue(CoveApiClient.playUrl("abc123").endsWith("&token=secret"))
    }

    @Test
    fun playUrlAppendsTokenToAbsoluteUrlInRemoteMode() {
        // mpv cannot send X-Cove-Token, so direct stream URLs need it inline too.
        useRemote("secret")

        assertEquals(
            "http://example.test/stream.mkv?token=secret",
            CoveApiClient.playUrl("http://example.test/stream.mkv"),
        )
    }

    @Test
    fun playUrlUsesAmpersandWhenAbsoluteUrlAlreadyHasQuery() {
        useRemote("secret")

        assertEquals(
            "http://example.test/stream.mkv?x=1&token=secret",
            CoveApiClient.playUrl("http://example.test/stream.mkv?x=1"),
        )
    }

    @Test
    fun playUrlAcceptsUppercaseHttpScheme() {
        assertEquals("HTTP://example.test/a.mkv", CoveApiClient.playUrl("HTTP://example.test/a.mkv"))
    }

    // ── resolveImgUrl ────────────────────────────────────────────────────────

    @Test
    fun resolveImgUrlReturnsNullForBlankInput() {
        assertNull(CoveApiClient.resolveImgUrl(""))
        assertNull(CoveApiClient.resolveImgUrl("   "))
    }

    @Test
    fun resolveImgUrlRewritesLoopbackHostToRemoteBase() {
        useRemote()
        val baseHost = CoveApiClient.BASE.substringBefore("/api")

        val resolved = CoveApiClient.resolveImgUrl("http://127.0.0.1:6969/api/img/poster.jpg")

        assertTrue("expected rewrite to $baseHost, got $resolved", resolved!!.startsWith(baseHost))
        assertFalse(resolved.contains("127.0.0.1:6969"))
    }

    @Test
    fun resolveImgUrlRewritesAnyLoopbackPort() {
        useRemote()

        val resolved = CoveApiClient.resolveImgUrl("http://127.0.0.1:12345/api/img/a.jpg")

        assertFalse(resolved!!.contains("12345"))
    }

    @Test
    fun resolveImgUrlLeavesForeignHostUntouched() {
        val resolved = CoveApiClient.resolveImgUrl("https://image.tmdb.org/t/p/w500/x.jpg")

        assertEquals("https://image.tmdb.org/t/p/w500/x.jpg", resolved)
    }

    @Test
    fun resolveImgUrlAppendsTokenWithCorrectSeparator() {
        useRemote("secret")

        // No existing query -> "?", existing query -> "&".
        assertTrue(CoveApiClient.resolveImgUrl("http://cdn.test/a.jpg")!!.endsWith("?token=secret"))
        assertTrue(CoveApiClient.resolveImgUrl("http://cdn.test/a.jpg?w=1")!!.endsWith("&token=secret"))
    }

    @Test
    fun resolveImgUrlAppendsNoTokenInLocalMode() {
        assertFalse(CoveApiClient.resolveImgUrl("http://cdn.test/a.jpg")!!.contains("token="))
    }

    // ── testConnection ───────────────────────────────────────────────────────

    @Test
    fun testConnectionBuildsPingPathFromBareBase() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val ok = CoveApiClient.testConnection(server.url("/").toString().trimEnd('/'), "tok")

        assertTrue(ok)
        assertEquals("/api/ping", server.takeRequest().path)
    }

    @Test
    fun testConnectionDoesNotDoubleApiSuffix() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        CoveApiClient.testConnection(server.url("/api").toString(), "tok")

        assertEquals("/api/ping", server.takeRequest().path)
    }

    @Test
    fun testConnectionToleratesTrailingSlash() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        CoveApiClient.testConnection(server.url("/api/").toString(), "tok")

        assertEquals("/api/ping", server.takeRequest().path)
    }

    @Test
    fun testConnectionSendsPairingToken() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        CoveApiClient.testConnection(server.url("/").toString(), "pairing-token")

        assertEquals("pairing-token", server.takeRequest().getHeader("X-Cove-Token"))
    }

    @Test
    fun testConnectionReturnsFalseOnNonSuccess() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertFalse(CoveApiClient.testConnection(server.url("/").toString(), "tok"))
    }

    @Test
    fun testConnectionReturnsFalseWhenHostUnreachable() = runTest {
        val dead = server.url("/").toString()
        server.shutdown()
        server = MockWebServer().also { it.start() } // keep @After happy

        assertFalse(CoveApiClient.testConnection(dead, "tok"))
    }

    // ── header injection ─────────────────────────────────────────────────────

    @Test
    fun requestsCarryNoAuthorizationHeaderWhenSignedOut() = runTest {
        useRemote()
        CoveApiClient.setJwt(null)
        server.enqueue(MockResponse().setBody("""{"value":"ok"}"""))

        CoveApiClient.getOrNull<Echo>("/thing")

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun requestsCarryTokenAndJwtWhenSet() = runTest {
        useRemote("remote-token")
        CoveApiClient.setJwt("jwt-abc")
        server.enqueue(MockResponse().setBody("""{"value":"ok"}"""))

        CoveApiClient.getOrNull<Echo>("/thing")

        val recorded = server.takeRequest()
        assertEquals("remote-token", recorded.getHeader("X-Cove-Token"))
        assertEquals("Bearer jwt-abc", recorded.getHeader("Authorization"))
    }

    @Test
    fun clearingJwtRemovesAuthorizationHeader() = runTest {
        useRemote()
        CoveApiClient.setJwt("jwt-abc")
        CoveApiClient.setJwt(null)
        server.enqueue(MockResponse().setBody("""{"value":"ok"}"""))

        CoveApiClient.getOrNull<Echo>("/thing")

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    // ── error swallowing ─────────────────────────────────────────────────────

    @Test
    fun getOrNullReturnsNullOn404() = runTest {
        useRemote()
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(CoveApiClient.getOrNull<Echo>("/missing"))
    }

    @Test
    fun getOrNullReturnsNullOnMalformedJson() = runTest {
        useRemote()
        server.enqueue(MockResponse().setBody("not json"))

        assertNull(CoveApiClient.getOrNull<Echo>("/bad"))
    }

    @Test
    fun getOrNullDecodesSuccessfulBody() = runTest {
        useRemote()
        server.enqueue(MockResponse().setBody("""{"value":"hello"}"""))

        assertEquals("hello", CoveApiClient.getOrNull<Echo>("/ok")?.value)
    }

    @Test
    fun patchReportsSuccessAndFailure() = runTest {
        useRemote()
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(CoveApiClient.patch("/x", "{}"))

        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(CoveApiClient.patch("/x", "{}"))
    }

    @Test
    fun deleteReportsSuccessAndFailure() = runTest {
        useRemote()
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(CoveApiClient.delete("/x"))

        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(CoveApiClient.delete("/x"))
    }

    @Test
    fun postForStatusReturnsCodeAndBody() = runTest {
        useRemote()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad creds"}"""))

        val (code, body) = CoveApiClient.postForStatus("/auth/signin", "{}")

        assertEquals(401, code)
        assertTrue(body.contains("bad creds"))
    }

    @Test
    fun postForStatusReturnsMinusOneOnNetworkError() = runTest {
        val dead = server.url("/api").toString().trimEnd('/')
        server.shutdown()
        server = MockWebServer().also { it.start() }
        CoveApiClient.applyMode(ServerMode.Remote(dead, "tok"))

        val (code, _) = CoveApiClient.postForStatus("/auth/signin", "{}")

        assertEquals(-1, code)
    }

    @Test
    fun getForStatusReturnsCodeAndBody() = runTest {
        useRemote()
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        val (code, body) = CoveApiClient.getForStatus("/auth/sync")

        assertEquals(503, code)
        assertEquals("unavailable", body)
    }
}
