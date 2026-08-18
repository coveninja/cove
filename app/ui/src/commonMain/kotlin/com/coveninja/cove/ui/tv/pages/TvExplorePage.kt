package com.coveninja.cove.ui.tv.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.ExploreState
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.pages.common.MediaRailStateStore
import com.coveninja.cove.ui.pages.common.rememberMediaRailStateStore
import com.coveninja.cove.ui.pages.explore.ExploreController
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.components.TvButton
import com.coveninja.cove.ui.tv.components.TvMediaCard
import com.coveninja.cove.ui.tv.components.TvMediaRow
import com.coveninja.cove.ui.tv.focus.TvSectionScroll
import com.coveninja.cove.ui.tv.focus.tvFocusGroup

/** Scroll positions worth keeping while another destination is on screen. */
@Stable
class TvExplorePageState internal constructor(
    internal val listState: LazyListState,
    internal val railStates: MediaRailStateStore,
)

@Composable
fun rememberTvExplorePageState(): TvExplorePageState {
    val listState = rememberLazyListState()
    val railStates = rememberMediaRailStateStore()
    return remember(listState, railStates) { TvExplorePageState(listState, railStates) }
}

/**
 * The catalogue, as rails.
 *
 * The phone's Explore carries a sort control, a genre facet list, a query field and a
 * grid/rails layout switch. Only the format switch survives: the rest are ways of narrowing a
 * catalogue that a remote is a poor instrument for, and `buildShelves` has already done the
 * editorial work of deciding which rails are worth drawing — a genre rail that merely repeats
 * what the page showed above it is dropped before it ever reaches here.
 *
 * Which means Explore on a television is what it claims to be: something to walk through, not
 * something to operate.
 */
@Composable
internal fun TvExplorePage(
    exploreState: ExploreState,
    controller: ExploreController,
    pageState: TvExplorePageState,
    onOpenMedia: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = TvTheme.dimens
    var type by remember { mutableStateOf(MediaType.Movie) }
    var focusedSection by remember { mutableStateOf<Int?>(null) }

    val seed = remember(exploreState) {
        (exploreState as? ExploreState.Ready)
            ?.let { it.movies + it.tv }
            ?.map { item -> item.toUiMedia() }
            .orEmpty()
    }

    LaunchedEffect(type, seed) { controller.loadShelves(type, seed) }

    TvSectionScroll(
        state = pageState.listState,
        focusedIndex = focusedSection,
        margin = dimens.focusScrollMargin,
    )

    LazyColumn(
        state = pageState.listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = dimens.overscanVertical + 20.dp,
            bottom = dimens.overscanVertical + 32.dp,
        ),
    ) {
        item(key = "type-switch") {
            Row(
                modifier = Modifier
                    .padding(horizontal = dimens.overscanHorizontal)
                    .tvFocusGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MediaType.entries.forEach { entry ->
                    TvButton(
                        label = entry.label,
                        onClick = { type = entry },
                        icon = if (entry == MediaType.Movie) "lucide:film" else "lucide:tv",
                        selected = entry == type,
                    )
                }
            }
        }

        if (controller.shelves.isEmpty()) {
            item(key = "state") {
                Text(
                    text = if (controller.shelvesLoading) {
                        "Building the catalogue…"
                    } else {
                        "Nothing to show here yet."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = CoveColors.Neutral.MutedDim,
                    modifier = Modifier.padding(
                        top = 28.dp,
                        start = dimens.overscanHorizontal,
                    ),
                )
            }
            return@LazyColumn
        }

        itemsIndexed(
            items = controller.shelves,
            key = { _, shelf -> shelf.id },
        ) { position, shelf ->
            TvMediaRow(
                title = shelf.title,
                subtitle = shelf.subtitle,
                icon = shelf.icon,
                items = shelf.media,
                key = Media::id,
                state = pageState.railStates.stateFor(shelf.id),
                // Offset by one: the format switch is item zero, and a shelf reporting its own
                // index would scroll the page to the row above it.
                onFocusChanged = { if (it) focusedSection = position + 1 },
                modifier = Modifier.padding(top = dimens.sectionSpacing),
            ) { media ->
                TvMediaCard(media = media, onClick = { onOpenMedia(media) })
            }
        }
    }
}
