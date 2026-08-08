package com.coveninja.cove.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LaunchOptionsTest {

    // Each row: args array → expected LaunchOptions
    private val happyCases = listOf(
        arrayOf("--play", "/tmp/movie.mkv") to
            LaunchOptions(playFile = "/tmp/movie.mkv"),
        arrayOf("--api-base", "http://localhost:6969") to
            LaunchOptions(apiBase = "http://localhost:6969"),
        arrayOf("--software-renderer") to
            LaunchOptions(softwareRenderer = true),
        arrayOf("--smoke-seconds", "3") to
            LaunchOptions(smokeSeconds = 3),
        arrayOf("--backend-mode", "kotlin") to
            LaunchOptions(backendMode = BackendMode.Kotlin),
        arrayOf("--export-legacy") to
            LaunchOptions(exportLegacy = true),
        // Multiple flags together
        arrayOf("--software-renderer", "--smoke-seconds", "5") to
            LaunchOptions(softwareRenderer = true, smokeSeconds = 5),
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

    @Test
    fun `invalid backend mode throws`() {
        assertFailsWith<IllegalArgumentException> {
            LaunchOptions.parse(arrayOf("--backend-mode", "rust"))
        }
    }

    @Test
    fun `in-process modes reject external backend arguments`() {
        for (args in listOf(
            arrayOf("--backend-mode", "kotlin", "--api-base", "http://localhost:6969"),
            arrayOf("--backend-mode", "fixtures", "--api-base", "http://localhost:6969"),
        )) {
            assertFailsWith<IllegalArgumentException> { LaunchOptions.parse(args) }
        }
    }

    @Test
    fun `legacy export rejects playback and external backends`() {
        for (args in listOf(
            arrayOf("--export-legacy", "--play", "/tmp/movie.mkv"),
            arrayOf("--export-legacy", "--api-base", "http://localhost:6969"),
        )) {
            assertFailsWith<IllegalArgumentException> { LaunchOptions.parse(args) }
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
            arrayOf("--play", "/tmp/movie.mkv", "--verbose", "on"),
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
