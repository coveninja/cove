package com.coveninja.cove.desktop.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RestartPolicyTest {

    @Test
    fun `first crash returns 250 ms backoff`() {
        var now = 0L
        val policy = RestartPolicy(maxRestarts = 3, restartWindowMillis = 60_000, clock = { now })

        val delay = policy.recordCrash()

        // Asserting the specific value (not just non-null) distinguishes this
        // from a mutation that changes the base multiplier to 500.
        assertEquals(250L, delay, "first crash in empty window should give 250 ms backoff")
    }

    @Test
    fun `exponential backoff doubles per crash up to three in window`() {
        var now = 0L
        val policy = RestartPolicy(maxRestarts = 3, restartWindowMillis = 60_000, clock = { now })

        val d1 = policy.recordCrash()
        now = 1_000L
        val d2 = policy.recordCrash()
        now = 2_000L
        val d3 = policy.recordCrash()

        assertEquals(250L,   d1, "1st crash: 250 ms")
        assertEquals(500L,   d2, "2nd crash: 500 ms")
        assertEquals(1_000L, d3, "3rd crash: 1000 ms")
    }

    @Test
    fun `fourth crash in window exhausts budget and returns null`() {
        var now = 0L
        val policy = RestartPolicy(maxRestarts = 3, restartWindowMillis = 60_000, clock = { now })

        repeat(3) { policy.recordCrash(); now += 1_000L }
        val result = policy.recordCrash()

        // assertNull pinpoints the budget exhaustion; a type-only assertion
        // would miss a mutation that makes only the 5th crash return null.
        assertNull(result, "4th crash inside the window should exhaust the budget")
    }

    @Test
    fun `crash after oldest entry ages out frees a slot and allows restart`() {
        // Three crashes fill the budget.  Advance the clock so the first crash
        // is more than restartWindowMillis old — it must be evicted, freeing
        // one slot so the fourth crash is allowed.
        var now = 0L
        val policy = RestartPolicy(maxRestarts = 3, restartWindowMillis = 60_000, clock = { now })

        policy.recordCrash()              // t =     0 ms
        now = 1_000L; policy.recordCrash()  // t = 1 000 ms
        now = 2_000L; policy.recordCrash()  // t = 2 000 ms

        // t=0 becomes 61 001 ms old: 61 001 > 60 000 → evicted
        now = 61_001L
        val result = policy.recordCrash()

        assertNotNull(result, "after the oldest crash ages out the budget has a free slot")
    }

    @Test
    fun `backoff is capped at 2000 ms regardless of crash count`() {
        var now = 0L
        // Large maxRestarts so budget is never the limiting factor here.
        val policy = RestartPolicy(maxRestarts = 10, restartWindowMillis = 3_600_000, clock = { now })

        val delays = (1..5).map { policy.recordCrash().also { now += 1_000L } }

        assertEquals(250L,   delays[0], "1st: 250")
        assertEquals(500L,   delays[1], "2nd: 500")
        assertEquals(1_000L, delays[2], "3rd: 1000")
        assertEquals(2_000L, delays[3], "4th: 2000 (cap)")
        assertEquals(2_000L, delays[4], "5th: still 2000 (cap)")
    }

    @Test
    fun `budget stays available when crashes are spaced wider than the window`() {
        // One crash per 61 seconds — each crash ages out before the next
        // arrives, so the policy should never give up.
        var now = 0L
        val policy = RestartPolicy(maxRestarts = 1, restartWindowMillis = 60_000, clock = { now })

        val results = (1..5).map {
            policy.recordCrash().also { now += 61_000L }
        }

        results.forEachIndexed { i, r ->
            assertNotNull(r, "crash ${i + 1} should be allowed; previous has aged out")
        }
    }
}
