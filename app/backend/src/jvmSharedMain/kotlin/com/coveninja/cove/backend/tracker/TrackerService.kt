package com.coveninja.cove.backend.tracker

import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.ExternalHistoryItem
import com.coveninja.cove.backend.store.ExternalWatchlistItem
import com.coveninja.cove.backend.store.LocalLibraryRepository
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.TrackerProvider
import com.coveninja.cove.shared.model.TrackerStats
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrackerPollRequest(@SerialName("device_code") val deviceCode: String)

@Serializable
data class TrackerPollResponse(val status: String, val username: String = "")

@Serializable
data class TrackerStatus(
    val connected: Boolean,
    val username: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    /** The sync cursor, so a caller can say when this account was last reconciled. */
    @SerialName("last_sync_at") val lastSyncAt: String = "",
    @SerialName("flow_state") val flowState: String = "idle",
)

@Serializable
data class TrackerScrobbleRequest(
    val action: String,
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("media_type") val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
    val progress: Double,
)

@Serializable
data class TrackerSyncResult(val completed: Boolean, val reason: String = "")

/** What a pull produced, before it is merged into the library. */
internal data class TrackerPull(
    val history: List<ExternalHistoryItem> = emptyList(),
    val watchlist: List<ExternalWatchlistItem> = emptyList(),
    /** False when the tracker's own activity timestamps say nothing has changed. */
    val fetched: Boolean = true,
)

/**
 * Profile-scoped account linking, scrobbling and additive two-way sync, shared by every
 * tracker.
 *
 * Everything here is mechanism rather than protocol: the settings gates, the session row,
 * the device-flow job, the throttled writes, the background loop, and the order the sync
 * cycle runs in. Subclasses supply the endpoints and the payload shapes, which is the only
 * part the two trackers genuinely disagree about.
 *
 * Note that [provider] is a constructor parameter rather than an abstract val, and that
 * nothing overridable is called from `init`. Base-class initialisers run before a
 * subclass's, so either would read a field the subclass has not assigned yet — the
 * background loop reads [isConfigured] only after its first delay, by which point
 * construction has finished.
 *
 * Lives in `jvmSharedMain` because it wants `java.time` and `ConcurrentHashMap`; both
 * hosts compile that source set, so a phone and a desktop scrobble identically.
 */
