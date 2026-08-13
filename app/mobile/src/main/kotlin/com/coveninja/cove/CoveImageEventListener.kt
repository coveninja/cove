package com.coveninja.cove

import android.os.Build
import android.os.Trace
import coil3.EventListener
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import java.util.concurrent.atomic.AtomicInteger

/** Coil phase slices for Perfetto; no image URL or user data is placed in the trace. */
internal class CoveImageEventListener : EventListener() {
    private val requestCookie = nextCookie()
    private var fetchCookie = 0
    private var decodeCookie = 0
    private var benchmarkGeneration = 0

    override fun onStart(request: ImageRequest) {
        benchmarkGeneration = CoveImageBenchmarkState.onRequestStarted()
        beginAsync("Cove image request", requestCookie)
    }

    override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
        fetchCookie = nextCookie()
        beginAsync("Cove image fetch", fetchCookie)
    }

    override fun fetchEnd(
        request: ImageRequest,
        fetcher: Fetcher,
        options: Options,
        result: FetchResult?,
    ) {
        endFetch()
    }

    override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
        decodeCookie = nextCookie()
        beginAsync("Cove image decode", decodeCookie)
    }

    override fun decodeEnd(
        request: ImageRequest,
        decoder: Decoder,
        options: Options,
        result: DecodeResult?,
    ) {
        endDecode()
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        finishRequest("Cove image ${result.dataSource.name.lowercase()}")
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        finishRequest("Cove image error")
    }

    override fun onCancel(request: ImageRequest) {
        finishRequest("Cove image cancelled")
    }

    private fun finishRequest(outcome: String) {
        endFetch()
        endDecode()
        endAsync("Cove image request", requestCookie)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.beginSection(outcome)
            Trace.endSection()
        }
        CoveImageBenchmarkState.onRequestFinished(benchmarkGeneration)
        benchmarkGeneration = 0
    }

    private fun endFetch() {
        if (fetchCookie == 0) return
        endAsync("Cove image fetch", fetchCookie)
        fetchCookie = 0
    }

    private fun endDecode() {
        if (decodeCookie == 0) return
        endAsync("Cove image decode", decodeCookie)
        decodeCookie = 0
    }

    private companion object {
        private val nextCookie = AtomicInteger(1)

        fun nextCookie(): Int = nextCookie.getAndUpdate { current ->
            if (current == Int.MAX_VALUE) 1 else current + 1
        }

        fun beginAsync(name: String, cookie: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.beginAsyncSection(name, cookie)
            }
        }

        fun endAsync(name: String, cookie: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.endAsyncSection(name, cookie)
            }
        }
    }
}

/** Observable completion instead of a fixed sleep in the physical cold-image benchmark. */
internal object CoveImageBenchmarkState {
    private val generation = AtomicInteger(0)
    private val active = AtomicInteger(0)
    private val completed = AtomicInteger(0)

    fun reset() {
        active.set(0)
        completed.set(0)
        generation.updateAndGet { current -> if (current == Int.MAX_VALUE) 1 else current + 1 }
    }

    fun onRequestStarted(): Int {
        val current = generation.get()
        if (current != 0) active.incrementAndGet()
        return current
    }

    fun onRequestFinished(requestGeneration: Int) {
        if (requestGeneration == 0 || requestGeneration != generation.get()) return
        active.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        completed.incrementAndGet()
    }

    fun snapshot(): String = "${active.get()}:${completed.get()}"
}
