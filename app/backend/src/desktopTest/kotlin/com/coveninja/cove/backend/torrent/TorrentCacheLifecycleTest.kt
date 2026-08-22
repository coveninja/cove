package com.coveninja.cove.backend.torrent

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class TorrentCacheLifecycleTest {
    private val hash = "a".repeat(40)

    @Test
    fun `a use lease makes deletion fail closed regardless of hash case`() = runTest {
        val lifecycle = TorrentCacheLifecycle()
        var deletionRan = false

        lifecycle.withUse(hash.uppercase()) {
            assertEquals(setOf(hash), lifecycle.activeHashes())
            assertFalse(lifecycle.tryDelete(hash) {
                deletionRan = true
                true
            })
            assertFalse(deletionRan)
        }

        assertTrue(lifecycle.activeHashes().isEmpty())
        assertTrue(lifecycle.tryDelete(hash) {
            deletionRan = true
            true
        })
        assertTrue(deletionRan)
    }

    @Test
    fun `a new use waits until deletion has completely finished`() = runTest {
        val lifecycle = TorrentCacheLifecycle()
        val deletionStarted = CountDownLatch(1)
        val allowDeletionToFinish = CountDownLatch(1)
        val useEntered = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(mutableListOf<String>())

        val deletion = async(Dispatchers.Default) {
            lifecycle.tryDelete(hash) {
                deletionStarted.countDown()
                check(allowDeletionToFinish.await(5, TimeUnit.SECONDS))
                order += "delete finished"
                true
            }
        }
        assertTrue(deletionStarted.await(5, TimeUnit.SECONDS))

        val use = async(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.withUse(hash) {
                order += "use entered"
                useEntered.complete(Unit)
            }
        }
        assertFalse(useEntered.isCompleted)

        allowDeletionToFinish.countDown()
        assertTrue(deletion.await())
        use.await()
        assertEquals(listOf("delete finished", "use entered"), order)
    }

    @Test
    fun `a failed use always releases its lease`() = runTest {
        val lifecycle = TorrentCacheLifecycle()

        val failure = runCatching {
            lifecycle.withUse(hash) { error("failed stream") }
        }.exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertTrue(lifecycle.activeHashes().isEmpty())
        assertTrue(lifecycle.tryDelete(hash) { true })
    }

    @Test
    fun `a failed deletion always lets later users proceed`() = runTest {
        val lifecycle = TorrentCacheLifecycle()

        val failure = runCatching {
            lifecycle.tryDelete(hash) { error("failed deletion") }
        }.exceptionOrNull()
        var entered = false
        lifecycle.withUse(hash) { entered = true }

        assertIs<IllegalStateException>(failure)
        assertTrue(entered)
    }
}
