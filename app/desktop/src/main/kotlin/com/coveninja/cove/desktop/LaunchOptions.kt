package com.coveninja.cove.desktop

data class LaunchOptions(
    val backendPath: String? = null,
    val playFile: String? = null,
    val apiBase: String? = null,
    val softwareRenderer: Boolean = false,
    val smokeSeconds: Int? = null,
) {
    companion object {
        private val knownFlags = setOf(
            "--backend", "--play", "--api-base", "--software-renderer", "--smoke-seconds",
        )

        fun parse(args: Array<String>): LaunchOptions {
            var backendPath: String? = null
            var playFile: String? = null
            var apiBase: String? = null
            var softwareRenderer = false
            var smokeSeconds: Int? = null

            var i = 0
            while (i < args.size) {
                val flag = args[i]
                if (flag !in knownFlags) {
                    throw IllegalArgumentException("Unknown flag: $flag")
                }
                when (flag) {
                    "--software-renderer" -> softwareRenderer = true
                    else -> {
                        // All other known flags take exactly one argument.
                        val value = args.getOrNull(i + 1)
                            ?: throw IllegalArgumentException("$flag requires a value")
                        when (flag) {
                            "--backend"       -> backendPath = value
                            "--play"          -> playFile = value
                            "--api-base"      -> apiBase = value
                            "--smoke-seconds" -> smokeSeconds = value.toIntOrNull()
                                ?: throw IllegalArgumentException("--smoke-seconds must be an integer, got: $value")
                        }
                        i++ // consume the value token
                    }
                }
                i++
            }
            return LaunchOptions(backendPath, playFile, apiBase, softwareRenderer, smokeSeconds)
        }
    }
}
