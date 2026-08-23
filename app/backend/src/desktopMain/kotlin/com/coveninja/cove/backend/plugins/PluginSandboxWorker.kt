package com.coveninja.cove.backend.plugins

import com.coveninja.cove.backend.addons.validateResolvedPublicUrl
import com.coveninja.cove.shared.data.PluginCapability
import com.coveninja.cove.shared.network.CoveJson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess

const val COVE_PLUGIN_WORKER_ARGUMENT = "--cove-plugin-worker"

/** Entry used by the packaged native launcher, whose stripped runtime has no bin/java command. */
fun runPluginSandboxWorker() = PluginSandboxWorker.main(emptyArray())

/** Long-lived, memory-capped guest process. Only this trusted wrapper has host access. */
internal object PluginSandboxWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        val reader = System.`in`.bufferedReader()
        val writer = System.out.bufferedWriter()
        val output = PluginWorkerOutput(writer)
        val init = runCatching {
            CoveJson.decodeFromString<PluginWorkerInit>(
                reader.readPluginProtocolLine(MAX_INIT_FRAME_CHARACTERS) ?: error("missing plugin init"),
            )
        }.getOrElse {
            output.send(PluginWorkerFrame("fatal", message = it.message ?: "invalid plugin init"))
            return
        }
        if (init.protocolVersion != 1) {
            output.send(PluginWorkerFrame("fatal", message = "unsupported plugin protocol"))
            return
        }

        val bridge = PluginGuestBridge(init, output)
        runCatching {
            Context.newBuilder("js")
                .allowHostAccess(HostAccess.EXPLICIT)
                .allowHostClassLookup { false }
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowCreateProcess(false)
                .build()
                .use { context ->
                    context.getBindings("js").putMember("__bridge", bridge)
                    context.getBindings("js").putMember("__pluginSettings", CoveJson.encodeToString(init.settings))
                    context.getBindings("js").putMember("__pluginStorage", CoveJson.encodeToString(init.storage))
                    context.eval(Source.newBuilder("js", bootstrap(), "cove-plugin-bootstrap.js").build())
                    context.eval(Source.newBuilder("js", init.source, init.manifest.entrypoint).build())
                    val activationError = invoke(context, "activate", JsonObject(emptyMap()), null, output)
                    if (activationError != null) {
                        output.send(PluginWorkerFrame("fatal", message = safeGuestError(activationError)))
                        return
                    }
                    output.send(PluginWorkerFrame("ready"))
                    while (true) {
                        val line = reader.readPluginProtocolLine(MAX_HOST_FRAME_CHARACTERS) ?: break
                        val frame = runCatching { CoveJson.decodeFromString<PluginHostFrame>(line) }
                            .getOrElse {
                                output.send(PluginWorkerFrame("error", message = "malformed host frame"))
                                continue
                            }
                        when (frame.type) {
                            "invoke" -> invoke(context, frame.method.orEmpty(), frame.payload, frame.id, output)
                            "event" -> invoke(context, frame.method.orEmpty(), frame.payload, null, output)
                            "ping" -> output.send(PluginWorkerFrame("pong", id = frame.id))
                            "shutdown" -> {
                                invoke(context, "deactivate", JsonObject(emptyMap()), null, output)
                                return
                            }
                        }
                    }
                }
        }.onFailure { error ->
            output.send(PluginWorkerFrame("fatal", message = safeGuestError(error)))
        }
    }

    private fun invoke(
        context: Context,
        method: String,
        payload: JsonElement,
        id: String?,
        output: PluginWorkerOutput,
    ): Throwable? {
        context.getBindings("js").putMember("__coveMethod", method)
        context.getBindings("js").putMember("__covePayload", payload.toString())
        val script = """
            globalThis.__coveDone = false;
            globalThis.__coveResult = 'null';
            globalThis.__coveError = '';
            (() => {
              try {
                const payload = JSON.parse(__covePayload);
                if (__coveMethod === 'settingsChanged' && payload && typeof payload === 'object') {
                  Object.assign(__settings, payload);
                }
                const exported = module.exports || exports || {};
                const fn = exported[__coveMethod] || globalThis[__coveMethod];
                if (typeof fn !== 'function') {
                  globalThis.__coveDone = true;
                  return;
                }
                Promise.resolve(fn(payload)).then(
                  value => {
                    globalThis.__coveResult = JSON.stringify(value === undefined ? null : value);
                    globalThis.__coveDone = true;
                  },
                  error => {
                    globalThis.__coveError = String(error && error.message ? error.message : error);
                    globalThis.__coveDone = true;
                  }
                );
              } catch (error) {
                globalThis.__coveError = String(error && error.message ? error.message : error);
                globalThis.__coveDone = true;
              }
            })();
        """.trimIndent()
        return runCatching {
            context.eval("js", script)
            repeat(200) {
                if (context.eval("js", "globalThis.__coveDone === true").asBoolean()) return@repeat
                context.eval("js", "0")
            }
            check(context.eval("js", "globalThis.__coveDone === true").asBoolean()) {
                "plugin promise did not settle"
            }
            val error = context.eval("js", "globalThis.__coveError || ''").asString()
            check(error.isBlank()) { error }
            val result = CoveJson.parseToJsonElement(
                context.eval("js", "globalThis.__coveResult || 'null'").asString(),
            )
            if (id != null) output.send(PluginWorkerFrame("result", id = id, payload = result))
        }.fold(
            onSuccess = { null },
            onFailure = { error ->
            if (id != null) {
                output.send(PluginWorkerFrame("result", id = id, message = safeGuestError(error)))
            } else {
                output.send(PluginWorkerFrame("error", method = method, message = safeGuestError(error)))
            }
                error
            },
        )
    }

    private fun bootstrap(): String = """
        globalThis.module = { exports: {} };
        globalThis.exports = globalThis.module.exports;
        globalThis.console = {
          log: (...v) => __bridge.log('info', v.map(String).join(' ')),
          info: (...v) => __bridge.log('info', v.map(String).join(' ')),
          warn: (...v) => __bridge.log('warn', v.map(String).join(' ')),
          error: (...v) => __bridge.log('error', v.map(String).join(' ')),
          debug: (...v) => __bridge.log('debug', v.map(String).join(' '))
        };
        globalThis.atob = value => __bridge.base64Decode(String(value));
        globalThis.btoa = value => __bridge.base64Encode(String(value));
        const __settings = JSON.parse(__pluginSettings || '{}');
        const __storage = JSON.parse(__pluginStorage || '{}');
        globalThis.cove = {
          settings: {
            get: key => __settings[String(key)],
            all: () => Object.assign({}, __settings)
          },
          storage: {
            get: key => __storage[String(key)],
            set: (key, value) => {
              __storage[String(key)] = value;
              __bridge.emit('storage.set', JSON.stringify({key: String(key), value}));
            },
            delete: key => {
              delete __storage[String(key)];
              __bridge.emit('storage.delete', JSON.stringify({key: String(key)}));
            }
          },
          player: {
            setPaused: paused => __bridge.emit('player.setPaused', JSON.stringify({paused: !!paused})),
            seek: seconds => __bridge.emit('player.seek', JSON.stringify({seconds: Number(seconds)})),
            seekRelative: seconds => __bridge.emit('player.seekRelative', JSON.stringify({seconds: Number(seconds)})),
            stop: () => __bridge.emit('player.stop', '{}')
          },
          discord: {
            setActivity: activity => __bridge.emit('discord.setActivity', JSON.stringify(activity || {})),
            clear: () => __bridge.emit('discord.clear', '{}')
          },
          fetch: async (url, options = {}) => {
            const response = JSON.parse(__bridge.fetch(String(url), JSON.stringify(options || {})));
            return {
              ok: response.status >= 200 && response.status < 300,
              status: response.status,
              url: response.url,
              redirected: response.redirected === true,
              headers: response.headers || {},
              text: async () => response.body,
              json: async () => JSON.parse(response.body)
            };
          }
        };
    """.trimIndent()
}

