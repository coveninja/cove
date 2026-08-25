package com.coveninja.cove.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A background failure must not be able to end the process.
 *
 * This is the regression test for an app that died on launch whenever its first network call
 * failed — a television with a wrong clock, in the case that found it, but a captive portal or
 * a device that starts before its Wi-Fi associates would have done the same.
 */
class BackendScopeTest {

    /** Swaps in a handler that records instead of killing, for the length of one block. */
    private fun <T> withRecordedUncaughtExceptions(block: (List<Throwable>) -> T): T {
        val seen = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> synchronized(seen) { seen += error } }
        return try {
            block(seen)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `a failed background task never reaches the handler that would kill the app`() {
        withRecordedUncaughtExceptions { uncaught ->
            runBlocking {
                val scope = backendScope("test")
                val finished = CompletableDeferred<Unit>()
                scope.launch {
                    try {
                        throw IllegalStateException("network is not there yet")
                    } finally {
                        finished.complete(Unit)
                    }
                }
                withTimeoutOrNull(5_000) { finished.await() }
                // The handler runs after the coroutine body, so give it the same chance the
                // process would have had to die.
                Thread.sleep(200)
                assertEquals(emptyList(), synchronized(uncaught) { uncaught.toList() })
            }
        }
    }

    // Establishes that the test above is measuring something real: the same failure in a plain
    // supervisor scope — which is what every backend scope was — does reach it.
    @Test
    fun `a plain supervisor scope is exactly what did not protect us`() {
        withRecordedUncaughtExceptions { uncaught ->
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val finished = CompletableDeferred<Unit>()
                scope.launch {
                    try {
                        throw IllegalStateException("network is not there yet")
                    } finally {
                        finished.complete(Unit)
                    }
                }
                withTimeoutOrNull(5_000) { finished.await() }
                Thread.sleep(200)
                assertNotNull(
                    synchronized(uncaught) { uncaught.firstOrNull() },
                    "a bare supervisor scope should still reach the uncaught handler",
                )
            }
        }
    }

    // A supervisor keeps siblings alive; the point here is that the *scope* is still usable
    // afterwards, so one failed refresh does not quietly end all future background work.
    @Test
    fun `the scope keeps working after one of its tasks fails`() {
        withRecordedUncaughtExceptions {
            runBlocking {
                val scope = backendScope("test")
                val failed = CompletableDeferred<Unit>()
                scope.launch {
                    try {
                        throw IllegalStateException("boom")
                    } finally {
                        failed.complete(Unit)
                    }
                }
                withTimeoutOrNull(5_000) { failed.await() }

                val ran = CompletableDeferred<String>()
                scope.launch { ran.complete("still alive") }
                assertEquals("still alive", withTimeoutOrNull(5_000) { ran.await() })
            }
        }
    }

    @Test
    fun `every backend scope carries a handler`() {
        assertNotNull(
            backendScope("test").coroutineContext[CoroutineExceptionHandler],
            "backendScope must install a CoroutineExceptionHandler",
        )
    }
}
