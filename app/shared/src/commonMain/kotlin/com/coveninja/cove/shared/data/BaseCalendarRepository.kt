package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.LibraryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    scope: CoroutineScope,
    private val freshness: Duration = DEFAULT_FRESHNESS,
    private val cacheVariant: () -> String = { "" },
) : CalendarRepository {

    /** What was last built or read back, before local watch progress is accounted for. */
    private val snapshot = MutableStateFlow<CalendarState>(CalendarState.Loading)

    /**
     * The snapshot with watch progress applied, so finishing an episode removes it here
     * rather than at the next refresh. Progress changes on every playback tick and the
     * calendar costs a request per saved title, so this is a recount rather than a refetch.
     */
    override val calendar: StateFlow<CalendarState> =
        combine(snapshot, library.watchProgress) { state, progress ->
            if (state is CalendarState.Ready) {
                state.copy(items = applyWatchProgress(state.items, progress))
            } else {
                state
            }
        }.stateIn(scope, SharingStarted.Eagerly, CalendarState.Loading)

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
        val signature = entries.signature(cacheVariant())

        loadCacheOnce(signature)

        if (!force && isFresh(signature)) return@withLock

        if (entries.isEmpty()) {
            publish(emptyList(), signature)
            return@withLock
        }

        // Keep whatever is already on screen while the refetch runs; a spinner that
        // replaces a perfectly good schedule is a downgrade.
        (snapshot.value as? CalendarState.Ready)?.let { ready ->
            snapshot.value = ready.copy(refreshing = true)
        }

        val items = runCatching { fetchCalendar() }.getOrElse { error ->
            // Cached items beat an error screen: the schedule was true this morning and
            // is still more use than a message about a network that came back down.
            snapshot.value = (snapshot.value as? CalendarState.Ready)?.copy(refreshing = false)
                ?: CalendarState.Failed(error.message ?: "Could not load the release calendar.")
            return@withLock
        }

        publish(items, signature)
    }

    private suspend fun loadCacheOnce(expectedSignature: String) {
        if (cacheReadAttempted) return
        cacheReadAttempted = true
        val cached = runCatching { readCache() }.getOrNull() ?: return
        // Old builds did not partition this cache by locale. Do not flash a valid but
        // wrong-language snapshot while the current presentation is rebuilt.
        if (cached.signature != expectedSignature) return
        snapshot.value = CalendarState.Ready(cached.items, cached.refreshedAt)
        lastSignature = cached.signature
        lastFetchedAt = runCatching { Instant.parse(cached.refreshedAt) }.getOrNull()
    }

    private suspend fun publish(items: List<CalendarItem>, signature: String) {
        val now = Clock.System.now()
        val refreshedAt = now.toString()

        lastFetchedAt = now
        lastSignature = signature
        snapshot.value = CalendarState.Ready(items, refreshedAt)
        runCatching { writeCache(CachedCalendar(items, refreshedAt, signature)) }
    }

    /**
     * Fresh means both recent enough *and* built from the same set of titles: saving a new
     * show has to reach the calendar without waiting out the window.
     */
    private fun isFresh(signature: String): Boolean {
        if (snapshot.value !is CalendarState.Ready) return false
        if (lastSignature != signature) return false
        val fetchedAt = lastFetchedAt ?: return false
        return Clock.System.now() - fetchedAt < freshness
    }

    private fun List<LibraryEntry>.signature(variant: String): String =
        (
            map { "${it.mediaType.wireName}:${it.tmdbId}:${it.status.wireName}" } +
                "variant:$variant" + "schema:$CACHE_SCHEMA"
        ).sorted().joinToString(",")

    protected companion object {
        val DEFAULT_FRESHNESS: Duration = 12.hours

        /**
         * Bumped whenever a cached snapshot stops being usable as it stands. Version 2
         * added [com.coveninja.cove.shared.model.CalendarItem.airedSeasons]; entries
         * without it cannot be re-counted against watch progress, so the rows written by
         * older builds are discarded rather than shown going stale.
         */
        const val CACHE_SCHEMA = 2
    }
}
