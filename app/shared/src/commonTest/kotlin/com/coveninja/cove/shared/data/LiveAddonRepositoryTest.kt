package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.network.CoveApi
import com.coveninja.cove.shared.network.CoveApiConfig
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ADDONS_JSON = """
[
  {
    "id": "com.example.provider",
    "url": "https://example.com/manifest.json",
    "manifest": {"id":"com.example.provider","name":"Example","description":"d","version":"1.0.0",
                 "resources":[{"name":"stream"}],"catalogs":[]},
    "kind": "provider",
    "enabled": true
  }
]
"""

private const val ADDON_ONE_JSON = """
{
  "id": "com.example.provider",
  "url": "https://example.com/manifest.json",
  "manifest": {"id":"com.example.provider","name":"Example","description":"d","version":"1.0.0"},
  "kind": "provider",
  "enabled": true
}
"""

private const val NUVIO_ONE_JSON = """
{"id":"r1","owner":"someone","repo":"scrapers","branch":"main",
 "url":"https://github.com/someone/scrapers","enabled":true,"scrapers":[],
 "fetchedAt":"2026-01-01T00:00:00Z"}
"""

private const val NUVIO_JSON = """
[
  {"id":"r1","owner":"someone","repo":"scrapers","branch":"main",
   "url":"https://github.com/someone/scrapers","enabled":true,
   "scrapers":[{"id":"s1","name":"One","filename":"one.js","enabled":false,"code":"x"}],
   "fetchedAt":"2026-01-01T00:00:00Z"}
]
"""

private class Recorder {
    val requests = mutableListOf<HttpRequestData>()

    fun client(): HttpClient = HttpClient(MockEngine { request ->
        requests += request
        // The add routes answer with the single created entity; the list routes
        // with an array. Returning an array to a POST makes it fail to decode,
        // which silently turns a refetch assertion into a no-op.
        val isCreate = request.method == HttpMethod.Post
        val body = when {
            request.url.encodedPath.endsWith("/nuvio/repos") ->
                if (isCreate) NUVIO_ONE_JSON else NUVIO_JSON

            request.url.encodedPath.endsWith("/addons") ->
                if (isCreate) ADDON_ONE_JSON else ADDONS_JSON

            else -> "{}"
        }
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }) {
        install(ContentNegotiation) { json(CoveJson) }
    }
}

private fun TestScope.repositoryWith(recorder: Recorder): LiveAddonRepository =
    LiveAddonRepository(CoveApi(recorder.client(), CoveApiConfig("http://127.0.0.1:6969")), this)

class LiveAddonRepositoryTest {

    // The UI only models part of the backend payload; ignoreUnknownKeys has to
    // carry the rest (resources, catalogs, scraper code) without failing.
    // Mutation applied to verify: renamed the `kind` property so it no longer
    // matched the wire name → test failed on the decoded kind.
    @Test
    fun `addon and scraper payloads decode into the client models`() = runTest {
        val recorder = Recorder()
        val repository = repositoryWith(recorder)
        repository.reload()

        val state = repository.state.value
        assertTrue(state is AddonsState.Ready, "was: $state")
        val addon = state.addons.single()
        assertEquals("com.example.provider", addon.id)
        assertEquals("Example", addon.manifest.name)
        assertEquals(com.coveninja.cove.shared.model.AddonKind.Provider, addon.kind)

        val repo = state.nuvioRepos.single()
        assertEquals("someone/scrapers", repo.displayName)
        assertEquals("One", repo.scrapers.single().name)
    }

    // Built on an already-cancelled scope so the constructor's own reload never
    // runs. Without that, the init GET satisfies this assertion on its own and the
    // test passes even with mutate's reload deleted — which is exactly what an
    // earlier version of it did.
    // Mutation applied to verify: dropped the reload() from mutate → test failed,
    // only the POST was recorded.
    @Test
    fun `adding an addon refetches rather than guessing the result`() = runTest {
        val recorder = Recorder()
        val repository = LiveAddonRepository(
            CoveApi(recorder.client(), CoveApiConfig()),
            CoroutineScope(Job().apply { cancel() }),
        )

        repository.addAddon("https://example.com/manifest.json")

        val methods = recorder.requests.map { it.method }
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Get), methods)
    }

    // Mutation applied to verify: removed the trim() → test failed, the pasted
    // URL kept its surrounding whitespace.
    @Test
    fun `a pasted url is trimmed before it is sent`() = runTest {
        val recorder = Recorder()
        val repository = repositoryWith(recorder)
        recorder.requests.clear()

        repository.addAddon("  https://example.com/manifest.json\n")

        val post = assertNotNull(
            recorder.requests.firstOrNull { it.method == HttpMethod.Post },
            "no POST was made",
        )
        val body = post.body.toString()
        assertTrue("\\n" !in body && "  http" !in body, "url was not trimmed: $body")
    }

    // A bad manifest URL is ordinary user input; it must surface next to the field
    // rather than propagate as an exception.
    // Mutation applied to verify: rethrew from mutate instead of recording
    // → test failed with the exception escaping runTest.
    @Test
    fun `a rejected addon reports through lastError`() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                content = """{"error":"not a manifest"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) { json(CoveJson) }
        }
        val repository = LiveAddonRepository(CoveApi(client, CoveApiConfig()), this)

        repository.addAddon("https://example.com/not-a-manifest")

        assertNotNull(repository.lastError.value, "the failure should have been recorded")
    }

    // Nuvio is optional on the backend; losing it must not blank the addon list,
    // which is the part that makes playback work.
    // Mutation applied to verify: dropped the runCatching around nuvioRepos()
    // → test failed, the whole state went Failed.
    @Test
    fun `a failing nuvio endpoint still yields the addon list`() = runTest {
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/nuvio/repos")) {
                respond("boom", HttpStatusCode.InternalServerError)
            } else {
                respond(
                    content = ADDONS_JSON,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }) {
            install(ContentNegotiation) { json(CoveJson) }
        }
        val repository = LiveAddonRepository(CoveApi(client, CoveApiConfig()), this)
        repository.reload()

        val state = repository.state.value
        assertTrue(state is AddonsState.Ready, "was: $state")
        assertEquals(1, state.addons.size)
        assertTrue(state.nuvioRepos.isEmpty())
    }
}
