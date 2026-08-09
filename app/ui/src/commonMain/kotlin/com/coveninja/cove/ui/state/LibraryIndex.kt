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
}

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
