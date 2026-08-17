package com.coveninja.cove.ui.pages.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Host-controlled gutter shared by page headers, rails, toolbars, and result grids. */
internal val LocalPageHorizontalPadding = staticCompositionLocalOf { 24.dp }

/**
 * How much room the window has across its width.
 *
 * Phones, tablets and desktop windows differ by more than a hero height: the gutter that
 * reads as generous on a 1280 dp desktop leaves a 360 dp phone with almost no content
 * width, and a gutter that fits a phone looks cramped on a desktop. Deriving both from one
 * enum keeps that single decision in one place instead of a per-call-site literal.
 */
internal enum class WindowSizeClass {
    Compact,
    Medium,
    Expanded,
}

/**
 * Size class from width alone.
 *
 * Separate from [PageViewport] because the navigation placement has to be resolved before a
 * viewport can be built — the viewport needs to know whether there is a bottom bar.
 */
internal fun windowSizeClassFor(width: Dp): WindowSizeClass = when {
    width < MediumWidthBreakpoint -> WindowSizeClass.Compact
    width < ExpandedWidthBreakpoint -> WindowSizeClass.Medium
    else -> WindowSizeClass.Expanded
}

/**
 * The part of the host window that changes how a page should lay itself out.
 *
 * Width alone is not enough on a phone: after rotation a 914 dp-wide window is still only
 * about 411 dp tall. Treating that as a desktop-sized canvas produced a 560 dp hero and left
 * the floating navigation directly over its controls. Keeping these metrics in one local lets
 * every page make the same decision without teaching shared UI about Android configuration.
 */
internal data class PageViewport(
    val width: Dp,
    val height: Dp,
    val hasBottomNavigation: Boolean,
) {
    val bottomNavigationClearance: Dp
        get() = if (hasBottomNavigation) MobileBottomNavigationClearance else 0.dp

    val sizeClass: WindowSizeClass
        get() = windowSizeClassFor(width)

    /**
     * The gutter every page, rail header, toolbar and grid aligns to.
     *
     * Mobile previously got 0.dp so that heroes could bleed to the edge, which left every
     * other section flush against the screen while neighbours that hardcoded their own
     * padding sat inset — the ragged left edge this replaces. Full-bleed content opts out
     * deliberately now (see [MediaRail]'s content padding) rather than the whole page
     * losing its gutter for the sake of one child.
     */
    val gutter: Dp
        get() = when (sizeClass) {
            WindowSizeClass.Compact -> CompactGutter
            WindowSizeClass.Medium -> MediumGutter
            WindowSizeClass.Expanded -> ExpandedGutter
        }

    val isShort: Boolean
        get() = hasBottomNavigation && height < ShortViewportBreakpoint

    fun heroMetrics(availableWidth: Dp): HeroViewportMetrics {
        val compact = availableWidth < CompactHeroWidth || isShort
        val normalHeight = if (compact) CompactHeroHeight else ExpandedHeroHeight
        val resolvedHeight = if (isShort) {
            min(normalHeight.value, (height.value * ShortHeroHeightFraction))
                .coerceAtLeast(MinimumShortHeroHeight.value)
                .dp
        } else {
            normalHeight
        }
        return HeroViewportMetrics(compact = compact, short = isShort, height = resolvedHeight)
    }
}

internal data class HeroViewportMetrics(
    val compact: Boolean,
    val short: Boolean,
    val height: Dp,
)

/**
 * Room a scrolling page leaves at its bottom for the floating bar and the system navigation
 * area.
 *
 * The page paints all the way to the physical bottom edge; stopping it short left an
 * unpainted strip showing the window background as a bar beneath the floating navigation.
 * Content clears the bar by padding its *scroll container* instead, so items scroll under
 * the bar and nothing is clipped at rest.
 */
internal val LocalPageBottomClearance = staticCompositionLocalOf { 0.dp }

internal val LocalPageViewport = staticCompositionLocalOf {
    PageViewport(width = 1280.dp, height = 800.dp, hasBottomNavigation = false)
}

internal val MobileBottomNavigationClearance = 64.dp
private val MediumWidthBreakpoint = 600.dp
private val ExpandedWidthBreakpoint = 840.dp
private val CompactGutter = 16.dp
private val MediumGutter = 20.dp
private val ExpandedGutter = 24.dp
private val ShortViewportBreakpoint = 600.dp
private val CompactHeroWidth = 720.dp
private val CompactHeroHeight = 460.dp
private val ExpandedHeroHeight = 560.dp
private val MinimumShortHeroHeight = 280.dp
private const val ShortHeroHeightFraction = 0.72f

object PageLayoutDefaults {
    val HorizontalPadding: Dp
        @Composable
        @ReadOnlyComposable
        get() = LocalPageHorizontalPadding.current

    internal val Viewport: PageViewport
        @Composable
        @ReadOnlyComposable
        get() = LocalPageViewport.current

    /** True on a phone-width window, where layouts should stack rather than sit side by side. */
    internal val IsCompact: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalPageViewport.current.sizeClass == WindowSizeClass.Compact

    /**
     * Smallest poster column a result grid will lay out.
     *
     * 150 dp was picked against a desktop window; on a phone it resolves to two columns of
     * roughly 200 dp, so the posters read as oversized next to the rails' 158 dp cards.
     * A lower floor gives a phone three columns at a similar card size.
     */
    /** Add to the bottom `contentPadding` of any scroll container that fills a page. */
    val BottomClearance: Dp
        @Composable
        @ReadOnlyComposable
        get() = LocalPageBottomClearance.current

    internal val PosterGridMinWidth: Dp
        @Composable
        @ReadOnlyComposable
        get() = if (IsCompact) 112.dp else 150.dp
}
