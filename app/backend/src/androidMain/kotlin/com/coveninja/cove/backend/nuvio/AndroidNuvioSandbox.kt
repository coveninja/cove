package com.coveninja.cove.backend.nuvio

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import app.cash.quickjs.QuickJs
import com.coveninja.cove.backend.addons.AddonUrlPolicy
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import java.net.URI
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal interface NuvioSandbox {
    suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream>
}

/**
 * Binds once per invocation to a component hosted in an Android isolated
 * process. The guest process gets only pipes and a narrow fetch broker; it has
 * no application UID, files, raw sockets, activities, or backend objects.
 */
internal class AndroidNuvioSandbox(
    context: Context,
    private val httpClient: HttpClient,
    private val urlPolicy: AddonUrlPolicy,
    private val timeoutMillis: Long = 20_000,
) : NuvioSandbox {
    private val appContext = context.applicationContext

    override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> =
        withTimeout(timeoutMillis) {
            runInterruptible(Dispatchers.IO) {
                val binding = bindWorker()
                try {
                    val input = ParcelFileDescriptor.createPipe()
                    val output = ParcelFileDescriptor.createPipe()
                    val writer = Thread({
                        ParcelFileDescriptor.AutoCloseOutputStream(input[1]).bufferedWriter().use {
                            it.write(CoveJson.encodeToString(invocation))
                        }
                    }, "cove-nuvio-input").apply {
                        isDaemon = true
                        start()
                    }

                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(NuvioBinder.DESCRIPTOR)
                        input[0].writeToParcel(data, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                        output[1].writeToParcel(data, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                        data.writeStrongBinder(FetchBroker())
                        check(binding.binder.transact(NuvioBinder.RUN, data, reply, 0)) {
                            "isolated scraper worker rejected the invocation"
                        }
                        reply.readException()
                    } finally {
                        data.recycle()
                        reply.recycle()
                        input[0].close()
                        output[1].close()
                    }
                    writer.join(1_000)
                    val encoded = ParcelFileDescriptor.AutoCloseInputStream(output[0])
                        .bufferedReader()
                        .use { it.readText() }
                    val result = CoveJson.decodeFromString<NuvioInvocationResult>(encoded)
                    require(result.error.isBlank()) { result.error }
                    result.streams
                } finally {
                    runCatching { appContext.unbindService(binding.connection) }
                }
            }
        }

    private fun bindWorker(): WorkerBinding {
        val ready = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                ready.countDown()
            }
        }
        val intent = Intent(appContext, AndroidNuvioWorkerService::class.java)
        check(appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            "could not start isolated scraper worker"
        }
        if (!ready.await(3, TimeUnit.SECONDS) || binder == null) {
            runCatching { appContext.unbindService(connection) }
            error("isolated scraper worker did not connect")
        }
        return WorkerBinding(connection, requireNotNull(binder))
    }

    private inner class FetchBroker : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != NuvioBinder.FETCH) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(NuvioBinder.FETCH_DESCRIPTOR)
            val url = data.readString().orEmpty()
            val options = data.readString().orEmpty()
            val output = ParcelFileDescriptor.CREATOR.createFromParcel(data)
            val result = runCatching {
                runBlocking(Dispatchers.IO) { fetch(url, options) }
            }.fold(
                onSuccess = { NuvioFetchResult(response = it) },
                onFailure = { NuvioFetchResult(error = it.message ?: "fetch failed") },
            )
            ParcelFileDescriptor.AutoCloseOutputStream(output).bufferedWriter().use {
                it.write(CoveJson.encodeToString(result))
            }
            reply?.writeNoException()
            return true
        }
    }

    private suspend fun fetch(rawUrl: String, optionsJson: String): NuvioFetchResponse {
        val options = runCatching { CoveJson.decodeFromString<NuvioFetchOptions>(optionsJson) }
            .getOrDefault(NuvioFetchOptions())
        val method = options.method.uppercase()
        require(method in ALLOWED_METHODS) { "unsupported fetch method $method" }
        var current = URI(rawUrl)
        var redirected = false
        repeat(6) { redirectCount ->
            urlPolicy.validate(current.toString())
            val response = httpClient.request(current.toString()) {
                this.method = HttpMethod.parse(method)
                header(HttpHeaders.UserAgent, "Cove Nuvio Sandbox")
                options.headers.forEach { (name, value) ->
                    // Browser fetch implementations silently own transport headers. Nuvio
                    // providers commonly include Connection or Content-Length in a copied
                    // browser header set, so rejecting the whole request is needlessly
                    // incompatible; omit those values while retaining the sandbox boundary.
                    if (name.lowercase() !in FORBIDDEN_HEADERS) header(name, value)
                }
                if (options.body.isNotEmpty()) setBody(options.body)
            }
            if (response.status.value in 300..399) {
                require(redirectCount < 5) { "too many fetch redirects" }
                val location = response.headers[HttpHeaders.Location]
                    ?: error("fetch redirect has no location")
                current = current.resolve(location)
                redirected = true
                return@repeat
            }
            val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            require(declaredLength == null || declaredLength <= MAX_FETCH_BYTES) {
                "fetch response exceeds 20 MiB"
            }
            val bytes = response.body<ByteArray>()
            require(bytes.size <= MAX_FETCH_BYTES) { "fetch response exceeds 20 MiB" }
            return NuvioFetchResponse(
                status = response.status.value,
                statusText = response.status.description,
                headers = response.headers.entries().associate { it.key to it.value.joinToString(", ") },
                body = bytes.decodeToString(),
                url = current.toString(),
                redirected = redirected,
            )
        }
        error("too many fetch redirects")
    }

    private data class WorkerBinding(val connection: ServiceConnection, val binder: IBinder)

    private companion object {
        const val MAX_FETCH_BYTES = 20 * 1024 * 1024
        val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        val FORBIDDEN_HEADERS = setOf("host", "content-length", "connection")
    }
}

