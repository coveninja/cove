package com.coveninja.cove.backend.addons

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class AddonHostResolverTest {
    @Test
    fun `desktop and broker policies keep the public address boundary`() = runBlocking<Unit> {
        DesktopAddonUrlPolicy.validate("https://8.8.8.8/video")
        validateResolvedPublicUrl("https://8.8.8.8/video")
        assertFailsWith<IllegalArgumentException> {
            DesktopAddonUrlPolicy.validate("https://127.0.0.1/video")
        }
        assertFailsWith<IllegalArgumentException> {
            validateResolvedPublicUrl("https://127.0.0.1/video")
        }
    }

    @Test
    fun `URL validation times out while the native lookup is still blocked`() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        AddonHostResolver(timeoutMillis = 1_000, lookup = {
            started.countDown()
            awaitIgnoringInterrupts(release)
            returned.countDown()
            listOf(publicAddress)
        }).use { resolver ->
            try {
                val validation = async(Dispatchers.Default) {
                    assertFailsWith<IllegalArgumentException> {
                        validateResolvedPublicUrl("https://video.test/movie", resolver::resolve)
                    }
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                withTimeout(2_000) { validation.await() }
                assertEquals(1L, returned.count, "The caller must stop waiting before native DNS returns")
            } finally {
                release.countDown()
                assertTrue(returned.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `cancellation releases callers and queued lookups still have a deadline`() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val calls = AtomicInteger()
        AddonHostResolver(parallelism = 1, lookup = {
            calls.incrementAndGet()
            started.countDown()
            awaitIgnoringInterrupts(release)
            returned.countDown()
            listOf(publicAddress)
        }).use { resolver ->
            try {
                val validation = async(Dispatchers.Default) {
                    validateResolvedPublicUrl("https://video.test/movie", resolver::resolve)
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                withTimeout(2_000) { validation.cancelAndJoin() }
                assertTrue(validation.isCancelled)
                assertEquals(1L, returned.count)
                assertNull(withTimeoutOrNull(100) { resolver.resolve("second.test") })
                assertEquals(1, calls.get(), "Queued work must not escape the worker limit")
            } finally {
                release.countDown()
                assertTrue(returned.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `public address validation still rejects mixed private and empty answers`() = runBlocking<Unit> {
        validateResolvedPublicUrl("https://video.test/movie") { listOf(publicAddress) }
        assertFailsWith<IllegalArgumentException> {
            validateResolvedPublicUrl("https://video.test/movie") {
                listOf(publicAddress, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            validateResolvedPublicUrl("https://video.test/movie") { emptyList() }
        }
    }
}

private val publicAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
    while (latch.count > 0) {
        try {
            latch.await()
        } catch (_: InterruptedException) {
            // Model a native resolver that cannot be interrupted by a cancelled request.
        }
    }
}
