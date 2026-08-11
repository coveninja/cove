package com.coveninja.cove.ui.pages.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.MenuPicker
import com.coveninja.cove.ui.pages.common.SegmentedControl
import com.coveninja.cove.ui.pages.common.ToolbarIconButton

/**
 * Format, order, arrangement, genre, and the two things you want to hide.
 *
 * Deliberately built from the same primitives as `ExploreToolbar` — [SegmentedControl],
 * [MenuPicker], [ToolbarIconButton], [ChoicePill] — so narrowing a search and narrowing a
 * browse are the same gesture in the same places rather than two dialects of one app. Same
 * two arrangements too: side by side where there is room, stacked below
 * [COMPACT_SEARCH_TOOLBAR_WIDTH], which a phone always is.
 */
@Composable
fun SearchToolbar(
    filters: SearchFilters,
    layout: SearchLayout,
    facets: List<GenreFacet>,
    movieCount: Int,
    seriesCount: Int,
    onFiltersChange: (SearchFilters) -> Unit,
    onLayoutChange: (SearchLayout) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun typeSwitch(switchModifier: Modifier) = SegmentedControl<MediaType?>(
        options = TYPE_OPTIONS,
        selected = filters.type,
        label = { option ->
            when (option) {
                null -> countedLabel("All", movieCount + seriesCount)
                MediaType.Movie -> countedLabel("Movies", movieCount)
                MediaType.Series -> countedLabel("Series", seriesCount)
            }
        },
        icon = { option ->
            when (option) {
                null -> "lucide:library"
                MediaType.Movie -> "lucide:film"
                MediaType.Series -> "lucide:tv"
            }
        },
        // The genre goes with it. Films and series do not share a genre vocabulary — id
        // 10759 is "Action & Adventure" for series and nothing at all for films — so a
        // genre carried across the switch would filter by something that does not exist.
        onSelect = { onFiltersChange(filters.copy(type = it, genreId = null)) },
        modifier = switchModifier,
    )

    @Composable
    fun sortPicker(pickerModifier: Modifier) = MenuPicker(
        options = SearchSort.entries,
        selected = filters.sort,
        label = { it.label },
        onSelect = { onFiltersChange(filters.copy(sort = it)) },
        modifier = pickerModifier,
    )

    @Composable
    fun layoutToggle() = SegmentedControl(
        options = SearchLayout.entries,
        selected = layout,
        label = { it.label },
        icon = { it.icon },
        showLabels = false,
        onSelect = onLayoutChange,
        modifier = Modifier.width(80.dp),
    )

    @Composable
    fun hideSavedToggle() = ToolbarIconButton(
        iconName = "lucide:eye-off",
        description = if (filters.hideSaved) {
            "Show titles already in My List"
        } else {
            "Hide titles already in My List"
        },
        active = filters.hideSaved,
        onClick = { onFiltersChange(filters.copy(hideSaved = !filters.hideSaved)) },
    )

    // Only ever present when there is something to clear, so it never reads as a control
    // that does nothing. Pops rather than fades, because it is an offer of help arriving.
    @Composable
    fun clearButton() = AnimatedVisibility(
        visible = filters.narrowed,
        enter = fadeIn(tween(140)) + scaleIn(
            initialScale = 0.7f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ),
        exit = fadeOut(tween(110)) + scaleOut(targetScale = 0.7f),
    ) {
        ToolbarIconButton(
            iconName = "lucide:filter-x",
            description = "Clear filters",
            onClick = { onFiltersChange(filters.cleared()) },
        )
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < COMPACT_SEARCH_TOOLBAR_WIDTH) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    typeSwitch(Modifier.fillMaxWidth())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        sortPicker(Modifier.weight(1f))
                        layoutToggle()
                        hideSavedToggle()
                        clearButton()
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    typeSwitch(Modifier.width(300.dp))
                    Spacer(Modifier.weight(1f))
                    clearButton()
                    hideSavedToggle()
                    sortPicker(Modifier)
                    layoutToggle()
                }
            }
        }

        GenreFacetPills(
            facets = facets,
            selected = filters.genreId,
            onSelect = { onFiltersChange(filters.copy(genreId = it)) },
        )
    }
}

/**
 * The genre row, which keeps the selection on screen.
 *
 * A `LazyRow` rather than the shared `ChoicePillRow` for the same reason Explore's is: it can
 * be scrolled to an index, and a filter that has scrolled off the edge is a filter you forget
 * you set. Each pill carries its count, so the row says how much narrowing each one is worth
 * before you press it.
 */
@Composable
private fun GenreFacetPills(
    facets: List<GenreFacet>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    if (facets.isEmpty()) return
    val state = rememberLazyListState()

    LaunchedEffect(selected, facets) {
        val index = facets.indexOfFirst { it.id == selected }
        // +1 for the leading "All genres" pill, which is not one of the facets.
        if (index >= 0) state.animateScrollToItem(index + 1)
    }

    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "all-genres") {
            ChoicePill(
                label = "All genres",
                selected = selected == null,
                iconName = "lucide:layout-grid",
                onClick = { onSelect(null) },
            )
        }
        items(facets.size, key = { facets[it].id }) { position ->
            val facet = facets[position]
            ChoicePill(
                label = "${facet.name}  ${facet.count}",
                selected = selected == facet.id,
                onClick = { onSelect(if (selected == facet.id) null else facet.id) },
            )
        }
    }
}

/** "Movies 14", or plain "Movies" when there are none to count. */
private fun countedLabel(name: String, count: Int): String =
    if (count > 0) "$name $count" else name

/** Null is "both", which is the default and the common case — hence first. */
private val TYPE_OPTIONS: List<MediaType?> = listOf(null, MediaType.Movie, MediaType.Series)

/** Below this the controls stop fitting on one line; phones are always under it. */
private val COMPACT_SEARCH_TOOLBAR_WIDTH = 720.dp
