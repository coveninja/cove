package com.coveninja.cove.backend

import android.content.Context
import com.coveninja.cove.backend.addons.AddonManager
import com.coveninja.cove.backend.addons.AddonSyncPayload
import com.coveninja.cove.backend.addons.AndroidAddonUrlPolicy
import com.coveninja.cove.backend.addons.LocalAddonRepository
import com.coveninja.cove.backend.auth.AuthService
import com.coveninja.cove.backend.auth.AuthSessionStore
import com.coveninja.cove.backend.auth.LocalAccountRepository
import com.coveninja.cove.backend.auth.SupabaseClient
import com.coveninja.cove.backend.auth.SupabaseConfig
import com.coveninja.cove.backend.auth.SupabaseSyncService
import com.coveninja.cove.backend.calendar.CalendarService
import com.coveninja.cove.backend.calendar.LocalCalendarRepository
import com.coveninja.cove.backend.content.LocalContentRepository
import com.coveninja.cove.backend.content.TmdbClient
import com.coveninja.cove.backend.discovery.DiscoveryService
import com.coveninja.cove.backend.discovery.LocalDiscoveryRepository
import com.coveninja.cove.backend.playback.AndroidPlaybackMediaHost
import com.coveninja.cove.backend.playback.AndroidPlaybackRepository
import com.coveninja.cove.backend.torrent.AndroidJlibtorrentPlaybackEngine
import com.coveninja.cove.shared.data.AccountRepository
import com.coveninja.cove.shared.data.AddonRepository
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.CalendarRepository
import com.coveninja.cove.shared.data.DiscoveryRepository
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.data.UnavailableAccountRepository
import com.coveninja.cove.shared.data.UnavailableDeviceRepository
import com.coveninja.cove.shared.data.UnavailableTraktRepository
import com.coveninja.cove.shared.data.PlaybackRepository
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Android composition root for the same repositories used by CoveApp. */
class AndroidBackendRuntime private constructor(
    private val stores: AndroidStoreGraph,
    private val client: HttpClient,
    private val untrustedClient: HttpClient,
    private val scope: CoroutineScope,
    private val media: AndroidPlaybackMediaHost,
    content: LocalContentRepository,
    playback: PlaybackRepository,
    addons: AddonRepository,
    calendar: CalendarRepository,
    discovery: DiscoveryRepository,
    account: AccountRepository,
) : AutoCloseable {
    private var closed = false

    val graph = AppGraph(
        content = content,
        library = stores.repositories.library,
        settings = stores.repositories.settings,
        playback = playback,
        addons = addons,
        // The calendar needs only the database and TMDB, both of which Android has.
        calendar = calendar,
        // Likewise discovery, now that DiscoveryService lives in commonMain: Explore is
        // the same page here as on the desktop, personalized rails included.
        discovery = discovery,
        // Sync is the whole point of an account on a phone: the library, settings
        // and watch progress here are the ones the desktop already has.
        account = account,
        profiles = stores.repositories.profiles,
        // Trakt scrobbling and the mpv config file are both desktop-only.
        trakt = UnavailableTraktRepository,
        device = UnavailableDeviceRepository,
        onClose = ::close,
    )

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        media.close()
        untrustedClient.close()
        client.close()
        stores.close()
    }

    companion object {
        /**
         * [supabaseUrl] and [supabaseKey] come from the app's BuildConfig. Blank
         * means this build has no account backend, and the account settings say
         * so rather than offering a sign-in that cannot work.
         */
        // Mirrors DesktopBackendEnvironment.supabaseConfig: a blank URL means the
        // feature is off, but a URL without a key is a broken build, not a
        // silently disabled one.
        private fun supabaseConfig(url: String, key: String): SupabaseConfig? {
            if (url.isBlank()) return null
            require(key.isNotBlank()) {
                "SUPABASE_PUBLISHABLE_KEY is required when SUPABASE_URL is set"
            }
            return SupabaseConfig(url, key)
        }

        fun open(
            context: Context,
            tmdbApiKey: String,
            supabaseUrl: String = "",
            supabaseKey: String = "",
        ): AndroidBackendRuntime {
            val stores = AndroidStoreGraph.open(context)
            val client = HttpClient(OkHttp) {
                install(ContentNegotiation) { json(CoveJson) }
                install(HttpTimeout) { requestTimeoutMillis = 20_000 }
            }
            // Addon and custom-discovery URLs are user controlled. Do not follow a
            // public URL's redirect into a private network; the policy validates the
            // original destination, and callers can explicitly install the final URL.
            val untrustedClient = HttpClient(OkHttp) {
                followRedirects = false
                install(ContentNegotiation) { json(CoveJson) }
                install(HttpTimeout) { requestTimeoutMillis = 25_000 }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var openedMedia: AndroidPlaybackMediaHost? = null
            try {
                val catalog = TmdbClient(
                    httpClient = client,
                    apiKey = tmdbApiKey,
                    localeProvider = {
                        (stores.repositories.settings.settings.value as? SettingsState.Ready)
                            ?.settings
                            ?.uiLanguage
                            .orEmpty()
                    },
                )
                val content = LocalContentRepository(catalog, scope)
                val addonManager = AddonManager(
                    database = stores.databaseHandle,
                    session = stores.repositories.profileSession,
                    httpClient = untrustedClient,
                    now = stores.now,
                    tmdbApiKey = tmdbApiKey,
                    imdbLookup = { id ->
                        runCatching { catalog.imdbId(id, MediaType.Tv) }.getOrNull()
                    },
                    urlPolicy = AndroidAddonUrlPolicy,
                )
                val addons = LocalAddonRepository(
                    addons = addonManager,
                    activeProfileIds = stores.repositories.profileSession.profileId,
                    scope = scope,
                )
                val media = AndroidPlaybackMediaHost.start(
                    httpClient = untrustedClient,
                    publicUrlPolicy = AndroidAddonUrlPolicy,
                    allowLanStreamSources = {
                        (stores.repositories.settings.settings.value as? SettingsState.Ready)
                            ?.settings
                            ?.allowLanStreamSources == true
                    },
                    torrentEngine = AndroidJlibtorrentPlaybackEngine(
                        context.filesDir.resolve("torrents").toPath(),
                    ),
                ).also { openedMedia = it }
                val playback = AndroidPlaybackRepository(catalog, addonManager, media)
                val calendar = LocalCalendarRepository(
                    service = CalendarService(
                        database = stores.databaseHandle,
                        session = stores.repositories.profileSession,
                        catalog = catalog,
                    ),
                    database = stores.databaseHandle,
                    session = stores.repositories.profileSession,
                    library = stores.repositories.library,
                )
                // The custom-algorithm hook is user-controlled network input, so it uses
                // the same redirect and resolved-address restrictions as addon manifests.
                val discovery = LocalDiscoveryRepository(
                    catalog = catalog,
                    service = DiscoveryService(
                        database = stores.databaseHandle,
                        session = stores.repositories.profileSession,
                        settings = stores.repositories.settings,
                        catalog = catalog,
                        customHttpClient = untrustedClient,
                        customUrlPolicy = AndroidAddonUrlPolicy,
                    ),
                )
                // Android owns the addon list now, so it merges that sync payload.
                // Nuvio still has no Android sandbox and remains an opaque passthrough.
                val account = supabaseConfig(supabaseUrl, supabaseKey)?.let { config ->
                    val supabase = SupabaseClient(config, client)
                    val now = stores.now
                    LocalAccountRepository(
                        auth = AuthService(
                            client = supabase,
                            sessions = AuthSessionStore(stores.databaseHandle, now),
                            profiles = stores.repositories.profiles,
                            settings = stores.repositories.settings,
                            sync = SupabaseSyncService(
                                client = supabase,
                                database = stores.databaseHandle,
                                profiles = stores.repositories.profiles,
                                library = stores.repositories.library,
                                settings = stores.repositories.settings,
                                now = now,
                                payloads = listOf(
                                    AddonSyncPayload(addonManager, addons::reload),
                                ),
                            ),
                        ),
                        settings = stores.repositories.settings,
                        library = stores.repositories.library,
                        scope = scope,
                    )
                } ?: UnavailableAccountRepository
                return AndroidBackendRuntime(
                    stores, client, untrustedClient, scope, media, content, playback, addons,
                    calendar, discovery, account,
                )
            } catch (error: Throwable) {
                scope.cancel()
                openedMedia?.close()
                untrustedClient.close()
                client.close()
                stores.close()
                throw error
            }
        }
    }
}
