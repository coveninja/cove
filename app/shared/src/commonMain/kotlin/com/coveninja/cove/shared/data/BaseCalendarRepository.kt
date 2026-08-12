package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.LibraryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Shared caching and state handling for every [CalendarRepository].
 *
 * Building a calendar means one metadata request per saved title, so the result is held
 * until it goes stale rather than rebuilt whenever the view appears. Subclasses supply the
 * build itself, and persistence where they have somewhere to put it.
 */
abstract class BaseCalendarRepository(
    private val library: LibraryRepository,
    private val freshness: Duration = DEFAULT_FRESHNESS,
) : CalendarRepository {

    private val _calendar = MutableStateFlow<CalendarState>(CalendarState.Loading)
    override val calendar: StateFlow<CalendarState> = _calendar.asStateFlow()

    private val refreshLock = Mutex()
    private var cacheReadAttempted = false
    private var lastFetchedAt: Instant? = null
    private var lastSignature: String? = null

    /** Builds the whole calendar. Throwing surfaces as [CalendarState.Failed]. */
    protected abstract suspend fun fetchCalendar(): List<CalendarItem>

    /** Null when this implementation has no persistence, or nothing is cached yet. */
    protected open suspend fun readCache(): CachedCalendar? = null

    protected open suspend fun writeCache(cache: CachedCalendar) = Unit

    override suspend fun refresh(force: Boolean): Unit = refreshLock.withLock {
        val entries = (library.entries.value as? LibraryState.Ready)?.entries.orEmpty()
        val signature = entries.signature()

        loadCacheOnce()

        if (!force && isFresh(signature)) return@withLock

        if (entries.isEmpty()) {
            publish(emptyList(), signature)
            return@withLock
        }

        // Keep whatever is already on screen while the refetch runs; a spinner that
        // replaces a perfectly good schedule is a downgrade.
        (_calendar.value as? CalendarState.Ready)?.let { ready ->
            _calendar.value = ready.copy(refreshing = true)
        }

        val items = runCatching { fetchCalendar() }.getOrElse { error ->
            // Cached items beat an error screen: the schedule was true this morning and
            // is still more use than a message about a network that came back down.
            _calendar.value = (_calendar.value as? CalendarState.Ready)?.copy(refreshing = false)
                ?: CalendarState.Failed(error.message ?: "Could not load the release calendar.")
            return@withLock
        }

        publish(items, signature)
    }

    private suspend fun loadCacheOnce() {
        if (cacheReadAttempted) return
        cacheReadAttempted = true
        val cached = runCatching { readCache() }.getOrNull() ?: return
        _calendar.value = CalendarState.Ready(cached.items, cached.refreshedAt)
        lastSignature = cached.signature
        lastFetchedAt = runCatching { Instant.parse(cached.refreshedAt) }.getOrNull()
    }

    private suspend fun publish(items: List<CalendarItem>, signature: String) {
        val now = Clock.System.now()
        val refreshedAt = now.toString()

        lastFetchedAt = now
        lastSignature = signature
        _calendar.value = CalendarState.Ready(items, refreshedAt)
        runCatching { writeCache(CachedCalendar(items, refreshedAt, signature)) }
    }

    /**
     * Fresh means both recent enough *and* built from the same set of titles: saving a new
     * show has to reach the calendar without waiting out the window.
     */
    private fun isFresh(signature: String): Boolean {
        if (_calendar.value !is CalendarState.Ready) return false
        if (lastSignature != signature) return false
        val fetchedAt = lastFetchedAt ?: return false
        return Clock.System.now() - fetchedAt < freshness
    }

    private fun List<LibraryEntry>.signature(): String =
        map { "${it.mediaType.wireName}:${it.tmdbId}:${it.status.wireName}" }
            .sorted()
            .joinToString(",")

    protected companion object {
        val DEFAULT_FRESHNESS: Duration = 12.hours
    }
}
