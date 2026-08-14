package com.coveninja.cove.backend.platform

import com.coveninja.cove.backend.auth.SupabaseConfig
import com.coveninja.cove.backend.trakt.TraktConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

object DesktopBackendEnvironment {
    data class BindAddress(val host: String, val port: Int)

    fun tmdbApiKey(
        environment: Map<String, String> = System.getenv(),
        searchStart: Path = Path.of("").toAbsolutePath(),
    ): String {
        value("TMDB_API_KEY", environment, searchStart)?.let { return it }
        throw IllegalStateException(
            "TMDB_API_KEY is not set; export it or provide it in a .env file for Kotlin backend mode",
        )
    }

    fun appVersion(environment: Map<String, String> = System.getenv()): String =
        environment["COVE_VERSION"]?.takeIf(String::isNotBlank)
            ?: System.getProperty("cove.version")?.takeIf(String::isNotBlank)
            ?: bundledValues["COVE_VERSION"]?.takeIf(String::isNotBlank)
            ?: "dev"

    fun updatePublicKeys(environment: Map<String, String> = System.getenv()): String =
        environment["COVE_UPDATE_PUBLIC_KEYS"]?.takeIf(String::isNotBlank)
            ?: bundledValues["UPDATE_PUBLIC_KEYS"]?.takeIf(String::isNotBlank)
            ?: ""

    fun updateApiBase(environment: Map<String, String> = System.getenv()): String =
        environment["COVE_UPDATE_API_BASE"]?.takeIf(String::isNotBlank)
            ?: "https://api.github.com/repos/coveninja/cove"

    fun supabaseConfig(
        environment: Map<String, String> = System.getenv(),
        searchStart: Path = Path.of("").toAbsolutePath(),
    ): SupabaseConfig? {
        val url = value("SUPABASE_URL", environment, searchStart) ?: return null
        val key = value("SUPABASE_PUBLISHABLE_KEY", environment, searchStart)
            ?: throw IllegalStateException("SUPABASE_PUBLISHABLE_KEY is required when SUPABASE_URL is set")
        return SupabaseConfig(url, key)
    }

    fun traktConfig(
        environment: Map<String, String> = System.getenv(),
        searchStart: Path = Path.of("").toAbsolutePath(),
    ): TraktConfig = TraktConfig(
        clientId = value("TRAKT_CLIENT_ID", environment, searchStart).orEmpty(),
        clientSecret = value("TRAKT_CLIENT_SECRET", environment, searchStart).orEmpty(),
    )

    fun remoteBindAddress(
        mainPort: Int,
        environment: Map<String, String> = System.getenv(),
    ): BindAddress {
        val raw = environment["COVE_REMOTE_ADDR"]?.trim().orEmpty()
        if (raw.isBlank()) return BindAddress("0.0.0.0", (mainPort + 1).coerceAtMost(65_535))
        val separator = raw.lastIndexOf(':')
        require(separator > 0 && separator < raw.lastIndex) {
            "COVE_REMOTE_ADDR must be host:port"
        }
        val host = raw.substring(0, separator).removeSurrounding("[", "]")
        val port = raw.substring(separator + 1).toIntOrNull()
            ?.takeIf { it in 1..65_535 }
            ?: throw IllegalArgumentException("COVE_REMOTE_ADDR has an invalid port")
        require(host.isNotBlank()) { "COVE_REMOTE_ADDR has an empty host" }
        return BindAddress(host, port)
    }

    private fun value(
        key: String,
        environment: Map<String, String>,
        searchStart: Path,
    ): String? {
        environment[key]?.takeIf(String::isNotBlank)?.let { return it }
        val explicitFile = environment["COVE_ENV_FILE"]?.takeIf(String::isNotBlank)?.let(Path::of)
        val candidates = explicitFile?.let(::sequenceOf) ?: generateSequence(
            searchStart.toAbsolutePath().normalize(),
        ) { it.parent }.take(5).map { it.resolve(".env") }
        for (file in candidates) {
            readDotEnvValue(file, key)?.takeIf(String::isNotBlank)?.let { return it }
        }
        return bundledValues[key]?.takeIf(String::isNotBlank)
    }

    private fun readDotEnvValue(path: Path, key: String): String? {
        if (!Files.isRegularFile(path)) return null
        return Files.readAllLines(path).firstNotNullOfOrNull { rawLine ->
            val line = rawLine.trim().removePrefix("export ").trim()
            if (line.isBlank() || line.startsWith('#')) return@firstNotNullOfOrNull null
            val separator = line.indexOf('=')
            if (separator <= 0 || line.substring(0, separator).trim() != key) {
                return@firstNotNullOfOrNull null
            }
            line.substring(separator + 1).trim().removeSurrounding("\"").removeSurrounding("'")
        }
    }

    private val bundledValues: Map<String, String> by lazy {
        DesktopBackendEnvironment::class.java.classLoader
            .getResourceAsStream("cove-build.properties")
            ?.use { stream ->
                Properties().apply { load(stream) }.entries.associate { (key, value) ->
                    key.toString() to value.toString()
                }
            }
            .orEmpty()
    }
}
