package com.coveninja.cove.backend

import android.content.Context
import android.os.Trace
import com.coveninja.cove.backend.addons.AddonManager
import com.coveninja.cove.backend.addons.AddonSyncPayload
import com.coveninja.cove.backend.addons.AndroidAddonUrlPolicy
import com.coveninja.cove.backend.addons.LocalAddonRepository
import com.coveninja.cove.backend.activity.ActivityService
import com.coveninja.cove.backend.activity.ActivitySyncPayload
import com.coveninja.cove.backend.auth.AuthService
import com.coveninja.cove.backend.auth.AuthSessionStore
import com.coveninja.cove.backend.auth.ClientSessionStore
import com.coveninja.cove.backend.auth.LocalAccountRepository
import com.coveninja.cove.backend.auth.SupabaseClient
import com.coveninja.cove.backend.auth.SupabaseConfig
import com.coveninja.cove.backend.auth.SupabaseSyncService
import com.coveninja.cove.backend.calendar.CalendarService
import com.coveninja.cove.backend.calendar.LocalCalendarRepository
import com.coveninja.cove.backend.content.LocalContentRepository
import com.coveninja.cove.backend.content.TmdbClient
import com.coveninja.cove.backend.content.resolveAppLocale
import com.coveninja.cove.backend.discovery.DiscoveryService
import com.coveninja.cove.backend.discovery.LocalDiscoveryRepository
import com.coveninja.cove.backend.insights.LocalInsightsRepository
import com.coveninja.cove.backend.http.CoreRouteServices
import com.coveninja.cove.backend.http.LocalBackendHost
import com.coveninja.cove.backend.http.RouteUpdater
import com.coveninja.cove.backend.nuvio.AndroidNuvioSandbox
import com.coveninja.cove.backend.nuvio.NuvioAddonService
import com.coveninja.cove.backend.nuvio.NuvioManager
import com.coveninja.cove.backend.nuvio.NuvioSyncPayload
import com.coveninja.cove.backend.playback.AndroidPlaybackMediaHost
import com.coveninja.cove.backend.playback.AndroidPlaybackRepository
import com.coveninja.cove.backend.playback.LazyAndroidPlaybackMediaHost
import com.coveninja.cove.backend.platform.AndroidDeviceRepository
import com.coveninja.cove.backend.platform.AndroidTorrentCachePolicyStore
import com.coveninja.cove.backend.storage.CacheDirectories
import com.coveninja.cove.backend.storage.CacheStorageService
import com.coveninja.cove.backend.storage.LocalStorageRepository
import com.coveninja.cove.backend.storage.TorrentCacheJournal
import com.coveninja.cove.backend.torrent.TorrentPlaybackEngine
import com.coveninja.cove.shared.data.StorageRepository
import com.coveninja.cove.shared.data.TorrentCachePolicy
import com.coveninja.cove.backend.prefetch.PrefetchService
import com.coveninja.cove.backend.quality.QualityService
import com.coveninja.cove.backend.torrent.AndroidJlibtorrentPlaybackEngine
import com.coveninja.cove.backend.trakt.LocalTraktRepository
import com.coveninja.cove.backend.trakt.TraktConfig
import com.coveninja.cove.backend.trakt.TraktScrobbleRequest
import com.coveninja.cove.backend.trakt.TraktService
import com.coveninja.cove.backend.updater.SignedUpdateService
import com.coveninja.cove.backend.updater.createAndroidUpdateRepository
import com.coveninja.cove.shared.data.AccountRepository
import com.coveninja.cove.shared.data.AddonRepository
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.CalendarRepository
import com.coveninja.cove.shared.data.DiscoveryRepository
import com.coveninja.cove.shared.data.InsightsRepository
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.data.UnavailableAccountRepository
import com.coveninja.cove.shared.data.UnavailableDeviceRepository
import com.coveninja.cove.shared.data.TraktRepository
import com.coveninja.cove.shared.data.DeviceRepository
import com.coveninja.cove.shared.data.PlaybackRepository
import com.coveninja.cove.shared.data.UpdateRepository
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.network.CoveJson
import com.coveninja.cove.shared.network.UpdateCheckDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Android composition root for the same repositories used by CoveApp. */
class AndroidBackendRuntime private constructor(
    private val stores: AndroidStoreGraph,
    private val client: HttpClient,
    private val untrustedClient: HttpClient,
    private val scope: CoroutineScope,
    private val media: LazyAndroidPlaybackMediaHost,
    private val routeServices: CoreRouteServices,
    content: LocalContentRepository,
    playback: PlaybackRepository,
    addons: AddonRepository,
    calendar: CalendarRepository,
    discovery: DiscoveryRepository,
    insights: InsightsRepository,
    account: AccountRepository,
    trakt: TraktRepository,
    device: DeviceRepository,
    private val updateRepository: UpdateRepository,
    storage: StorageRepository,
) : AutoCloseable {
    private var closed = false
    private var remoteHost: LocalBackendHost? = null

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
        // Watch-time counters live in jvmSharedMain and the taste profile in commonMain, so
        // the insights page is the same page here as on the desktop.
        insights = insights,
        // Sync is the whole point of an account on a phone: the library, settings
        // and watch progress here are the ones the desktop already has.
        account = account,
        profiles = stores.repositories.profiles,
        trakt = trakt,
        device = device,
        updates = updateRepository,
        // Android keeps torrent downloads under filesDir, which the system never reclaims, so
        // the storage screen is if anything more load-bearing here than on the desktop.
        storage = storage,
        onClose = ::close,
    )

    /** Starts the authenticated desktop-compatible LAN API while its foreground service is alive. */
    @Synchronized
    fun startRemoteAccessHost() {
        check(!closed) { "backend runtime is closed" }
        if (remoteHost != null) return
        remoteHost = LocalBackendHost(
            services = routeServices,
            host = "127.0.0.1",
            port = LOOPBACK_API_PORT,
            remoteHost = "0.0.0.0",
            remotePort = REMOTE_API_PORT,
        ).also(LocalBackendHost::start)
    }

    @Synchronized
    fun stopRemoteAccessHost() {
        remoteHost?.close()
        remoteHost = null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        remoteHost?.close()
        remoteHost = null
        scope.cancel()
        media.close()
        (updateRepository as? AutoCloseable)?.close()
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
            traktClientId: String = "",
            traktClientSecret: String = "",
            appVersion: String = "dev",
            updatePublicKeys: String = "",
            updateApiBase: String = SignedUpdateService.DEFAULT_API_BASE,
            systemLocale: StateFlow<String> = MutableStateFlow(
                context.resources.configuration.locales[0].toLanguageTag(),
            ),
        ): AndroidBackendRuntime {
            val stores = tracedStartup("Cove startup stores") {
                AndroidStoreGraph.open(context)
            }
            val client = tracedStartup("Cove startup HTTP client") {
                HttpClient(OkHttp) {
                    engine { config { fastFallback(false) } }
                    install(ContentNegotiation) { json(CoveJson) }
                    install(HttpTimeout) { requestTimeoutMillis = 20_000 }
                }
            }
            // Addon and custom-discovery URLs are user controlled. Do not follow a
            // public URL's redirect into a private network; the policy validates the
            // original destination, and callers can explicitly install the final URL.
            val untrustedClient = tracedStartup("Cove startup untrusted HTTP client") {
                HttpClient(OkHttp) {
                    engine { config { fastFallback(false) } }
                    followRedirects = false
                    install(ContentNegotiation) { json(CoveJson) }
                    install(HttpTimeout) { requestTimeoutMillis = 25_000 }
                }
            }
            val scope = backendScope("runtime")
            var openedMedia: LazyAndroidPlaybackMediaHost? = null
            try {
                return tracedStartup("Cove startup repositories") {
                    val localeProvider = {
                        val profileOverride =
                            (stores.repositories.settings.settings.value as? SettingsState.Ready)
                                ?.settings
                                ?.uiLanguage
                                .orEmpty()
                        resolveAppLocale(profileOverride, systemLocale.value)
                    }
                    val localeChanges = combine(
                        stores.repositories.settings.settings,
                        systemLocale,
                    ) { _, _ -> localeProvider() }.distinctUntilChanged()
                    val catalog = TmdbClient(
                        httpClient = client,
                        apiKey = tmdbApiKey,
                        localeProvider = localeProvider,
                    )
                    val content = LocalContentRepository(
                        catalog = catalog,
                        scope = scope,
                        localeChanges = localeChanges,
                        initialLocale = localeProvider(),
                    )
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
                    val nuvio = NuvioManager(
                        database = stores.databaseHandle,
                        session = stores.repositories.profileSession,
                        httpClient = untrustedClient,
                        now = stores.now,
                        sandbox = AndroidNuvioSandbox(context, untrustedClient, AndroidAddonUrlPolicy),
                        urlPolicy = AndroidAddonUrlPolicy,
                    )
                    val addons = LocalAddonRepository(
                        addons = addonManager,
                        activeProfileIds = stores.repositories.profileSession.profileId,
                        scope = scope,
                        nuvio = NuvioAddonService(nuvio),
                    )
                    val torrentDirectory = context.filesDir.resolve("torrents").toPath()
                    val cacheJournal = TorrentCacheJournal(torrentDirectory)
                    val cachePolicyStore = AndroidTorrentCachePolicyStore(context)
                    val cachePolicy = MutableStateFlow(
                        runCatching(cachePolicyStore::read).getOrDefault(TorrentCachePolicy()),
                    )
                    // Late-bound on purpose: the engine is built inside the lazy media host, and
                    // forcing it up front would start a torrent session on every launch just so
                    // the storage screen could ask what is playing. Until it exists nothing can
                    // be playing, which is exactly what an absent reference reports.
                    val engineRef = java.util.concurrent.atomic.AtomicReference<TorrentPlaybackEngine?>()
                    val media = LazyAndroidPlaybackMediaHost {
                        AndroidPlaybackMediaHost.start(
                            httpClient = untrustedClient,
                            publicUrlPolicy = AndroidAddonUrlPolicy,
                            allowLanStreamSources = {
                                (stores.repositories.settings.settings.value as? SettingsState.Ready)
                                    ?.settings
                                    ?.allowLanStreamSources == true
                            },
                            torrentEngine = AndroidJlibtorrentPlaybackEngine(
                                downloadDirectory = torrentDirectory,
                                policy = cachePolicy::value,
                                journal = cacheJournal,
                            ).also(engineRef::set),
                        )
                    }.also { openedMedia = it }
                    val storage = LocalStorageRepository(
                        service = CacheStorageService(
                            // Torrents only, and both omissions are deliberate. yt-dlp is
                            // bundled in the APK here rather than downloaded, so there is no
                            // tools directory at all. Artwork is Coil's own disk cache: it is
                            // already capped, it sits in cacheDir where the system can reclaim
                            // it, and it is journalled — deleting its files from underneath it
                            // is not the same operation as clearing it.
                            directories = CacheDirectories(torrents = torrentDirectory),
                            journal = cacheJournal,
                            activeHashes = { engineRef.get()?.activeHashes().orEmpty() },
                            release = { hash -> engineRef.get()?.release(hash) ?: true },
                        ),
                        store = cachePolicyStore,
                        state = cachePolicy,
                    ).also { it.start(scope) }
                    val playback = AndroidPlaybackRepository(catalog, addonManager, media, nuvio)
                    val calendarService = CalendarService(
                        database = stores.databaseHandle,
                        session = stores.repositories.profileSession,
                        catalog = catalog,
                    )
                    val calendar = LocalCalendarRepository(
                        service = calendarService,
                        database = stores.databaseHandle,
                        session = stores.repositories.profileSession,
                        library = stores.repositories.library,
                        localeProvider = localeProvider,
                    )
                    // The custom-algorithm hook is user-controlled network input, so it uses
                    // the same redirect and resolved-address restrictions as addon manifests.
                    val discoveryService = DiscoveryService(
                        database = stores.databaseHandle,
                        session = stores.repositories.profileSession,
                        settings = stores.repositories.settings,
                        catalog = catalog,
                        customHttpClient = untrustedClient,
                        customUrlPolicy = AndroidAddonUrlPolicy,
                    )
                    val discovery = LocalDiscoveryRepository(
                        catalog = catalog,
                        service = discoveryService,
                        localeProvider = localeProvider,
                    )
                    scope.launch {
                        localeChanges.drop(1).collectLatest {
                            calendar.refresh(force = true)
                        }
                    }
                    val activity = ActivityService(
                        stores.databaseHandle,
                        stores.repositories.profileSession,
                    )
                    val traktService = TraktService(
                        config = TraktConfig(traktClientId, traktClientSecret),
                        database = stores.databaseHandle,
                        session = stores.repositories.profileSession,
                        settings = stores.repositories.settings,
                        library = stores.repositories.library,
                        catalog = catalog,
                        httpClient = client,
                        scope = scope,
                    )
                    val trakt = LocalTraktRepository(traktService, scope)
                    val device = AndroidDeviceRepository(context, appVersion)
                    val updateRepository = createAndroidUpdateRepository(
                        context = context,
                        currentVersion = appVersion,
                        scope = scope,
                        publicKeys = updatePublicKeys,
                        apiBase = updateApiBase,
                    )
                    val quality = QualityService(catalog, addonManager)
                    val prefetch = PrefetchService(
                        database = stores.databaseHandle,
                        session = stores.repositories.profileSession,
                        settings = stores.repositories.settings,
                        catalog = catalog,
                        addons = addonManager,
                        scope = scope,
                        warmScrapers = { type, id, imdb, title, year, season, episode ->
                            nuvio.streams(type, id, imdb, title, year, season, episode)
                        },
                    )
                    stores.progressEvents.subscribe { progress ->
                        activity.record(progress)
                        prefetch.notifyProgressChanged()
                        traktService.enqueueScrobble(
                            TraktScrobbleRequest(
                                action = if (progress.completed) "stop" else "start",
                                tmdbId = progress.tmdbId,
                                mediaType = progress.mediaType.wireName,
                                season = progress.season,
                                episode = progress.episode,
                                progress = if (progress.durationSeconds > 0.0) {
                                    (progress.positionSeconds / progress.durationSeconds * 100.0)
                                        .coerceIn(0.0, 100.0)
                                } else 0.0,
                            ),
                        )
                    }
                    var routeAuth: AuthService? = null
                    val account = supabaseConfig(supabaseUrl, supabaseKey)?.let { config ->
                        val supabase = SupabaseClient(config, client)
                        val now = stores.now
                        val auth = AuthService(
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
                                    NuvioSyncPayload(nuvio),
                                    ActivitySyncPayload(activity),
                                ),
                            ),
                        ).also { routeAuth = it }
                        LocalAccountRepository(
                            auth = auth,
                            settings = stores.repositories.settings,
                            library = stores.repositories.library,
                            scope = scope,
                        )
                    } ?: UnavailableAccountRepository
                    val routeServices = CoreRouteServices(
                        profiles = stores.repositories.profiles,
                        settings = stores.repositories.settings,
                        library = stores.repositories.library,
                        catalog = catalog,
                        addons = addonManager,
                        nuvio = nuvio,
                        media = media,
                        auth = routeAuth,
                        clientSessions = ClientSessionStore(stores.databaseHandle, stores.now),
                        activity = activity,
                        calendar = calendarService,
                        trakt = traktService,
                        deviceSettings = device,
                        discovery = discoveryService,
                        quality = quality,
                        updater = object : RouteUpdater {
                            override fun check() = UpdateCheckDto(currentVersion = appVersion)
                            override fun apply(): Nothing = throw IllegalStateException(
                                "self-update is unavailable on Android; use the app store or package installer",
                            )
                        },
                        prefetch = prefetch,
                    )
                    AndroidBackendRuntime(
                        stores, client, untrustedClient, scope, media, routeServices, content, playback, addons,
                        calendar, discovery,
                        LocalInsightsRepository(
                            activity = activity,
                            discovery = discoveryService,
                            database = stores.databaseHandle,
                            session = stores.repositories.profileSession,
                            trakt = traktService,
                        ),
                        account, trakt, device,
                        updateRepository,
                        storage,
                    )
                }
            } catch (error: Throwable) {
                scope.cancel()
                openedMedia?.close()
                untrustedClient.close()
                client.close()
                stores.close()
                throw error
            }
        }

        const val LOOPBACK_API_PORT = 6969
        const val REMOTE_API_PORT = 6970
    }
}

private inline fun <T> tracedStartup(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}
