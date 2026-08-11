package com.coveninja.cove.ui.pages.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon
import kotlin.math.roundToInt

/**
 * The band under the hero: who is watching, and what is waiting for them.
 *
 * Its job is to make Home feel *owned* — the hero above it could belong to anybody, and the
 * rails below it are just content. This is the only part of the page that talks to the
 * viewer. It costs nothing to produce: every number here is already in memory.
 */
@Composable
fun HomeGreeting(
    greeting: String,
    stats: HomeStats,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val stacked = maxWidth < COMPACT_GREETING_WIDTH

        val heading: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = greeting,
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitleFor(stats),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val chips: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (stats.watching > 0) {
                    StatChip("lucide:play-circle", stats.watching, "on the go")
                }
                if (stats.episodesWaiting > 0) {
                    StatChip("lucide:tv", stats.episodesWaiting, "waiting")
                }
                if (stats.watchLater > 0) {
                    StatChip("lucide:bookmark-check", stats.watchLater, "saved")
                }
            }
        }

        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                heading()
                if (!stats.isEmpty) chips()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) { heading() }
                if (!stats.isEmpty) chips()
            }
        }
    }
}

/**
 * The line that changes with the state of the library.
 *
 * An empty library gets an invitation rather than a report of zeroes: on a first run this is
 * the first sentence the app ever says, and "0 titles" is a poor opening.
 */
private fun subtitleFor(stats: HomeStats): String = when {
    stats.isEmpty -> "Find something worth an evening"
    stats.episodesWaiting > 0 -> "There are new episodes waiting for you"
    stats.watching > 0 -> "Here's where you left off"
    else -> "Ready when you are"
}

/**
 * A single counter.
 *
 * The number counts up from zero on first composition. Purely decorative, and cheap — the
 * text has to recompose to change anyway, so animating it costs no more than drawing it.
 */
@Composable
private fun StatChip(iconName: String, value: Int, label: String) {
    val counter = remember { Animatable(0f) }
    LaunchedEffect(value) {
        counter.animateTo(
            targetValue = value.toFloat(),
            animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        )
    }

    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(
            icon = iconName,
            modifier = Modifier.size(15.dp),
            tint = colors.tertiary,
        )
        Text(
            text = counter.value.roundToInt().toString(),
            color = colors.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/** Under this the greeting and the chips stop fitting on one line. */
private val COMPACT_GREETING_WIDTH = 560.dp
