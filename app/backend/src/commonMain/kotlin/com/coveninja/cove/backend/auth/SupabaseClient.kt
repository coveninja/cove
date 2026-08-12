package com.coveninja.cove.backend.auth

import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class SupabaseConfig(val url: String, val publishableKey: String) {
    init {
        require(url.startsWith("https://") || url.startsWith("http://127.0.0.1")) {
            "SUPABASE_URL must use HTTPS"
        }
        require(publishableKey.isNotBlank()) { "SUPABASE_PUBLISHABLE_KEY is required" }
    }

    val baseUrl: String = url.trimEnd('/')
}

@Serializable
data class SupabaseUser(
    val id: String,
    val email: String = "",
)

data class SupabaseSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long?,
)

data class SignUpResult(
    val userId: String,
    val session: SupabaseSession?,
)

class SupabaseException(
    val statusCode: Int,
    message: String,
    /**
     * Supabase's own wording, without the operation prefix — "Invalid login
     * credentials" rather than "Supabase auth (400): Invalid login credentials".
     * This is what a sign-in form shows the user.
     */
    val detail: String = message,
) : RuntimeException(message)

private val JwtPayloadBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

/** Minimal public Supabase Auth and PostgREST client; RLS remains the boundary. */
class SupabaseClient(
    private val config: SupabaseConfig,
    private val httpClient: HttpClient,
    private val epochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    suspend fun signUp(email: String, password: String): SignUpResult {
        val response = authPost("/signup", buildJsonObject {
            put("email", email)
            put("password", password)
        })
        val userId = response.user?.id.orEmpty().ifBlank { response.id }
        return SignUpResult(
            userId = userId,
            session = response.accessToken.takeIf(String::isNotBlank)?.let {
                response.toSession(userId)
            },
        )
    }

    suspend fun verifySignup(email: String, token: String) {
        authPost("/verify", buildJsonObject {
            put("type", "signup")
            put("email", email)
            put("token", token)
        })
    }

    suspend fun signIn(email: String, password: String): SupabaseSession =
        authPost("/token?grant_type=password", buildJsonObject {
            put("email", email)
            put("password", password)
        }).toRequiredSession()

    suspend fun sendOtp(email: String) {
        authPost("/otp", buildJsonObject {
            put("email", email)
            put("create_user", true)
            put("should_create_user", true)
        })
    }

    suspend fun verifyOtp(email: String, token: String): SupabaseSession =
        authPost("/verify", buildJsonObject {
            put("type", "email")
            put("email", email)
            put("token", token)
        }).toRequiredSession()

    suspend fun refresh(refreshToken: String): SupabaseSession =
        authPost("/token?grant_type=refresh_token", buildJsonObject {
            put("refresh_token", refreshToken)
        }).toRequiredSession()

    /** Validates an externally supplied access token with Supabase Auth. */
    suspend fun user(accessToken: String): SupabaseUser {
        val response = httpClient.get("${config.baseUrl}/auth/v1/user") {
            header("apikey", config.publishableKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        val text = response.requireSuccess("Supabase user")
        return CoveJson.decodeFromString(text)
    }

    suspend fun select(accessToken: String, table: String, query: String = ""): JsonArray {
        val response = httpClient.get(restUrl(table, query)) { restHeaders(accessToken) }
        return CoveJson.parseToJsonElement(response.requireSuccess("Supabase REST GET $table")) as? JsonArray
            ?: throw SupabaseException(response.status.value, "Supabase REST GET $table returned a non-array response")
    }

    suspend fun upsert(accessToken: String, table: String, rows: JsonElement) {
        val response = httpClient.post(restUrl(table, "")) {
            restHeaders(accessToken)
            header("Prefer", "resolution=merge-duplicates,return=representation")
            setBody(jsonBody(rows))
        }
        response.requireSuccess("Supabase REST POST $table")
    }

    suspend fun delete(accessToken: String, table: String, query: String) {
        val response = httpClient.delete(restUrl(table, query)) { restHeaders(accessToken) }
        response.requireSuccess("Supabase REST DELETE $table")
    }

    private suspend fun authPost(path: String, body: JsonObject): AuthResponse {
        val response = httpClient.post("${config.baseUrl}/auth/v1$path") {
            header("apikey", config.publishableKey)
            setBody(jsonBody(body))
        }
        val text = response.requireSuccess("Supabase auth")
        return runCatching { CoveJson.decodeFromString<AuthResponse>(text) }
            .getOrElse { throw SupabaseException(response.status.value, "Supabase auth returned an invalid response") }
    }

    private fun AuthResponse.toRequiredSession(): SupabaseSession {
        if (accessToken.isBlank()) throw SupabaseException(502, "Supabase auth returned no access token")
        val userId = user?.id.orEmpty().ifBlank { id }.ifBlank { subFromJwt(accessToken) }
        if (userId.isBlank()) throw SupabaseException(502, "Supabase auth returned no user ID")
        return toSession(userId)
    }

    private fun AuthResponse.toSession(userId: String): SupabaseSession = SupabaseSession(
        userId = userId,
        email = user?.email.orEmpty().ifBlank { email },
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochSeconds = expiresIn?.let { epochSeconds() + it },
    )

    private fun io.ktor.client.request.HttpRequestBuilder.restHeaders(accessToken: String) {
        require(accessToken.isNotBlank()) { "Supabase REST request requires a user token" }
        header("apikey", config.publishableKey)
        header(HttpHeaders.Authorization, "Bearer $accessToken")
        header("Prefer", "return=representation")
    }

    private fun restUrl(table: String, query: String): String {
        require(table.matches(Regex("[a-z_]+"))) { "invalid Supabase table name" }
        return "${config.baseUrl}/rest/v1/$table" + if (query.isBlank()) "" else "?$query"
    }

    private suspend fun HttpResponse.requireSuccess(context: String): String {
        val text = bodyAsText()
        if (status.isSuccess()) return text
        val message = runCatching {
            val body = CoveJson.parseToJsonElement(text).jsonObject
            listOf("error_description", "error", "msg", "message")
                .firstNotNullOfOrNull { body[it]?.jsonPrimitive?.contentOrNull }
        }.getOrNull().orEmpty().ifBlank { text.ifBlank { status.description } }
        throw SupabaseException(status.value, "$context (${status.value}): $message", message)
    }

    private fun jsonBody(value: JsonElement) = TextContent(
        CoveJson.encodeToString(JsonElement.serializer(), value),
        ContentType.Application.Json,
    )

    // JWT segments are base64url with the padding stripped, which the strict
    // decoder rejects outright — hence the explicit optional-padding decoder
    // rather than Base64.UrlSafe on its own.
    private fun subFromJwt(token: String): String = runCatching {
        val payload = token.split('.').takeIf { it.size == 3 }?.get(1) ?: return@runCatching ""
        val decoded = JwtPayloadBase64.decode(payload).decodeToString()
        CoveJson.parseToJsonElement(decoded).jsonObject["sub"]?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault("")

    @Serializable
    private data class AuthResponse(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("refresh_token") val refreshToken: String = "",
        @SerialName("expires_in") val expiresIn: Long? = null,
        val user: SupabaseUser? = null,
        val id: String = "",
        val email: String = "",
    )
}
