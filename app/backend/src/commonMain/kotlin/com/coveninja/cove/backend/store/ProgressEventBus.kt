package com.coveninja.cove.backend.store

import com.coveninja.cove.shared.model.WatchProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Fan-out for side effects caused by a committed progress row.
 *
 * Library persistence is the source of truth on embedded desktop and Android,
 * so activity, Trakt and prefetch subscribe here instead of relying on an HTTP
 * route that in-process Compose playback never calls.
 */
class ProgressEventBus(private val scope: CoroutineScope) {
    private val events = MutableSharedFlow<WatchProgress>(extraBufferCapacity = 32)

    internal suspend fun publish(progress: WatchProgress) = events.emit(progress)

    fun subscribe(listener: suspend (WatchProgress) -> Unit): Job = scope.launch(
        start = CoroutineStart.UNDISPATCHED,
    ) {
        events.collect { progress ->
            try {
                listener(progress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Side effects are isolated from persistence and from each other.
                // A later committed progress event must still reach this listener.
            }
        }
    }
}
