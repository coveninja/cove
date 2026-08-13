package com.coveninja.cove

import kotlin.test.assertEquals
import org.junit.Test

class CoveImageBenchmarkStateTest {
    @Test
    fun tracksRequestsInCurrentGeneration() {
        CoveImageBenchmarkState.reset()
        val first = CoveImageBenchmarkState.onRequestStarted()
        val second = CoveImageBenchmarkState.onRequestStarted()

        assertEquals("2:0", CoveImageBenchmarkState.snapshot())
        CoveImageBenchmarkState.onRequestFinished(first)
        assertEquals("1:1", CoveImageBenchmarkState.snapshot())
        CoveImageBenchmarkState.onRequestFinished(second)
        assertEquals("0:2", CoveImageBenchmarkState.snapshot())
    }

    @Test
    fun ignoresCompletionFromPreviousGeneration() {
        CoveImageBenchmarkState.reset()
        val stale = CoveImageBenchmarkState.onRequestStarted()
        CoveImageBenchmarkState.reset()

        CoveImageBenchmarkState.onRequestFinished(stale)

        assertEquals("0:0", CoveImageBenchmarkState.snapshot())
    }
}
