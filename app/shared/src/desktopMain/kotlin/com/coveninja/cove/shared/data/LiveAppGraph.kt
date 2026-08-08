package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.network.CoveApi
import com.coveninja.cove.shared.network.CoveApiConfig
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*

// Wires the full live graph: OkHttp client → CoveApi → Live repositories.
// The returned AppGraph owns the scope and client; call close() when done.
fun createLiveAppGraph(
    config: CoveApiConfig = CoveApiConfig(),
    tokenProvider: () -> String? = { null },
    deviceTokenProvider: () -> String? = { null },
): AppGraph {
    // CoveJson, not a local Json{} block: the tests bind to the same instance,
    // so serialization behaviour under test is the behaviour that ships. A
    // divergent config here is what hid the missing encodeDefaults.
    val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(CoveJson) }
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }
    val api = CoveApi(client, config, tokenProvider, deviceTokenProvider)
    // SupervisorJob so a single failing coroutine doesn't cancel all siblings.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    return AppGraph(
        content  = LiveContentRepository(api, scope),
        library  = LiveLibraryRepository(api, scope),
        settings = LiveSettingsRepository(api, scope),
        onClose  = {
            scope.cancel()
            client.close()
        },
    )
}
