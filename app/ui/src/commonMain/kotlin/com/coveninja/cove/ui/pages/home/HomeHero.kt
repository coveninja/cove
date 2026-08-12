package com.coveninja.cove.ui.pages.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.tmdbImageSize
import com.coveninja.cove.ui.platform.hasPointerHover

/**
 * The one title Home leads with.
 *
 * Deliberately *not* a carousel. Explore already rotates through five picks; Home's claim is
 * that it knows which single thing you want, and a hero that slides away mid-read undercuts
 * that. What changes instead is the framing — the kicker, the button and the progress bar all
 * follow [HomeHero.kind], so the same block reads as "carry on with this", "this has new
 * episodes" or "look at this" without becoming three separate layouts.
 */
@Composable
fun HomeHeroBlock(
    hero: HomeHero,
    art: Media,
    inList: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onToggleList: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Vertical scroll offset in pixels, read inside a graphics layer so the parallax costs a
     * redraw rather than a recomposition per frame.
     */
    scrollOffset: () -> Float = { 0f },
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compact = maxWidth < COMPACT_HERO_WIDTH
        // On desktop this is taller than it looks because the top runs under the floating
        // nav bar; mobile shows the same cinematic crop with its bar at the bottom.
        val height = if (compact) 460.dp else 560.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .hoverable(hover),
        ) {
            // Crossfades on both counts that matter: a different title taking the lead, and
            // the enriched artwork arriving for the current one. Both are rare, and both look
            // better dissolved than cut.
            AnimatedContent(
                targetState = art.backdropUrl ?: art.posterUrl,
                transitionSpec = { fadeIn(tween(420)) togetherWith fadeOut(tween(420)) },
                label = "HomeHeroBackdrop",
            ) { image ->
                // A very slow push keeps a still frame from reading as a stalled image while
                // everything around it animates.
                val drift = rememberInfiniteTransition(label = "HomeHeroDrift")
                val zoom by drift.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 20_000, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "HomeHeroZoom",
                )

                AsyncImage(
                    model = tmdbImageSize(image, "w1280"),
                    contentDescription = "${hero.media.title ?: hero.media.name} backdrop",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            transformOrigin = TransformOrigin(0.5f, 0.35f)
                            // Half-speed drift against the page: the hero appears to sit
                            // behind the rails rather than on the same plane as them.
                            translationY = scrollOffset() * PARALLAX_FACTOR
                        },
                    contentScale = ContentScale.Crop,
                )
            }

            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.94f),
                            Color.Black.copy(alpha = 0.60f),
                            Color.Transparent,
                        ),
                    ),
                ),
            )
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.42f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
                ),
            )

            HeroCopy(
                hero = hero,
                art = art,
                compact = compact,
                hovered = hovered,
                inList = inList,
                onPlay = onPlay,
                onOpen = onOpen,
                onToggleList = onToggleList,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun HeroCopy(
    hero: HomeHero,
    art: Media,
    compact: Boolean,
    hovered: Boolean,
    inList: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onToggleList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 620.dp)
            .padding(
                start = if (compact) 24.dp else 48.dp,
                end = 24.dp,
                bottom = if (compact) 28.dp else 40.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconifyIcon(
                icon = hero.kind.icon,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = hero.kind.kicker,
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (art.logoUrl != null) {
            AsyncImage(
                model = tmdbImageSize(art.logoUrl, "w500"),
                contentDescription = "${art.title ?: art.name} logo",
                modifier = Modifier.width(if (compact) 200.dp else 280.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
            )
        } else {
            Text(
                text = art.title ?: art.name ?: hero.media.title ?: "Untitled",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        hero.caption.takeIf { it.isNotBlank() }?.let { caption ->
            Text(
                text = caption,
                color = Color.White.copy(alpha = 0.74f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        hero.watchFraction?.let { fraction -> HeroProgress(fraction) }

        // The overview belongs to a title being *offered*. On something already part-watched
        // the viewer knows the plot, and three lines of synopsis merely pushes Resume down.
        if (hero.kind == HomeHeroKind.Spotlight) {
            art.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    color = Color.White.copy(alpha = 0.84f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hero.playable) {
                HeroButton(
                    label = hero.playLabel,
                    iconName = "lucide:play",
                    primary = true,
                    lifted = hovered,
                    onClick = onPlay,
                )
                HeroButton(
                    label = "Details",
                    iconName = "lucide:info",
                    primary = false,
                    lifted = hovered,
                    onClick = onOpen,
                )
            } else {
                HeroButton(
                    label = "Details",
                    iconName = "lucide:info",
                    primary = true,
                    lifted = hovered,
                    onClick = onOpen,
                )
                HeroButton(
                    label = if (inList) "In My List" else "My List",
                    iconName = if (inList) "lucide:bookmark-check" else "lucide:bookmark-plus",
                    primary = false,
                    lifted = hovered,
                    onClick = onToggleList,
                )
            }
        }
    }
}

@Composable
private fun HeroProgress(fraction: Float) {
    val grown = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        grown.animateTo(
            targetValue = fraction.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        )
    }

    Box(
        modifier = Modifier
            .width(260.dp)
            .height(4.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.22f)),
    ) {
        // scaleX rather than an animated width: no relayout per frame, and it matches how
        // the cards and the details sheet draw their bars.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(pivotFractionX = 0f, pivotFractionY = 0.5f)
                    scaleX = grown.value
                }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}

@Composable
private fun HeroButton(
    label: String,
    iconName: String,
    primary: Boolean,
    lifted: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (hovered) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "HomeHeroButtonScale",
    )
    // The whole row rises a touch when the hero is hovered, before either button is pointed
    // at — it reads as the hero offering them rather than as a hover state.
    val lift by animateDpAsState(
        targetValue = if (lifted && hasPointerHover) 2.dp else 0.dp,
        animationSpec = tween(180),
        label = "HomeHeroButtonLift",
    )

    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .padding(bottom = lift)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .clip(CircleShape)
            .background(if (primary) colors.tertiary else Color.White.copy(alpha = 0.16f))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val contentColor = if (primary) colors.onTertiary else Color.White
        IconifyIcon(icon = iconName, modifier = Modifier.size(16.dp), tint = contentColor)
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Fraction of the page's scroll the backdrop moves by. */
private const val PARALLAX_FACTOR = 0.35f

/** Under this the copy column and the hero's height both need to come in. */
private val COMPACT_HERO_WIDTH = 720.dp
