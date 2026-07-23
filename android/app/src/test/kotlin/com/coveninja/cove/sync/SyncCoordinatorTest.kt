package com.coveninja.cove.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SyncCoordinator is an object with process-wide state that has no reset hook,
 * so every assertion here is a *delta* on libraryVersion rather than an absolute
 * value, and each test seeds the generation it needs first. That keeps the cases
 * independent of execution order.
 */
class SyncCoordinatorTest {

    private fun version(): Int = SyncCoordinator.libraryVersion.value

    @Test
    fun changedGenerationBumpsLibraryVersion() {
        SyncCoordinator.handleSyncResponse(1_000L)
        val before = version()

        SyncCoordinator.handleSyncResponse(1_001L)

        assertEquals(before + 1, version())
    }

    @Test
    fun unchangedGenerationDoesNotBumpLibraryVersion() {
        SyncCoordinator.handleSyncResponse(2_000L)
        val before = version()

        SyncCoordinator.handleSyncResponse(2_000L)

        assertEquals(before, version())
    }

    @Test
    fun repeatedUnchangedGenerationsStayQuiet() {
        SyncCoordinator.handleSyncResponse(3_000L)
        val before = version()

        repeat(5) { SyncCoordinator.handleSyncResponse(3_000L) }

        assertEquals(before, version())
    }

    @Test
    fun nullGenerationAlwaysBumpsForOlderBackends() {
        // A backend built without the supabase tag omits library_generation;
        // the client can't tell whether anything changed, so it refreshes.
        val before = version()

        SyncCoordinator.handleSyncResponse(null)

        assertEquals(before + 1, version())
    }

    @Test
    fun consecutiveNullGenerationsBumpEveryTime() {
        val before = version()

        SyncCoordinator.handleSyncResponse(null)
        SyncCoordinator.handleSyncResponse(null)

        assertEquals(before + 2, version())
    }

    @Test
    fun generationGoingBackwardsStillCountsAsChanged() {
        // The check is inequality, not ordering — a backend rollback or profile
        // switch can legitimately lower the generation and must refresh the UI.
        SyncCoordinator.handleSyncResponse(5_000L)
        val before = version()

        SyncCoordinator.handleSyncResponse(4_000L)

        assertEquals(before + 1, version())
    }

    @Test
    fun returningToAPreviousGenerationBumpsAgain() {
        SyncCoordinator.handleSyncResponse(6_000L)
        SyncCoordinator.handleSyncResponse(6_001L)
        val before = version()

        SyncCoordinator.handleSyncResponse(6_000L)

        assertEquals(before + 1, version())
    }
}
