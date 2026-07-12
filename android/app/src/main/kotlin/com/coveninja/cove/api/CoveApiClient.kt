package com.coveninja.cove.api

import com.coveninja.cove.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object CoveApiClient {

    // BASE and remoteToken are updated atomically by applyMode() when the user
    // switches between Local and Remote server modes.
    @Volatile var BASE: String = BuildConfig.BACKEND_URL
        private set

    // null in Local mode; the pairing token in Remote mode.
    @Volatile private var remoteToken: String? = null

    // Supabase JWT — null when signed out. Set by setJwt() and cleared on sign-out.
    @Volatile private var jwtToken: String? = null

    // @PublishedApi makes these accessible from public inline functions.
    @PublishedApi
    internal val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @PublishedApi
    internal val http: OkHttpClient = run {
        val dispatcher = Dispatcher().also { it.maxRequestsPerHost = 8 }
        OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .dispatcher(dispatcher)
            // Inject X-Cove-Token (Remote mode) and Authorization: Bearer <jwt>
            // (Supabase session) on every request. Both are no-ops when their
            // respective fields are null.
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                remoteToken?.let { builder.header("X-Cove-Token", it) }
                jwtToken?.let { builder.header("Authorization", "Bearer $it") }
                chain.proceed(builder.build())
            }
            .build()
    }

    /**
     * Updates the active Supabase JWT. Pass null to clear (sign-out / 401).
     * Called from CoveApplication at startup (pre-loaded from TokenStore) and
     * by AuthViewModel on sign-in / sign-out.
     */
    fun setJwt(token: String?) {
        jwtToken = token
    }

    /**
     * Applies the given [mode], updating BASE and the token interceptor.
     * Called by ServerModeStore when the user saves a new server configuration.
     */
    fun applyMode(mode: ServerMode) {
        when (mode) {
            is ServerMode.Local -> {
                BASE = BuildConfig.BACKEND_URL
                remoteToken = null
            }
            is ServerMode.Remote -> {
                BASE = mode.baseUrl
                remoteToken = mode.token
            }
        }
    }

    suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE$path").get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: "null"
            json.decodeFromString<T>(body)
        }
    }

    // Returns null on 404 or exception — for endpoints where "nothing yet" is normal.
    suspend inline fun <reified T> getOrNull(path: String): T? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE$path").get().build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                json.decodeFromString<T>(body)
            }
        } catch (e: Exception) { null }
    }

    suspend inline fun <reified T> post(path: String, body: String): T? = withContext(Dispatchers.IO) {
        try {
            val rb = body.toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$BASE$path").post(rb).build()
            http.newCall(req).execute().use { resp ->
                val b = resp.body?.string() ?: return@withContext null
                json.decodeFromString<T>(b)
            }
        } catch (e: Exception) { null }
    }

    suspend inline fun <reified T> put(path: String, body: String): T? = withContext(Dispatchers.IO) {
        try {
            val rb = body.toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$BASE$path").put(rb).build()
            http.newCall(req).execute().use { resp ->
                val b = resp.body?.string() ?: return@withContext null
                json.decodeFromString<T>(b)
            }
        } catch (e: Exception) { null }
    }

    suspend fun patch(path: String, body: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val rb = body.toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$BASE$path").patch(rb).build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE$path").delete().build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    /**
     * POST returning (httpStatusCode, responseBodyString). Used by auth endpoints
     * where the caller must distinguish 200 / 401 / 503 / network-error to drive
     * UI state (signed-in, wrong-credentials, auth-unavailable, offline).
     * Does not throw on non-2xx — returns the status code and body as-is.
     * Returns (-1, errorMessage) on network error.
     */
    suspend fun postForStatus(path: String, body: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            try {
                val rb = body.toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("$BASE$path").post(rb).build()
                http.newCall(req).execute().use { resp ->
                    Pair(resp.code, resp.body?.string() ?: "")
                }
            } catch (e: Exception) {
                Pair(-1, e.message ?: "Network error")
            }
        }

    /**
     * GET returning (httpStatusCode, responseBodyString). Used by auth probe
     * endpoints (e.g. /api/auth/sync) that need explicit status discrimination.
     */
    suspend fun getForStatus(path: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("$BASE$path").get().build()
                http.newCall(req).execute().use { resp ->
                    Pair(resp.code, resp.body?.string() ?: "")
                }
            } catch (e: Exception) {
                Pair(-1, e.message ?: "Network error")
            }
        }

    /**
     * Probes [candidateBaseUrl]/api/ping with [candidateToken]. Used by the Settings
     * screen's "Test connection" button before persisting a new Remote configuration.
     * Creates a one-shot OkHttp client so it never affects the main client's state.
     */
    suspend fun testConnection(candidateBaseUrl: String, candidateToken: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val base = candidateBaseUrl.trimEnd('/')
                val pingUrl = if (base.endsWith("/api")) {
                    base.dropLast(4) + "/api/ping"
                } else {
                    "$base/api/ping"
                }
                val req = Request.Builder()
                    .url(pingUrl)
                    .header("X-Cove-Token", candidateToken)
                    .get()
                    .build()
                OkHttpClient.Builder()
                    .callTimeout(10, TimeUnit.SECONDS)
                    .build()
                    .newCall(req)
                    .execute()
                    .use { it.isSuccessful }
            } catch (e: Exception) { false }
        }

    /**
     * Rewrites an image URL from the backend so it uses the same host:port as BASE.
     * The Go backend always hardcodes 127.0.0.1:6969 in image URLs; in Remote mode
     * this rewrites those to the configured remote host so images load correctly.
     * In Remote mode the token is also appended as ?token= because the /api/img/
     * proxy endpoint requires authentication on the LAN listener.
     * Returns null for blank/empty input so it can be passed directly to AsyncImage.
     */
    fun resolveImgUrl(url: String): String? {
        if (url.isBlank()) return null
        val baseHost = BASE.substringBefore("/api") // e.g. "http://10.0.2.2:6969"
        val rewritten = url.replaceFirst(Regex("""http://127\.0\.0\.1:\d+"""), baseHost)
        val token = remoteToken
        return if (token != null) appendQueryToken(rewritten, token) else rewritten
    }

    /**
     * Builds a full play URL. For a bare infohash, wraps it in /api/play?hash=…
     * mirroring api.ts playUrl(). For an absolute http URL, returns it as-is.
     * In Remote mode, ?token= is appended so mpv (which opens the URL directly
     * and cannot inject custom headers) is authenticated by the LAN listener.
     */
    fun playUrl(src: String, season: Int? = null, episode: Int? = null): String {
        val token = remoteToken
        if (src.startsWith("http", ignoreCase = true)) {
            // Direct stream URL — append token for mpv if in Remote mode.
            return if (token != null) appendQueryToken(src, token) else src
        }
        val sb = StringBuilder("$BASE/play?hash=").append(src)
        if (season != null) sb.append("&season=").append(season)
        if (episode != null) sb.append("&episode=").append(episode)
        // mpv opens the play URL directly; it cannot inject X-Cove-Token.
        if (token != null) sb.append("&token=").append(token)
        return sb.toString()
    }

    private fun appendQueryToken(url: String, token: String): String {
        val sep = if ('?' in url) '&' else '?'
        return "$url${sep}token=$token"
    }
}
