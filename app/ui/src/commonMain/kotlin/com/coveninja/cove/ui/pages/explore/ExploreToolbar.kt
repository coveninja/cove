package com.coveninja.cove.ui.pages.explore

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.model.MediaGenre
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.MenuPicker
import com.coveninja.cove.ui.pages.common.SegmentedControl
import com.coveninja.cove.ui.pages.common.ToolbarIconButton

/**
 * Format, genre, order, arrangement, search, and the dice.
 *
 * Two arrangements, like My List's: side by side where there is room, stacked where there
 * is not. Below [COMPACT_TOOLBAR_WIDTH] the controls stop fitting on one line, and a phone
 * is always below it.
 */
@Composable
fun ExploreToolbar(
    filters: ExploreFilters,
    layout: ExploreLayout,
    genres: List<MediaGenre>,
    onFiltersChange: (ExploreFilters) -> Unit,
    onLayoutChange: (ExploreLayout) -> Unit,
    onSurpriseMe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchOpen by remember { mutableStateOf(false) }
    val expanded = searchOpen || filters.query.isNotBlank()

    @Composable
    fun typeSwitch(switchModifier: Modifier) = SegmentedControl(
        options = MediaType.entries,
        selected = filters.type,
        label = { if (it == MediaType.Movie) "Movies" else "Series" },
        icon = { if (it == MediaType.Movie) "lucide:film" else "lucide:tv" },
        // Changing format invalidates the genre: the two vocabularies do not share ids,
        // so carrying one over would filter by a genre that does not exist here.
        onSelect = { onFiltersChange(filters.copy(type = it, genreId = null)) },
        modifier = switchModifier,
    )

    @Composable
    fun sortPicker(pickerModifier: Modifier) = SortPicker(
        sort = filters.sort,
        onSelect = { onFiltersChange(filters.copy(sort = it)) },
        modifier = pickerModifier,
    )

    @Composable
    fun layoutToggle() = SegmentedControl(
        options = ExploreLayout.entries,
        selected = layout,
        label = { it.label },
        icon = { it.icon },
        showLabels = false,
        onSelect = onLayoutChange,
        modifier = Modifier.width(80.dp),
    )

    @Composable
    fun surpriseButton() = SurpriseMeButton(onClick = onSurpriseMe)

    @Composable
    fun searchToggle() = ToolbarIconButton(
        iconName = if (expanded) "lucide:x" else "lucide:search",
        description = if (expanded) "Clear filter" else "Filter these titles",
        active = expanded,
        onClick = {
            searchOpen = !expanded
            if (expanded) onFiltersChange(filters.copy(query = ""))
        },
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val narrow = maxWidth < COMPACT_TOOLBAR_WIDTH

            if (narrow) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    typeSwitch(Modifier.fillMaxWidth())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        sortPicker(Modifier.weight(1f))
                        layoutToggle()
                        surpriseButton()
                        searchToggle()
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn(tween(140)) + expandVertically(tween(180)),
                        exit = fadeOut(tween(110)) + shrinkVertically(tween(160)),
                    ) {
                        ExploreSearchField(
                            query = filters.query,
                            onQueryChange = { onFiltersChange(filters.copy(query = it)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    typeSwitch(Modifier.width(220.dp))
                    Spacer(Modifier.weight(1f))
                    AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn(tween(140)) + expandHorizontally(
                            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                        ),
                        exit = fadeOut(tween(110)) + shrinkHorizontally(tween(160)),
                    ) {
                        ExploreSearchField(
                            query = filters.query,
                            onQueryChange = { onFiltersChange(filters.copy(query = it)) },
                            modifier = Modifier.width(220.dp),
                        )
                    }
                    searchToggle()
                    sortPicker(Modifier)
                    layoutToggle()
                    surpriseButton()
                }
            }
        }

        GenrePills(
            genres = genres,
            selected = filters.genreId,
            onSelect = { onFiltersChange(filters.copy(genreId = it)) },
        )
    }
}

/**
 * The genre row, which keeps the selection on screen.
 *
 * A LazyRow rather than the shared [com.coveninja.cove.ui.pages.common.ChoicePillRow]
 * precisely so it can be scrolled to an index: with thirty-odd genres the one you just
 * picked is regularly off the edge, and a filter you cannot see is a filter you forget
 * you set.
 */
@Composable
private fun GenrePills(
    genres: List<MediaGenre>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    if (genres.isEmpty()) return
    val state = rememberLazyListState()

    LaunchedEffect(selected, genres) {
        val index = genres.indexOfFirst { it.id == selected }
        // +1 for the leading "All genres" pill, which is not part of the genre list.
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
        items(genres.size, key = { genres[it].id }) { position ->
            val genre = genres[position]
            ChoicePill(
                label = genre.name,
                selected = selected == genre.id,
                onClick = { onSelect(if (selected == genre.id) null else genre.id) },
            )
        }
    }
}

@Composable
private fun SortPicker(
    sort: ExploreSort,
    onSelect: (ExploreSort) -> Unit,
    modifier: Modifier = Modifier,
) = MenuPicker(
    options = ExploreSort.entries,
    selected = sort,
    label = { it.label },
    onSelect = onSelect,
    modifier = modifier,
)

/**
 * Rolls the dice on whatever is currently on screen.
 *
 * The icon completes one turn per press. It is feedback, not decoration: the result opens
 * a details sheet, and without the spin a second press on the same title looks like the
 * button did nothing.
 */
@Composable
private fun SurpriseMeButton(onClick: () -> Unit) {
    var rolls by remember { mutableStateOf(0) }
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = rolls * 360f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "SurpriseMeSpin",
    )

    ToolbarIconButton(
        iconName = "lucide:dices",
        description = "Surprise me",
        rotation = rotation,
        onClick = {
            rolls += 1
            onClick()
        },
    )
}

@Composable
private fun ExploreSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }

    // The field only exists once it was asked for, so typing is the next thing wanted.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .height(36.dp)
            .focusRequester(focusRequester)
            .clip(CircleShape)
            .background(colors.onSurface.copy(alpha = 0.06f))
            .border(1.dp, colors.outline.copy(alpha = 0.30f), CircleShape),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.tertiary),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Filter these titles…",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                innerTextField()
            }
        },
    )
}

/** Below this the controls stop fitting on one line; phones are always under it. */
private val COMPACT_TOOLBAR_WIDTH = 720.dp
