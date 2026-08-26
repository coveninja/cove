package com.coveninja.cove.ui.pages.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.CompositionLocalProvider
import com.coveninja.cove.shared.model.ActivityStats
import com.coveninja.cove.shared.model.DiscoveryInsights
import com.coveninja.cove.shared.model.InsightsRange
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.components.insights.PosterWall
import com.coveninja.cove.ui.model.displayImageUrl
import com.coveninja.cove.ui.platform.ImageExporter
import com.coveninja.cove.ui.platform.encodeToPng
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * The viewer's year as one portrait image they can send to somebody.
 *
 * This is the part of the page that Trakt and Simkl structurally cannot answer. Their stats
 * live behind a login and read as a report; a picture of your year is a thing you send to a
 * friend, and it only works at all because Cove already holds the artwork to build it from.
 *
 * Rendered on screen before it is captured rather than assembled off-screen. That is not a
 * shortcut — it is the fix for the one failure this feature has: Coil resolves images
 * asynchronously, and recording a layer whose posters have not landed captures empty boxes
 * with nothing anywhere reporting an error. Showing the viewer the real thing and capturing
 * only when they press the button means what is captured is, by construction, what they were
 * looking at.
 */
@Composable
internal fun InsightsRecapDialog(
    stats: ActivityStats,
    profile: DiscoveryInsights,
    breakdown: LibraryBreakdown,
    range: InsightsRange,
    today: LocalDate,
    exporter: ImageExporter?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RecapPreview(layer = layer) {
                    RecapCard(
                        stats = stats,
                        profile = profile,
                        breakdown = breakdown,
                        range = range,
                        today = today,
                    )
                }

                if (failed) {
                    Text(
                        text = "That did not work. The log will say why.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RecapButton(label = "Close", filled = false, onClick = onDismiss)
                    exporter?.let { export ->
                        RecapButton(
                            label = if (busy) "Working…" else export.actionLabel,
                            filled = true,
                            enabled = !busy,
                        ) {
                            busy = true
                            failed = false
                            scope.launch {
                                val png = runCatching { layer.toImageBitmap() }
                                    .getOrNull()
                                    ?.encodeToPng()
                                val done = png != null &&
                                    export.export(png, recapFileName(range, today))
                                failed = !done
                                busy = false
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lays the card out at capture size and shows it scaled down.
 *
 * Two things have to be true at once: the captured image wants to be big enough to look like
 * a photograph on somebody's phone, and the preview has to fit in a dialog. Overriding the
 * density gets the first — the card is written in ordinary dp and measures to
 * [RecapPixelDensity] times as many pixels — and a `graphicsLayer` scale gets the second
 * without touching the layout the recording sees.
 *
 * The order matters. The recording modifier sits on the card, inside the scale; put the scale
 * inside and the capture would come out at preview size, which is the whole problem this is
 * avoiding.
 */
@Composable
private fun RecapPreview(
    layer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    content: @Composable () -> Unit,
) {
    val screen = LocalDensity.current
    val captureDensity = remember(screen.fontScale) {
        Density(RecapPixelDensity, fontScale = screen.fontScale)
    }
    // The card measures in *capture* pixels (RecapWidth x RecapPixelDensity), so bringing it
    // back to a given size in *screen* dp needs the ratio between the two densities. Scaling
    // by PreviewScale alone would leave a desktop preview a third of its intended size, and
    // the footprint below would crop it rather than shrink it.
    val shrink = screen.density / RecapPixelDensity * PreviewScale

    Box(
        // The two cancel out to plain screen dp: capture px x shrink / screen density is
        // RecapWidth x PreviewScale however dense the display is. Sizing this from `shrink`
        // instead is the bug that crops the preview.
        modifier = Modifier.size(RecapWidth * PreviewScale, RecapHeight * PreviewScale),
        contentAlignment = Alignment.TopStart,
    ) {
        CompositionLocalProvider(LocalDensity provides captureDensity) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = shrink
                        scaleY = shrink
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    // requiredSize so the shrunken footprint above cannot squash the card
                    // it is only meant to be displaying smaller.
                    .requiredSize(RecapWidth, RecapHeight)
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                content()
            }
        }
    }
}

/** The image itself: nine-by-sixteen, and readable at thumbnail size. */
@Composable
private fun RecapCard(
    stats: ActivityStats,
    profile: DiscoveryInsights,
    breakdown: LibraryBreakdown,
    range: InsightsRange,
    today: LocalDate,
) {
    val accent = CoveColors.Brand.Accent
    val posters = remember(stats.titlesWatchedThisYear) {
        stats.titlesWatchedThisYear.map { it.posterPath }.filter { it.isNotBlank() }
    }
    val period = when (range) {
        InsightsRange.ThisYear -> "${today.year}"
        InsightsRange.LastYear -> "${today.year - 1}"
        InsightsRange.AllTime -> "All time"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CoveColors.Neutral.Background),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(RecapHeight * 0.42f)) {
            PosterWall(
                posterPaths = posters,
                modifier = Modifier.fillMaxSize(),
                tile = 64.dp,
                scrim = CoveColors.Neutral.Background,
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(22.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = period.uppercase(),
                    color = accent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                )
                Text(
                    text = formatWatchDuration(stats.totalSeconds),
                    style = MaterialTheme.typography.displaySmall.copy(
                        brush = Brush.linearGradient(
                            listOf(Color.White, accent),
                        ),
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                Text(
                    text = "across ${stats.titlesThisYear} " +
                        if (stats.titlesThisYear == 1) "title" else "titles",
                    color = CoveColors.Neutral.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                identityLine(breakdown, stats, profile.decades)?.let {
                    Text(
                        text = it,
                        color = accent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                stats.titlesWatchedThisYear.take(RECAP_POSTERS)
                    .takeIf { it.isNotEmpty() }
                    ?.let { top ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RecapLabel("Most watched")
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                top.forEach { title ->
                                    CoveAsyncImage(
                                        model = displayImageUrl(title.posterPath, "w185"),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(2f / 3f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(CoveColors.Neutral.SurfaceHigh),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                // Keeps the row a fixed grid when there are fewer than five,
                                // so three posters do not stretch to fill it.
                                repeat(RECAP_POSTERS - top.size) {
                                    Box(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                RecapFacts(stats = stats, profile = profile, breakdown = breakdown, today = today)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "COVE",
                        color = accent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.5.sp,
                    )
                    Box(
                        modifier = Modifier
                            .width(34.dp)
                            .height(2.dp)
                            .background(accent.copy(alpha = 0.5f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecapFacts(
    stats: ActivityStats,
    profile: DiscoveryInsights,
    breakdown: LibraryBreakdown,
    today: LocalDate,
) {
    // Only facts that are actually there. A recap padded out with "—" says less about the
    // viewer than a shorter one that is entirely true.
    val facts = buildList {
        rhythmHeadline(stats).takeIf { peakHour(stats.byHourOfDay) != null }
            ?.let { add("Rhythm" to it) }
        stats.biggestDay?.takeIf { !it.isEmpty }?.let { moment ->
            biggestDayHeadline(moment, today)?.let { add("Biggest day" to it) }
        }
        genreHeadline(profile.topMovieGenres, profile.topTvGenres)
            .takeIf { profile.topMovieGenres.isNotEmpty() || profile.topTvGenres.isNotEmpty() }
            ?.let { add("Favourites" to it) }
        stats.currentStreak.takeIf { it > 1 }?.let { add("Streak" to "$it days running") }
        breakdown.averageRating?.let {
            add("Average rating" to "★ ${(it * 10).toInt() / 10.0}")
        }
    }.take(RECAP_FACTS)

    if (facts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        facts.forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.width(96.dp),
                    color = CoveColors.Neutral.MutedDim,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = value,
                    modifier = Modifier.weight(1f),
                    color = CoveColors.Neutral.Text,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun RecapLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = CoveColors.Neutral.MutedDim,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
    )
}

@Composable
private fun RecapButton(
    label: String,
    filled: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (filled) accent else MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClick,
        enabled = enabled,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            color = if (filled) CoveColors.Brand.OnAccent else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A name someone can find again in a downloads folder full of other people's images. */
internal fun recapFileName(range: InsightsRange, today: LocalDate): String {
    val period = when (range) {
        InsightsRange.ThisYear -> "${today.year}"
        InsightsRange.LastYear -> "${today.year - 1}"
        InsightsRange.AllTime -> "all-time"
    }
    return "cove-$period.png"
}

/** Nine by sixteen, the shape every phone expects a shareable image to be. */
private val RecapWidth: Dp = 360.dp
private val RecapHeight: Dp = 640.dp

/**
 * The density the card is measured at, and therefore its pixel size: 1080 × 1920.
 *
 * Fixed rather than taken from the screen, so a desktop at 1× and a phone at 3× produce the
 * same image. Without this the same feature would hand a desktop viewer a 360-pixel-wide
 * thumbnail and a phone viewer a usable picture.
 */
private const val RecapPixelDensity = 3f

/** How much of the available width the preview takes; the rest is the dialog's margins. */
private const val PreviewScale = 0.68f

private const val RECAP_POSTERS = 5
private const val RECAP_FACTS = 4
