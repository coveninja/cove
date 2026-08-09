package com.coveninja.cove.backend

import android.content.Context
import com.coveninja.cove.backend.content.LocalContentRepository
import com.coveninja.cove.backend.content.TmdbClient
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.data.UnavailableAddonRepository
import com.coveninja.cove.shared.data.UnavailablePlaybackRepository
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
    private val scope: CoroutineScope,
    content: LocalContentRepository,
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
        fun open(context: Context, tmdbApiKey: String): AndroidBackendRuntime {
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
                return AndroidBackendRuntime(stores, client, scope, content)
            } catch (error: Throwable) {
                scope.cancel()
                client.close()
                stores.close()
                throw error
            }
        }
    }
}
