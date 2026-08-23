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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess

internal interface NuvioSandbox {
    suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream>
}

/**
 * Executes each untrusted scraper in a disposable, memory-capped JVM. The
 * guest gets no host classes, filesystem, processes, native access, threads,
 * or raw sockets; its only network path is the public-address fetch bridge.
 */
internal class ProcessNuvioSandbox(
    private val timeoutMillis: Long = 17_000,
    private val javaExecutable: Path = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java",
    ),
    private val classpath: String = System.getProperty("java.class.path"),
) : NuvioSandbox {
    override suspend fun run(invocation: NuvioInvocation): List<NuvioScrapedStream> = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(
            javaExecutable.toString(),
            "-Xms16m",
            "-Xmx128m",
            "-Dpolyglot.engine.WarnInterpreterOnly=false",
            "-cp",
            classpath,
            NuvioSandboxWorker::class.java.name,
        ).start()
        try {
            coroutineScope {
                val stdout = async { process.inputStream.bufferedReader().readText() }
                val stderr = async { process.errorStream.bufferedReader().readText() }
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(CoveJson.encodeToString(invocation))
                }
                var workerExited = false
                try {
                    withTimeout(timeoutMillis) {
                        while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                            kotlinx.coroutines.yield()
                        }
                    }
                    workerExited = true
                } finally {
                    // Kill before coroutineScope waits for the blocking stdout
                    // and stderr readers; otherwise a runaway guest leaves
                    // those child coroutines waiting forever for EOF.
                    if (!workerExited && process.isAlive) process.destroyForcibly()
                }
                val output = stdout.await()
                val errorOutput = stderr.await().take(2_000)
                require(process.exitValue() == 0) {
                    "scraper worker failed${errorOutput.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
                }
                val result = CoveJson.decodeFromString<NuvioInvocationResult>(output)
                require(result.error.isBlank()) { result.error }
                result.streams
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** Entry point for the disposable scraper process. */
internal object NuvioSandboxWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        val invocation = CoveJson.decodeFromString<NuvioInvocation>(System.`in`.bufferedReader().readText())
        val result = runCatching { execute(invocation) }
            .fold(
                onSuccess = { NuvioInvocationResult(it) },
                onFailure = { NuvioInvocationResult(error = it.message ?: "scraper failed") },
            )
        print(CoveJson.encodeToString(result))
    }

    private fun execute(invocation: NuvioInvocation): List<NuvioScrapedStream> {
        val bridge = FetchBridge()
        Context.newBuilder("js")
            .allowHostAccess(HostAccess.EXPLICIT)
            .allowHostClassLookup { false }
            .allowIO(IOAccess.NONE)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .allowCreateProcess(false)
            .build().use { context ->
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
    }

    private fun bootstrap(): String {
        val modules = mapOf(
            "crypto-js" to resource("crypto-js.js"),
            "cheerio-without-node-native" to resource("cheerio-without-node-native.js"),
        )
        val moduleFactories = modules.entries.joinToString(",") { (name, source) ->
            "${CoveJson.encodeToString(name)}: function(module, exports) {\n$source\n}"
        }
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
            const __factories = {$moduleFactories};
            const __moduleCache = {};
            const __moduleAliases = {
              'cheerio': 'cheerio-without-node-native',
              'react-native-cheerio': 'cheerio-without-node-native'
            };
            globalThis.require = requestedName => {
              const name = __moduleAliases[requestedName] || requestedName;
              if (__moduleCache[name]) return __moduleCache[name].exports;
              const factory = __factories[name];
              if (!factory) throw new Error('unsupported module: ' + name);
              const loaded = { exports: {} };
              __moduleCache[name] = loaded;
              factory(loaded, loaded.exports);
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

    private fun resource(name: String): String = requireNotNull(
        NuvioSandboxWorker::class.java.classLoader.getResourceAsStream(name),
    ) { "missing Nuvio module $name" }.bufferedReader().use { it.readText() }
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
