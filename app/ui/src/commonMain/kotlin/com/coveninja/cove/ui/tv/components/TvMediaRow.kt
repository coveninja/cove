package com.coveninja.cove.ui.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.tv.TvTheme
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
            items(items = items, key = key) { item -> itemContent(item) }
        }
    }
}
