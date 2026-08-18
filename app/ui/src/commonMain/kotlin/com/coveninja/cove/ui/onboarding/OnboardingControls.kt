package com.coveninja.cove.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.platform.hasPointerHover
import com.coveninja.cove.ui.state.LocalMotionPolicy

/**
 * The onboarding flow's own controls.
 *
 * Separate from the settings primitives on purpose. A settings row is a dense, uniform thing
 * you scan; a first-run control is a large, single target you press once, and the two want
 * genuinely different sizes and different feedback. What they do share is the palette and the
 * reduced-motion policy, so nothing here invents a colour or animates unconditionally.
 */

/** How hard a control reacts to a press. Shared so every button in the flow feels alike. */
private const val PRESS_SCALE = 0.96f
private const val HOVER_SCALE = 1.03f

@Composable
internal fun OnboardingPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val active = enabled && !busy

    val scale by animateFloatAsState(
        targetValue = when {
            !active -> 1f
            pressed -> PRESS_SCALE
            hovered -> HOVER_SCALE
            else -> 1f
        },
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.55f),
        label = "OnboardingPrimaryScale",
    )

    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = if (hasPointerHover) 46.dp else 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                CoveColors.Brand.Accent.copy(alpha = if (active) 1f else 0.4f),
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = active,
                onClick = onClick,
            )
            .padding(horizontal = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(17.dp),
                color = CoveColors.Brand.OnAccent,
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = label,
            color = CoveColors.Brand.OnAccent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        if (!busy && icon != null) {
            IconifyIcon(
                icon = icon,
                tint = CoveColors.Brand.OnAccent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The quiet counterpart: Back, and the flow-level "Skip setup". */
@Composable
internal fun OnboardingGhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val content by animateColorAsState(
        targetValue = if (hovered) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(140),
        label = "OnboardingGhostContent",
    )

    Row(
        modifier = modifier
            .heightIn(min = if (hasPointerHover) 42.dp else 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (hovered) 0.09f else 0.04f),
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            IconifyIcon(icon = it, tint = content, modifier = Modifier.size(16.dp))
        }
        Text(text = label, color = content, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A genre bubble.
 *
 * Selecting one is the most-repeated gesture in the whole flow — someone picks four or five in
 * a row — so it is worth the extra feedback: the fill springs in, the bubble overshoots its
 * size and settles, and a check swaps in for the genre's own glyph. The overshoot is what makes
 * a grid of these feel like buttons that pop rather than checkboxes that tick.
 */
@Composable
internal fun OnboardingGenreBubble(
    genre: OnboardingGenre,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion

    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> PRESS_SCALE
            selected -> 1.05f
            hovered -> 1.02f
            else -> 1f
        },
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "OnboardingBubbleScale",
    )
    val container by animateColorAsState(
        targetValue = when {
            selected -> CoveColors.Brand.Accent
            hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = if (reducedMotion) snap() else tween(180),
        label = "OnboardingBubbleContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            CoveColors.Brand.OnAccent
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = if (reducedMotion) snap() else tween(180),
        label = "OnboardingBubbleContent",
    )

    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = if (hasPointerHover) 44.dp else 50.dp)
            .clip(CircleShape)
            .background(container)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                if (reducedMotion) {
                    fadeIn(snap()) togetherWith fadeOut(snap())
                } else {
                    (scaleIn(spring(dampingRatio = 0.45f)) + fadeIn(tween(120)))
                        .togetherWith(scaleOut(tween(90)) + fadeOut(tween(90)))
                }
            },
            label = "OnboardingBubbleGlyph",
        ) { isSelected ->
            IconifyIcon(
                icon = if (isSelected) "lucide:check" else genre.icon,
                tint = content,
                modifier = Modifier.size(17.dp),
            )
        }
        Text(
            text = genre.label,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * One poster on the taste wall.
 *
 * Picking a title pops it — a quick overshoot, an accent ring, and a heart badge that scales in
 * from nothing at the corner. The ring rather than a dimming scrim, because the point of the
 * wall is the artwork and covering it to say "chosen" fights the thing being chosen.
 */
@Composable
internal fun OnboardingPosterTile(
    media: Media,
    picked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val shape = RoundedCornerShape(14.dp)

    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.94f
            picked -> 1.04f
            hovered -> 1.05f
            else -> 1f
        },
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "OnboardingPosterScale",
    )
    val ring by animateFloatAsState(
        targetValue = if (picked) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(180),
        label = "OnboardingPosterRing",
    )

    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .border(
                width = 2.5.dp,
                color = CoveColors.Brand.Accent.copy(alpha = ring),
                shape = shape,
            ),
    ) {
        val poster = media.posterUrl
        if (poster.isNullOrBlank()) {
            Text(
                text = media.title ?: media.name.orEmpty(),
                modifier = Modifier.align(Alignment.Center).padding(10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            CoveAsyncImage(
                model = poster,
                contentDescription = media.title ?: media.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = picked,
            enter = if (reducedMotion) {
                fadeIn(snap())
            } else {
                scaleIn(spring(dampingRatio = 0.4f)) + fadeIn(tween(120))
            },
            exit = if (reducedMotion) fadeOut(snap()) else scaleOut(tween(110)) + fadeOut(tween(110)),
            modifier = Modifier.align(Alignment.TopEnd).padding(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(CoveColors.Brand.Accent),
                contentAlignment = Alignment.Center,
            ) {
                IconifyIcon(
                    icon = "lucide:heart",
                    tint = CoveColors.Brand.OnAccent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * A preference, as a full-width pressable card rather than a row with a switch on the end.
 *
 * The whole card is the target: on a phone a 40 dp switch at the far right of a 360 dp row is a
 * needlessly precise thing to ask of someone in their first minute with the app. The track
 * still renders as a switch, because that is what says "this is a two-state choice".
 */
@Composable
internal fun OnboardingToggleCard(
    icon: String,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val shape = RoundedCornerShape(16.dp)

    val container by animateColorAsState(
        targetValue = when {
            checked -> CoveColors.Brand.AccentContainer.copy(alpha = 0.55f)
            hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
            else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)
        },
        animationSpec = if (reducedMotion) snap() else tween(180),
        label = "OnboardingToggleContainer",
    )
    val accent by animateColorAsState(
        targetValue = if (checked) {
            CoveColors.Brand.Accent
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = if (reducedMotion) snap() else tween(180),
        label = "OnboardingToggleAccent",
    )
    // The glyph tips over as it lights up. Small enough to register as liveliness rather than
    // as the icon spinning, which is what a full rotation reads as at this size.
    val tilt by animateFloatAsState(
        targetValue = if (checked) -12f else 0f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.38f),
        label = "OnboardingToggleTilt",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .border(
                width = 1.dp,
                color = if (checked) {
                    CoveColors.Brand.Accent.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                },
                shape = shape,
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { onCheckedChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = icon,
                tint = accent,
                modifier = Modifier.size(19.dp).graphicsLayer { rotationZ = tilt },
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OnboardingSwitchTrack(checked = checked, accent = accent)
    }
}

/**
 * The switch, drawn rather than taken from Material.
 *
 * `Switch` brings its own colour roles, its own ripple and its own minimum touch target, all of
 * which fight a card that is already the touch target. Two boxes and an animated offset is the
 * whole widget.
 */
@Composable
private fun OnboardingSwitchTrack(checked: Boolean, accent: Color) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    // Animated in dp rather than as a fraction fed to graphicsLayer: a translation is in raw
    // pixels, so a hardcoded one is only correct at a single density and drifts off the track
    // on every phone that is not it.
    val travel by animateDpAsState(
        targetValue = if (checked) TrackTravel else 0.dp,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.62f),
        label = "OnboardingSwitchTravel",
    )

    Box(
        modifier = Modifier
            .width(TrackWidth)
            .height(TrackHeight)
            .clip(CircleShape)
            .background(
                if (checked) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
            )
            .padding(TrackInset),
    ) {
        Box(
            modifier = Modifier
                .size(KnobSize)
                .offset(x = travel)
                .clip(CircleShape)
                .background(
                    if (checked) CoveColors.Brand.OnAccent else MaterialTheme.colorScheme.onSurface,
                ),
        )
    }
}

private val TrackWidth = 46.dp
private val TrackHeight = 27.dp
private val TrackInset = 3.dp
private val KnobSize = 21.dp

/** What is left of the track once the inset and the knob have taken their share. */
private val TrackTravel = TrackWidth - TrackInset * 2 - KnobSize

/** A counter that animates to its value, matching the stat chips on Home. */
@Composable
internal fun OnboardingCountBadge(
    icon: String,
    count: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val animated by animateFloatAsState(
        targetValue = count.toFloat(),
        animationSpec = if (reducedMotion) snap() else tween(520),
        label = "OnboardingCount",
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(
            icon = icon,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = "${animated.toInt()}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/** Vertical rhythm shared by every step body, so the seven screens sit on one grid. */
internal val StepGap = 18.dp
