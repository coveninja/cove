package com.coveninja.cove.desktop.backend

import java.util.ArrayDeque

/**
 * Rolling-window crash budget.  Records individual crash timestamps so that
 * as old crashes age past [restartWindowMillis] they free their slot again —
 * this is the key distinction from a simpler "reset the count when the window
 * expires" approach, which would permanently accumulate toward the cap without
 * ever freeing budget from individual events.
 *
 * @param clock Injectable time source so tests never need to sleep.
 */
class RestartPolicy(
    val maxRestarts: Int = 3,
    val restartWindowMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val crashTimes = ArrayDeque<Long>()

    /**
     * Records a crash and returns the recommended delay before the next
     * restart attempt, or `null` if the crash budget is exhausted.
     *
     * The delay uses exponential backoff: 250 ms for the first crash in the
     * current window, doubling per crash up to a 2000 ms cap.
     */
    fun recordCrash(): Long? {
        val now = clock()
        // Remove timestamps older than the window boundary so their budget
        // slots become available for future crashes.
        while (crashTimes.isNotEmpty() && now - crashTimes.peekFirst() > restartWindowMillis) {
            crashTimes.pollFirst()
        }
        crashTimes.addLast(now)

        val countInWindow = crashTimes.size
        if (countInWindow > maxRestarts) return null

        return minOf(250L shl (countInWindow - 1), 2_000L)
    }
}
