package com.coveninja.cove.backend.nuvio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The fan-out a sandbox gets when it has no cheaper way to run a batch: one [NuvioSandbox.run]
 * per scraper, bounded concurrency, a per-scraper deadline, and each answer reported the moment
 * it lands. `ProcessNuvioSandbox` overrides `runBatch` to do all of this inside one child JVM
 * instead, which is worth roughly an order of magnitude there; Android's QuickJS sandbox has no
 * process to start per scraper and uses this.
 */
internal suspend fun NuvioSandbox.runInvocationsIndividually(
    batch: NuvioBatch,
    onOutcome: (NuvioBatchOutcome) -> Unit,
): Unit = coroutineScope {
    val slots = Semaphore(batch.concurrency.coerceAtLeast(1))
    batch.invocations.map { invocation ->
        async {
            slots.withPermit {
                val startedAt = System.nanoTime()
                val outcome = try {
                    val streams = withTimeoutOrNull(batch.perScraperTimeoutMillis) { run(invocation) }
                    if (streams == null) {
                        NuvioBatchOutcome(
                            invocation.scraperId,
                            error = "timed out after ${batch.perScraperTimeoutMillis} ms",
                        )
                    } else {
                        NuvioBatchOutcome(invocation.scraperId, streams)
                    }
                } catch (error: CancellationException) {
                    // A sandbox may own a shorter timeout than the batch allowed it. Its
                    // TimeoutCancellationException is a failure of that scraper only. Preserve
                    // cancellation from the batch itself, where this context is no longer active.
                    currentCoroutineContext().ensureActive()
                    NuvioBatchOutcome(invocation.scraperId, error = error.describe())
                } catch (error: Throwable) {
                    NuvioBatchOutcome(invocation.scraperId, error = error.describe())
                }
                onOutcome(outcome.copy(elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000))
            }
        }
    }.awaitAll()
}

private fun Throwable.describe(): String =
    message?.takeIf(String::isNotBlank) ?: this::class.java.simpleName
