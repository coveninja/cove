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
        arrayOf("--tv") to
            LaunchOptions(tv = true),
        // The combination the TV shell is actually developed with.
        arrayOf("--tv", "--backend-mode", "kotlin") to
            LaunchOptions(tv = true, backendMode = BackendMode.Kotlin),
        arrayOf("--onboarding") to
            LaunchOptions(onboarding = true),
        // What `make onboarding-tv` runs: the first-run flow, in the television shell,
        // against fixtures so no TMDB key is needed to look at it.
        arrayOf("--backend-mode", "fixtures", "--tv", "--onboarding") to
            LaunchOptions(
                backendMode = BackendMode.Fixtures,
                tv = true,
                onboarding = true,
            ),
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

    // --play is a bare video window with no navigation shell and --export-legacy shows no
    // window at all, so there is no UI for --tv to pick in either case. Accepting the
    // combination would silently ignore one of the two flags.
    @Test
    fun `the tv shell rejects modes that have no navigation to show`() {
        for (args in listOf(
            arrayOf("--tv", "--play", "/tmp/movie.mkv"),
            arrayOf("--tv", "--export-legacy"),
        )) {
            assertFailsWith<IllegalArgumentException>("expected rejection of ${args.toList()}") {
                LaunchOptions.parse(args)
            }
        }
    }

    // Same reasoning as --tv: --play opens a bare video window with no navigation shell and
    // --export-legacy opens no window at all, so neither has a UI for the onboarding flow to
    // render in. Accepting the combination would silently ignore one of the two flags.
    @Test
    fun `the onboarding harness rejects modes that have no UI to show it in`() {
        for (args in listOf(
            arrayOf("--onboarding", "--play", "/tmp/movie.mkv"),
            arrayOf("--onboarding", "--export-legacy"),
        )) {
            assertFailsWith<IllegalArgumentException>("expected rejection of ${args.toList()}") {
                LaunchOptions.parse(args)
            }
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
