package com.coveninja.cove.backend.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopBackendEnvironmentTest {
    @Test
    fun `remote bind defaults beside main port and accepts explicit address`() {
        assertEquals(
            DesktopBackendEnvironment.BindAddress("0.0.0.0", 6970),
            DesktopBackendEnvironment.remoteBindAddress(6969, emptyMap()),
        )
        assertEquals(
            DesktopBackendEnvironment.BindAddress("192.168.1.2", 7777),
            DesktopBackendEnvironment.remoteBindAddress(
                6969,
                mapOf("COVE_REMOTE_ADDR" to "192.168.1.2:7777"),
            ),
        )
    }

    @Test
    fun `plugin trust and catalog endpoints have explicit desktop overrides`() {
        assertEquals(
            "plugins-1=public-key",
            DesktopBackendEnvironment.pluginPublicKeys(
                mapOf("COVE_PLUGIN_PUBLIC_KEYS" to "plugins-1=public-key"),
            ),
        )
        assertEquals(
            "https://catalog.test/repos/plugins",
            DesktopBackendEnvironment.pluginCatalogApiBase(
                mapOf("COVE_PLUGIN_CATALOG_API_BASE" to "https://catalog.test/repos/plugins"),
            ),
        )
        assertEquals(
            "https://api.github.com/repos/coveninja/cove-plugins",
            DesktopBackendEnvironment.pluginCatalogApiBase(emptyMap()),
        )
    }

    @Test
    fun `environment takes precedence over nearest dotenv`() {
        val root = Files.createTempDirectory("cove-env")
        Files.writeString(root.resolve(".env"), "TMDB_API_KEY=file-key\n")

        assertEquals(
            "environment-key",
            DesktopBackendEnvironment.tmdbApiKey(mapOf("TMDB_API_KEY" to "environment-key"), root),
        )
    }

    @Test
    fun `finds quoted key in ancestor dotenv`() {
        val root = Files.createTempDirectory("cove-env")
        val child = Files.createDirectories(root.resolve("app/backend"))
        Files.writeString(root.resolve(".env"), "# development\nexport TMDB_API_KEY='file-key'\n")

        assertEquals("file-key", DesktopBackendEnvironment.tmdbApiKey(emptyMap(), child))
    }

    @Test
    fun `missing key has an actionable error`() {
        val root = Files.createTempDirectory("cove-env")
        val error = assertFailsWith<IllegalStateException> {
            DesktopBackendEnvironment.tmdbApiKey(emptyMap(), root)
        }
        kotlin.test.assertTrue("TMDB_API_KEY" in error.message.orEmpty())
    }

    @Test
    fun `supabase is optional but requires its publishable key when configured`() {
        val root = Files.createTempDirectory("cove-supabase-env")
        assertEquals(null, DesktopBackendEnvironment.supabaseConfig(emptyMap(), root))

        val error = assertFailsWith<IllegalStateException> {
            DesktopBackendEnvironment.supabaseConfig(
                mapOf("SUPABASE_URL" to "https://project.invalid"),
                root,
            )
        }
        kotlin.test.assertTrue("SUPABASE_PUBLISHABLE_KEY" in error.message.orEmpty())

        val config = DesktopBackendEnvironment.supabaseConfig(
            mapOf(
                "SUPABASE_URL" to "https://project.invalid/",
                "SUPABASE_PUBLISHABLE_KEY" to "publishable",
            ),
            root,
        )
        assertEquals("https://project.invalid", config?.baseUrl)
        assertEquals("publishable", config?.publishableKey)
    }
}
