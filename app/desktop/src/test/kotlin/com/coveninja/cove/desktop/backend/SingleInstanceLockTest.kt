package com.coveninja.cove.desktop.backend

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceLockTest {

    @Test
    fun `first lock on a fresh file is acquired`() {
        val dir = Files.createTempDirectory("cove-lock-test")
        val lock = SingleInstanceLock(dir, "first.lock")
        try {
            assertTrue(lock.acquired, "first lock on a fresh file must be acquired")
        } finally {
            lock.close()
        }
    }

    @Test
    fun `second lock on the same file while first is held is not acquired`() {
        val dir = Files.createTempDirectory("cove-lock-test")
        val lock1 = SingleInstanceLock(dir, "dup.lock")
        try {
            assertTrue(lock1.acquired, "first lock must be acquired")

            val lock2 = SingleInstanceLock(dir, "dup.lock")
            try {
                // Asserting false specifically (not just "not true") distinguishes
                // this from a mutation that always returns acquired=true.
                assertFalse(lock2.acquired, "second lock on a held file must not be acquired")
            } finally {
                lock2.close()
            }
        } finally {
            lock1.close()
        }
    }

    @Test
    fun `releasing first lock allows a subsequent lock to succeed`() {
        val dir = Files.createTempDirectory("cove-lock-test")
        val lock1 = SingleInstanceLock(dir, "reuse.lock")
        assertTrue(lock1.acquired, "initial lock must be acquired")
        lock1.close()

        val lock2 = SingleInstanceLock(dir, "reuse.lock")
        try {
            assertTrue(lock2.acquired, "lock after release must be acquired")
        } finally {
            lock2.close()
        }
    }

    @Test
    fun `lock file is not deleted after close`() {
        val dir = Files.createTempDirectory("cove-lock-test")
        val lock = SingleInstanceLock(dir, "persist.lock")
        lock.close()

        // Asserting the specific path so a mutation that deletes the file
        // would fail this check, not just a generic "something exists" test.
        assertTrue(
            dir.resolve("persist.lock").toFile().exists(),
            "lock file must remain on disk after close to avoid the open-then-lock race"
        )
    }
}
