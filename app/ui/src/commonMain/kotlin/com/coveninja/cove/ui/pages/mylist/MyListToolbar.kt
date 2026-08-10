package com.coveninja.cove.ui.pages.mylist

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.components.menu.CMenuItem
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.ChoicePillRow
import com.coveninja.cove.ui.pages.common.SegmentedControl
import com.coveninja.cove.ui.pages.common.ToolbarIconButton

/**
 * The category filters, with counts that roll rather than cut when the library changes.
 *
 * [MyListCategory.NotInterested] is deliberately absent: it is a dismissal action, not a
 * status any entry can hold, so a pill for it could only ever read "(0)".
 */
@Composable
fun MyListCategoryPills(
    counts: Map<MyListCategory, Int>,
    total: Int,
    selected: MyListCategory?,
    onSelect: (MyListCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChoicePillRow(modifier = modifier) {
        CountedPill(
            label = "All",
            count = total,
            selected = selected == null,
            iconName = "lucide:library",
            onClick = { onSelect(null) },
        )
        MyListCategory.entries
            .filter { it != MyListCategory.NotInterested }
            .forEach { category ->
                CountedPill(
                    label = category.label,
                    count = counts[category] ?: 0,
                    selected = selected == category,
                    iconName = category.icon,
                    accentColor = category.accentColor,
                    onClick = { onSelect(if (selected == category) null else category) },
                )
            }
    }
}

@Composable
private fun CountedPill(
    label: String,
    count: Int,
    selected: Boolean,
    iconName: String,
    onClick: () -> Unit,
    accentColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChoicePill(
            label = label,
            selected = selected,
            iconName = iconName,
            accentColor = accentColor,
            onClick = onClick,
        )
        // The count sits outside the pill so its width changes cannot make the pill
        // itself jump while the number animates.
        AnimatedContent(
            targetState = count,
            transitionSpec = {
                if (targetState > initialState) {
                    slideInVertically { it } + fadeIn() togetherWith
                        slideOutVertically { -it } + fadeOut()
                } else {
                    slideInVertically { -it } + fadeIn() togetherWith
                        slideOutVertically { it } + fadeOut()
                }
            },
            label = "MyListPillCount",
        ) { value ->
            Text(
                text = value.toString(),
                modifier = Modifier.padding(start = 6.dp, end = 2.dp),
                color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Filters, search, sort, layout and selection.
 *
 * Two arrangements, because a phone cannot hold what a desktop window can: side by side
 * when there is room, and stacked with a full-width search field below when there is not.
 * The wide form uses a weighted spacer, which is exactly why the narrow form cannot simply
 * be the same row inside a horizontal scroll — a weight needs bounded width.
 */
@Composable
fun MyListToolbar(
    filters: MyListFilters,
    layout: MyListLayout,
    selectionActive: Boolean,
    onFiltersChange: (MyListFilters) -> Unit,
    onLayoutChange: (MyListLayout) -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchOpen by remember { mutableStateOf(false) }
    val expanded = searchOpen || filters.query.isNotBlank()

    val onQueryChange: (String) -> Unit = { onFiltersChange(filters.copy(query = it)) }
    val onExpandedChange: (Boolean) -> Unit = { open ->
        searchOpen = open
        if (!open) onFiltersChange(filters.copy(query = ""))
    }

    @Composable
    fun sort() = SortControl(
        sort = filters.sort,
        descending = filters.descending,
        onSortChange = { onFiltersChange(filters.copy(sort = it)) },
        onDirectionToggle = { onFiltersChange(filters.copy(descending = !filters.descending)) },
    )

    @Composable
    fun layoutToggle() = SegmentedControl(
        options = MyListLayout.entries,
        selected = layout,
        label = { it.label },
        icon = { it.icon },
        showLabels = false,
        onSelect = onLayoutChange,
        modifier = Modifier.width(80.dp),
    )

    @Composable
    fun selectButton() = ToolbarIconButton(
        iconName = "lucide:square-check-big",
        description = "Select titles",
        active = selectionActive,
        onClick = onToggleSelection,
    )

    @Composable
    fun searchToggle() = ToolbarIconButton(
        iconName = if (expanded) "lucide:x" else "lucide:search",
        description = if (expanded) "Clear filter" else "Filter your list",
        active = expanded,
        onClick = { onExpandedChange(!expanded) },
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val narrow = maxWidth < COMPACT_TOOLBAR_WIDTH

        if (narrow) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TypeFilter(
                        selected = filters.type,
                        onSelect = { onFiltersChange(filters.copy(type = it)) },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) { sort() }
                    layoutToggle()
                    selectButton()
                    searchToggle()
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(140)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(110)) + shrinkVertically(tween(160)),
                ) {
                    SearchField(
                        query = filters.query,
                        onQueryChange = onQueryChange,
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
                TypeFilter(
                    selected = filters.type,
                    onSelect = { onFiltersChange(filters.copy(type = it)) },
                )

                Spacer(Modifier.weight(1f))

                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(140)) + expandHorizontally(
                        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                    ),
                    exit = fadeOut(tween(110)) + shrinkHorizontally(tween(160)),
                ) {
                    SearchField(
                        query = filters.query,
                        onQueryChange = onQueryChange,
                        modifier = Modifier.width(210.dp),
                    )
                }
                searchToggle()

                sort()
                layoutToggle()
                selectButton()
            }
        }
    }
}

