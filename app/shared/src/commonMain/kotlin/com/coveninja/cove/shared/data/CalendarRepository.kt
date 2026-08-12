package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.CalendarItem
import kotlinx.coroutines.flow.StateFlow

sealed interface CalendarState {
    data object Loading : CalendarState

    /** [refreshedAt] is an ISO timestamp, or blank when nothing has been fetched yet. */
    data class Ready(
        val items: List<CalendarItem>,
        val refreshedAt: String = "",
        /** True while a refresh runs over already-displayable items. */
        val refreshing: Boolean = false,
    ) : CalendarState

    data class Failed(val message: String) : CalendarState
}

/**
 * The release schedule for the titles in the viewer's library.
 *
 * Building it costs one TMDB request per saved title, which is why the contract is a
 * cached [StateFlow] with an explicit [refresh] rather than a plain suspend query.
 */
interface CalendarRepository {
    val calendar: StateFlow<CalendarState>

    /**
     * @param force refetch even when the cached snapshot is still inside its freshness
     *   window. This is the manual refresh button; automatic loads pass false.
     */
    suspend fun refresh(force: Boolean = false)
}

/** A calendar snapshot as persisted; [signature] identifies the library it was built from. */
data class CachedCalendar(
    val items: List<CalendarItem>,
    val refreshedAt: String,
    val signature: String,
)
