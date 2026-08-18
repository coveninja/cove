package com.coveninja.cove.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.platform.hasPointerHover
import com.coveninja.cove.ui.state.LocalMotionPolicy
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The light behind the whole flow.
 *
 * Three accent blobs drifting on slow, mutually prime sine paths, so the field never visibly
 * repeats and never resolves into a pattern the eye can lock onto. Drawn rather than blurred:
 * `Modifier.blur` is a no-op below Android 12 and would leave a phone showing three hard-edged
 * circles, whereas a radial gradient with a transparent outer stop is soft everywhere by
 * construction.
 *
 * Under reduced motion the blobs are placed at their t=0 positions and nothing animates. The
 * page still has depth; it simply holds still.
 */
@Composable
fun OnboardingAurora(modifier: Modifier = Modifier) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val transition = rememberInfiniteTransition(label = "OnboardingAurora")
    val drift by if (reducedMotion) {
        remember { mutableFloatStateOf(0f) }
    } else {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 38_000, easing = LinearEasing),
            ),
            label = "OnboardingAuroraDrift",
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(CoveColors.Neutral.Background)
        val angle = drift * 2f * PI.toFloat()
        BLOBS.forEach { blob ->
            val cx = size.width * (blob.originX + blob.spanX * sin(angle * blob.rateX + blob.phase))
            val cy = size.height * (blob.originY + blob.spanY * sin(angle * blob.rateY))
            val radius = size.minDimension * blob.radius
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(blob.color.copy(alpha = blob.alpha), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
    }
}

private class AuroraBlob(
    val color: Color,
    val alpha: Float,
    val radius: Float,
    val originX: Float,
    val originY: Float,
    val spanX: Float,
    val spanY: Float,
    val rateX: Float,
    val rateY: Float,
    val phase: Float,
)

/**
 * Rates chosen so no two blobs share a period — the field takes minutes to come back to any
 * arrangement it has already shown, which is longer than anyone spends on a first run.
 */
private val BLOBS = listOf(
    AuroraBlob(
        color = CoveColors.Brand.Accent, alpha = 0.16f, radius = 0.85f,
        originX = 0.22f, originY = 0.24f, spanX = 0.12f, spanY = 0.09f,
        rateX = 1f, rateY = 0.7f, phase = 0f,
    ),
    AuroraBlob(
        color = CoveColors.Segment.Recap, alpha = 0.13f, radius = 0.72f,
        originX = 0.82f, originY = 0.30f, spanX = 0.10f, spanY = 0.12f,
        rateX = 0.6f, rateY = 1.1f, phase = 2.1f,
    ),
    AuroraBlob(
        color = CoveColors.Segment.Credits, alpha = 0.11f, radius = 0.95f,
        originX = 0.55f, originY = 0.92f, spanX = 0.16f, spanY = 0.07f,
        rateX = 0.9f, rateY = 0.5f, phase = 4.2f,
    ),
)

/**
 * Where the pointer is over the flow, if it is anywhere.
 *
 * Held in one object shared by the ancestor that observes the pointer and the backdrop that
 * reacts to it, because those are not the same composable: the backdrop is the *bottom* sibling
 * in the layout and would never be hit-tested while the pointer is over a button. Listening on a
 * common ancestor during [PointerEventPass.Initial] is what lets a decoration underneath
 * everything follow a pointer that is really interacting with the content on top of it.
 */
@Stable
class BackdropPointer {
    /** Null when no pointer is over the flow — a mouse that left, or a finger that lifted. */
    var position: Offset? by mutableStateOf(null)
        internal set
}

@Composable
fun rememberBackdropPointer(): BackdropPointer = remember { BackdropPointer() }

/**
 * Reports pointer movement into [pointer] without ever consuming it.
 *
 * Two details carry this. The pass is [PointerEventPass.Initial], which runs from ancestor down
 * to child, so this sees every event before the button or text field underneath decides what to
 * do with it — and because nothing here calls `consume()`, that decision is unaffected.
 *
 * And what counts as "the pointer left" differs by device. A mouse leaves by exiting the window,
 * so [PointerEventType.Exit] is the signal. A finger has no hover state at all: it is either
 * touching or gone, so a release *is* an exit. Keying that off [hasPointerHover] rather than the
 * event alone is what stops a desktop mouse-click from extinguishing the field it is pointing at.
 */