internal class PluginGuestBridge(
    private val init: PluginWorkerInit,
    private val output: PluginWorkerOutput,
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @HostAccess.Export
    fun log(level: String, message: String) {
        output.send(PluginWorkerFrame("log", method = level.take(12), message = message.take(2_000)))
    }

    @HostAccess.Export
    fun emit(method: String, payload: String) {
        val required = when {
            method.startsWith("player.") -> PluginCapability.PlaybackTransport
            method.startsWith("discord.") -> PluginCapability.DiscordPresence
            method.startsWith("storage.") -> PluginCapability.StorageProfile
            else -> null
        }
        require(required == null || required in init.grantedCapabilities) { "capability $required is not granted" }
        val element = runCatching { CoveJson.parseToJsonElement(payload) }.getOrDefault(JsonNull)
        output.send(PluginWorkerFrame("broker", method = method, payload = element))
    }

    @HostAccess.Export
    fun base64Encode(value: String): String = Base64.getEncoder().encodeToString(value.encodeToByteArray())

    @HostAccess.Export
    fun base64Decode(value: String): String = String(Base64.getDecoder().decode(value))

    @HostAccess.Export
    fun fetch(url: String, optionsJson: String): String {
        require(PluginCapability.NetworkHttp in init.grantedCapabilities) { "network.http is not granted" }
        val options = runCatching { CoveJson.decodeFromString<PluginFetchOptions>(optionsJson) }
            .getOrDefault(PluginFetchOptions())
        val method = options.method.uppercase()
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
            "unsupported HTTP method"
        }
        var current = URI(url)
        var redirected = false
        repeat(6) { redirect ->
            require(current.scheme == "http" || current.scheme == "https") { "fetch must use HTTP or HTTPS" }
            require(current.rawUserInfo == null) { "fetch URL must not contain credentials" }
            val host = current.host?.lowercase() ?: throw IllegalArgumentException("fetch URL has no host")
            require(host in init.manifest.allowedHosts.map(String::lowercase)) { "fetch host is not declared" }
            if (!init.allowLan || PluginCapability.NetworkLan !in init.grantedCapabilities) {
                validateResolvedPublicUrl(current.toString())
            }
            val body = if (options.body.isEmpty()) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(options.body)
            val builder = HttpRequest.newBuilder(current)
                .timeout(Duration.ofSeconds(20))
                .method(method, body)
                .header("User-Agent", "Cove Plugin/${init.manifest.id}")
            options.headers.forEach { (name, value) ->
                if (name.lowercase() !in FORBIDDEN_HEADERS) builder.header(name, value)
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { input ->
                if (response.statusCode() in 300..399) {
                    val location = response.headers().firstValue("Location").orElse("")
                    require(location.isNotBlank() && redirect < 5) { "invalid fetch redirect" }
                    current = current.resolve(location)
                    redirected = true
                } else {
                    val bytes = input.readNBytes(MAX_FETCH_BYTES + 1)
                    require(bytes.size <= MAX_FETCH_BYTES) { "plugin fetch response exceeds 5 MiB" }
                    return CoveJson.encodeToString(
                        PluginFetchResponse(
                            response.statusCode(),
                            current.toString(),
                            response.headers().map().mapValues { it.value.joinToString(", ") },
                            bytes.decodeToString(),
                            redirected,
                        ),
                    )
                }
            }
        }
        error("too many fetch redirects")
    }

    private companion object {
        const val MAX_FETCH_BYTES = 5 * 1024 * 1024
        val FORBIDDEN_HEADERS = setOf("host", "content-length", "connection", "cookie", "authorization")
    }
}

internal class PluginWorkerOutput(private val writer: java.io.BufferedWriter) {
    @Synchronized
    fun send(frame: PluginWorkerFrame) {
        writer.write(CoveJson.encodeToString(frame))
        writer.newLine()
        writer.flush()
    }
}

private fun safeGuestError(error: Throwable): String =
    (error.message ?: error::class.simpleName ?: "plugin failed").lineSequence().first().take(500)

private const val MAX_INIT_FRAME_CHARACTERS = 16 * 1024 * 1024
private const val MAX_HOST_FRAME_CHARACTERS = 8 * 1024 * 1024
