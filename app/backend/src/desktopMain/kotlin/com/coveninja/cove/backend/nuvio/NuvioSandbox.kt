package com.coveninja.cove.backend.nuvio

import com.coveninja.cove.backend.addons.validateResolvedPublicUrl
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.http.HttpStatusCode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess

internal interface NuvioSandbox {
    suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream>

    /**
     * Runs a whole request's scrapers, reporting each answer through [onOutcome] as it lands
     * rather than returning them together, so a batch abandoned at its deadline still yields
     * everything that already came back.
     */
    suspend fun runBatch(batch: NuvioBatch, onOutcome: (NuvioBatchOutcome) -> Unit) =
        runInvocationsIndividually(batch, onOutcome)
}

/**
 * Executes a request's untrusted scrapers in one disposable, memory-capped JVM, each in its own
 * Graal context off a shared engine. The guest gets no host classes, filesystem, processes,
 * native access, threads, or raw sockets; its only network path is the public-address fetch
 * bridge.
 *
 * One process per *request* rather than per scraper, because a child JVM costs about 1.2 s to
 * start on a normal desktop however it is tuned — trimming the classpath and dropping to
 * TieredStopAtLevel=1 together save under 15% — while a context off a warm shared engine costs
 * about 2 ms. Two dozen enabled scrapers was therefore half a minute of pinned CPU per play, and
 * no scheduling of it fit inside a budget a viewer would wait through.
 */
