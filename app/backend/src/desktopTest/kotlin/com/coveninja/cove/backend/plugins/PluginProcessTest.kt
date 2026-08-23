package com.coveninja.cove.backend.plugins

import com.coveninja.cove.shared.data.PluginManifest
import com.coveninja.cove.shared.data.PluginCapability
import com.coveninja.cove.shared.data.PluginPlaybackActivity
import com.coveninja.cove.shared.network.CoveJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginProcessTest {
    @Test
    fun `guest cannot see host classes node globals or filesystem APIs`() = withProcess(
        source = """
            module.exports = {
              probe() {
                let javaAccess = false;
                let polyglotImport = false;
                try {
                  Java.type('java.lang.System');
                  javaAccess = true;
                } catch (_) {}
                try {
                  polyglotImport = Polyglot.import('host') !== undefined;
                } catch (_) {}
                return {
                  javaAccess,
                  processType: typeof process,
                  requireType: typeof require,
                  polyglotImport
                };
              }
            };
        """.trimIndent(),
    ) { process ->
        val result = process.invoke("probe", JsonNull, 5_000).jsonObject
        assertEquals("false", result["javaAccess"]?.jsonPrimitive?.content)
        assertEquals("undefined", result["processType"]?.jsonPrimitive?.content)
        assertEquals("undefined", result["requireType"]?.jsonPrimitive?.content)
        assertEquals("false", result["polyglotImport"]?.jsonPrimitive?.content)
    }

    @Test
    fun `activation fails when plugin uses an ungranted broker capability`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val process = createProcess(
            scope,
            "module.exports = { activate() { cove.discord.setActivity({details: 'no'}); } };",
        )
        try {
            val error = assertFailsWith<IllegalStateException> { runBlocking { process.awaitReady() } }
            assertTrue(error.message.orEmpty().contains("not granted"))
        } finally {
            process.close()
            scope.cancel()
        }
    }

    @Test
    fun `timed out guest call terminates a hung worker`() = withProcess(
        source = "module.exports = { hang() { while (true) {} } };",
    ) { process ->
        val error = assertFailsWith<IllegalStateException> {
            process.invoke("hang", JsonNull, 100)
        }
        assertEquals("plugin call timed out", error.message)
        val stopped = assertFailsWith<IllegalStateException> { process.invoke("hang", JsonNull, 100) }
        assertEquals("plugin worker is not running", stopped.message)
    }

    @Test
    fun `reference Discord plugin maps playback and clears presence`() = runBlocking {
        val source = Files.readString(findRepositoryFile("plugins/discord/main.js"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val brokers = Channel<Pair<String, JsonElement>>(Channel.UNLIMITED)
        val process = PluginProcess(
            init = PluginWorkerInit(
                manifest = manifest().copy(
                    capabilities = setOf(
                        PluginCapability.PlaybackObserve,
                        PluginCapability.UiSettings,
                        PluginCapability.DiscordPresence,
                    ),
                    discordApplicationId = "1234567890123456",
                ),
                source = source,
                settings = mapOf(
                    "show_title" to JsonPrimitive(true),
                    "show_episode" to JsonPrimitive(true),
                    "show_artwork" to JsonPrimitive(true),
                    "show_playback_state" to JsonPrimitive(true),
                    "show_progress" to JsonPrimitive(true),
                ),
                grantedCapabilities = setOf(
                    PluginCapability.PlaybackObserve,
                    PluginCapability.UiSettings,
                    PluginCapability.DiscordPresence,
                ),
            ),
            scope = scope,
            onBroker = { method, payload -> brokers.send(method to payload) },
            onLog = {},
            onExit = {},
        )
        try {
            process.awaitReady()
            process.invoke(
                "onPlaybackChanged",
                CoveJson.encodeToJsonElement(
                    PluginPlaybackActivity.serializer(),
                    PluginPlaybackActivity(
                        active = true,
                        title = "Example series",
                        artworkUrl = "https://image.tmdb.org/t/p/w500/example.jpg",
                        season = 2,
                        episode = 3,
                        episodeTitle = "The Test",
                        phase = "playing",
                        paused = false,
                        positionSeconds = 60.0,
                        durationSeconds = 3600.0,
                    ),
                ),
                5_000,
            )
            val set = withTimeout(5_000) { brokers.receive() }
            assertEquals("discord.setActivity", set.first)
            assertEquals("Example series", set.second.jsonObject["details"]?.jsonPrimitive?.content)
            assertTrue(set.second.jsonObject["state"]?.jsonPrimitive?.content.orEmpty().contains("S02 E03"))
            val assets = set.second.jsonObject["assets"]?.jsonObject
            assertEquals(
                "https://image.tmdb.org/t/p/w500/example.jpg",
                assets?.get("large_image")?.jsonPrimitive?.content,
            )
            assertEquals("cove", assets?.get("small_image")?.jsonPrimitive?.content)

            process.invoke(
                "onPlaybackChanged",
                CoveJson.encodeToJsonElement(
                    PluginPlaybackActivity.serializer(),
                    PluginPlaybackActivity(active = true, title = "No poster", phase = "playing"),
                ),
                5_000,
            )
            val fallback = withTimeout(5_000) { brokers.receive() }
            assertEquals("cove", fallback.second.jsonObject["assets"]?.jsonObject
                ?.get("large_image")?.jsonPrimitive?.content)

            process.invoke(
                "onPlaybackChanged",
                CoveJson.encodeToJsonElement(
                    PluginPlaybackActivity.serializer(),
                    PluginPlaybackActivity(),
                ),
                5_000,
            )
            assertEquals("discord.clear", withTimeout(5_000) { brokers.receive() }.first)
        } finally {
            process.close()
            scope.cancel()
        }
    }

    private fun withProcess(
        source: String,
        test: suspend (PluginProcess) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val process = createProcess(scope, source)
        try {
            runBlocking {
                process.awaitReady()
                test(process)
            }
        } finally {
            process.close()
            scope.cancel()
        }
    }

    private fun createProcess(scope: CoroutineScope, source: String): PluginProcess {
        val exits = CopyOnWriteArrayList<String?>()
        return PluginProcess(
            init = PluginWorkerInit(manifest = manifest(), source = source),
            scope = scope,
            onBroker = { _, _ -> },
            onLog = {},
            onExit = { exits += it },
        )
    }

    private fun manifest() = PluginManifest(
        id = "io.github.coveninja.process-test",
        name = "Process test",
        version = "1.0.0",
        publisher = "Cove tests",
        minimumCoveVersion = "1.1.1",
    )

    private fun findRepositoryFile(relative: String): Path = generateSequence(
        Path.of("").toAbsolutePath().normalize(),
        Path::getParent,
    ).map { it.resolve(relative) }.first(Files::isRegularFile)
}
