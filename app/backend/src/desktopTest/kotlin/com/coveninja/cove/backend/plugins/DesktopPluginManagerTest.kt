package com.coveninja.cove.backend.plugins

import com.coveninja.cove.shared.data.PluginCapability
import com.coveninja.cove.shared.data.PluginManifest
import com.coveninja.cove.shared.data.PluginMediaRequest
import com.coveninja.cove.shared.data.PluginRuntimeStatus
import com.coveninja.cove.shared.data.PluginSettingDefinition
import com.coveninja.cove.shared.data.PluginSettingType
import com.coveninja.cove.shared.data.PluginsState
import com.coveninja.cove.shared.network.CoveJson
import com.coveninja.cove.shared.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive

class DesktopPluginManagerTest {
    @Test
    fun `local plugin consent enablement and settings are profile scoped`() = runBlocking {
        val directory = createTempDirectory("cove-plugin-manager")
        val packageFile = directory.resolve("profile-plugin.zip")
        Files.write(packageFile, pluginZip())
        val profile = MutableStateFlow("primary")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = HttpClient(MockEngine { error("catalog should not be requested in this test") })
        val manager = DesktopPluginManager(
            dataDirectory = directory,
            activeProfileIds = profile,
            scope = scope,
            httpClient = client,
            catalogApiBase = "https://api.github.com/repos/coveninja/cove-plugins",
            publicKeys = emptyMap(),
            currentCoveVersion = "1.1.1",
            allowLan = { false },
        )
        try {
            manager.setDeveloperMode(true)
            manager.installLocal(packageFile.toString())
            assertEquals(PluginRuntimeStatus.PermissionRequired, manager.ready().installed.single().status)

            manager.approve(PLUGIN_ID, setOf(PluginCapability.UiSettings))
            assertEquals(PluginRuntimeStatus.PermissionRequired, manager.ready().installed.single().status)
            assertFailsWith<IllegalArgumentException> { manager.setEnabled(PLUGIN_ID, true) }

            manager.approve(PLUGIN_ID, manifest().capabilities)
            manager.updateSetting(PLUGIN_ID, "label", JsonPrimitive("Primary value"))
            manager.setEnabled(PLUGIN_ID, true)
            val running = manager.awaitStatus(PluginRuntimeStatus.Running)
            assertEquals(true, running.enabled)
            assertEquals("Primary value", running.settings["label"]?.toString()?.trim('"'))
            assertEquals(
                "Primary value",
                manager.streams(PluginMediaRequest(42, MediaType.Movie)).single().title,
            )

            manager.updateSetting(PLUGIN_ID, "label", JsonPrimitive("Updated value"))
            assertEquals(
                "Updated value",
                manager.streams(PluginMediaRequest(42, MediaType.Movie)).single().title,
            )

            profile.value = "secondary"
            val secondary = manager.awaitEnabled(false)
            assertEquals("Default value", secondary.settings["label"]?.toString()?.trim('"'))
            assertEquals(emptyList(), manager.streams(PluginMediaRequest(42, MediaType.Movie)))

            profile.value = "primary"
            val restarted = manager.awaitStatus(PluginRuntimeStatus.Running)
            assertEquals(true, restarted.enabled)
            assertEquals("Updated value", restarted.settings["label"]?.toString()?.trim('"'))
        } finally {
            manager.close()
            client.close()
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    private suspend fun DesktopPluginManager.ready(): PluginsState.Ready =
        assertIs<PluginsState.Ready>(state.value)

    private suspend fun DesktopPluginManager.awaitStatus(status: PluginRuntimeStatus) =
        awaitState("status == $status") { current ->
            (current as? PluginsState.Ready)?.installed?.singleOrNull()?.status == status
        }

    private suspend fun DesktopPluginManager.awaitEnabled(enabled: Boolean) =
        awaitState("enabled == $enabled") { current ->
            (current as? PluginsState.Ready)?.installed?.singleOrNull()?.enabled == enabled
        }

    /**
     * Waits for a plugin state, and says what it was still waiting on if it never arrives.
     *
     * Reaching Running means spawning a child JVM that interprets the plugin's JavaScript,
     * so this bound is the slowest thing in the suite and the first to give way on a loaded
     * CI runner. A bare `TimeoutCancellationException` names neither the condition nor how
     * far the plugin actually got, which leaves a CI-only failure with nothing to go on —
     * so report both, and the elapsed time, to tell "never started" apart from "too slow".
     */
    private suspend fun DesktopPluginManager.awaitState(
        expectation: String,
        predicate: (PluginsState) -> Boolean,
    ) = try {
        val started = System.nanoTime()
        withTimeout(AWAIT_STATE_TIMEOUT_MILLIS) {
            state.filter(predicate).first().let { (it as PluginsState.Ready).installed.single() }
        }.also {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            println("[DesktopPluginManagerTest] $expectation reached in ${elapsed}ms")
        }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError(
            "Timed out after ${AWAIT_STATE_TIMEOUT_MILLIS}ms waiting for $expectation; " +
                "last state was ${state.value}",
            timeout,
        )
    }

    private fun pluginZip(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            listOf(
                "plugin.json" to CoveJson.encodeToString(manifest()).encodeToByteArray(),
                "main.js" to """
                    module.exports = {
                      activate() {},
                      provideStreams() {
                        return [{name: 'Test stream', title: cove.settings.get('label'), url: 'https://video.test/movie'}];
                      }
                    };
                """.trimIndent().encodeToByteArray(),
            ).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun manifest() = PluginManifest(
        id = PLUGIN_ID,
        name = "Profile plugin",
        version = "1.0.0",
        publisher = "Cove tests",
        minimumCoveVersion = "1.1.1",
        capabilities = setOf(
            PluginCapability.UiSettings,
            PluginCapability.StorageProfile,
            PluginCapability.MediaStreams,
        ),
        settings = listOf(
            PluginSettingDefinition(
                key = "label",
                type = PluginSettingType.String,
                label = "Label",
                default = JsonPrimitive("Default value"),
            ),
        ),
    )

    private companion object {
        const val PLUGIN_ID = "io.github.coveninja.profile-plugin"
        const val AWAIT_STATE_TIMEOUT_MILLIS = 10_000L
    }
}
