package com.coveninja.cove.backend.store

import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.WatchProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressEventBusTest {
    @Test
    fun committedProgressFansOutToEverySubscriber() = runTest {
        val bus = ProgressEventBus(backgroundScope)
        val first = CompletableDeferred<WatchProgress>()
        val second = CompletableDeferred<WatchProgress>()
        bus.subscribe { first.complete(it) }
        bus.subscribe { second.complete(it) }
        runCurrent()

        val progress = WatchProgress(
            id = "progress-1",
            tmdbId = 42,
            mediaType = MediaType.Movie,
            positionSeconds = 600.0,
            durationSeconds = 1_200.0,
            watchedAt = "2026-08-12T12:00:00Z",
        )
        bus.publish(progress)

        assertEquals(progress, first.await())
        assertEquals(progress, second.await())
    }

    @Test
    fun oneFailingSideEffectDoesNotStopLaterEvents() = runTest {
        val bus = ProgressEventBus(backgroundScope)
        val received = CompletableDeferred<WatchProgress>()
        bus.subscribe { error("simulated side-effect failure") }
        bus.subscribe { received.complete(it) }
        runCurrent()

        val progress = WatchProgress(
            id = "progress-2",
            tmdbId = 7,
            mediaType = MediaType.Tv,
            season = 1,
            episode = 2,
        )
        bus.publish(progress)

        assertEquals(progress, received.await())
    }
}
