package com.coveninja.cove.backend.addons

import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull

/** Bounds native DNS work even when the system resolver ignores interruption. */
internal class AddonHostResolver(
    parallelism: Int = 8,
    private val timeoutMillis: Long = 5_000L,
    private val lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : AutoCloseable {
    private val workers = ThreadPoolExecutor(
        parallelism,
        parallelism,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(64),
        { task -> Thread(task, "cove-addon-dns").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    suspend fun resolve(host: String): List<InetAddress> =
        withTimeoutOrNull(timeoutMillis) {
            val pending = try {
                CompletableFuture.supplyAsync({ lookup(host) }, workers)
            } catch (_: RejectedExecutionException) {
                throw IllegalArgumentException("addon host resolver is busy")
            }
            // Cancelling await releases the caller without waiting for native DNS to finish.
            // The worker and queue ceilings keep abandoned lookups bounded.
            pending.await()
        } ?: throw IllegalArgumentException("addon host lookup timed out")

    override fun close() {
        workers.shutdownNow()
    }
}
