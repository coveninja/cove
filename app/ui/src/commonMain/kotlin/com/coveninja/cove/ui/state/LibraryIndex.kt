package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.model.toUiMedia

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
fun rememberLibraryIndex(): LibraryIndex {
    val graph = LocalAppGraph.current
    val libraryState by graph.library.entries.collectAsState()
    return remember(libraryState) {
        val entries = (libraryState as? LibraryState.Ready)?.entries.orEmpty()
        LibraryIndex(entries)
    }
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
