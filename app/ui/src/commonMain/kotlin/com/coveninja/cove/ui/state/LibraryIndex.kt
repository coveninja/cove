package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.model.uiMediaId

@Stable
class LibraryIndex(val entries: List<LibraryEntry>) {
    val byUiId: Map<String, LibraryEntry> = entries.associateBy { it.toUiMedia().id }

    // Map<mediaUiId, MyListCategory> — used by the pill filter in MyListPage and
    // by the card slot for toggling and drag-drop assignment.
    val categories: Map<String, MyListCategory> = byUiId.mapValues { (_, entry) ->
        entry.status.toUiCategory()
    }

    fun entryOf(mediaId: String): LibraryEntry? = byUiId[mediaId]

    fun categoryOf(mediaId: String): MyListCategory? = categories[mediaId]

    fun hasUnwatchedAired(mediaId: String): Boolean = byUiId[mediaId]?.hasUnwatchedAired() == true
}

/**
 * A calendar item names a title by TMDB id; the app navigates by [com.coveninja.cove.ui.model.Media].
 * The library is the bridge, and it always has the entry — nothing reaches the calendar that
 * is not saved.
 */
fun LibraryIndex.mediaFor(item: CalendarItem, catalog: MediaCatalog? = null): Media? =
    entryOf(uiMediaId(item.tmdbId, item.type))?.let { entry ->
        catalog?.enrich(entry) ?: entry.toUiMedia()
    }

/**
 * Whether this title has never been played at all.
 *
 * Both halves matter. [progress] covers anything that reached the player; the watched
 * counters cover episodes ticked off by hand, which write a completed progress row but are
 * still "watched" as far as the viewer is concerned.
 *
 * Exists because a library *status* answers a different question. `Watching` is an intention
 * the viewer sets once and which nothing ever clears, so it stays set long after the last
 * episode is finished — anything offering a title to play has to ask this instead, or it ends
 * up advertising something already watched.
 */
fun LibraryEntry.neverPlayed(progress: WatchProgress?): Boolean =
    progress == null && lastWatchedSeason == null && lastWatchedEpisode == null

/**
 * Whether episodes have aired since the viewer last watched one.
 *
 * Compared as (season, episode) pairs so a new season counts even though its episode
 * number restarts at 1. A title with nothing watched yet is not "new": it is unstarted,
 * which the card already says by having no progress.
 */
fun LibraryEntry.hasUnwatchedAired(): Boolean {
    val airedSeason = lastAiredSeason ?: return false
    val airedEpisode = lastAiredEpisode ?: return false
    val watchedSeason = lastWatchedSeason ?: return false
    val watchedEpisode = lastWatchedEpisode ?: return false
    return (airedSeason to airedEpisode) > (watchedSeason to watchedEpisode)
}

private operator fun Pair<Int, Int>.compareTo(other: Pair<Int, Int>): Int =
    compareValuesBy(this, other, { it.first }, { it.second })

@Composable
fun rememberLibraryIndex(libraryState: LibraryState): LibraryIndex =
    remember(libraryState) {
        val entries = (libraryState as? LibraryState.Ready)?.entries.orEmpty()
        LibraryIndex(entries)
    }

// Internal so both CoveApp and pages can use these without exposing them as API.
internal fun LibraryStatus.toUiCategory(): MyListCategory = when (this) {
    LibraryStatus.Watching -> MyListCategory.Watching
    LibraryStatus.WatchLater -> MyListCategory.WatchLater
    LibraryStatus.Finished -> MyListCategory.Finished
    LibraryStatus.Dropped -> MyListCategory.Dropped
}

internal fun MyListCategory.toLibraryStatus(): LibraryStatus? = when (this) {
    MyListCategory.Watching -> LibraryStatus.Watching
    MyListCategory.WatchLater -> LibraryStatus.WatchLater
    MyListCategory.Finished -> LibraryStatus.Finished
    MyListCategory.Dropped -> LibraryStatus.Dropped
    MyListCategory.NotInterested -> null
}
