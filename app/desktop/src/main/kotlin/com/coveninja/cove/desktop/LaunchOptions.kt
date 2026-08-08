package com.coveninja.cove.desktop

data class LaunchOptions(
    val playFile: String? = null,
    val apiBase: String? = null,
    val softwareRenderer: Boolean = false,
    val smokeSeconds: Int? = null,
    val backendMode: BackendMode? = null,
    val exportLegacy: Boolean = false,
) {
    companion object {
        private val knownFlags = setOf(
            "--play", "--api-base", "--software-renderer", "--smoke-seconds",
            "--backend-mode",
            "--export-legacy",
        )

        fun parse(args: Array<String>): LaunchOptions {
            var playFile: String? = null
            var apiBase: String? = null
            var softwareRenderer = false
            var smokeSeconds: Int? = null
            var backendMode: BackendMode? = null
            var exportLegacy = false

            var i = 0
            while (i < args.size) {
                val flag = args[i]
                if (flag !in knownFlags) {
                    throw IllegalArgumentException("Unknown flag: $flag")
                }
                when (flag) {
                    "--software-renderer" -> softwareRenderer = true
                    "--export-legacy" -> exportLegacy = true
                    else -> {
                        // All other known flags take exactly one argument.
                        val value = args.getOrNull(i + 1)
                            ?: throw IllegalArgumentException("$flag requires a value")
                        when (flag) {
                            "--play"          -> playFile = value
                            "--api-base"      -> apiBase = value
                            "--smoke-seconds" -> smokeSeconds = value.toIntOrNull()
                                ?: throw IllegalArgumentException("--smoke-seconds must be an integer, got: $value")
                            "--backend-mode" -> backendMode = BackendMode.parse(value)
                        }
                        i++ // consume the value token
                    }
                }
                i++
            }
            require(backendMode != BackendMode.Kotlin || apiBase == null) {
                "--backend-mode kotlin cannot be combined with --api-base"
            }
            require(backendMode != BackendMode.Fixtures || apiBase == null) {
                "--backend-mode fixtures cannot be combined with --api-base"
            }
            require(!exportLegacy || (playFile == null && apiBase == null)) {
                "--export-legacy cannot be combined with playback or an external backend"
            }
            return LaunchOptions(
                playFile = playFile,
                apiBase = apiBase,
                softwareRenderer = softwareRenderer,
                smokeSeconds = smokeSeconds,
                backendMode = backendMode,
                exportLegacy = exportLegacy,
            )
        }
    }
}

enum class BackendMode {
    Kotlin,
    Fixtures;

    companion object {
        fun parse(value: String): BackendMode = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "--backend-mode must be one of kotlin or fixtures; got: $value",
        )
    }
}