fun Modifier.trackBackdropPointer(pointer: BackdropPointer): Modifier =
    this.pointerInput(pointer) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Exit -> pointer.position = null
                    PointerEventType.Release -> if (!hasPointerHover) pointer.position = null
                    else -> event.changes.lastOrNull()?.let { pointer.position = it.position }
                }
            }
        }
    }

/**
 * A uniform grid of card-shaped tiles, scrolling upward on a seamless loop, that light up under
 * the pointer.
 *
 * This is the shape of Cove's own media cards — 2:3, the same proportion every poster in the app
 * is drawn at — held a little smaller than a real card and rendered as soft colour rather than
 * artwork. The intent is that the welcome screen reads as *a wall of things to watch* without
 * claiming anything about a particular title, which it has no business doing before the viewer
 * has told it anything.
 *
 * It replaces two earlier attempts, and both failures are worth recording because they are what
 * the design now avoids:
 *
 *  - **Real posters** repeated and overlapped. A discover feed yields about a dozen distinct
 *    images, and three columns tiled from twelve pictures at different speeds puts the same face
 *    on screen twice at once. No arrangement of a short list hides that.
 *  - **A scattered field** of varied rectangles fixed the repetition but read as noise. Uniform
 *    tiles on a grid are calmer, and the regularity is the point: it looks like a catalog.
 *
 * **The loop is genuinely seamless**, not merely long. The scroll position is carried in row
 * units and wrapped by exactly the colour pattern's period, so the wrap subtracts a whole number
 * of rows from a pattern that repeats on that number — the frame after the wrap is
 * pixel-identical to the frame before it. See [ScrollPeriodRows].
 *
 * **The softness is drawn, not blurred.** `Modifier.blur` and `BlurEffect` are no-ops below
 * Android 12 and `minSdk` here is 28, so half the phones Cove supports would see hard-edged
 * rectangles. [drawSoftTile] composites the same falloff everywhere.
 */
@Composable
fun OnboardingTileField(
    pointer: BackdropPointer,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val density = LocalDensity.current
    val targetTileWidth = with(density) { TargetTileWidth.toPx() }
    val gap = with(density) { TileGap.toPx() }

    // Carried in rows rather than pixels so the wrap can be exact: one row of scroll is one step
    // through the colour pattern, and wrapping by the pattern's period leaves the field
    // unchanged. In pixels the same wrap would depend on the tile height, which depends on the
    // viewport, which changes when the window is resized mid-animation.
    var scrolled by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val elapsedSeconds = (now - previous) / 1_000_000_000f
            previous = now
            scrolled = (scrolled + elapsedSeconds * RowsPerSecond) % BackdropScrollPeriodRows
        }
    }

    val focus = pointer.position
    // The pointer's *presence* is animated, not its position: a highlight that snapped off the
    // instant a mouse left the window would read as a rendering glitch, while the position
    // itself has to stay exact or the light lags behind the cursor. Reduced motion keeps the
    // highlight — direct pointer feedback is what the policy explicitly retains — and drops
    // only the fade and the scroll.
    val influence by animateFloatAsState(
        targetValue = if (focus == null) 0f else 1f,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 420),
        label = "TileFieldInfluence",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val grid = tileGridFor(
            width = size.width,
            height = size.height,
            targetTileWidth = targetTileWidth,
            gap = gap,
        )
        val glowRadius = size.minDimension * GlowRadiusFraction
        val baseRow = floor(scrolled).toInt()
        val fraction = scrolled - baseRow

        for (row in 0 until grid.rowCount) {
            // Content moves up, so a growing fraction lifts every row; new ones arrive from
            // below. The row index is offset by the whole rows already scrolled past, which is
            // what carries the colour pattern along with the tiles instead of leaving it
            // stationary while they move through it.
            val top = (row - fraction) * grid.rowPitch
            val absoluteRow = baseRow + row

            for (column in 0 until grid.columns) {
                val left = column * (grid.tileWidth + grid.gap)
                val glow = focus?.let { position ->
                    val centre = Offset(
                        left + grid.tileWidth / 2f,
                        top + grid.tileHeight / 2f,
                    )
                    influence * backdropGlow((position - centre).getDistance(), glowRadius)
                } ?: 0f

                val tint = TilePalette[tileColorIndex(absoluteRow, column, TilePalette.size)]
                drawSoftTile(
                    left = left,
                    top = top,
                    width = grid.tileWidth,
                    height = grid.tileHeight,
                    color = tint,
                    alpha = TileAlpha * (1f + glow * GlowAlphaGain),
                    softness = grid.tileWidth * (SoftnessFraction + glow * GlowSpread),
                )
            }
        }
    }
}

