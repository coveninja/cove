package com.coveninja.cove.ui.tv.focus

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * Scrolls so that the item at [index] clears [marginPx] at both ends of the viewport.
 *
 * Compose already scrolls a newly focused element into view, but "into view" means flush
 * against the edge — which is how the previous TV shell kept losing the top of its hero and the
 * heading above whichever row had just been entered. The margin is the whole point: on a
 * television the edge of the panel is not a reliable place to put anything.
 *
 * Only moves when the item would actually breach the margin, so walking along a row that is
 * already comfortably visible does not drag the page around under the viewer.
 */
suspend fun LazyListState.tvKeepInView(index: Int, marginPx: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        ?: run {
            // Off-screen entirely: place it directly, since there is no current position to
            // measure a correction from.
            animateScrollToItem(index, -marginPx)
            return
        }
    val start = info.viewportStartOffset + marginPx
    val end = info.viewportEndOffset - marginPx
    val delta = when {
        item.offset < start -> item.offset - start
        item.offset + item.size > end -> item.offset + item.size - end
        else -> 0
    }
    if (delta != 0) animateScrollBy(delta.toFloat())
}

/**
 * Keeps the focused section of a vertical page clear of the screen edges.
 *
 * The page owns [focusedIndex] — each section reports focus as it gains it — rather than this
 * observing focus itself, because a section is a whole row of cards and what has to stay in
 * view is the row, not the one card inside it that happens to hold focus.
 */
@Composable
fun TvSectionScroll(state: LazyListState, focusedIndex: Int?, margin: Dp) {
    val marginPx = with(LocalDensity.current) { margin.roundToPx() }
    LaunchedEffect(focusedIndex, marginPx) {
        val index = focusedIndex ?: return@LaunchedEffect
        state.tvKeepInView(index, marginPx)
    }
}
