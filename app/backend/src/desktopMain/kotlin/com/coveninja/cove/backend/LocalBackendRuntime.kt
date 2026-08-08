package com.coveninja.cove.backend

import com.coveninja.cove.backend.addons.AddonManager
import com.coveninja.cove.backend.addons.DesktopAddonUrlPolicy
import com.coveninja.cove.backend.activity.ActivityService
import com.coveninja.cove.backend.auth.AuthService
import com.coveninja.cove.backend.auth.AuthSessionStore
import com.coveninja.cove.backend.auth.ClientSessionStore
import com.coveninja.cove.backend.auth.SupabaseClient
import com.coveninja.cove.backend.auth.SupabaseSyncService
import com.coveninja.cove.backend.nuvio.NuvioManager
import com.coveninja.cove.backend.content.LocalContentRepository
import com.coveninja.cove.backend.content.TmdbClient
import com.coveninja.cove.backend.calendar.CalendarService
import com.coveninja.cove.backend.trakt.TraktService
import com.coveninja.cove.backend.platform.DeviceSettingsService
import com.coveninja.cove.backend.discovery.DiscoveryService
import com.coveninja.cove.backend.quality.QualityService
import com.coveninja.cove.backend.updater.UpdateService
import com.coveninja.cove.backend.prefetch.PrefetchService
import com.coveninja.cove.backend.http.LocalBackendHost
import com.coveninja.cove.backend.http.MediaBoundary
import com.coveninja.cove.backend.torrent.JlibtorrentPlaybackEngine
import com.coveninja.cove.backend.platform.DesktopBackendEnvironment
import com.coveninja.cove.backend.platform.DesktopConfigPaths
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Owns the complete in-process Kotlin backend used by the desktop app. */
class LocalBackendRuntime private constructor(
    private val stores: LocalStoreGraph,
    private val contentClient: HttpClient,
    private val untrustedClient: HttpClient,
    private val contentScope: CoroutineScope,
    private val host: LocalBackendHost?,
    private val media: MediaBoundary,
    content: LocalContentRepository,
) : AutoCloseable {
    val graph = AppGraph(
        content = content,
        library = stores.library,
        settings = stores.settings,
        onClose = ::close,
    )

    @Volatile
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        host?.close()
        media.close()
        contentScope.cancel()
        untrustedClient.close()
        contentClient.close()
        stores.close()
    }

    companion object {
        fun open(
            dataDirectory: Path = DesktopConfigPaths.dataDirectory(),
            tmdbApiKey: String = DesktopBackendEnvironment.tmdbApiKey(),
            startHttpHost: Boolean = true,
            host: String = "127.0.0.1",
            port: Int = 6969,
            remoteAddress: DesktopBackendEnvironment.BindAddress =
                DesktopBackendEnvironment.remoteBindAddress(port),
        ): LocalBackendRuntime {
            val stores = LocalStoreGraph.open(dataDirectory)
            val client = HttpClient(CIO) {
                install(ContentNegotiation) { json(CoveJson) }
                install(HttpTimeout) { requestTimeoutMillis = 20_000 }
            }
            val untrustedClient = HttpClient(CIO) {
                followRedirects = false
                install(ContentNegotiation) { json(CoveJson) }
                install(HttpTimeout) { requestTimeoutMillis = 25_000 }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val catalog = TmdbClient(
                    httpClient = client,
                    apiKey = tmdbApiKey,
                    localeProvider = {
                        (stores.settings.settings.value as? SettingsState.Ready)
                            ?.settings
                            ?.uiLanguage
                            .orEmpty()
                    },
                )
                val content = LocalContentRepository(catalog, scope)
                val activity = ActivityService(stores.databaseHandle, stores.profileSession)
                val calendar = CalendarService(stores.databaseHandle, stores.profileSession, catalog)
                val discovery = DiscoveryService(
                    stores.databaseHandle,
                    stores.profileSession,
                    stores.settings,
                    catalog,
                    untrustedClient,
                    DesktopAddonUrlPolicy,
                )
                val addons = AddonManager(
                    database = stores.databaseHandle,
                    session = stores.profileSession,
                    httpClient = untrustedClient,
                    now = stores.now,
                    tmdbApiKey = tmdbApiKey,
                    imdbLookup = { id -> runCatching { catalog.imdbId(id, com.coveninja.cove.shared.model.MediaType.Tv) }.getOrNull() },
                    urlPolicy = DesktopAddonUrlPolicy,
                )
                val quality = QualityService(catalog, addons)
                val media = MediaBoundary(
                    httpClient = untrustedClient,
                    imageCacheDirectory = dataDirectory.resolve("image-cache"),
                    publicUrlPolicy = DesktopAddonUrlPolicy,
                    allowLanStreamSources = {
                        (stores.settings.settings.value as? SettingsState.Ready)
                            ?.settings
                            ?.allowLanStreamSources == true
                    },
                    torrentEngine = JlibtorrentPlaybackEngine(dataDirectory.resolve("torrents")),
                )
                val nuvio = NuvioManager(
                    database = stores.databaseHandle,
                    session = stores.profileSession,
                    httpClient = untrustedClient,
                    now = stores.now,
                )
                val prefetch = PrefetchService(
                    stores.databaseHandle,
                    stores.profileSession,
                    stores.settings,
                    catalog,
                    addons,
                    nuvio,
                    scope,
                )
                val trakt = TraktService(
                    config = DesktopBackendEnvironment.traktConfig(),
                    database = stores.databaseHandle,
                    session = stores.profileSession,
                    settings = stores.settings,
                    library = stores.library,
                    catalog = catalog,
                    httpClient = client,
                    scope = scope,
                )
                val clientSessions = ClientSessionStore(stores.databaseHandle, stores.now)
                val auth = DesktopBackendEnvironment.supabaseConfig()?.let { config ->
                    val supabase = SupabaseClient(config, client)
                    AuthService(
                        client = supabase,
                        sessions = AuthSessionStore(stores.databaseHandle, stores.now),
                        profiles = stores.profiles,
                        settings = stores.settings,
                        sync = SupabaseSyncService(
                            client = supabase,
                            database = stores.databaseHandle,
                            profiles = stores.profiles,
                            library = stores.library,
                            settings = stores.settings,
                            addons = addons,
                            now = stores.now,
                            nuvio = nuvio,
                            activity = activity,
                        ),
                    )
                }
                val httpHost = stores.createHttpHost(
                    catalog,
                    addons,
                    nuvio,
                    media,
                    auth,
                    clientSessions,
                    activity,
                    calendar,
                    trakt,
                    DeviceSettingsService(dataDirectory),
                    discovery,
                    quality,
                    UpdateService(DesktopBackendEnvironment.appVersion()),
                    prefetch,
                    host,
                    port,
                    remoteAddress.host,
                    remoteAddress.port,
                ).takeIf { startHttpHost }
                httpHost?.start()
                return LocalBackendRuntime(stores, client, untrustedClient, scope, httpHost, media, content)
            } catch (error: Throwable) {
                scope.cancel()
                untrustedClient.close()
                client.close()
                stores.close()
                throw error
            }
        }
    }
}
