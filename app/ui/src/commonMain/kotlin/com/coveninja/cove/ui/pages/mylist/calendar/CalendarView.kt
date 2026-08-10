package com.coveninja.cove.ui.pages.mylist.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.pages.common.PageEmptyState
import com.coveninja.cove.ui.pages.mylist.ToolbarIconButton
import com.coveninja.cove.ui.platform.hasPointerHover
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

@Composable
fun CalendarMonthBar(
    month: YearMonth,
    isCurrentMonth: Boolean,
    refreshing: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarIconButton(
            iconName = "lucide:chevron-left",
            description = "Previous month",
            onClick = onPrevious,
        )

        // Direction-aware slide: the label should travel the way the month did.
        AnimatedContent(
            targetState = month,
            transitionSpec = {
                val forward = targetState > initialState
                val enter = slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) {
                    if (forward) it / 2 else -it / 2
                } + fadeIn(tween(180))
                val exit = slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) {
                    if (forward) -it / 2 else it / 2
                } + fadeOut(tween(140))
                enter togetherWith exit
            },
            label = "CalendarMonthLabel",
        ) { value ->
            Text(
                text = monthLabel(value),
                modifier = Modifier.width(170.dp),
                color = colors.onBackground,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }

        ToolbarIconButton(
            iconName = "lucide:chevron-right",
            description = "Next month",
            onClick = onNext,
        )

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(
            visible = !isCurrentMonth,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(120)),
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.surfaceContainer)
                    .clickable(onClick = onToday)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Today",
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        val spin = rememberInfiniteTransition(label = "CalendarRefreshSpin")
        val angle by spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "CalendarRefreshAngle",
        )

        Box(
            modifier = Modifier.graphicsLayer {
                rotationZ = if (refreshing) angle else 0f
            },
        ) {
            ToolbarIconButton(
                iconName = "lucide:refresh-cw",
                description = "Refresh the calendar",
                active = refreshing,
                onClick = onRefresh,
            )
        }
    }
}

/**
 * Which sections the viewer has folded away, by section key.
 *
 * Held outside the agenda so it survives switching to the Library tab and back, and so
 * collapsing "Available now" — a backlog that can be dozens of episodes long — is not
 * undone the moment the calendar recomposes.
 */
@Stable
class CalendarSectionState {
    private val collapsedKeys = mutableStateListOf<String>()

    fun isCollapsed(key: String): Boolean = key in collapsedKeys

    fun toggle(key: String) {
        if (!collapsedKeys.remove(key)) collapsedKeys.add(key)
    }
}

@Composable
fun rememberCalendarSections(): CalendarSectionState = remember { CalendarSectionState() }