abstract class TrackerService(
    val provider: TrackerProvider,
    protected val database: CoveDatabase,
    protected val session: ActiveProfileSession,
    protected val settings: LocalSettingsRepository,
    protected val library: LocalLibraryRepository,
    protected val catalog: MediaCatalog,
    httpClient: HttpClient,
    protected val scope: CoroutineScope,
    protected val clock: Clock = Clock.systemUTC(),
    baseUrl: String,
    minimumWriteIntervalMillis: Long = 1_100,
    /**
     * How long a scrobble for one item suppresses the next. Zero for Trakt, which does not
     * mind the repeats; Simkl holds a 20-second per-user lock and rejects anything inside
     * it, while the progress ticker behind these fires every ten.
     */
    private val scrobbleDebounceMillis: Long = 0,
    startBackgroundSync: Boolean = true,
) {
    protected val sessions = TrackerSessionStore(database, provider)
    protected val links = DeviceLinkCoordinator(scope, clock)
    private val refreshMutex = Mutex()
    private val lastScrobbleMillis = ConcurrentHashMap<String, Long>()

    protected val http = TrackerHttp(
        provider = provider,
        httpClient = httpClient,
        baseUrl = baseUrl,
        clock = clock,
        minimumWriteIntervalMillis = minimumWriteIntervalMillis,
        decorate = { accessToken -> decorate(accessToken) },
    )

    abstract val isConfigured: Boolean

    init {
        if (startBackgroundSync) {
            scope.launch {
                delay(BACKGROUND_START_DELAY_MILLIS)
                if (!isConfigured) return@launch
                while (isActive) {
                    runCatching { syncNow() }
                    delay(BACKGROUND_INTERVAL_MILLIS)
                }
            }
        }
    }

    // ---- protocol, supplied per provider -----------------------------------------

    /** Provider headers and query parameters. The bearer token is added by [TrackerHttp]. */
    protected abstract fun HttpRequestBuilder.decorate(accessToken: String)

    protected abstract fun scrobbleEnabled(settings: AppSettings): Boolean
    protected abstract fun syncEnabled(settings: AppSettings): Boolean

    abstract suspend fun startDeviceFlow(): TrackerDeviceCode

    /**
     * One poll of a pending link, which persists the session itself when it authorises.
     *
     * Takes the whole [TrackerDeviceCode] because the two flows poll with different
     * halves of it: Trakt posts the device code back, Simkl asks about the user code the
     * viewer can see.
     */
    protected abstract suspend fun pollOnce(
        code: TrackerDeviceCode,
        profileId: String,
    ): LinkPoll

    protected abstract suspend fun sendScrobble(
        token: TrackerSession,
        request: TrackerScrobbleRequest,
    )

    // Internal rather than protected: the library items these carry are internal to the
    // backend module, and a protected member cannot expose them.
    internal abstract suspend fun pull(token: TrackerSession, cursor: Instant?): TrackerPull

    internal abstract suspend fun push(token: TrackerSession, cursor: Instant?)

    /** All-time totals, or null for anything at all going wrong. */
    abstract suspend fun stats(): TrackerStats?

    /**
     * Whether [refreshIfNeeded] is worth the mutex. False by default, which is right for
     * a tracker whose tokens do not expire and keeps every scrobble off the lock.
     */
    protected open fun needsRefresh(current: TrackerSession): Boolean = false

    /** Trakt trades an expiring token for a fresh one here; Simkl has nothing to trade. */
    protected open suspend fun refreshIfNeeded(
        profileId: String,
        current: TrackerSession,
    ): TrackerSession? = current

    /** Trakt revokes remotely on unlink; Simkl publishes no such endpoint. */
    protected open fun revokeRemote(accessToken: String) = Unit

    /** Only Trakt has a pre-SQLite payload to adopt; the default is a no-op. */
    protected open fun importLegacy(profileId: String) = Unit

    // ---- shared mechanism --------------------------------------------------------

    /**
     * Polls a link the caller is driving itself, over HTTP.
     *
     * The string is whichever code that tracker polls with — the device code for Trakt,
     * the user code for Simkl — which is why it arrives in both halves of the code object
     * below rather than being guessed at here.
     */
    suspend fun poll(code: String): TrackerPollResponse {
        requireConfigured()
        require(code.isNotBlank()) { "device_code is required" }
        val profileId = session.profileId.value
        val outcome = pollOnce(TrackerDeviceCode(code, code, "", 0), profileId)
        return TrackerPollResponse(
            status = when (outcome) {
                LinkPoll.Authorized -> DeviceLinkCoordinator.AUTHORIZED
                LinkPoll.Pending -> DeviceLinkCoordinator.PENDING
                LinkPoll.SlowDown -> "slow_down"
                LinkPoll.Denied -> "denied"
                LinkPoll.Invalid -> "invalid"
                LinkPoll.Expired -> "expired"
            },
            username = if (outcome == LinkPoll.Authorized) {
                stored(profileId)?.username.orEmpty()
            } else {
                ""
            },
        )
    }

    fun status(): TrackerStatus {
        val profileId = session.profileId.value
        val stored = stored(profileId)
        return TrackerStatus(
            connected = stored != null,
            username = stored?.username.orEmpty(),
            expiresAt = stored?.expiresAt?.takeIf { it > 0 }
                ?.let(Instant::ofEpochSecond)?.toString().orEmpty(),
            lastSyncAt = stored?.lastSyncAt.orEmpty(),
            flowState = links.state(profileId),
        )
    }

    fun unlink() {
        val profileId = session.profileId.value
        links.reset(profileId)
        val token = stored(profileId)?.accessToken.orEmpty()
        sessions.delete(profileId)
        lastScrobbleMillis.clear()
        if (token.isNotBlank() && isConfigured) revokeRemote(token)
    }

    /** Returns false when the feature is disabled, debounced, or the profile is unlinked. */
    fun enqueueScrobble(request: TrackerScrobbleRequest): Boolean {
        validateScrobble(request)
        val profileId = session.profileId.value
        if (!scrobbleAllowed(profileId, request)) return false
        scope.launch { runCatching { deliverScrobble(profileId, request) } }
        return true
    }

    /** Direct form used by in-process callers; HTTP keeps using the async wrapper above. */
    suspend fun scrobbleNow(request: TrackerScrobbleRequest): Boolean {
        validateScrobble(request)
        val profileId = session.profileId.value
        if (!scrobbleAllowed(profileId, request)) return false
        deliverScrobble(profileId, request)
        return true
    }

    fun enqueueSync(): Boolean {
        if (!isConfigured || !syncEnabled(currentSettings() ?: return false)) return false
        if (stored(session.profileId.value) == null) return false
        scope.launch { runCatching { syncNow() } }
        return true
    }

    suspend fun syncNow(): TrackerSyncResult {
        requireConfigured()
        if (!syncEnabled(currentSettings() ?: return TrackerSyncResult(false, "disabled"))) {
            return TrackerSyncResult(false, "disabled")
        }
        val profileId = session.profileId.value
        var token = ensureValidToken(profileId) ?: return TrackerSyncResult(false, "not_connected")
        val cursor = token.lastSyncAt.toInstantOrNull()
        val cycleStart = clock.instant()

        val pulled = pull(token, cursor)
        if (pulled.fetched) library.applyExternal(pulled.history, pulled.watchlist)
        push(token, cursor)

        // A profile switch during network work must not advance another profile's cursor.
        // The token remains correctly scoped to profileId.
        token = stored(profileId) ?: return TrackerSyncResult(false, "unlinked_during_sync")
        save(profileId, token.copy(lastSyncAt = cycleStart.toString()))
        return TrackerSyncResult(true)
    }

    protected fun stored(profileId: String): TrackerSession? {
        importLegacy(profileId)
        return sessions.read(profileId)
    }

    protected fun save(profileId: String, value: TrackerSession) = sessions.write(profileId, value)

    protected suspend fun ensureValidToken(profileId: String): TrackerSession? {
        val current = stored(profileId) ?: return null
        if (!needsRefresh(current)) return current
        return refreshMutex.withLock {
            val latest = stored(profileId) ?: return@withLock null
            if (needsRefresh(latest)) refreshIfNeeded(profileId, latest) else latest
        }
    }

    protected fun requireConfigured() {
        check(isConfigured) {
            "${provider.label} integration not configured (credentials missing)"
        }
    }

    protected fun currentSettings(): AppSettings? =
        (settings.settings.value as? SettingsState.Ready)?.settings

    private fun scrobbleAllowed(profileId: String, request: TrackerScrobbleRequest): Boolean {
        if (!isConfigured || stored(profileId) == null) return false
        if (!scrobbleEnabled(currentSettings() ?: return false)) return false
        if (scrobbleDebounceMillis <= 0) return true
        // "stop" is the event that marks a title watched, so it is never dropped; only the
        // repeating progress reports in between are worth suppressing.
        if (request.action == "stop") return true
        val key = "$profileId:${request.tmdbId}:${request.season}:${request.episode}"
        val now = clock.millis()
        val last = lastScrobbleMillis[key]
        if (last != null && now - last < scrobbleDebounceMillis) return false
        lastScrobbleMillis[key] = now
        return true
    }

    private suspend fun deliverScrobble(profileId: String, request: TrackerScrobbleRequest) {
        val token = ensureValidToken(profileId) ?: return
        sendScrobble(token, request)
    }

    private fun validateScrobble(request: TrackerScrobbleRequest) {
        require(request.action in setOf("start", "pause", "stop")) {
            "action must be start, pause, or stop"
        }
        require(request.tmdbId > 0 && request.mediaType in setOf("movie", "tv")) {
            "positive tmdb_id and media_type movie or tv required"
        }
        require(request.progress in 0.0..100.0) { "progress must be between 0 and 100" }
        if (request.mediaType == "tv") {
            require(
                request.season != null && request.episode != null &&
                    request.season >= 0 && request.episode >= 0,
            ) {
                "tv scrobbles require non-negative season and episode"
            }
        }
    }

    companion object {
        private const val BACKGROUND_START_DELAY_MILLIS = 60_000L
        private const val BACKGROUND_INTERVAL_MILLIS = 6 * 60 * 60 * 1_000L
    }
}

internal fun String.toInstantOrNull(): Instant? {
    if (isBlank()) return null
    return runCatching { Instant.parse(this) }.getOrElse {
        runCatching { java.time.OffsetDateTime.parse(this).toInstant() }.getOrNull()
    }
}