/**
 * One tile, as a stack of concentric rounded rects fading outward.
 *
 * Drawn largest-and-faintest first so the alpha accumulates toward the middle, which is what a
 * real blur does to a solid shape. Five layers is where it stops looking banded; more is
 * indistinguishable and costs a draw call per tile per layer.
 */
private fun DrawScope.drawSoftTile(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color,
    alpha: Float,
    softness: Float,
) {
    val radius = width * TileCornerFraction
    for (layer in BloomLayers - 1 downTo 0) {
        val grow = softness * layer / (BloomLayers - 1).toFloat()
        // Squared falloff: a linear one leaves the outermost ring visible as an edge, which is
        // the one thing the whole approach exists to avoid.
        val fade = (1f - layer.toFloat() / BloomLayers).let { it * it }
        drawRoundRect(
            color = color.copy(alpha = (alpha * fade).coerceIn(0f, 1f)),
            topLeft = Offset(left - grow, top - grow),
            size = Size(width + grow * 2f, height + grow * 2f),
            cornerRadius = CornerRadius(radius + grow),
        )
    }
}

/** The grid the tiles are laid out on, in pixels. Every tile is the same size. */
internal data class TileGrid(
    val columns: Int,
    val tileWidth: Float,
    val tileHeight: Float,
    val gap: Float,
    /** Distance from one row's top edge to the next. */
    val rowPitch: Float,
    /** How many rows to draw — enough to cover the viewport throughout the scroll. */
    val rowCount: Int,
)

/**
 * Fits the grid to the viewport.
 *
 * Columns are chosen so tiles land near [targetTileWidth] and then sized to divide the width
 * exactly, so the grid reaches both edges rather than leaving a ragged margin — a backdrop that
 * stops short of the screen edge reads as a mistake.
 *
 * [rowCount] is the part worth checking. Rows are drawn from a scroll fraction that runs to just
 * under 1, so at the very end of a cycle the whole grid has lifted by nearly a full row. Two
 * spare rows rather than one is what guarantees the bottom of the screen is still covered at
 * that instant; with one, an empty band flickers along the bottom edge once per row — the kind
 * of defect that is obvious in motion and invisible in a screenshot.
 */
internal fun tileGridFor(
    width: Float,
    height: Float,
    targetTileWidth: Float,
    gap: Float,
): TileGrid {
    require(width > 0f && height > 0f) { "the grid needs a viewport with area" }
    require(targetTileWidth > 0f) { "tiles need a target width" }

    val columns = ((width + gap) / (targetTileWidth + gap)).roundToInt().coerceAtLeast(1)
    val tileWidth = (width - gap * (columns - 1)) / columns
    val tileHeight = tileWidth * PosterAspect
    val rowPitch = tileHeight + gap
    val rowCount = ceil(height / rowPitch).toInt() + SpareRows

    return TileGrid(
        columns = columns,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        gap = gap,
        rowPitch = rowPitch,
        rowCount = rowCount,
    )
}

/**
 * Which colour a tile takes, from its position in the infinite grid.
 *
 * Deliberately periodic in [row] with period [BackdropScrollPeriodRows], because that is what
 * makes the loop seamless: the scroll wraps by exactly that many rows, so the wrap subtracts a
 * whole number of periods and every tile keeps the colour it already had. A non-periodic
 * pattern — or a period that did not divide the wrap — would show a single frame where the
 * whole field changed colour, once per cycle.
 *
 * The mixing is written out rather than taken from `hashCode`, whose combination is only
 * specified on the JVM. A backdrop that coloured itself differently on a phone and a desktop
 * would be a small thing, but it would be wrong for no reason.
 *
 * It is three steps rather than the usual four. A final `xor (hash ushr 16)` was there first and
 * was measured out: across the full 240-row period it moved the palette's distribution spread
 * from 0.017 to 0.019 — that is, very slightly for the worse — and left adjacent-tile variety
 * unmoved. No test could distinguish it, which is the definition of a step not earning its place.
 *
 * The multiply that remains is the borderline case, and is kept deliberately: removing it costs
 * only 0.004 of spread, which no test here can honestly assert, but xor-and-shift alone is a
 * weak avalanche in principle and this pattern is meant to hold up if the palette or the period
 * ever changes. `OnboardingBackdropTest` records the same finding from the other side.
 */
