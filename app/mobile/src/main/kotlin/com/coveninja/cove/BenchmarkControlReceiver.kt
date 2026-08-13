package com.coveninja.cove

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Installed only in benchmarkRelease; gives Macrobenchmark an observable cold-cache boundary. */
class BenchmarkControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.BENCHMARK_FIXTURE) return
        when (intent.action) {
            ACTION_CLEAR_IMAGE_CACHE -> {
                runBlocking(Dispatchers.IO) {
                    val loader = SingletonImageLoader.get(context)
                    loader.memoryCache?.clear()
                    loader.diskCache?.clear()
                }
                // Requests that were in flight before the clear carry the previous generation and
                // cannot make the new benchmark appear complete prematurely.
                CoveImageBenchmarkState.reset()
                resultData = "cleared"
            }

            ACTION_IMAGE_STATUS -> resultData = CoveImageBenchmarkState.snapshot()
        }
    }

    companion object {
        const val ACTION_CLEAR_IMAGE_CACHE = "com.coveninja.cove.benchmark.CLEAR_IMAGE_CACHE"
        const val ACTION_IMAGE_STATUS = "com.coveninja.cove.benchmark.IMAGE_STATUS"
    }
}
