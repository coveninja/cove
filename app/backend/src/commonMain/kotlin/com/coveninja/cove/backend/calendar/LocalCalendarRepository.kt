package com.coveninja.cove.backend.calendar

import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.shared.data.BaseCalendarRepository
import com.coveninja.cove.shared.data.CachedCalendar
import com.coveninja.cove.shared.data.LibraryRepository
import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.network.CoveJson
import kotlinx.serialization.builtins.ListSerializer

/**
 * Serves [CalendarService] to the UI, persisting each snapshot per profile.
 *
 * The service reaches TMDB once per saved title, so a cold start would otherwise spend
 * that on every launch. Keeping the last result in SQLite means the calendar paints
 * immediately and still works with no network.
 */
class LocalCalendarRepository(
    private val service: CalendarService,
    private val database: CoveDatabase,
    private val session: ActiveProfileSession,
    library: LibraryRepository,
    localeProvider: () -> String = { "en" },
) : BaseCalendarRepository(library, cacheVariant = localeProvider) {

    override suspend fun fetchCalendar(): List<CalendarItem> = service.calendar()

    override suspend fun readCache(): CachedCalendar? {
        val row = database.coveQueries
            .selectCalendarCache(session.profileId.value)
            .executeAsOneOrNull()
            ?: return null
        // A row that will not parse is worthless, not fatal: drop it and let the refresh
        // that follows rebuild from TMDB.
        val items = runCatching {
            CoveJson.decodeFromString(ListSerializer(CalendarItem.serializer()), row.items)
        }.getOrNull() ?: return null
        return CachedCalendar(items, row.refreshed_at, row.signature)
    }

    override suspend fun writeCache(cache: CachedCalendar) {
        database.coveQueries.upsertCalendarCache(
            profile_id = session.profileId.value,
            items = CoveJson.encodeToString(
                ListSerializer(CalendarItem.serializer()),
                cache.items,
            ),
            refreshed_at = cache.refreshedAt,
            signature = cache.signature,
        )
    }
}