internal fun tileColorIndex(row: Int, column: Int, paletteSize: Int): Int {
    require(paletteSize > 0) { "a palette needs at least one colour" }
    val wrapped = row.mod(BackdropScrollPeriodRows)
    var hash = wrapped * 73856093 xor column * 19349663
    hash = hash xor (hash ushr 13)
    hash *= 1274126177
    return (hash and 0x7FFFFFFF) % paletteSize
}

/** A media card's proportions. Height per unit width — the same 2:3 every poster in Cove uses. */
private const val PosterAspect = 3f / 2f

/**
 * Rows drawn beyond the ones the viewport needs at rest.
 *
 * Two, not one. See [tileGridFor] — one leaves an empty band along the bottom edge for the
 * instant before each row wraps.
 */
private const val SpareRows = 2

/** A little smaller than a real media card, so the field reads as backdrop rather than content. */
private val TargetTileWidth = 104.dp
private val TileGap = 14.dp

/**
 * How many rows the pattern repeats over, and therefore where the scroll wraps.
 *
 * One constant used in both places, deliberately. The loop is seamless only because the scroll
 * wraps by subtracting exactly the number of rows the colour pattern repeats on — the frame
 * after the wrap is then pixel-identical to the frame before it. Two constants that happened to
 * agree would be one edit away from a field that changes colour all at once, once per cycle.
 *
 * Internal rather than private so the test can hold the two ends together instead of restating
 * the number and hoping.
 *
 * Large enough that the repeat is not something a viewer could notice — about twenty screens of
 * unique arrangement — and small enough that the accumulated scroll never loses float precision.
 */
internal const val BackdropScrollPeriodRows = 240

/** Slow enough to read as ambient rather than as scrolling. One row takes about twenty seconds. */
private const val RowsPerSecond = 0.05f

private const val TileAlpha = 0.085f
private const val TileCornerFraction = 0.12f
private const val BloomLayers = 5

/** Softness at rest, and how much more of it the highlight adds, as a fraction of tile width. */
private const val SoftnessFraction = 0.13f
private const val GlowSpread = 0.11f

/** How far the highlight reaches, as a fraction of the panel's short side. */
private const val GlowRadiusFraction = 0.30f

/** How much brighter a tile gets at the centre of the highlight. */
private const val GlowAlphaGain = 5.5f

/**
 * How brightly a tile [distance] away from the pointer should light up, 0f..1f.
 *
 * Smoothstep rather than a linear ramp: the derivative is zero at both ends, so a tile entering
 * the radius eases in instead of switching on, and the tile directly under the pointer is not a
 * hard peak. A linear falloff reads as a disc following the cursor; this reads as light.
 */
internal fun backdropGlow(distance: Float, radius: Float): Float {
    if (radius <= 0f) return 0f
    val t = (1f - distance / radius).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The tile colours: Cove's green ramp, deepest to palest.
 *
 * One hue family rather than a spread of them. An earlier version drew from the accent, the
 * status hues and the player's segment colours at once — green, purple, teal, blue, orange and
 * gold — and a wall of those reads as a colour test rather than as a backdrop. Shades of one
 * green give the same sense of *variety* without the field competing with the content in front
 * of it, and they say the app has an opinion about colour.
 *
 * Taken from [CoveColors.Seafoam] rather than declared here, so the backdrop still cannot
 * introduce a colour that exists nowhere else in Cove — the palette is the one place hues live.
 * Internal rather than private so a test can hold that: without it, nothing stops the field
 * quietly acquiring a purple again, since the colour-pattern tests only ever deal in indices.
 */
internal val TilePalette = CoveColors.Seafoam.ramp