internal class ProcessNuvioSandbox(
    private val timeoutMillis: Long = 12_000,
    private val heapMegabytes: Int = 512,
    private val javaExecutable: Path = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java",
    ),
    private val classpath: String = System.getProperty("java.class.path"),
) : NuvioSandbox {
    override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> {
        var outcome: NuvioBatchOutcome? = null
        execute(
            NuvioBatch(listOf(invocation), concurrency = 1, perScraperTimeoutMillis = timeoutMillis),
            deadlineMillis = timeoutMillis,
        ) { outcome = it }
        val result = outcome ?: error("scraper worker produced no result")
        require(result.error.isBlank()) { result.error }
        return result.streams
    }

    override suspend fun runBatch(batch: NuvioBatch, onOutcome: (NuvioBatchOutcome) -> Unit) {
        if (batch.invocations.isEmpty()) return
        // Only a backstop: the caller's own budget is the authority and cancels this first.
        execute(batch, deadlineMillis = batch.perScraperTimeoutMillis * 2 + STARTUP_ALLOWANCE_MILLIS, onOutcome)
    }

    private suspend fun execute(
        batch: NuvioBatch,
        deadlineMillis: Long,
        onOutcome: (NuvioBatchOutcome) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(
            javaExecutable.toString(),
            "-Xms32m",
            "-Xmx${heapMegabytes}m",
            // A worker lives for one request, so JIT tiering and a concurrent collector never pay
            // for themselves.
            "-XX:TieredStopAtLevel=1",
            "-XX:+UseSerialGC",
            "-Dpolyglot.engine.WarnInterpreterOnly=false",
            "-cp",
            classpath,
            NuvioSandboxWorker::class.java.name,
        ).start()
        try {
            coroutineScope {
                val reader = launch {
                    runCatching {
                        process.inputStream.bufferedReader().forEachLine { line ->
                            if (line.isNotBlank()) {
                                runCatching { CoveJson.decodeFromString<NuvioBatchOutcome>(line) }
                                    .onSuccess(onOutcome)
                            }
                        }
                    }
                }
                val stderr = async {
                    runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")
                }
                runCatching {
                    process.outputStream.bufferedWriter().use { it.write(CoveJson.encodeToString(batch)) }
                }
                var exited = false
                try {
                    exited = withTimeoutOrNull(deadlineMillis) {
                        while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                            kotlinx.coroutines.yield()
                        }
                        true
                    } == true
                } finally {
                    // Kill before coroutineScope waits for the blocking readers; otherwise a
                    // runaway guest leaves those child coroutines waiting forever for EOF.
                    if (!exited && process.isAlive) process.destroyForcibly()
                }
                reader.join()
                stderr.await()
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private companion object {
        /** Room for JVM start plus however many waves the concurrency limit implies. */
        const val STARTUP_ALLOWANCE_MILLIS = 15_000L
    }
}

/** Entry point for the disposable scraper process. */
internal object NuvioSandboxWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        val batch = CoveJson.decodeFromString<NuvioBatch>(System.`in`.bufferedReader().readText())
        // One engine, many contexts: the engine holds the parsed-language machinery that makes a
        // cold context expensive, and sharing it is the whole reason a batch beats a process each.
        val engine = Engine.create("js")
        val out = System.out.bufferedWriter()
        val emit = { outcome: NuvioBatchOutcome ->
            synchronized(out) {
                out.write(CoveJson.encodeToString(outcome))
                out.newLine()
                out.flush()
            }
        }
        val threads = ThreadFactory { runnable -> Thread(runnable).apply { isDaemon = true } }
        val watchdogs = Executors.newSingleThreadScheduledExecutor(threads)
        val pool = Executors.newFixedThreadPool(batch.concurrency.coerceIn(1, 32), threads)
        val finished = CountDownLatch(batch.invocations.size)
        batch.invocations.forEach { invocation ->
            pool.execute {
                val startedAt = System.nanoTime()
                val outcome = runCatching {
                    execute(engine, invocation, batch.perScraperTimeoutMillis, watchdogs)
                }.fold(
                    onSuccess = { NuvioBatchOutcome(invocation.scraperId, it) },
                    onFailure = { failure ->
                        // Graal reports a watchdog interrupt as "Execution got interrupted.",
                        // which in a log reads like a Cove fault rather than the scraper simply
                        // running out of its slice.
                        val reason = if (failure is PolyglotException && failure.isInterrupted) {
                            "timed out after ${batch.perScraperTimeoutMillis} ms"
                        } else {
                            failure.describe()
                        }
                        NuvioBatchOutcome(invocation.scraperId, error = reason)
                    },
                )
                emit(outcome.copy(elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000))
                finished.countDown()
            }
        }
        // Bounded so a guest that survives its interrupt cannot keep the worker alive; the parent
        // kills the process anyway, but exiting on our own keeps a stray JVM off the machine.
        finished.await(batch.perScraperTimeoutMillis * 2 + 5_000, TimeUnit.MILLISECONDS)
        runCatching { out.flush() }
        exitProcess(0)
    }

    private fun execute(
        engine: Engine,
        invocation: NuvioInvocation,
        timeoutMillis: Long,
        watchdogs: ScheduledExecutorService,
    ): List<NuvioScrapedStream> {
        val bridge = FetchBridge()
        val context = Context.newBuilder("js")
            .engine(engine)
            .allowHostAccess(HostAccess.EXPLICIT)
            .allowHostClassLookup { false }
            .allowIO(IOAccess.NONE)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .allowCreateProcess(false)
            .build()
        // A scraper sharing the process with its siblings cannot be stopped by killing it, so the
        // guest is interrupted where it runs instead.
        val guard = watchdogs.schedule(
            { runCatching { context.interrupt(Duration.ofMillis(500)) } },
            timeoutMillis,
            TimeUnit.MILLISECONDS,
        )
        try {
            context.use {
                context.getBindings("js").putMember("__bridge", bridge)
                context.getBindings("js").putMember("__invocation", CoveJson.encodeToString(invocation))
                context.eval(Source.newBuilder("js", bootstrap(), "cove-nuvio-bootstrap.js").build())
                context.eval(Source.newBuilder("js", invocation.code, "${invocation.scraperId}.js").build())
                context.eval("js", invokeScript())
                repeat(100) {
                    if (context.eval("js", "globalThis.__coveDone === true").asBoolean()) return@repeat
                    context.eval("js", "0")
                }
                check(context.eval("js", "globalThis.__coveDone === true").asBoolean()) {
                    "scraper promise did not settle"
                }
                val error = context.eval("js", "globalThis.__coveError || ''").asString()
                check(error.isBlank()) { error }
                val json = context.eval("js", "globalThis.__coveResult || '[]'").asString()
                return CoveJson.decodeFromString(json)
            }
        } finally {
            guard.cancel(false)
        }
    }

    private fun Throwable.describe(): String = message?.takeIf(String::isNotBlank)
        ?: this::class.java.simpleName

    private fun bootstrap(): String {
        return """
            globalThis.console = { log(){}, info(){}, debug(){}, warn(){}, error(){} };
            globalThis.logger = console;
            globalThis.global = globalThis;
            globalThis.window = globalThis;
            globalThis.atob = value => __bridge.base64Decode(String(value));
            globalThis.btoa = value => __bridge.base64Encode(String(value));
            globalThis.base64Decode = globalThis.atob;
            globalThis.base64Encode = globalThis.btoa;
            if (typeof globalThis.setTimeout !== 'function') {
              globalThis.setTimeout = (callback, _delay, ...args) => {
                callback(...args);
                return 0;
              };
              globalThis.clearTimeout = () => {};
            }
            if (typeof globalThis.URLSearchParams !== 'function') {
              globalThis.URLSearchParams = class {
                constructor(input = '') {
                  this.pairs = [];
                  if (typeof input === 'string') {
                    const value = input.startsWith('?') ? input.slice(1) : input;
                    if (value) value.split('&').forEach(part => {
                      const separator = part.indexOf('=');
                      const name = separator < 0 ? part : part.slice(0, separator);
                      const entry = separator < 0 ? '' : part.slice(separator + 1);
                      this.append(decodeURIComponent(name.replace(/\+/g, ' ')), decodeURIComponent(entry.replace(/\+/g, ' ')));
                    });
                  } else if (input && typeof input[Symbol.iterator] === 'function') {
                    for (const pair of input) this.append(pair[0], pair[1]);
                  } else if (input) {
                    Object.keys(input).forEach(name => this.append(name, input[name]));
                  }
                }
                append(name, value) { this.pairs.push([String(name), String(value)]); }
                delete(name) { name = String(name); this.pairs = this.pairs.filter(pair => pair[0] !== name); }
                get(name) { name = String(name); const pair = this.pairs.find(value => value[0] === name); return pair ? pair[1] : null; }
                getAll(name) { name = String(name); return this.pairs.filter(value => value[0] === name).map(value => value[1]); }
                has(name) { name = String(name); return this.pairs.some(value => value[0] === name); }
                set(name, value) { this.delete(name); this.append(name, value); }
                entries() { return this.pairs[Symbol.iterator](); }
                keys() { return this.pairs.map(value => value[0])[Symbol.iterator](); }
                values() { return this.pairs.map(value => value[1])[Symbol.iterator](); }
                forEach(callback, self) { this.pairs.forEach(value => callback.call(self, value[1], value[0], this)); }
                [Symbol.iterator]() { return this.entries(); }
                toString() { return this.pairs.map(value => encodeURIComponent(value[0]).replace(/%20/g, '+') + '=' + encodeURIComponent(value[1]).replace(/%20/g, '+')).join('&'); }
              };
            }
            $NUVIO_BROWSER_COMPATIBILITY_SCRIPT
            // cheerio and crypto-js are a quarter of a megabyte of JavaScript between them, and
            // used to be pasted into this bootstrap on every invocation — parsed by a fresh
            // interpreted Graal context each time whether or not the scraper wanted either. The
            // host hands over a module's source only when something actually requires it.
            const __moduleCache = {};
            const __moduleAliases = {
              'cheerio': 'cheerio-without-node-native',
              'react-native-cheerio': 'cheerio-without-node-native'
            };
            globalThis.require = requestedName => {
              const name = __moduleAliases[requestedName] || requestedName;
              if (__moduleCache[name]) return __moduleCache[name].exports;
              const source = __bridge.moduleSource(name);
              if (!source) throw new Error('unsupported module: ' + name);
              const loaded = { exports: {} };
              __moduleCache[name] = loaded;
              Function('module', 'exports', 'require', source)(loaded, loaded.exports, globalThis.require);
              return loaded.exports;
            };
            const __coveHeaders = rawHeaders => {
              const raw = rawHeaders || {};
              const result = Object.assign({}, raw);
              const normalized = {};
              Object.keys(raw).forEach(name => { normalized[name.toLowerCase()] = String(raw[name]); });
              Object.defineProperties(result, {
                get: { value: name => normalized[String(name).toLowerCase()] ?? null },
                has: { value: name => Object.prototype.hasOwnProperty.call(normalized, String(name).toLowerCase()) },
                forEach: { value: callback => Object.keys(normalized).forEach(name => callback(normalized[name], name)) },
                entries: { value: () => Object.entries(normalized)[Symbol.iterator]() },
                keys: { value: () => Object.keys(normalized)[Symbol.iterator]() },
                values: { value: () => Object.values(normalized)[Symbol.iterator]() },
                [Symbol.iterator]: { value: () => Object.entries(normalized)[Symbol.iterator]() }
              });
              return result;
            };
            globalThis.fetch = async (url, options = {}) => {
              const signal = options && options.signal;
              if (signal && signal.aborted) throw __coveAbortError(signal.reason);
              const requestOptions = Object.assign({}, options || {});
              delete requestOptions.signal;
              const payload = JSON.parse(__bridge.request(String(url), JSON.stringify(requestOptions)));
              if (signal && signal.aborted) throw __coveAbortError(signal.reason);
              return {
                ok: payload.status >= 200 && payload.status < 300,
                status: payload.status,
                statusText: payload.statusText || '',
                url: payload.url || String(url),
                redirected: payload.redirected === true,
                headers: __coveHeaders(payload.headers),
                text: async () => payload.body,
                json: async () => JSON.parse(payload.body)
              };
            };
            globalThis.fetchWithTimeout = globalThis.fetch;
            globalThis.module = { exports: {} };
            globalThis.exports = globalThis.module.exports;
        """.trimIndent()
    }

    private fun invokeScript() = """
        globalThis.__coveDone = false;
        globalThis.__coveResult = '';
        globalThis.__coveError = '';
        (() => {
          const input = JSON.parse(__invocation);
          const exported = module.exports || exports;
          const getStreams = exported.getStreams || globalThis.getStreams;
          const scrape = exported.scrape || globalThis.scrape;
          const fn = getStreams || scrape;
          if (typeof fn !== 'function') throw new Error('no getStreams or scrape export');
          const value = getStreams
            ? fn(input.tmdbId, input.mediaType, input.season, input.episode)
            : fn({title: input.title, year: input.year, type: input.mediaType, imdbId: input.imdbId}, {});
          Promise.resolve(value).then(
            streams => { globalThis.__coveResult = JSON.stringify(streams || []); globalThis.__coveDone = true; },
            error => { globalThis.__coveError = String(error); globalThis.__coveDone = true; }
          );
        })();
    """.trimIndent()
}

