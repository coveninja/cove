package com.coveninja.cove.ui.components.insights

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.LocalMotionPolicy

/**
 * One headline number with its label — the row of these under the hero.
 *
 * The value is the largest thing in the tile and the caption the smallest, because the
 * whole row is meant to be skimmed at a glance and only the numbers are worth stopping on.
 * [detail] carries the qualifier that would otherwise be crammed into the caption ("longest
 * was 21"), and is allowed to be absent.
 *
 * Hovering lights the tile from behind its own icon rather than just outlining it: the glow
 * places the highlight where the eye already is, and on a four-tile row it makes the one
 * under the pointer unmistakable without moving anything around it. Hover is a desktop-only
 * nicety here — nothing is revealed by it, so a phone loses nothing.
 */
@Composable
internal fun StatTile(
    icon: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tone: Color = MaterialTheme.colorScheme.tertiary,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val active = hovered && !reducedMotion

    val restingBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val lift by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "StatTileLift",
    )

    Surface(
        // fillMaxHeight so a row measured to its tallest tile hands that height down; on its
        // own the tile is content-sized and the shortest one would sit proud of the rest.
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .hoverable(interaction)
            .graphicsLayer {
                scaleX = 1f + 0.018f * lift
                scaleY = 1f + 0.018f * lift
                translationY = -3f * lift
            },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, lerp(restingBorder, tone.copy(alpha = 0.5f), lift)),
    ) {
        Column(
            modifier = Modifier
                .drawBehind {
                    if (lift <= 0f) return@drawBehind
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(tone.copy(alpha = 0.16f * lift), Color.Transparent),
                            center = Offset(size.width * 0.16f, size.height * 0.22f),
                            radius = size.maxDimension * 0.9f,
                        ),
                    )
                }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            scaleX = 1f + 0.12f * lift
                            scaleY = 1f + 0.12f * lift
                        }
                        .background(
                            tone.copy(alpha = 0.14f + 0.14f * lift),
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    IconifyIcon(icon = icon, modifier = Modifier.size(13.dp), tint = tone)
                }
                Text(
                    text = caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            detail?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
