package com.coveninja.cove.backend.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SupabaseClientTest {
    @Test
    fun authLifecycleUsesPublishableKeyAndPersistsRefreshableSessionShape() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += request
                    when (request.url.encodedPath) {
                        "/auth/v1/signup" -> json("""{"id":"pending-user"}""")
                        "/auth/v1/token" -> json(
                            """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"user":{"id":"user-1","email":"a@example.com"}}""",
                        )
                        "/auth/v1/user" -> json("""{"id":"user-1","email":"a@example.com"}""")
                        else -> error("unexpected ${request.url}")
                    }
                }
            }
        }
        try {
            val client = SupabaseClient(
                SupabaseConfig("https://project.invalid/", "publishable"),
                http,
                epochSeconds = { 100L },
            )
            val signup = client.signUp("a@example.com", "secret")
            assertEquals("pending-user", signup.userId)
            assertEquals(null, signup.session)

            val session = client.signIn("a@example.com", "secret")
            assertEquals("user-1", session.userId)
            assertEquals(3700L, session.expiresAtEpochSeconds)
            assertEquals("user-1", client.user("access").id)

            assertEquals("publishable", requests.first().headers["apikey"])
            assertEquals("grant_type=password", requests[1].url.encodedQuery)
            assertEquals("Bearer access", requests[2].headers[HttpHeaders.Authorization])
        } finally {
            http.close()
        }
    }

    @Test
    fun postgrestAlwaysUsesUserJwtAndSurfacesUpstreamMessage() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    assertEquals("Bearer jwt", request.headers[HttpHeaders.Authorization])
                    assertEquals("anon", request.headers["apikey"])
                    respond(
                        """{"message":"row-level security rejected write"}""",
                        HttpStatusCode.Forbidden,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
        try {
            val client = SupabaseClient(SupabaseConfig("https://project.invalid", "anon"), http)
            val error = assertFailsWith<SupabaseException> {
                client.delete("jwt", "profiles", "id=eq.profile")
            }
            assertEquals(403, error.statusCode)
            assertTrue(error.message.orEmpty().contains("row-level security"))
        } finally {
            http.close()
        }
    }

    private fun MockRequestHandleScope.json(body: String) = respond(
        body,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