/**
 * Graal can expose annotated methods only when their declaring JVM class is public. Kotlin
 * `private` compiles this top-level class package-private, which made every guest `fetch()` fail
 * with `Unknown identifier: request` even though the method itself was annotated and public.
 */
internal class FetchBridge {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** Empty rather than an exception for an unknown name: the guest's require() reports that. */
    @HostAccess.Export
    fun moduleSource(name: String): String = NUVIO_MODULE_RESOURCES[name]?.let { resource ->
        FetchBridge::class.java.classLoader.getResourceAsStream(resource)
            ?.bufferedReader()
            ?.use { it.readText() }
    }.orEmpty()

    @HostAccess.Export
    fun base64Encode(value: String): String = Base64.getEncoder().encodeToString(value.encodeToByteArray())

    @HostAccess.Export
    fun base64Decode(value: String): String = String(Base64.getDecoder().decode(value))

    @HostAccess.Export
    fun request(url: String, optionsJson: String): String {
        val options = runCatching { CoveJson.decodeFromString<FetchOptions>(optionsJson) }
            .getOrDefault(FetchOptions())
        val method = options.method.uppercase()
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
            "unsupported fetch method $method"
        }
        var current = URI(url)
        var redirected = false
        repeat(6) { redirectCount ->
            require(current.scheme == "http" || current.scheme == "https") {
                "fetch URL must use HTTP or HTTPS"
            }
            validateResolvedPublicUrl(current.toString())
            val body = if (options.body.isEmpty()) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(options.body)
            val builder = HttpRequest.newBuilder(current)
                .timeout(Duration.ofSeconds(10))
                .method(method, body)
                .header("User-Agent", "Cove Nuvio Sandbox")
            options.headers.forEach { (name, value) ->
                // Browser fetch implementations own these transport headers. Providers often
                // include them in copied browser header sets, so omit them instead of rejecting
                // the whole scraper invocation.
                if (name.lowercase() !in setOf("host", "content-length", "connection")) {
                    builder.header(name, value)
                }
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { input ->
                if (response.statusCode() in 300..399) {
                    if (options.redirect.equals("error", ignoreCase = true)) {
                        throw IllegalArgumentException("fetch redirect is not allowed")
                    }
                    if (options.redirect.equals("manual", ignoreCase = true)) {
                        return encodeFetchResponse(response, input.readNBytes(20 * 1024 * 1024 + 1), current, redirected)
                    }
                    require(redirectCount < 5) { "too many fetch redirects" }
                    val location = response.headers().firstValue("location").orElseThrow {
                        IllegalArgumentException("fetch redirect has no location")
                    }
                    current = current.resolve(location)
                    redirected = true
                    return@repeat
                }
                val bytes = input.readNBytes(20 * 1024 * 1024 + 1)
                return encodeFetchResponse(response, bytes, current, redirected)
            }
        }
        error("too many fetch redirects")
    }

    private fun encodeFetchResponse(
        response: HttpResponse<java.io.InputStream>,
        bytes: ByteArray,
        current: URI,
        redirected: Boolean,
    ): String {
        require(bytes.size <= 20 * 1024 * 1024) { "fetch response exceeds 20 MiB" }
        val status = response.statusCode()
        return CoveJson.encodeToString(FetchResponse(
            status = status,
            statusText = runCatching { HttpStatusCode.fromValue(status).description }.getOrDefault(""),
            headers = response.headers().map().mapValues { it.value.joinToString(", ") },
            body = String(bytes),
            url = current.toString(),
            redirected = redirected,
        ))
    }
}

private val NUVIO_MODULE_RESOURCES = mapOf(
    "crypto-js" to "crypto-js.js",
    "cheerio-without-node-native" to "cheerio-without-node-native.js",
)

@Serializable
private data class FetchOptions(
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val redirect: String = "follow",
)

@Serializable
private data class FetchResponse(
    val status: Int,
    val statusText: String = "",
    val headers: Map<String, String>,
    val body: String,
    val url: String = "",
    val redirected: Boolean = false,
)
