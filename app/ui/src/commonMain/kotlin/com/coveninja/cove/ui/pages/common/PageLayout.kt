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

internal val LocalPageViewport = staticCompositionLocalOf {
    PageViewport(width = 1280.dp, height = 800.dp, hasBottomNavigation = false)
}

internal val MobileBottomNavigationClearance = 64.dp
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
}
