package com.coveninja.cove.backend.plugins

import com.coveninja.cove.shared.network.CoveJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

internal class PluginProcess(
    private val init: PluginWorkerInit,
    private val scope: CoroutineScope,
    private val onBroker: suspend (String, JsonElement) -> Unit,
    private val onLog: (String) -> Unit,
    private val onExit: (String?) -> Unit,
    private val javaExecutable: Path = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java",
    ),
    private val classpath: String = System.getProperty("java.class.path"),
    private val applicationExecutable: Path? = ProcessHandle.current().info().command()
        .orElse(null)
        ?.let(Path::of),
) : AutoCloseable {
    private val process = workerProcessBuilder().start()
    private val writer = process.outputStream.bufferedWriter()
    private val ready = CompletableDeferred<Unit>()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    private val readerJob: Job
    @Volatile private var closing = false

    init {
        sendRaw(CoveJson.encodeToString(init))
        readerJob = scope.launch(Dispatchers.IO) {
            var terminal: String? = null
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readPluginProtocolLine(MAX_WORKER_FRAME_CHARACTERS) ?: break
                        val frame = CoveJson.decodeFromString<PluginWorkerFrame>(line)
                        when (frame.type) {
                            "ready" -> ready.complete(Unit)
                            "result" -> {
                                val deferred = frame.id?.let { synchronized(pending) { pending.remove(it) } }
                                if (frame.message != null) {
                                    deferred?.completeExceptionally(IllegalStateException(frame.message))
                                } else {
                                    deferred?.complete(frame.payload)
                                }
                            }
                            "broker" -> scope.launch {
                                runCatching { onBroker(frame.method.orEmpty(), frame.payload) }
                                    .onFailure { onLog("broker: ${it.message ?: "request failed"}") }
                            }
                            "log" -> onLog("${frame.method ?: "info"}: ${frame.message.orEmpty()}")
                            "error" -> onLog("${frame.method ?: "plugin"}: ${frame.message.orEmpty()}")
                            "fatal" -> {
                                terminal = frame.message ?: "plugin worker failed"
                                if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException(terminal))
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                terminal = error.message ?: "plugin worker connection failed"
                if (!ready.isCompleted) ready.completeExceptionally(error)
            } finally {
                val error = IllegalStateException(terminal ?: "plugin worker stopped")
                synchronized(pending) {
                    pending.values.forEach { it.completeExceptionally(error) }
                    pending.clear()
                }
                if (!closing) onExit(terminal)
            }
        }
        scope.launch(Dispatchers.IO) {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { onLog("worker: ${it.take(2_000)}") }
            }
        }
    }

    suspend fun awaitReady() {
        withTimeout(START_TIMEOUT_MILLIS) { ready.await() }
    }

    suspend fun invoke(method: String, payload: JsonElement, timeoutMillis: Long): JsonElement {
        check(process.isAlive) { "plugin worker is not running" }
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonElement>()
        synchronized(pending) { pending[id] = deferred }
        return try {
            send(PluginHostFrame("invoke", id, method, payload))
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (error: TimeoutCancellationException) {
            close()
            throw IllegalStateException("plugin call timed out", error)
        } finally {
            synchronized(pending) { pending.remove(id) }
        }
    }

    fun event(method: String, payload: JsonElement) {
        if (process.isAlive) send(PluginHostFrame("event", method = method, payload = payload))
    }

    @Synchronized
    private fun send(frame: PluginHostFrame) = sendRaw(CoveJson.encodeToString(frame))

    @Synchronized
    private fun sendRaw(line: String) {
        writer.write(line)
        writer.newLine()
        writer.flush()
    }

    override fun close() {
        closing = true
        runCatching { send(PluginHostFrame("shutdown")) }
        runCatching {
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        runCatching { writer.close() }
        readerJob.cancel()
    }

    private fun workerProcessBuilder(): ProcessBuilder {
        if (Files.isExecutable(javaExecutable)) {
            return ProcessBuilder(
                javaExecutable.toString(),
                "-Xms16m",
                "-Xmx128m",
                "-Dpolyglot.engine.WarnInterpreterOnly=false",
                "-cp",
                classpath,
                PluginSandboxWorker::class.java.name,
            )
        }
        val executable = applicationExecutable
            ?.takeIf(Files::isExecutable)
            ?: throw IllegalStateException("Cove's plugin worker launcher is unavailable")
        return ProcessBuilder(executable.toString(), COVE_PLUGIN_WORKER_ARGUMENT).apply {
            val inherited = environment()["JAVA_TOOL_OPTIONS"].orEmpty().trim()
            environment()["JAVA_TOOL_OPTIONS"] = listOf(
                inherited,
                "-Xms16m -Xmx128m -Dpolyglot.engine.WarnInterpreterOnly=false",
            ).filter(String::isNotBlank).joinToString(" ")
        }
    }

    private companion object {
        const val START_TIMEOUT_MILLIS = 5_000L
        const val MAX_WORKER_FRAME_CHARACTERS = 8 * 1024 * 1024
    }
}
