package com.coveninja.cove.ui.components.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import com.coveninja.cove.ui.components.common.CoveTooltip
import com.coveninja.cove.ui.components.common.TooltipSide
import com.coveninja.cove.ui.components.media.MyListCategory

/** Where the app host places the floating navigation bar. */
enum class NavBarPlacement {
    Top,
    Bottom,
}

/**
 * How much room the floating nav bar needs at the edge of a page that must not run under it.
 *
 * The bar is 48.dp tall and sits 16.dp inside the safe area, so this is those plus enough
 * breathing room that content does not crowd it. This is only layout clearance for the
 * desktop top bar; the mobile bottom bar deliberately floats over page content.
 */
val NavBarClearance = 96.dp

private enum class NavBarMode {
    Navigation,
    Search,
    ListCategories,
}

@Composable
private fun MyListCategoryTarget(
    category: MyListCategory,
    hovered: Boolean,
    onBoundsChanged: (Rect) -> Unit,
) {
    val accent = category.accentColor
    val icon = category.icon

    val backgroundColor by animateColorAsState(
        targetValue = if (hovered) {
            accent
        } else {
            accent.copy(alpha = 0.20f)
        },
        animationSpec = tween(durationMillis = 140),
        label = "CategoryBackground",
    )

    val contentColor by animateColorAsState(
        targetValue = if (hovered) {
            Color.White
        } else {
            accent
        },
        animationSpec = tween(durationMillis = 140),
        label = "CategoryContent",
    )

    val scale by animateFloatAsState(
        targetValue = if (hovered) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "CategoryScale",
    )

    Box(
        modifier = Modifier
            .width(48.dp)
            .fillMaxHeight()
            .onGloballyPositioned { coordinates ->
                onBoundsChanged(coordinates.boundsInRoot())
            }
            .scale(scale)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(
            icon = icon,
            modifier = Modifier
                .scale(1.0f)
                .size(18.dp),
            tint = contentColor,
        )
    }
}


@Composable
private fun MyListCategoryContent(
    hoveredCategory: MyListCategory?,
    onCategoryBoundsChanged: (
        category: MyListCategory,
        bounds: Rect,
    ) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MyListCategory.entries.forEach { category ->
            MyListCategoryTarget(
                category = category,
                hovered = category == hoveredCategory,
                onBoundsChanged = { bounds ->
                    onCategoryBoundsChanged(category, bounds)
                },
            )
        }
    }
}


@Composable
fun NavBarButton(
    label: String,
    iconName: String,
    selected: Boolean,
    tooltipSide: TooltipSide = TooltipSide.Below,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val backgroundColor by animateColorAsState(
        targetValue = when {
            selected && isHovered ->
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)

            selected ->
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.09f)

            isHovered ->
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 160),
        label = "NavItemBackground",
    )

    val iconColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.tertiary
            isHovered -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.outline
        },
        animationSpec = tween(durationMillis = 150),
        label = "NavItemIconColor",
    )

    val iconScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.88f
            isHovered -> 1.10f
            selected -> 1.04f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "NavItemIconScale",
    )

    val iconOffset by animateDpAsState(
        targetValue = when {
            isPressed -> 1.dp
            isHovered -> (-1).dp
            else -> 0.dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "NavItemIconOffset",
    )

    CoveTooltip(label = label, side = tooltipSide) {
        Box(
            modifier = modifier
                .width(48.dp)
                .fillMaxHeight()
                .semantics {
                    this.selected = selected
                }
                .hoverable(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                )
                .background(backgroundColor),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = iconName,
                modifier = Modifier
                    .offset(y = iconOffset)
                    .scale(iconScale)
                    .size(20.dp),
                tint = iconColor,
            )

            AnimatedVisibility(
                visible = selected,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp),
                enter = fadeIn(tween(140)) +
                        expandHorizontally(
                            animationSpec = spring(
                                dampingRatio =
                                    Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                            expandFrom = Alignment.CenterHorizontally,
                        ),
                exit = fadeOut(tween(100)) +
                        shrinkHorizontally(
                            animationSpec = tween(120),
                            shrinkTowards = Alignment.CenterHorizontally,
                        ),
            ) {
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }
        }
    }
}

@Composable
private fun SearchActionButton(
    iconName: String,
    description: String,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled ->
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)

            accent ->
                MaterialTheme.colorScheme.tertiary

            isHovered ->
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)

            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 150),
        label = "SearchActionBackground",
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled ->
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)

            accent ->
                MaterialTheme.colorScheme.onTertiary

            else ->
                MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 150),
        label = "SearchActionContent",
    )

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed && enabled -> 0.88f
            isHovered && enabled -> 1.06f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "SearchActionScale",
    )

    Box(
        modifier = Modifier
            .size(40.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(backgroundColor)
            .semantics {
                contentDescription = description
            }
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(
            icon = iconName,
            modifier = Modifier.size(20.dp),
            tint = contentColor,
        )
    }
}

@Composable
private fun SearchNavContent(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchActionButton(
            iconName = "iconamoon:arrow-left-1",
            description = "Close search",
            onClick = onBack,
        )

        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .focusRequester(focusRequester)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(24.dp),
                ),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    if (query.isNotBlank()) {
                        onSubmit()
                    }
                },
            ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    innerTextField()
                }
            },
        )

        SearchActionButton(
            iconName = "iconamoon:search",
            description = "Submit search",
            accent = true,
            enabled = query.isNotBlank(),
            onClick = onSubmit,
        )
    }
}

