package com.coveninja.cove.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LaunchOptionsTest {

    // Each row: args array → expected LaunchOptions
    private val happyCases = listOf(
        arrayOf("--backend", "/usr/bin/cove") to
            LaunchOptions(backendPath = "/usr/bin/cove"),
        arrayOf("--play", "/tmp/movie.mkv") to
            LaunchOptions(playFile = "/tmp/movie.mkv"),
        arrayOf("--api-base", "http://localhost:6969") to
            LaunchOptions(apiBase = "http://localhost:6969"),
        arrayOf("--software-renderer") to
            LaunchOptions(softwareRenderer = true),
        arrayOf("--smoke-seconds", "3") to
            LaunchOptions(smokeSeconds = 3),
        // Multiple flags together
        arrayOf("--backend", "/usr/bin/cove", "--smoke-seconds", "5") to
            LaunchOptions(backendPath = "/usr/bin/cove", smokeSeconds = 5),
        // Empty args → defaults
        emptyArray<String>() to LaunchOptions(),
    )

    @Test
    fun `all valid flag combinations parse correctly`() {
        for ((input, expected) in happyCases) {
            assertEquals(expected, LaunchOptions.parse(input), "failed for: ${input.toList()}")
        }
    }

    @Test
    fun `missing value for backend flag throws`() {
        assertFailsWith<IllegalArgumentException> {
            LaunchOptions.parse(arrayOf("--backend"))
        }
    }

    @Test
    fun `missing value for smoke-seconds flag throws`() {
        assertFailsWith<IllegalArgumentException> {
            LaunchOptions.parse(arrayOf("--smoke-seconds"))
        }
    }

    @Test
    fun `non-integer smoke-seconds throws`() {
        assertFailsWith<IllegalArgumentException> {
            LaunchOptions.parse(arrayOf("--smoke-seconds", "notanumber"))
        }
    }

    // These assert on the message, not just the exception type. An unknown flag
    // with no value would also throw "requires a value" if the unknown-flag
    // check were removed, so type alone cannot tell the two failures apart and
    // a bare assertFailsWith here passes even with the validation deleted.
    @Test
    fun `unknown flag throws even when followed by a value`() {
        for (args in listOf(
            arrayOf("--unknown-flag"),
            arrayOf("--unknown-flag", "value"),
            arrayOf("--verbose"),
            arrayOf("--backend", "/usr/bin/cove", "--verbose", "on"),
        )) {
            val error = assertFailsWith<IllegalArgumentException>("expected rejection of ${args.toList()}") {
                LaunchOptions.parse(args)
            }
            assertTrue(
                error.message.orEmpty().startsWith("Unknown flag:"),
                "wrong failure for ${args.toList()}: ${error.message}",
            )
        }
    }

    @Test
    fun `a bare value with no preceding flag is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            LaunchOptions.parse(arrayOf("/tmp/movie.mkv"))
        }
        assertTrue(error.message.orEmpty().startsWith("Unknown flag:"), error.message)
    }
}
