package com.coveninja.cove.ui.tv.onboarding

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The two measurements the television flow gets wrong if they are constants.
 *
 * Both were constants first, and both were wrong on the panel that matters most: a 1080p
 * television reports 960×540 dp at density 2, which is the *smallest* viewport this shell sees
 * rather than an edge case. A 380 dp sidebar left 388 dp for the step, and five posters at the
 * rail's own poster width need 674 dp of it — so the wall ran off the right edge of every real
 * television while looking correct in a 1280 dp desktop dev window.
 *
 * Nothing on screen reports a Row overflowing; it simply clips. That is why these are pure
 * functions with tests rather than numbers inline in a layout.
 */

/**
 * Width of the fixed left column, from the panel's own width.
 *
 * The lower clamp is about the narrowest a television headline stays readable at; the upper one
 * stops the column turning into a margin on a 4K panel, where 30% would be over 1000 dp.
 */
internal fun tvSidebarWidthFor(width: Dp): Dp =
    (width.value * SidebarFraction).coerceIn(SidebarMin, SidebarMax).dp

/**
 * Poster width for the taste wall, from the room the step was actually given.
 *
 * Clamped at both ends. The lower clamp is the load-bearing one: a panel too narrow for five
 * legible posters gets five slightly-too-small ones, which is a visible compromise, rather than
 * a row that overflows its parent and is silently cropped.
 */
internal fun tvWallPosterWidth(available: Dp, gap: Dp, count: Int): Dp {
    require(count > 0) { "a wall needs at least one poster" }
    val usable = available.value - gap.value * (count - 1)
    return (usable / count).coerceIn(PosterMin, PosterMax).dp
}

private const val SidebarFraction = 0.30f
private const val SidebarMin = 260f
private const val SidebarMax = 400f
private const val PosterMin = 72f
private const val PosterMax = 150f