@Composable
fun NavBar(
    selectedDestination: NavDestination,
    searchMode: Boolean,
    listCategoryMode: Boolean,
    hoveredListCategory: MyListCategory?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSubmitSearch: (String) -> Unit,
    onDestinationSelected: (NavDestination) -> Unit,
    onListCategoryBoundsChanged: (
        category: MyListCategory,
        bounds: Rect,
    ) -> Unit,
    placement: NavBarPlacement = NavBarPlacement.Top,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val navbarMode = when {
        listCategoryMode -> NavBarMode.ListCategories
        searchMode -> NavBarMode.Search
        else -> NavBarMode.Navigation
    }

    val normalWidth = (NavDestination.entries.size * 48).dp
    val searchWidth = (NavDestination.entries.size * 64).dp
    val categoryWidth = (MyListCategory.entries.size * 48).dp
    val navbarHeight = 48.dp


    val surfaceWidth by animateDpAsState(
        targetValue = when (navbarMode) {
            NavBarMode.Navigation -> normalWidth
            NavBarMode.Search -> searchWidth
            NavBarMode.ListCategories -> categoryWidth
        },
        animationSpec = tween(
            durationMillis = 220,
            easing = FastOutSlowInEasing,
        ),
        label = "NavbarSurfaceWidth",
    )

    val shadowElevation by animateDpAsState(
        targetValue = when {
            searchMode -> 10.dp
            isHovered -> 8.dp
            else -> 3.dp
        },
        animationSpec = tween(durationMillis = 180),
        label = "NavbarShadow",
    )

    val navbarShape = RoundedCornerShape(percent = 50)

    val pulseTransition = rememberInfiniteTransition(
        label = "ListCategoryOutlinePulse",
    )

    val pulseProgress by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 850,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ListCategoryOutlinePulseProgress",
    )

    val regularBorderColor by animateColorAsState(
        targetValue = when {
            searchMode ->
                MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)

            isHovered ->
                MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)

            else ->
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "NavbarRegularBorderColor",
    )

    val pulsingBorderColor =
        MaterialTheme.colorScheme.tertiary.copy(
            alpha = 0.4f + (pulseProgress * 0.55f),
        )

    val borderColor = if (listCategoryMode) {
        pulsingBorderColor
    } else {
        regularBorderColor
    }

    val borderWidth = if (listCategoryMode) {
        2.dp
    } else {
        1.dp
    }

    /*
     * This outer Box never changes size.
     *
     * As a result:
     * - the parent Column does not remeasure and recenter the navbar;
     * - content below the navbar does not jump;
     * - only the visible surface animates.
     */
    Box(
        modifier = modifier
            .width(surfaceWidth)
            .height(navbarHeight),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .width(surfaceWidth)
                .height(navbarHeight)
                .hoverable(interactionSource)
                .shadow(
                    elevation = shadowElevation,
                    shape = navbarShape,
                    clip = false,
                )
                .border(
                    width = borderWidth,
                    color = borderColor,
                    shape = navbarShape,
                )
                .clip(navbarShape)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            AnimatedContent(
                targetState = navbarMode,
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
                transitionSpec = {
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = 120,
                            delayMillis = 20,
                        ),
                    ) togetherWith fadeOut(
                        animationSpec = tween(durationMillis = 80),
                    )
                },
                label = "NavbarModeTransition",
            ) { mode ->
                when (mode) {
                    NavBarMode.Search -> {
                        SearchNavContent(
                            query = searchQuery,
                            onQueryChange = onSearchQueryChange,
                            onBack = onCloseSearch,
                            onSubmit = {
                                val trimmedQuery = searchQuery.trim()

                                if (trimmedQuery.isNotEmpty()) {
                                    onSubmitSearch(trimmedQuery)
                                }
                            },
                        )
                    }

                    NavBarMode.ListCategories -> {
                        MyListCategoryContent(
                            hoveredCategory = hoveredListCategory,
                            onCategoryBoundsChanged =
                                onListCategoryBoundsChanged,
                        )
                    }

                    NavBarMode.Navigation -> {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NavDestination.entries.forEach { destination ->
                                NavBarButton(
                                    label = destination.label,
                                    iconName = destination.iconName,
                                    selected =
                                        destination == selectedDestination,
                                    tooltipSide = if (placement == NavBarPlacement.Bottom) {
                                        TooltipSide.Above
                                    } else {
                                        TooltipSide.Below
                                    },
                                    onClick = {
                                        if (
                                            destination ==
                                            NavDestination.Search
                                        ) {
                                            onOpenSearch()
                                        } else {
                                            onDestinationSelected(destination)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
enum class NavDestination(
    val label: String,
    val iconName: String,
) {
    Home(
        label = "Home",
        iconName = "iconamoon:home",
    ),
    MyList(
        label = "My List",
        iconName = "iconamoon:bookmark",
    ),
    Explore(
        label = "Explore",
        iconName = "iconamoon:discover",
    ),
    Search(
        label = "Search",
        iconName = "iconamoon:search",
    ),
    Account(
        label = "Profile",
        iconName = "iconamoon:profile-circle",
    ),
}
