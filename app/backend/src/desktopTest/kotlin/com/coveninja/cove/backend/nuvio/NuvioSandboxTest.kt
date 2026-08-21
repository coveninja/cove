package com.coveninja.cove.backend.nuvio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class NuvioSandboxTest {
    @Test
    fun scraperRunsInDisposableWorkerWithVendoredModules() = runTest {
        val sandbox = ProcessNuvioSandbox(timeoutMillis = 15_000)
        val streams = sandbox.run(invocation("""
            const CryptoJS = require('crypto-js');
            module.exports.getStreams = async function(id, type) {
              return [{
                name: type + '-' + id,
                title: CryptoJS.SHA256('cove').toString().slice(0, 8),
                url: 'https://cdn.example/video.mkv',
                size: '1.5 GB'
              }];
            };
        """.trimIndent()))

        assertEquals("movie-42", streams.single().name)
        assertEquals("https://cdn.example/video.mkv", streams.single().url)
        assertEquals(1_610_612_736L, streams.single().toAddonStream()?.sizeBytes)
    }

    @Test
    fun guestCannotReachJvmHostClasses() = runTest {
        val sandbox = ProcessNuvioSandbox(timeoutMillis = 15_000)
        val error = assertFailsWith<IllegalArgumentException> {
            sandbox.run(invocation("""
                module.exports.getStreams = function() {
                  return [{url: Java.type('java.lang.System').getProperty('user.home')}];
                };
            """.trimIndent()))
        }
        assertTrue(error.message.orEmpty().isNotBlank())
    }

    @Test
    fun guestCanReachFetchBridgeAndBrowserCompatibilityGlobals() = runTest {
        val streams = ProcessNuvioSandbox(timeoutMillis = 15_000).run(invocation("""
            module.exports.getStreams = async function() {
              if (typeof __bridge.request !== 'function') throw new Error('fetch bridge is hidden');
              if (global !== globalThis || window !== globalThis) throw new Error('global aliases are missing');
              const query = new URLSearchParams({title: 'Cove test'});
              let timerRan = false;
              await new Promise(resolve => setTimeout(() => { timerRan = true; resolve(); }, 10));
              if (!timerRan) throw new Error('timer did not run');
              return [{name: query.toString(), url: 'https://cdn.example/video.mkv'}];
            };
        """.trimIndent()))

        assertEquals("title=Cove+test", streams.single().name)
    }

    @Test
    fun runawayGuestIsKilledAtTheProcessBoundary() = runTest {
        val started = System.nanoTime()
        assertFailsWith<Exception> {
            ProcessNuvioSandbox(timeoutMillis = 3_000).run(invocation("while (true) {}"))
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMillis < 8_000, "worker was not killed promptly: ${elapsedMillis}ms")
    }

    private fun invocation(code: String) = NuvioInvocation(
        scraperId = "test",
        code = code,
        tmdbId = 42,
        mediaType = "movie",
        title = "Movie",
        year = 2026,
        imdbId = "tt42",
    )
}
