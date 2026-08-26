package com.coveninja.cove.ui.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.focus.TvRowScroll
import com.coveninja.cove.ui.tv.focus.tvFocusGroup

/**
 * A heading and a row of cards.
 *
 * Generic over the item because the rows carry different things — posters, carry-on episodes,
 * a backlog — and everything around them is identical. The phone's `MediaRail` is the same idea
 * and cannot be reused: its arrows, its "see all" link and its edge controls are all revealed on
 * hover, which on a television means they can never be reached at all.
 *
 * The row is one focus group that remembers its position, so leaving it for the row above and
 * coming back lands on the card you left rather than back at the start.
 */
@Composable
internal fun <T> TvMediaRow(
    /** Null for a row that is part of a grid, where a heading per chunk would be noise. */
    title: String?,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: String? = null,
    state: LazyListState = rememberLazyListState(),
    onFocusChanged: (Boolean) -> Unit = {},
    itemContent: @Composable (T) -> Unit,
) {
    val dimens = TvTheme.dimens
    // Which card in this row holds focus, so the row can keep it clear of its own ends. Owned
    // here rather than by the cards, which are supplied by the caller and differ per row.
    //
    // Deliberately not keyed on [items]. Several callers build their list inline, so a key would
    // hand back null on any recomposition that rebuilt it — and nothing re-reports focus that
    // has not moved, so the row would silently stop keeping the focused card in view. A stale
    // index costs nothing: it is only ever read when it changes.
    var focusedItem by remember { mutableStateOf<Int?>(null) }
    TvRowScroll(
        state = state,
        focusedIndex = focusedItem,
        margin = dimens.focusScrollMarginHorizontal,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focus -> onFocusChanged(focus.hasFocus) },
    ) {
        if (title != null) {
            TvRowHeader(
                title = title,
                subtitle = subtitle,
                icon = icon,
                modifier = Modifier.padding(horizontal = dimens.overscanHorizontal),
            )
        }
        LazyRow(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (title != null) 12.dp else 0.dp)
                .tvFocusGroup(),
            // The vertical padding is room for the focused card to grow into. A row that
            // clipped its own focus ring would be worse than having no ring at all.
            contentPadding = PaddingValues(
                start = dimens.overscanHorizontal,
                end = dimens.overscanHorizontal,
                top = 10.dp,
                bottom = 10.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(dimens.cardSpacing),
        ) {
            itemsIndexed(items = items, key = { _, item -> key(item) }) { index, item ->
                // A wrapper rather than a requirement on the item: every card in the shell
                // already reports focus one way or another, but they are passed in as opaque
                // content and this row cannot reach inside them to ask.
                Box(
                    modifier = Modifier.onFocusChanged { focus ->
                        if (focus.hasFocus) focusedItem = index
                    },
                ) {
                    itemContent(item)
                }
            }
        }
    }
}