/** Below this the controls stop fitting on one line; phones are always under it. */
private val COMPACT_TOOLBAR_WIDTH = 720.dp

@Composable
private fun TypeFilter(
    selected: MediaType?,
    onSelect: (MediaType?) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoicePill(
            label = "All formats",
            selected = selected == null,
            iconName = "lucide:layout-grid",
            onClick = { onSelect(null) },
        )
        ChoicePill(
            label = MediaType.Movie.label,
            selected = selected == MediaType.Movie,
            iconName = "lucide:film",
            onClick = { onSelect(if (selected == MediaType.Movie) null else MediaType.Movie) },
        )
        ChoicePill(
            label = MediaType.Series.label,
            selected = selected == MediaType.Series,
            iconName = "lucide:tv",
            onClick = { onSelect(if (selected == MediaType.Series) null else MediaType.Series) },
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }

    // Focused on appearance: the field only exists once the viewer asked for it, so the
    // next thing they want to do is type.
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
                        text = "Filter your list…",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun SortControl(
    sort: MyListSort,
    descending: Boolean,
    onSortChange: (MyListSort) -> Unit,
    onDirectionToggle: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box {
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(CircleShape)
                .background(
                    if (hovered) colors.onSurface.copy(alpha = 0.10f) else colors.surfaceContainer,
                )
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { menuOpen = true }
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconifyIcon(
                icon = "lucide:arrow-up-down",
                modifier = Modifier.size(15.dp),
                tint = colors.onSurfaceVariant,
            )
            Text(
                text = sort.label,
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            IconifyIcon(
                icon = "lucide:chevron-down",
                modifier = Modifier.size(14.dp),
                tint = colors.onSurfaceVariant,
            )
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.width(230.dp),
            shape = RoundedCornerShape(16.dp),
            containerColor = colors.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 18.dp,
        ) {
            MyListSort.entries.forEach { option ->
                CMenuItem(
                    text = if (option == sort) "${option.label}  •  Current" else option.label,
                    iconName = if (option == sort) "lucide:check" else "lucide:arrow-up-down",
                    accent = option == sort,
                    onClick = {
                        menuOpen = false
                        onSortChange(option)
                    },
                )
            }
            CMenuItem(
                text = if (descending) "Newest / highest first" else "Oldest / lowest first",
                iconName = if (descending) "lucide:arrow-down" else "lucide:arrow-up",
                onClick = {
                    menuOpen = false
                    onDirectionToggle()
                },
            )
        }
    }
}

