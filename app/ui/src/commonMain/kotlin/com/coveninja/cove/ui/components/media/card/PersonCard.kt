package com.coveninja.cove.ui.components.media.card

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.coveninja.cove.ui.model.Person
import com.coveninja.cove.ui.model.tmdbImageSize

/**
 * One face in a cast row.
 *
 * Nothing is revealed on hover — the name and the billing are always drawn — so the card
 * says the same thing to a finger as it does to a mouse.
 */
@Composable
fun PersonCard(
    person: Person,
    modifier: Modifier = Modifier,
    /** Where the shared-element hook goes when this card opens the person sheet. */
    avatarModifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val avatarScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.96f
            isHovered -> 1.035f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "PersonAvatarScale",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isHovered) {
            colors.surfaceContainerHighest.copy(alpha = 0.72f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(140),
        label = "PersonCardContainer",
    )
    val ringColor by animateColorAsState(
        targetValue = if (isHovered) {
            colors.tertiary
        } else {
            colors.outlineVariant.copy(alpha = 0.55f)
        },
        animationSpec = tween(140),
        label = "PersonAvatarRing",
    )
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .width(112.dp)
            .hoverable(interactionSource)
            .then(clickModifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(containerColor)
                .padding(horizontal = 6.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(86.dp)
                    .graphicsLayer {
                        scaleX = avatarScale
                        scaleY = avatarScale
                    }
                    .then(avatarModifier)
                    .clip(CircleShape)
                    .background(ringColor)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (person.profileUrl != null) {
                    AsyncImage(
                        model = tmdbImageSize(person.profileUrl, "w185"),
                        contentDescription = person.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                    )
                } else {
                    Text(
                        text = person.initial,
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Text(
                text = person.name,
                modifier = Modifier.padding(top = 8.dp),
                color = colors.onSurface,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            person.role?.let { role ->
                Text(
                    text = role,
                    modifier = Modifier.padding(top = 2.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
