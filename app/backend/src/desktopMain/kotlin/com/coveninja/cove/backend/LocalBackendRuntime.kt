package com.coveninja.cove.backend

import com.coveninja.cove.backend.addons.AddonManager
import com.coveninja.cove.backend.addons.AddonSyncPayload
import com.coveninja.cove.backend.addons.DesktopAddonUrlPolicy
import com.coveninja.cove.backend.addons.LocalAddonRepository
import com.coveninja.cove.backend.activity.ActivityService
import com.coveninja.cove.backend.activity.ActivitySyncPayload
import com.coveninja.cove.backend.nuvio.NuvioSyncPayload
import com.coveninja.cove.backend.nuvio.NuvioAddonService
import com.coveninja.cove.backend.auth.AuthService
import com.coveninja.cove.backend.auth.AuthSessionStore
import com.coveninja.cove.backend.auth.ClientSessionStore
import com.coveninja.cove.backend.auth.SupabaseClient
import com.coveninja.cove.backend.auth.SupabaseSyncService
import com.coveninja.cove.backend.nuvio.NuvioManager
import com.coveninja.cove.backend.content.LocalContentRepository
import com.coveninja.cove.backend.content.TmdbClient
import com.coveninja.cove.backend.content.resolveAppLocale
import com.coveninja.cove.backend.calendar.CalendarService
import com.coveninja.cove.backend.calendar.LocalCalendarRepository
import com.coveninja.cove.shared.data.CalendarRepository
import com.coveninja.cove.backend.trakt.TraktService
import com.coveninja.cove.backend.platform.DeviceSettingsService
import com.coveninja.cove.backend.discovery.DiscoveryService
import com.coveninja.cove.backend.discovery.LocalDiscoveryRepository
import com.coveninja.cove.backend.insights.LocalInsightsRepository
import com.coveninja.cove.shared.data.DiscoveryRepository
import com.coveninja.cove.shared.data.InsightsRepository
import com.coveninja.cove.backend.quality.QualityService
import com.coveninja.cove.backend.updater.UpdateService
import com.coveninja.cove.backend.updater.createDesktopUpdateRepository
import com.coveninja.cove.backend.prefetch.PrefetchService
import com.coveninja.cove.backend.plugins.DesktopPluginManager
import com.coveninja.cove.backend.plugins.parsePluginPublicKeys
import com.coveninja.cove.backend.http.LocalBackendHost
import com.coveninja.cove.backend.http.MediaBoundary
import com.coveninja.cove.backend.torrent.JlibtorrentPlaybackEngine
import com.coveninja.cove.backend.torrent.TorrentCacheLifecycle
import com.coveninja.cove.backend.platform.DesktopBackendEnvironment
import com.coveninja.cove.backend.platform.DesktopConfigPaths
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.AccountRepository
import com.coveninja.cove.shared.data.AddonRepository
import com.coveninja.cove.shared.data.DeviceRepository
import com.coveninja.cove.shared.data.TraktRepository
import com.coveninja.cove.shared.data.UnavailableAccountRepository
import com.coveninja.cove.backend.auth.LocalAccountRepository
import com.coveninja.cove.backend.platform.LocalDeviceRepository
import com.coveninja.cove.backend.storage.CacheDirectories
import com.coveninja.cove.backend.storage.CacheStorageService
import com.coveninja.cove.backend.storage.LocalStorageRepository
import com.coveninja.cove.backend.storage.TorrentCacheJournal
import com.coveninja.cove.shared.data.StorageRepository
import com.coveninja.cove.shared.data.TorrentCachePolicy
import com.coveninja.cove.backend.trakt.LocalTraktRepository
import com.coveninja.cove.backend.trakt.TraktScrobbleRequest
import com.coveninja.cove.shared.data.LivePlaybackRepository
import com.coveninja.cove.shared.data.PlaybackRepository
import com.coveninja.cove.shared.data.PluginRepository
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.data.UnavailablePlaybackRepository
import com.coveninja.cove.shared.data.UpdateRepository
import com.coveninja.cove.shared.network.CoveApi
import com.coveninja.cove.shared.network.CoveApiConfig
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Owns the complete in-process Kotlin backend used by the desktop app. */
class LocalBackendRuntime private constructor(
    private val stores: LocalStoreGraph,
    private val contentClient: HttpClient,
    private val untrustedClient: HttpClient,
    private val contentScope: CoroutineScope,
    private val host: LocalBackendHost?,
    private val media: MediaBoundary,
    content: LocalContentRepository,
    playback: PlaybackRepository,
    addonRepository: AddonRepository,
    calendarRepository: CalendarRepository,
    discoveryRepository: DiscoveryRepository,
    insightsRepository: InsightsRepository,
    accountRepository: AccountRepository,
    traktRepository: TraktRepository,
    deviceRepository: DeviceRepository,
    private val updateRepository: UpdateRepository,
    storageRepository: StorageRepository,
    private val pluginRepository: PluginRepository,
) : AutoCloseable {
    val graph = AppGraph(
        content = content,
        library = stores.library,
        settings = stores.settings,
        playback = playback,
        addons = addonRepository,
        calendar = calendarRepository,
        discovery = discoveryRepository,
        insights = insightsRepository,
        account = accountRepository,
        profiles = stores.profiles,
        trakt = traktRepository,
        device = deviceRepository,
        updates = updateRepository,
        storage = storageRepository,
        plugins = pluginRepository,
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
        (updateRepository as? AutoCloseable)?.close()
        (pluginRepository as? AutoCloseable)?.close()
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
            val scope = backendScope("runtime")
            var pluginManager: DesktopPluginManager? = null
            try {
                val systemLocale = MutableStateFlow(Locale.getDefault().toLanguageTag())
                val localeProvider = {
                    val profileOverride = (stores.settings.settings.value as? SettingsState.Ready)
                        ?.settings
                        ?.uiLanguage
                        .orEmpty()
                    resolveAppLocale(profileOverride, systemLocale.value)
                }
                val localeChanges = combine(stores.settings.settings, systemLocale) { _, _ ->
                    localeProvider()
                }.distinctUntilChanged()
                val catalog = TmdbClient(
                    httpClient = client,
                    apiKey = tmdbApiKey,
                    localeProvider = localeProvider,
                )
                val plugins = DesktopPluginManager(
                    dataDirectory = dataDirectory,
                    activeProfileIds = stores.profileSession.profileId,
                    scope = scope,
                    httpClient = client,
                    catalogApiBase = DesktopBackendEnvironment.pluginCatalogApiBase(),
                    publicKeys = parsePluginPublicKeys(DesktopBackendEnvironment.pluginPublicKeys()),
                    currentCoveVersion = DesktopBackendEnvironment.appVersion(),
                    allowLan = {
                        (stores.settings.settings.value as? SettingsState.Ready)
                            ?.settings
                            ?.allowLanStreamSources == true
                    },
                ).also { pluginManager = it }
                val content = LocalContentRepository(
                    catalog = catalog,
                    scope = scope,
                    localeChanges = localeChanges,
                    initialLocale = localeProvider(),
                    plugins = plugins,
                )
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
                val deviceSettings = DeviceSettingsService(dataDirectory)
                val torrentDirectory = dataDirectory.resolve("torrents")
                val imageCacheDirectory = dataDirectory.resolve("image-cache")
                val cacheJournal = TorrentCacheJournal(torrentDirectory)
                // One holder shared by the engine and the repository below. The engine consults
                // it per piece and must not touch the disk to do so; the repository owns writing
                // it back. See LocalStorageRepository for why it is passed in rather than made
                // there.
                val cachePolicy = MutableStateFlow(
                    runCatching(deviceSettings::read).getOrDefault(TorrentCachePolicy()),
                )
                val torrentLifecycle = TorrentCacheLifecycle()
                val torrentEngine = JlibtorrentPlaybackEngine(
                    downloadDirectory = torrentDirectory,
                    lifecycle = torrentLifecycle,
                    policy = cachePolicy::value,
                    journal = cacheJournal,
                )
                val storageRepository = LocalStorageRepository(
                    service = CacheStorageService(
                        directories = CacheDirectories(
                            torrents = torrentDirectory,
                            images = imageCacheDirectory,
                            tools = dataDirectory.resolve("tools"),
                        ),
                        torrentLifecycle = torrentLifecycle,
                        journal = cacheJournal,
                        activeHashes = torrentEngine::activeHashes,
                        release = torrentEngine::release,
                    ),
                    store = deviceSettings,
                    state = cachePolicy,
                ).also { it.start(scope) }
                val media = MediaBoundary(
                    httpClient = untrustedClient,
                    imageCacheDirectory = imageCacheDirectory,
                    publicUrlPolicy = DesktopAddonUrlPolicy,
                    allowLanStreamSources = {
                        (stores.settings.settings.value as? SettingsState.Ready)
                            ?.settings
                            ?.allowLanStreamSources == true
                    },
                    torrentEngine = torrentEngine,
                )
                val nuvio = NuvioManager(
                    database = stores.databaseHandle,
                    session = stores.profileSession,
                    httpClient = untrustedClient,
                    now = stores.now,
                    sandbox = com.coveninja.cove.backend.nuvio.ProcessNuvioSandbox(),
                    urlPolicy = DesktopAddonUrlPolicy,
                )
                val addonRepository = LocalAddonRepository(
                    addons = addons,
                    activeProfileIds = stores.profileSession.profileId,
                    scope = scope,
                    nuvio = NuvioAddonService(nuvio),
                )
                val prefetch = PrefetchService(
                    stores.databaseHandle,
                    stores.profileSession,
                    stores.settings,
                    catalog,
                    addons,
                    scope,
                    warmScrapers = { type, id, imdb, title, year, season, episode ->
                        nuvio.streams(type, id, imdb, title, year, season, episode)
                    },
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
                stores.progressEvents.subscribe { progress ->
                    activity.record(progress)
                    prefetch.notifyProgressChanged()
                    trakt.enqueueScrobble(
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
                            now = stores.now,
                            // The desktop owns all three subsystems, so all three
                            // merge properly instead of being passed through.
                            payloads = listOf(
                                AddonSyncPayload(addons, addonRepository::reload),
                                NuvioSyncPayload(nuvio),
                                ActivitySyncPayload(activity),
                            ),
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
                    deviceSettings,
                    discovery,
                    quality,
                    UpdateService(DesktopBackendEnvironment.appVersion()),
                    prefetch,
                    plugins,
                    host,
                    port,
                    remoteAddress.host,
                    remoteAddress.port,
                ).takeIf { startHttpHost }
                httpHost?.start()
                // Playback deliberately goes back out over loopback HTTP instead of
                // calling addons/media in-process: /api/play only serves a URL that a
                // prior /api/streams call registered, and that registry lives behind
                // the route. Loopback requests carry no auth (ApiAccess.Loopback).
                val loopback = httpHost?.let { CoveApi(client, CoveApiConfig("http://$host:$port")) }
                val playback = loopback?.let(::LivePlaybackRepository)
                    ?: UnavailablePlaybackRepository
                // In-process, not over loopback: the calendar has no per-request registry
                // to go through, and the HTTP host is optional here.
                val calendarRepository = LocalCalendarRepository(
                    service = calendar,
                    database = stores.databaseHandle,
                    session = stores.profileSession,
                    library = stores.library,
                    scope = scope,
                    localeProvider = localeProvider,
                )
                // In-process like the calendar, not over loopback like playback: discovery
                // has no per-request registry living behind a route, and the HTTP host is
                // optional here.
                val discoveryRepository = LocalDiscoveryRepository(catalog, discovery, localeProvider)
                val updateRepository = createDesktopUpdateRepository(
                    dataDirectory = dataDirectory,
                    currentVersion = DesktopBackendEnvironment.appVersion(),
                    scope = scope,
                )
                scope.launch {
                    localeChanges.drop(1).collectLatest {
                        calendarRepository.refresh(force = true)
                    }
                }
                // Without Supabase credentials there is no account to sign in to,
                // and the settings page says exactly that rather than offering a
                // form that could not work.
                val accountRepository = auth?.let {
                    LocalAccountRepository(
                        auth = it,
                        settings = stores.settings,
                        library = stores.library,
                        scope = scope,
                    )
                } ?: UnavailableAccountRepository
                return LocalBackendRuntime(
                    stores, client, untrustedClient, scope, httpHost, media, content,
                    playback, addonRepository, calendarRepository, discoveryRepository,
                    LocalInsightsRepository(
                        activity = activity,
                        discovery = discovery,
                        database = stores.databaseHandle,
                        session = stores.profileSession,
                        trakt = trakt,
                    ),
                    accountRepository,
                    LocalTraktRepository(trakt, scope),
                    LocalDeviceRepository(deviceSettings, DesktopBackendEnvironment.appVersion()),
                    updateRepository,
                    storageRepository,
                    plugins,
                )
            } catch (error: Throwable) {
                pluginManager?.close()
                scope.cancel()
                untrustedClient.close()
                client.close()
                stores.close()
                throw error
            }
        }
    }
}
