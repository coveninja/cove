package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.components.media.drag.MediaDragPayload
import com.coveninja.cove.ui.model.Media

@Stable
class DragSession {
    var draggedPayload by mutableStateOf<MediaDragPayload?>(null)
        private set
    var draggedSource by mutableStateOf<Media?>(null)
        private set
    var dragPositionInRoot by mutableStateOf<Offset?>(null)
        private set
    var detailsDragActive by mutableStateOf(false)
        private set

    // Populated by NavBar's onListCategoryBoundsChanged callbacks; used to derive
    // hoveredCategory without a hit-test callback round-trip.
    val categoryBounds = mutableStateMapOf<MyListCategory, Rect>()

    val hoveredCategory: MyListCategory? get() = dragPositionInRoot?.let { position ->
        MyListCategory.entries.firstOrNull { category ->
            categoryBounds[category]?.contains(position) == true
        }
    }

    fun start(payload: MediaDragPayload, source: Media?, position: Offset, fromDetails: Boolean) {
        detailsDragActive = fromDetails
        draggedPayload = payload
        draggedSource = source
        dragPositionInRoot = position
    }

    fun move(position: Offset) {
        dragPositionInRoot = position
    }

    fun cancel() {
        detailsDragActive = false
        draggedPayload = null
        draggedSource = null
        dragPositionInRoot = null
        categoryBounds.clear()
    }

    // finish takes the commit action as a lambda so DragSession stays free of
    // MediaActions — the caller decides what happens on a successful drop.
    fun finish(commitCategory: (Media, MyListCategory) -> Unit) {
        val source = draggedSource
        val category = hoveredCategory
        if (source != null && category != null) commitCategory(source, category)
        cancel()
    }
}

@Composable
fun rememberDragSession(): DragSession = remember { DragSession() }
