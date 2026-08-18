package com.coveninja.cove.ui.tv

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every measurement the TV shell makes about the screen it is on.
 *
 * A television is not a large phone. The viewer is three metres away with no pointer, so the
 * numbers that matter here — how much of the panel is safe to draw in, how big a card has to be
 * to be legible, how much room a focused row leaves above itself — are different in kind from
 * the ones in `PageLayout`, not merely larger. Keeping them in one derived object means the
 * rail, the rows and the hero cannot drift apart, and it lets the desktop dev window
 * (`--tv`) resolve the same proportions from a smaller viewport.
 */
data class TvDimens(
    val width: Dp,
    val height: Dp,
    /** Horizontal safe area. Older panels overscan; content inside this is always visible. */
    val overscanHorizontal: Dp,
    val overscanVertical: Dp,
    /** The rail at rest — icons only. */
    val railCollapsedWidth: Dp,
    /** The rail once focus enters it and the labels appear. */
    val railExpandedWidth: Dp,
    val posterWidth: Dp,
    val wideCardWidth: Dp,
    val heroHeight: Dp,
    /** Gap between cards in a row. */
    val cardSpacing: Dp,
    /** Gap between one row (header included) and the next. */
    val sectionSpacing: Dp,
    /**
     * Room kept above the focused row.
     *
     * The old TV shell lost the top of every tall section to this: focus scrolling brings the
     * focused *element* into view, which puts a row header or a hero's upper edge exactly at
     * the screen edge. Scrolling to a fixed inset instead is also what gives the page its
     * settled feel — the focused row lands in the same place every time.
     */
    val focusScrollMargin: Dp,
) {
    val posterHeight: Dp get() = posterWidth * 3f / 2f
    val wideCardHeight: Dp get() = wideCardWidth * 9f / 16f

    /** Where page content starts once the rail has taken its share of the left edge. */
    val contentStart: Dp get() = railCollapsedWidth + overscanHorizontal

    /**
     * How many posters fit across the content area.
     *
     * A results page has no rows to organise itself into, so it borrows the row layout and
     * fills it — chunking results into rows this wide gives a grid that a D-pad can cross in
     * both directions, without a second layout to teach focus scrolling about.
     *
     * Floored at three: a panel narrow enough to want fewer than that would be showing posters
     * wide enough to read from the next room, which is not the problem worth solving.
     */
    val posterColumns: Int
        get() {
            val usable = width - railCollapsedWidth - overscanHorizontal * 2 + cardSpacing
            val step = posterWidth + cardSpacing
            return (usable.value / step.value).toInt().coerceAtLeast(3)
        }
}

/**
 * Card and hero sizes from the viewport, so one set of proportions serves a 960 dp television
 * and a 1280 dp desktop window.
 *
 * The clamps are the lesson from the first TV round, where cards sized in root-relative units
 * came out bulky and the hero swallowed the screen: a poster is allowed to grow with the panel
 * but not past the point where a row stops showing that it continues off-screen.
 */
fun tvDimensFor(width: Dp, height: Dp): TvDimens {
    val poster = (width.value * 0.155f).coerceIn(128f, 176f).dp
    return TvDimens(
        width = width,
        height = height,
        overscanHorizontal = (width.value * 0.05f).coerceIn(24f, 64f).dp,
        overscanVertical = (height.value * 0.05f).coerceIn(16f, 40f).dp,
        railCollapsedWidth = 84.dp,
        railExpandedWidth = 272.dp,
        posterWidth = poster,
        wideCardWidth = poster * 2f,
        // Enough for artwork to establish the title without pushing the first row of cards
        // off the bottom — the row underneath has to be visible, or nothing says it is there.
        heroHeight = (height.value * 0.56f).coerceIn(280f, 520f).dp,
        cardSpacing = 16.dp,
        sectionSpacing = 34.dp,
        focusScrollMargin = (height.value * 0.12f).coerceIn(48f, 112f).dp,
    )
}

val LocalTvDimens = staticCompositionLocalOf {
    tvDimensFor(width = 960.dp, height = 540.dp)
}

object TvTheme {
    val dimens: TvDimens
        @Composable
        @ReadOnlyComposable
        get() = LocalTvDimens.current
}

/**
 * Cove's type, one step up.
 *
 * Deliberately a modest lift rather than a global scale factor. The first TV attempt scaled the
 * root font size and everything turned bulky; what actually reads at distance is a heavier
 * weight and a slightly larger body, with the jump saved for the few things — a hero title, a
 * row heading — that are meant to be read across the room.
 */
private val TvTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
)

/**
 * Wraps TV content in the shared palette with TV metrics and type.
 *
 * Colours and shapes are taken from whatever `CoveTheme` the host already installed, so there
 * is exactly one palette in the app; only the type scale and [TvDimens] differ.
 */
@Composable
internal fun TvTheme(dimens: TvDimens, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = TvTypography,
        shapes = MaterialTheme.shapes,
    ) {
        CompositionLocalProvider(LocalTvDimens provides dimens, content = content)
    }
}
