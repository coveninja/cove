package com.coveninja.cove.ui.components.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.model.displayImageUrl
import kotlin.math.ceil

/**
 * The viewer's own posters, drifting behind the hero.
 *
 * The point of the whole thing: Trakt and Simkl are text-and-number pages because that is
 * all their data is. Cove holds artwork for everything anybody watched, so the one place a
 * stats page can look like the app it belongs to is its own ground. Two people's insights
 * pages are then made of different pictures rather than differing only in the length of
 * their bars.
 *
 * **Never reach for `Modifier.blur` here.** On Android it silently does nothing below API
 * 31, and Cove's minSdk is 28 — a phone would show sharp posters fighting the text while a
 * desktop showed a soft wash, and nothing anywhere would report a problem. The softness
 * instead comes from asking TMDB for a deliberately small render and letting it scale up,
 * which costs the same on both hosts and is a good deal less bandwidth besides.
 *
 * Legibility never depends on the artwork. Two scrims sit over the wall — a horizontal one
 * that keeps the text end dark whatever poster lands there, and a vertical one that settles
 * the whole thing back towards the card. A wall of pale posters must not be able to make the
 * total unreadable.
 */
@Composable
internal fun PosterWall(
    posterPaths: List<String>,
    modifier: Modifier = Modifier,
    tile: Dp = 84.dp,
    scrim: Color = Color.Black,
) {
    val usable = remember(posterPaths) {
        posterPaths.filter { it.isNotBlank() }.distinct()
    }
    if (usable.isEmpty()) return

    val drift = rememberDrift(durationMillis = 42_000, label = "PosterWall")

    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        // One tile of travel plus one of overhang, so the drift never exposes the end of
        // the row. Repeating the list is deliberate — a viewer with three watched titles
        // gets a wall of three posters rather than a third of a wall.
        val columns = ceil(maxWidth / tile).toInt() + 2
        val tiles = remember(usable, columns) {
            List(columns) { usable[it % usable.size] }
        }

        Row(
            modifier = Modifier
                .fillMaxHeight()
                .graphicsLayer {
                    alpha = 0.34f
                    translationX = -drift * tile.toPx()
                },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tiles.forEach { path ->
                CoveAsyncImage(
                    // w185 rather than a poster-sized render: this is texture, not a poster
                    // anyone will look at directly, and scaling a small image up is the
                    // whole of the softness effect.
                    model = displayImageUrl(path, "w185"),
                    contentDescription = null,
                    modifier = Modifier.width(tile).fillMaxHeight(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            scrim.copy(alpha = 0.92f),
                            scrim.copy(alpha = 0.72f),
                            scrim.copy(alpha = 0.34f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scrim.copy(alpha = 0.30f),
                            Color.Transparent,
                            scrim.copy(alpha = 0.55f),
                        ),
                    ),
                ),
        )
    }
}
