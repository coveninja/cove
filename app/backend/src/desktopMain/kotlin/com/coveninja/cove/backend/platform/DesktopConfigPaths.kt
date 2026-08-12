package com.coveninja.cove.backend.platform

import java.nio.file.Files
import java.nio.file.Path

object DesktopConfigPaths {
    fun dataDirectory(
        environment: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name"),
        userHome: String = System.getProperty("user.home"),
    ): Path {
        environment["COVE_DATA_DIR"]?.takeIf(String::isNotBlank)?.let {
            return Path.of(it).toAbsolutePath().normalize().also(Files::createDirectories)
        }

        val directory = when {
            osName.startsWith("Windows", ignoreCase = true) ->
                Path.of(environment["APPDATA"] ?: Path.of(userHome, "AppData", "Roaming").toString(), "cove")
            osName.startsWith("Mac", ignoreCase = true) ->
                Path.of(userHome, "Library", "Application Support", "cove")
            else ->
                Path.of(environment["XDG_CONFIG_HOME"] ?: Path.of(userHome, ".config").toString(), "cove")
        }
        return directory.toAbsolutePath().normalize().also(Files::createDirectories)
    }
}
