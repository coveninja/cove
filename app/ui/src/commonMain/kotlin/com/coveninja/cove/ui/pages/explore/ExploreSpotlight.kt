package com.coveninja.cove.ui.pages.explore

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlin.math.roundToInt

/**
 * The rotating hero at the top of Explore.
 *
 * Advances itself every [SPOTLIGHT_DWELL_MILLIS] and shows how long is left as a row of
 * ticks, so the movement is announced rather than surprising. Hovering freezes it — the
 * one thing worse than a carousel is a carousel that slides away mid-read — and resumes
 * from where it stopped rather than restarting the countdown.
 *
 * On a touch screen nothing ever hovers, so the ticks double as the manual control: they
 * are tappable targets, not decoration.
 */
@Composable
fun ExploreSpotlight(
    picks: List<Media>,
    inList: (Media) -> Boolean,
    onOpen: (Media) -> Unit,
    onToggleList: (Media) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Vertical scroll offset in pixels, read inside a graphics layer so the parallax
     * costs a redraw rather than a recomposition per frame.
     */
    scrollOffset: () -> Float = { 0f },
) {
    if (picks.isEmpty()) return

    var index by remember(picks.size) { mutableStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val current = picks[index.coerceIn(picks.indices)]

    // Restarting the countdown belongs to a change of slide, not to a pause — split so
    // that un-hovering carries on from where hovering stopped.
    LaunchedEffect(index) { progress.snapTo(0f) }
    LaunchedEffect(index, paused, picks.size) {
        if (paused || picks.size <= 1) return@LaunchedEffect
        val remaining = ((1f - progress.value) * SPOTLIGHT_DWELL_MILLIS).roundToInt()
        if (remaining > 0) {
            progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        }
        index = (index + 1) % picks.size
    }

    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) { paused = hovered }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compact = maxWidth < COMPACT_SPOTLIGHT_WIDTH
        // Sized to match Home's featured block. On desktop its top runs under the floating
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
            AnimatedContent(
                targetState = current.id,
                transitionSpec = { fadeIn(tween(420)) togetherWith fadeOut(tween(420)) },
                label = "SpotlightBackdrop",
            ) { id ->
                val slide = picks.firstOrNull { it.id == id } ?: return@AnimatedContent

                // A very slow push keeps a still frame from reading as a stalled image
                // while everything around it animates.
                val drift = rememberInfiniteTransition(label = "SpotlightDrift")
                val zoom by drift.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 20_000, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "SpotlightZoom",
                )

                AsyncImage(
                    model = tmdbImageSize(slide.backdropUrl, "w1280"),
                    contentDescription = "${slide.title ?: slide.name} backdrop",
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

            AnimatedContent(
                targetState = current.id,
                modifier = Modifier.align(Alignment.BottomStart),
                transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(220)) },
                label = "SpotlightCopy",
            ) { id ->
                val slide = picks.firstOrNull { it.id == id } ?: return@AnimatedContent
                SpotlightCopy(
                    media = slide,
                    compact = compact,
                    inList = inList(slide),
                    hovered = hovered,
                    onOpen = { onOpen(slide) },
                    onToggleList = { onToggleList(slide) },
                )
            }

            if (picks.size > 1) {
                SpotlightTicks(
                    count = picks.size,
                    current = index,
                    progress = { progress.value },
                    onSelect = { index = it },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 32.dp, bottom = 28.dp),
                )
            }
        }
    }
}

@Composable
private fun SpotlightCopy(
    media: Media,
    compact: Boolean,
    inList: Boolean,
    hovered: Boolean,
    onOpen: () -> Unit,
    onToggleList: () -> Unit,
) {
    Column(
        modifier = Modifier
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
                icon = "lucide:sparkles",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = "In the spotlight",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (media.logoUrl != null) {
            AsyncImage(
                model = tmdbImageSize(media.logoUrl, "w500"),
                contentDescription = "${media.title ?: media.name} logo",
                modifier = Modifier.width(if (compact) 200.dp else 280.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
            )
        } else {
            Text(
                text = media.title ?: media.name ?: "Untitled",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        SpotlightMeta(media)

        media.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            Text(
                text = overview,
                color = Color.White.copy(alpha = 0.84f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SpotlightButton(
                label = "Details",
                iconName = "lucide:info",
                primary = true,
                lifted = hovered,
                onClick = onOpen,
            )
            SpotlightButton(
                label = if (inList) "In My List" else "My List",
                iconName = if (inList) "lucide:bookmark-check" else "lucide:bookmark-plus",
                primary = false,
                lifted = hovered,
                onClick = onToggleList,
            )
        }
    }
}

@Composable
private fun SpotlightMeta(media: Media) {
    val parts = buildList {
        media.released?.takeIf { it.isNotBlank() }?.let(::add)
        media.type?.label?.let(::add)
        media.rating?.let { add("★ ${((it * 10).roundToInt() / 10.0)}") }
        addAll(media.genres.take(2))
    }
    if (parts.isEmpty()) return

    Text(
        text = parts.joinToString("  ·  "),
        color = Color.White.copy(alpha = 0.72f),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SpotlightButton(
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
        label = "SpotlightButtonScale",
    )
    // The whole pair rises a touch when the hero is hovered, before either button is
    // pointed at — it reads as the hero offering them rather than as a hover state.
    val lift by animateDpAsState(
        targetValue = if (lifted && hasPointerHover) 2.dp else 0.dp,
        animationSpec = tween(180),
        label = "SpotlightButtonLift",
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

/**
 * One tick per slide; the current one fills as its dwell elapses.
 *
 * [progress] is a lambda so the fill is read at draw time. Reading the Animatable's value
 * directly here would recompose the whole row on every animation frame for a bar that only
 * ever changes width.
 */
@Composable
private fun SpotlightTicks(
    count: Int,
    current: Int,
    progress: () -> Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { position ->
            val active = position == current
            val width by animateDpAsState(
                targetValue = if (active) 30.dp else 10.dp,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                label = "SpotlightTickWidth",
            )
            Box(
                modifier = Modifier
                    // The visible tick is slim, but the tappable box around it is a full
                    // finger target — on a phone these are the only way to change slide.
                    .size(width = width.coerceAtLeast(24.dp), height = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(position) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(width)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.30f)),
                ) {
                    if (active) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                    scaleX = progress()
                                }
                                .clip(CircleShape)
                                .background(Color.White),
                        )
                    }
                }
            }
        }
    }
}

/** How long each slide holds before the next one takes over. */
private const val SPOTLIGHT_DWELL_MILLIS = 8_000

/** Fraction of the page's scroll the backdrop moves by. */
private const val PARALLAX_FACTOR = 0.35f

/** Under this the copy column and the hero's height both need to come in. */
private val COMPACT_SPOTLIGHT_WIDTH = 720.dp
