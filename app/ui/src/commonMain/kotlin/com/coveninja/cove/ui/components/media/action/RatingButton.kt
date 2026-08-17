package com.coveninja.cove.ui.components.media.action

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.icons.IconifyIcon

private val RatingGold = CoveColors.Status.Rating
private val RatingGoldBright = CoveColors.Status.RatingBright

@Composable
fun RatingButton(
    modifier: Modifier = Modifier,
    currentRating: Int? = null,
    onStatusSelected: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val interactionSource = remember {
        MutableInteractionSource()
    }

    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHighlighted = isHovered || isFocused

    val savedRating = (currentRating ?: 0)
        .coerceIn(0, 5)

    var expanded by remember {
        mutableStateOf(false)
    }

    var previewRating by remember(currentRating) {
        mutableIntStateOf(savedRating)
    }

    val buttonSize = 48.dp
    val starSize = 28.dp
    val starSpacing = 7.dp

    val ratingStepPx = with(density) {
        (starSize + starSpacing).toPx()
    }

    /*
     * Prevents an existing rating from changing just
     * because the user clicked/pressed the button.
     */
    val activationSlopPx = with(density) {
        9.dp.toPx()
    }

    val popupGapPx = with(density) {
        9.dp.roundToPx()
    }

    val displayRating = if (expanded) {
        previewRating
    } else {
        savedRating
    }

    /*
     * Button micro-interactions.
     */
    val buttonScale by animateFloatAsState(
        targetValue = when {
            expanded -> 0.94f
            isHovered -> 1.05f
            isFocused -> 1.025f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = 0.55f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "RatingButtonScale",
    )

    val buttonRotation by animateFloatAsState(
        targetValue = when {
            expanded -> -3f
            isHovered -> 2f
            else -> 0f
        },
        animationSpec = spring(
            dampingRatio = 0.55f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "RatingButtonRotation",
    )

    val buttonElevation by animateDpAsState(
        targetValue = when {
            expanded -> 8.dp
            isHighlighted -> 6.dp
            else -> 2.dp
        },
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "RatingButtonElevation",
    )

    val buttonCornerRadius by animateDpAsState(
        targetValue = when {
            expanded -> 16.dp
            isHighlighted -> 15.dp
            else -> 14.dp
        },
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "RatingButtonCorners",
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            expanded ->
                colors.surfaceContainerHighest

            isHighlighted ->
                colors.surfaceContainerHighest

            else ->
                colors.surfaceContainerHigh
        },
        animationSpec = tween(140),
        label = "RatingButtonColor",
    )

    val buttonBorderColor by animateColorAsState(
        targetValue = when {
            expanded ->
                RatingGold.copy(alpha = 0.72f)

            isHighlighted && savedRating > 0 ->
                RatingGoldBright.copy(alpha = 0.56f)

            isHighlighted ->
                colors.onSurface.copy(alpha = 0.28f)

            savedRating > 0 ->
                RatingGold.copy(alpha = 0.34f)

            else ->
                colors.outlineVariant.copy(alpha = 0.65f)
        },
        animationSpec = tween(140),
        label = "RatingButtonBorder",
    )

    val popupPositionProvider = remember(popupGapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = (
                        anchorBounds.left +
                                anchorBounds.width / 2 -
                                popupContentSize.width / 2
                        ).coerceIn(
                        minimumValue = 0,
                        maximumValue = (
                                windowSize.width -
                                        popupContentSize.width
                                ).coerceAtLeast(0),
                    )

                val y = (
                        anchorBounds.top -
                                popupContentSize.height -
                                popupGapPx
                        ).coerceAtLeast(0)

                return IntOffset(x, y)
            }
        }
    }

    Box(
        modifier = modifier.size(buttonSize),
        contentAlignment = Alignment.Center,
    ) {

        if (expanded) {
            Popup(
                popupPositionProvider = popupPositionProvider,
                properties = PopupProperties(
                    focusable = false,
                ),
            ) {
                RatingPickerPopup(
                    rating = previewRating,
                    width = buttonSize,
                    starSize = starSize,
                    starSpacing = starSpacing,
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = buttonScale
                    scaleY = buttonScale
                    rotationZ = buttonRotation
                }
                .hoverable(interactionSource)
                .focusable(interactionSource = interactionSource)
                .pointerInput(currentRating) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                        )

                        previewRating = savedRating
                        expanded = true

                        try {
                            while (true) {
                                val event = awaitPointerEvent()

                                val change = event.changes
                                    .firstOrNull {
                                        it.id == down.id
                                    }
                                    ?: break

                                if (!change.pressed) {
                                    onStatusSelected(
                                        previewRating
                                    )
                                    break
                                }

                                val distanceUp = (
                                        down.position.y -
                                                change.position.y
                                        ).coerceAtLeast(0f)

                                /*
                                 * Small dead zone first.
                                 *
                                 * Then:
                                 *
                                 * 1
                                 * 2
                                 * 3
                                 * 4
                                 * 5
                                 */
                                previewRating =
                                    if (
                                        distanceUp <
                                        activationSlopPx
                                    ) {
                                        savedRating
                                    } else {
                                        (
                                                (
                                                        distanceUp -
                                                                activationSlopPx
                                                        ) /
                                                        ratingStepPx
                                                )
                                            .toInt()
                                            .plus(1)
                                            .coerceIn(1, 5)
                                    }

                                change.consume()
                            }
                        } finally {
                            // Also close if the gesture is cancelled or lost.
                            expanded = false
                        }
                    }
                },
            shape = RoundedCornerShape(
                buttonCornerRadius
            ),
            color = buttonColor,
            contentColor = colors.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = buttonElevation,
            border = BorderStroke(
                width = 1.dp,
                color = buttonBorderColor,
            ),
        ) {
            Box(
                contentAlignment = Alignment.Center,
            ) {
                val buttonHasRating = displayRating > 0

                val buttonIcon = when {
                    displayRating >= 5 ->
                        "mingcute:star-fill"
                    displayRating >= 3 ->
                        "mingcute:star-half-fill"
                    else ->
                        "mingcute:star-line"
                }

                val iconScale by animateFloatAsState(
                    targetValue = when {
                        expanded && displayRating > 0 -> 1.15f
                        isHovered -> 1.10f
                        isFocused -> 1.06f
                        else -> 1f
                    },
                    animationSpec = spring(
                        dampingRatio = 0.5f,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    label = "RatingButtonIconScale",
                )

                val iconRotation by animateFloatAsState(
                    targetValue = when {
                        expanded -> -7f
                        isHovered -> 6f
                        else -> 0f
                    },
                    animationSpec = spring(
                        dampingRatio = 0.48f,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    label = "RatingButtonIconRotation",
                )

                val iconTint by animateColorAsState(
                    targetValue = when {
                        buttonHasRating && isHighlighted -> RatingGoldBright
                        buttonHasRating -> RatingGold
                        isHighlighted -> colors.onSurface
                        else -> colors.onSurfaceVariant
                    },
                    animationSpec = tween(120),
                    label = "RatingButtonIconTint",
                )

                val haloColor by animateColorAsState(
                    targetValue = when {
                        expanded -> RatingGold.copy(alpha = 0.14f)
                        isHighlighted && buttonHasRating ->
                            RatingGold.copy(alpha = 0.10f)
                        isHighlighted ->
                            colors.onSurface.copy(alpha = 0.06f)
                        else -> Color.Transparent
                    },
                    animationSpec = tween(140),
                    label = "RatingButtonIconHalo",
                )

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(haloColor),
                )

                key(buttonIcon) {
                    IconifyIcon(
                        icon = buttonIcon,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                                rotationZ = iconRotation
                            },
                        tint = iconTint,
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingPickerPopup(
    rating: Int,
    width: Dp,
    starSize: Dp,
    starSpacing: Dp,
) {
    val colors = MaterialTheme.colorScheme

    var entered by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {
        entered = true
    }

    val popupScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.78f,
        animationSpec = spring(
            dampingRatio = 0.58f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "RatingPopupScale",
    )

    val popupAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(100),
        label = "RatingPopupAlpha",
    )

    /*
     * RatingPickerStar uses a 38.dp container.
     *
     * The layout needs to use that slot size rather than the
     * 28.dp icon size, otherwise the bottom star gets squeezed.
     */
    val starSlotSize = 38.dp

    val railHeight =
        starSlotSize * 5 +
                starSpacing * 4

    Surface(
        modifier = Modifier
            .width(width)
            .graphicsLayer {
                transformOrigin = TransformOrigin(
                    pivotFractionX = 0.5f,
                    pivotFractionY = 1f,
                )

                scaleX = popupScale
                scaleY = popupScale
                alpha = popupAlpha
            },
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerHigh,
        shadowElevation = 18.dp,
        border = BorderStroke(
            width = 1.dp,
            color = if (rating > 0) {
                RatingGold.copy(alpha = 0.28f)
            } else {
                colors.outlineVariant.copy(
                    alpha = 0.55f,
                )
            },
        ),
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = 5.dp,
                vertical = 12.dp,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(starSlotSize)
                    .height(railHeight),
                verticalArrangement =
                    Arrangement.spacedBy(starSpacing),
                horizontalAlignment =
                    Alignment.CenterHorizontally,
            ) {
                (5 downTo 1).forEach { star ->
                    RatingPickerStar(
                        number = star,
                        filled = star <= rating,
                        active =
                            star == rating &&
                                    rating > 0,
                        size = starSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingPickerStar(
    number: Int,
    filled: Boolean,
    active: Boolean,
    size: Dp,
) {
    val colors = MaterialTheme.colorScheme

    val starColor by animateColorAsState(
        targetValue = if (filled) {
            RatingGold
        } else {
            colors.onSurfaceVariant.copy(
                alpha = 0.38f
            )
        },
        animationSpec = tween(90),
        label = "RatingStarColor-$number",
    )

    /*
     * Newly selected star gets a little bounce.
     */
    val scale by animateFloatAsState(
        targetValue = when {
            active -> 1.28f
            filled -> 1.06f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = 0.42f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "RatingStarScale-$number",
    )

    val rotation by animateFloatAsState(
        targetValue = if (active) {
            -8f
        } else {
            0f
        },
        animationSpec = spring(
            dampingRatio = 0.45f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "RatingStarRotation-$number",
    )

    val haloColor by animateColorAsState(
        targetValue = if (active) {
            RatingGoldBright.copy(
                alpha = 0.18f
            )
        } else {
            Color.Transparent
        },
        animationSpec = tween(100),
        label = "RatingStarHalo-$number",
    )

    val iconName = if (filled) {
        "iconamoon:star-fill"
    } else {
        "iconamoon:star"
    }

    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(haloColor),
        contentAlignment = Alignment.Center,
    ) {
        /*
         * Include star number in the key because multiple
         * stars can use the same Iconify icon.
         */
        key(number, iconName) {
            IconifyIcon(
                icon = iconName,
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        rotationZ = rotation
                    },
                tint = starColor,
            )
        }
    }
}
