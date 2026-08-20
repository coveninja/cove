package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.network.CoveApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reads the calendar the HTTP backend already assembles at `/api/library/calendar`.
 *
 * No persistence: the base class's in-memory snapshot is the whole story here, so a
 * restart refetches. The in-process backend has a database to lean on and does better.
 */
class LiveCalendarRepository(
    private val api: CoveApi,
    library: LibraryRepository,
    scope: CoroutineScope,
) : BaseCalendarRepository(library, scope) {

    override suspend fun fetchCalendar(): List<CalendarItem> = api.libraryCalendar()
}

/** Used where no content backend exists to ask, such as `--play` mode. */
object UnavailableCalendarRepository : CalendarRepository {
    private const val REASON = "the calendar is unavailable: no content backend is running"

    override val calendar: StateFlow<CalendarState> =
        MutableStateFlow(CalendarState.Failed(REASON))

    override suspend fun refresh(force: Boolean) = Unit
}
