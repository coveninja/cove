package com.coveninja.cove.backend

import android.content.Context
import com.coveninja.cove.backend.addons.BasicAddonUrlPolicy
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
import com.coveninja.cove.shared.data.AccountRepository
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.CalendarRepository
import com.coveninja.cove.shared.data.DiscoveryRepository
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.data.UnavailableAccountRepository
import com.coveninja.cove.shared.data.UnavailableAddonRepository
import com.coveninja.cove.shared.data.UnavailableDeviceRepository
import com.coveninja.cove.shared.data.UnavailablePlaybackRepository
import com.coveninja.cove.shared.data.UnavailableTraktRepository
import com.coveninja.cove.shared.network.CoveJson
import java.time.Instant
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
    private val scope: CoroutineScope,
    content: LocalContentRepository,
    calendar: CalendarRepository,
    discovery: DiscoveryRepository,
    account: AccountRepository,
) : AutoCloseable {
    private var closed = false

    val graph = AppGraph(
        content = content,
        library = stores.repositories.library,
        settings = stores.repositories.settings,
        // Android runs no HTTP host and has no player, so there is nothing to
        // resolve streams against yet. Fails with the reason if anything asks.
        playback = UnavailablePlaybackRepository,
        addons = UnavailableAddonRepository,
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
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                // The custom-algorithm hook needs an HTTP client and a URL policy even
                // though nothing on Android configures one yet; BasicAddonUrlPolicy is the
                // commonMain structural check, which is the right floor for a URL the user
                // typed. Desktop swaps in the stricter DNS/IP-aware policy.
                val discovery = LocalDiscoveryRepository(
                    catalog = catalog,
                    service = DiscoveryService(
                        database = stores.databaseHandle,
                        session = stores.repositories.profileSession,
                        settings = stores.repositories.settings,
                        catalog = catalog,
                        customHttpClient = client,
                        customUrlPolicy = BasicAddonUrlPolicy,
                    ),
                )
                // No addon manager and no Nuvio sandbox on Android, so no sync
                // participants: those blobs are pulled and pushed back untouched
                // instead of being replaced with this host's empty view of them.
                val account = supabaseConfig(supabaseUrl, supabaseKey)?.let { config ->
                    val supabase = SupabaseClient(config, client)
                    val now = { Instant.now().toString() }
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
                            ),
                        ),
                        settings = stores.repositories.settings,
                        library = stores.repositories.library,
                        scope = scope,
                    )
                } ?: UnavailableAccountRepository
                return AndroidBackendRuntime(
                    stores, client, scope, content, calendar, discovery, account,
                )
            } catch (error: Throwable) {
                scope.cancel()
                client.close()
                stores.close()
                throw error
            }
        }
    }
}