@Composable
fun CalendarAgenda(
    available: List<CalendarItem>,
    days: List<CalendarDay>,
    today: LocalDate,
    showAvailable: Boolean,
    sections: CalendarSectionState,
    state: LazyListState,
    contentPadding: PaddingValues,
    onOpen: (CalendarItem) -> Unit,
    onPlay: (CalendarItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (available.isEmpty() && days.isEmpty()) {
        PageEmptyState(
            iconName = "lucide:calendar-days",
            title = "Nothing scheduled",
            message = "Add a returning series to My List and its next episode shows up here.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Available items live outside the month sections on purpose: an episode that
        // aired six weeks ago is still waiting, and filing it under the month it aired
        // would hide the one thing the viewer came here to find.
        if (showAvailable && available.isNotEmpty()) {
            val key = AVAILABLE_SECTION_KEY
            val collapsed = sections.isCollapsed(key)

            item(key = "available-header") {
                CalendarSectionHeader(
                    title = "Available now",
                    detail = "${available.size} waiting",
                    collapsed = collapsed,
                    accent = true,
                    onToggle = { sections.toggle(key) },
                    modifier = Modifier.animateItem(),
                )
            }
            // Collapsed sections emit no rows at all rather than hiding them: a lazy list
            // should not carry the cost of what is not on screen, and animateItem gives
            // the fold its exit for free.
            if (!collapsed) {
                items(available, key = { "available-${it.id}" }) { item ->
                    Box(modifier = Modifier.animateItem()) {
                        CalendarRow(
                            item = item,
                            today = today,
                            onOpen = { onOpen(item) },
                            onPlay = { onPlay(item) },
                        )
                    }
                }
            }
        }

        days.forEach { day ->
            val key = "day-${day.key}"
            val collapsed = sections.isCollapsed(key)

            item(key = key) {
                CalendarSectionHeader(
                    title = day.label,
                    // The count earns its place once it is either not obvious (more than
                    // one) or not visible (folded away).
                    detail = if (collapsed || day.items.size > 1) {
                        "${day.relative}  ·  ${day.items.size}"
                    } else {
                        day.relative
                    },
                    collapsed = collapsed,
                    onToggle = { sections.toggle(key) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (!collapsed) {
                items(day.items, key = { "${day.key}-${it.id}" }) { item ->
                    Box(modifier = Modifier.animateItem()) {
                        CalendarRow(
                            item = item,
                            today = today,
                            onOpen = { onOpen(item) },
                            onPlay = { onPlay(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarSectionHeader(
    title: String,
    detail: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val chevronRotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "CalendarSectionChevron",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (hovered) colors.onSurface.copy(alpha = 0.06f) else Color.Transparent,
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .semantics {
                contentDescription =
                    if (collapsed) "$title, collapsed. Expand" else "$title, expanded. Collapse"
            }
            .padding(top = 12.dp, bottom = 4.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(
            icon = "lucide:chevron-down",
            modifier = Modifier
                .size(15.dp)
                .graphicsLayer { rotationZ = chevronRotation },
            tint = if (accent) colors.tertiary else colors.onSurfaceVariant,
        )
        Text(
            text = title.uppercase(),
            color = if (accent) colors.tertiary else colors.onBackground,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = detail,
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.outlineVariant.copy(alpha = 0.5f)),
        )
    }
}

private const val AVAILABLE_SECTION_KEY = "available"

@Composable
private fun CalendarRow(
    item: CalendarItem,
    today: LocalDate,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val available = item.available

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.onSurface.copy(alpha = if (hovered) 0.10f else 0.04f))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvailabilityDot(available = available)

        Box(
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            val artwork = calendarImageUrl(item)
            if (artwork == null) {
                // A tinted glyph rather than an empty box, so "this title has no art" does
                // not look like "the image failed to load".
                IconifyIcon(
                    icon = if (item.type == MediaType.Movie) "lucide:film" else "lucide:tv",
                    modifier = Modifier.size(16.dp),
                    tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                )
            } else {
                AsyncImage(
                    model = artwork,
                    contentDescription = "${item.title} poster",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                color = colors.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.episodeMarker()?.let { marker ->
                    Text(
                        text = marker,
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                item.episodeName.takeIf { it.isNotBlank() }?.let { name ->
                    Text(
                        text = name,
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.waitingCount > 1) {
                    Text(
                        text = "${item.waitingCount} waiting",
                        color = colors.tertiary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Text(
            text = if (available) {
                "available"
            } else {
                item.parsedDate()?.let { countdownLabel(it, today) }.orEmpty()
            },
            color = if (available) colors.tertiary else colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (available) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )

        // Always offered where nothing can hover: on a touch screen this is the only way
        // to start a backlog episode from the calendar.
        AnimatedVisibility(
            visible = (hovered || !hasPointerHover) && available,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(120)),
        ) {
            ToolbarIconButton(
                iconName = "lucide:play",
                description = "Play ${item.title}",
                onClick = onPlay,
            )
        }
    }
}

/** Filled and slowly pulsing when watchable now; a hollow ring when it is still coming. */
@Composable
private fun AvailabilityDot(available: Boolean) {
    val colors = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "CalendarDotPulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "CalendarDotAlpha",
    )

    Box(
        modifier = Modifier.size(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (available) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(CircleShape)
                    .background(colors.tertiary),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(colors.onSurfaceVariant.copy(alpha = 0.45f)),
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.85f)),
                )
            }
        }
    }
}
