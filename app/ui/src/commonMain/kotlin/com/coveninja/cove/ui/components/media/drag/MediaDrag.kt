
package com.coveninja.cove.ui.components.media.drag

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.icons.IconifyIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

data class MediaDragPayload(
    val mediaId: String,
    val posterUrl: String?,
    val title: String?,
    val mediaType: String,
    val rating: Double?,
    val sourceSize: Size,
    val grabFraction: Offset,
)

@Composable
fun MediaDragPreview(
    media: MediaDragPayload,
    positionInRoot: Offset,
    hoveredCategory: MyListCategory?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(10.dp)

    val sourceWidth = with(density) {
        media.sourceSize.width.toDp()
    }

    val sourceHeight = with(density) {
        media.sourceSize.height.toDp()
    }

    val targetWidthPx = with(density) {
        128.dp.toPx()
    }

    val targetScale = (
            targetWidthPx / media.sourceSize.width
            ).coerceIn(
            minimumValue = 0.1f,
            maximumValue = 1f,
        )

    val pickupScale = remember(media) {
        Animatable(1f)
    }

    var previousPosition by remember(media) {
        mutableStateOf(positionInRoot)
    }

    var targetRotationX by remember(media) {
        mutableFloatStateOf(0f)
    }

    var targetRotationY by remember(media) {
        mutableFloatStateOf(0f)
    }

    var targetRotationZ by remember(media) {
        mutableFloatStateOf(0f)
    }

    LaunchedEffect(positionInRoot) {
        val deltaX = positionInRoot.x - previousPosition.x
        val deltaY = positionInRoot.y - previousPosition.y

        previousPosition = positionInRoot

        // Convert pixels to dp so sensitivity remains similar across densities.
        val deltaXDp = with(density) {
            deltaX.toDp().value
        }

        val deltaYDp = with(density) {
            deltaY.toDp().value
        }

        /*
         * Z creates the main swinging motion.
         * Y adds horizontal perspective.
         * X responds to vertical movement.
         */
        targetRotationZ = (deltaXDp)
            .coerceIn(-90f, 90f)

        targetRotationY = (deltaXDp * 0.18f)
            .coerceIn(-90f, 90f)

        targetRotationX = (deltaYDp)
            .coerceIn(-90f, 90f)
        /*
         * This effect is restarted while the pointer moves.
         * When movement stops, it reaches this delay and returns to neutral.
         */
        delay(55.milliseconds)

        targetRotationX = 0f
        targetRotationY = 0f
        targetRotationZ = 0f
    }

    val motionRotationX by animateFloatAsState(
        targetValue = targetRotationX,
        animationSpec = spring(
            dampingRatio = 0.68f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "DragPreviewRotationX",
    )

    val motionRotationY by animateFloatAsState(
        targetValue = targetRotationY,
        animationSpec = spring(
            dampingRatio = 0.68f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "DragPreviewRotationY",
    )

    val motionRotationZ by animateFloatAsState(
        targetValue = targetRotationZ,
        animationSpec = spring(
            dampingRatio = 0.62f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "DragPreviewRotationZ",
    )


    LaunchedEffect(media) {
        pickupScale.snapTo(1f)
        launch {
            pickupScale.animateTo(
                targetValue = targetScale,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    val borderColor by animateColorAsState(
        targetValue = hoveredCategory?.accentColor
            ?: MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 140),
        label = "DragPreviewBorderColor",
    )

    Box(
        modifier = modifier
            /*
             * Positions the original full-sized card so the exact point
             * the user grabbed remains underneath the pointer.
             */
            .offset {
                IntOffset(
                    x = (
                            positionInRoot.x -
                                    media.sourceSize.width *
                                    media.grabFraction.x
                            ).roundToInt(),
                    y = (
                            positionInRoot.y -
                                    media.sourceSize.height *
                                    media.grabFraction.y
                            ).roundToInt(),
                )
            }
            .width(sourceWidth)
            .height(sourceHeight)
            .graphicsLayer {
                transformOrigin = TransformOrigin(
                    pivotFractionX = media.grabFraction.x,
                    pivotFractionY = media.grabFraction.y,
                )

                scaleX = pickupScale.value
                scaleY = pickupScale.value

                rotationX = motionRotationX
                rotationY = motionRotationY
                rotationZ = motionRotationZ

                alpha = 0.92f

                /*
                 * Larger values reduce perspective distortion.
                 * The value can be increased further for a flatter effect.
                 */
                cameraDistance = 18.dp.toPx()
            }
            .shadow(
                elevation = 18.dp,
                shape = shape,
            )
            .clip(shape)
            .border(
                width = 2.dp,
                color = borderColor,
                shape = shape,
            ),
    ) {
        CoveAsyncImage(
            model = media.posterUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .clip(shape),
            contentScale = ContentScale.Crop,
        )
        AnimatedContent(
            targetState = hoveredCategory,
            modifier = Modifier.align(Alignment.Center),
            transitionSpec = {
                (
                        fadeIn(tween(140)) +
                                scaleIn(
                                    initialScale = 0.75f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                        ) togetherWith (
                        fadeOut(tween(100)) +
                                scaleOut(
                                    targetScale = 0.75f,
                                    animationSpec = tween(120),
                                )
                        )
            },
            contentKey = { category ->
                category?.name ?: "none"
            },
            label = "DragCategoryIcon",
        ) { category ->
            if (category != null) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            category.accentColor.copy(alpha = 0.92f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    key(category.icon) {
                        IconifyIcon(
                            icon = category.icon,
                            modifier = Modifier.size(32.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
        hoveredCategory?.let { category ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        color = category.accentColor.copy(alpha = 0.94f)
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = category.label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}