/** The only component that loads downloaded scraper code. Declared isolated in the app manifest. */
class AndroidNuvioWorkerService : Service() {
    private val worker = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != NuvioBinder.RUN) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(NuvioBinder.DESCRIPTOR)
            val input = ParcelFileDescriptor.CREATOR.createFromParcel(data)
            val output = ParcelFileDescriptor.CREATOR.createFromParcel(data)
            val broker = requireNotNull(data.readStrongBinder())
            Thread({ execute(input, output, broker) }, "cove-nuvio-quickjs").apply {
                isDaemon = true
                start()
            }
            reply?.writeNoException()
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = worker

    private fun execute(input: ParcelFileDescriptor, output: ParcelFileDescriptor, broker: IBinder) {
        val invocation = runCatching {
            val encoded = ParcelFileDescriptor.AutoCloseInputStream(input).bufferedReader().use { it.readText() }
            CoveJson.decodeFromString<NuvioInvocation>(encoded)
        }
        val result = invocation.mapCatching { executeQuickJs(it, broker) }.fold(
            onSuccess = { NuvioInvocationResult(streams = it) },
            onFailure = { NuvioInvocationResult(error = it.message ?: "scraper failed") },
        )
        ParcelFileDescriptor.AutoCloseOutputStream(output).bufferedWriter().use {
            it.write(CoveJson.encodeToString(result))
        }
        stopSelf()
    }

    private fun executeQuickJs(invocation: NuvioInvocation, broker: IBinder): List<NuvioScrapedStream> {
        QuickJs.create().use { quickJs ->
            quickJs.set(
                "__bridge",
                JavascriptBridgeApi::class.java,
                JavascriptBridge(broker, invocation.scraperId),
            )
            quickJs.set("__invocationHost", InvocationHostApi::class.java, InvocationHost(
                CoveJson.encodeToString(invocation),
            ))
            quickJs.evaluate(bootstrap(), "cove-nuvio-bootstrap.js")
            quickJs.evaluate(synchronousNuvioScraperSource(invocation.code), "${invocation.scraperId}.js")
            quickJs.evaluate(ANDROID_NUVIO_INVOKE_SCRIPT, "cove-nuvio-invoke.js")
            check(quickJs.evaluate("globalThis.__coveDone === true") == true) {
                "scraper did not finish"
            }
            val error = quickJs.evaluate("String(globalThis.__coveError || '')") as String
            check(error.isBlank()) { error }
            val encoded = quickJs.evaluate("String(globalThis.__coveResult || '[]')") as String
            return CoveJson.decodeFromString(encoded)
        }
    }

    private fun bootstrap(): String {
        val modules = mapOf(
            "crypto-js" to assets.open("crypto-js.js").bufferedReader().use { it.readText() },
            "cheerio-without-node-native" to assets.open("cheerio-without-node-native.js")
                .bufferedReader().use { it.readText() },
        )
        val factories = modules.entries.joinToString(",") { (name, source) ->
            "${CoveJson.encodeToString(name)}: function(module, exports) {\n$source\n}"
        }
        return androidNuvioBootstrap(factories)
    }

    private interface InvocationHostApi {
        fun json(): String
    }

    private class InvocationHost(private val encoded: String) : InvocationHostApi {
        override fun json(): String = encoded
    }

    private interface JavascriptBridgeApi {
        fun base64Encode(value: String): String
        fun base64Decode(value: String): String
        fun request(url: String, optionsJson: String): String
        fun log(level: String, message: String)
    }

    private class JavascriptBridge(
        private val broker: IBinder,
        private val scraperId: String,
    ) : JavascriptBridgeApi {
        private var logCount = 0

        override fun base64Encode(value: String): String =
            Base64.getEncoder().encodeToString(value.encodeToByteArray())

        override fun base64Decode(value: String): String = Base64.getDecoder().decode(value).decodeToString()

        override fun request(url: String, optionsJson: String): String {
            val output = ParcelFileDescriptor.createPipe()
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(NuvioBinder.FETCH_DESCRIPTOR)
                data.writeString(url)
                data.writeString(optionsJson)
                output[1].writeToParcel(data, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                check(broker.transact(NuvioBinder.FETCH, data, reply, 0)) { "fetch broker rejected request" }
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
                output[1].close()
            }
            val encoded = ParcelFileDescriptor.AutoCloseInputStream(output[0]).bufferedReader().use { it.readText() }
            val result = CoveJson.decodeFromString<NuvioFetchResult>(encoded)
            require(result.error.isBlank()) { result.error }
            return CoveJson.encodeToString(requireNotNull(result.response))
        }

        override fun log(level: String, message: String) {
            if (logCount >= MAX_GUEST_LOG_LINES) return
            logCount += 1
            System.err.println(
                "Cove Nuvio [$scraperId/${level.take(12)}]: ${message.take(MAX_GUEST_LOG_LENGTH)}",
            )
        }

        private companion object {
            const val MAX_GUEST_LOG_LINES = 100
            const val MAX_GUEST_LOG_LENGTH = 2_000
        }
    }
}

private object NuvioBinder {
    const val DESCRIPTOR = "com.coveninja.cove.nuvio.worker"
    const val FETCH_DESCRIPTOR = "com.coveninja.cove.nuvio.fetch"
    const val RUN = IBinder.FIRST_CALL_TRANSACTION
    const val FETCH = IBinder.FIRST_CALL_TRANSACTION + 1
}

@Serializable
private data class NuvioFetchOptions(
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)

@Serializable
private data class NuvioFetchResponse(
    val status: Int,
    val statusText: String = "",
    val headers: Map<String, String>,
    val body: String,
    val url: String = "",
    val redirected: Boolean = false,
)

@Serializable
private data class NuvioFetchResult(
    val response: NuvioFetchResponse? = null,
    val error: String = "",
)
