package com.coveninja.cove.ui.components.player

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.MediaEpisode
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.ChoicePillRow

/**
 * Everything the episode picker needs, gathered by the layer so these controls do
 * no fetching of their own.
 */
data class EpisodeBrowser(
    val seasons: List<Int>,
    val playingSeason: Int,
    val playingEpisode: Int,
    val browsingSeason: Int,
    val episodes: List<MediaEpisode>,
    /** 0..1 through the episode being played, for the bar on its row. */
    val playingProgress: Float,
    val onBrowseSeason: (Int) -> Unit,
    val onPlayEpisode: (season: Int, episode: MediaEpisode) -> Unit,
)

/**
 * Which episode is playing, and a way to move to another.
 *
 * The button itself carries the current position, so the answer to "which one am
 * I on" needs no click.
 */
@Composable
internal fun EpisodeMenuButton(
    browser: EpisodeBrowser,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Box {
        LabelledControlButton(
            icon = "lucide:layout-grid",
            label = "S${browser.playingSeason}E${browser.playingEpisode}",
            active = expanded,
            onClick = { onExpandedChange(true) },
        )
        // Sized against the window rather than to fixed numbers. The picker is
        // opened deliberately and closed again, so it can afford most of the
        // height; the caps stop it becoming absurd on a very large display, and
        // the fractions stop it overflowing a small one.
        val window = LocalWindowInfo.current.containerSize
        val density = LocalDensity.current
        val menuWidth = with(density) { (window.width * 0.42f).toDp() }
            .coerceIn(440.dp, 720.dp)
        val menuHeight = with(density) { (window.height * 0.78f).toDp() }
            .coerceIn(360.dp, 900.dp)

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.width(menuWidth).heightIn(max = menuHeight),
        ) {
            if (browser.seasons.size > 1) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    ChoicePillRow {
                        browser.seasons.forEach { season ->
                            ChoicePill(
                                label = "S$season",
                                selected = season == browser.browsingSeason,
                                onClick = { browser.onBrowseSeason(season) },
                            )
                        }
                    }
                }
            }

            if (browser.episodes.isEmpty()) {
                Text(
                    text = "Loading episodes…",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            browser.episodes.forEach { episode ->
                val isPlaying = browser.browsingSeason == browser.playingSeason &&
                    episode.number == browser.playingEpisode
                EpisodeRow(
                    episode = episode,
                    playing = isPlaying,
                    progress = if (isPlaying) browser.playingProgress else null,
                    onClick = {
                        onExpandedChange(false)
                        // Re-selecting what is already playing would restart it
                        // from the resume point, which is not what picking your
                        // current episode out of a list means.
                        if (!isPlaying) browser.onPlayEpisode(browser.browsingSeason, episode)
                    },
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: MediaEpisode,
    playing: Boolean,
    progress: Float?,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val container by animateColorAsState(
        targetValue = when {
            playing -> colors.tertiary.copy(alpha = 0.14f)
            hovered -> colors.onSurface.copy(alpha = 0.07f)
            else -> Color.Transparent
        },
        animationSpec = tween(140),
        label = "EpisodeRowContainer",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EpisodeThumbnail(episode = episode, playing = playing)

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${episode.number}. ${episode.title}",
                    modifier = Modifier.weight(1f, fill = false),
                    // Watched episodes step back rather than disappear: the point
                    // is to find the one you have not seen, not to hide history.
                    color = if (episode.watched && !playing) {
                        colors.onSurfaceVariant
                    } else {
                        colors.onSurface
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episode.watched) {
                    IconifyIcon(
                        icon = "lucide:check",
                        modifier = Modifier.padding(start = 6.dp).size(13.dp),
                        tint = colors.tertiary,
                    )
                }
            }

            episode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    modifier = Modifier.padding(top = 3.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Only the episode actually playing has a live position to show.
            progress?.let { fraction ->
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.onSurface.copy(alpha = 0.18f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .fillMaxSize()
                            .background(colors.tertiary),
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeThumbnail(episode: MediaEpisode, playing: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(width = 104.dp, height = 59.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (episode.stillUrl != null) {
            AsyncImage(
                model = episode.stillUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    // Dimmed once seen, so the unwatched ones carry the eye.
                    .graphicsLayer { alpha = if (episode.watched && !playing) 0.45f else 1f },
                contentScale = ContentScale.Crop,
            )
        } else {
            IconifyIcon(
                icon = "lucide:film",
                modifier = Modifier.size(18.dp),
                tint = colors.onSurfaceVariant,
            )
        }

        if (playing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(colors.tertiary),
                    contentAlignment = Alignment.Center,
                ) {
                    IconifyIcon(
                        icon = "lucide:play",
                        modifier = Modifier.size(11.dp),
                        tint = colors.onTertiary,
                    )
                }
            }
        }
    }
}

/** A control button that also carries a short label, for the episode position. */
@Composable
private fun LabelledControlButton(
    icon: String,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else if (hovered) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "EpisodeButtonScale",
    )
    val container by animateColorAsState(
        targetValue = when {
            active -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.24f)
            hovered -> Color.White.copy(alpha = 0.2f)
            else -> Color.White.copy(alpha = 0.1f)
        },
        animationSpec = tween(140),
        label = "EpisodeButtonContainer",
    )

    Row(
        modifier = Modifier
            .height(38.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(19.dp))
            .background(container)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        IconifyIcon(
            icon = icon,
            modifier = Modifier.size(16.dp),
            tint = if (active) MaterialTheme.colorScheme.tertiary else Color.White,
        )
        Text(
            text = label,
            color = if (active) MaterialTheme.colorScheme.tertiary else Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
