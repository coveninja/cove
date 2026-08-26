package com.coveninja.cove.ui.components.player

import kotlin.math.abs

/**
 * What a drag across the picture is doing.
 *
 * Decided once, when the drag first travels far enough to have a direction, and then held for
 * the rest of it. Re-deciding on every movement would make a diagonal drag flicker between
 * seeking and changing the volume, and a hand is never as straight as the axis it means.
 */
internal enum class SurfaceDrag { Seek, Volume, Brightness }

/**
 * Which gesture a drag of [totalDx] by [totalDy] from [startX] is, or null while it is still
 * too small to say.
 *
 * The vertical half splits by where the finger started rather than where it is now: the
 * convention every mobile player shares is brightness on the left and volume on the right, and
 * a gesture that changed its mind halfway down the screen would be unusable.
 *
 * Ties go to [SurfaceDrag.Seek]. An exactly diagonal drag has to be called something, and a
 * mis-seek is visible and instantly undone where a mis-set brightness is neither.
 */
internal fun classifySurfaceDrag(
    totalDx: Float,
    totalDy: Float,
    startX: Float,
    width: Float,
    slop: Float,
): SurfaceDrag? {
    if (abs(totalDx) < slop && abs(totalDy) < slop) return null
    if (abs(totalDx) >= abs(totalDy)) return SurfaceDrag.Seek
    return if (startX < width / 2f) SurfaceDrag.Brightness else SurfaceDrag.Volume
}

/**
 * How much of the 0..1 range a vertical travel of [dy] over a [height]-tall surface covers.
 *
 * Negative [dy] is upward, which is more of whatever is being adjusted — so the sign flips
 * here rather than at each of the two call sites. A drag of the full height covers the whole
 * range: shorter would make the bottom of the range unreachable on a small screen, longer
 * would make a small correction impossible.
 */
internal fun verticalDragFraction(dy: Float, height: Float): Float {
    if (height <= 0f) return 0f
    return -dy / height
}

/**
 * How many seconds a horizontal travel of [dx] over a [width]-wide surface covers.
 *
 * Proportional to the running time rather than fixed, so the gesture means the same thing on a
 * 22-minute episode as on a three-hour film — a fixed span would cross a whole sitcom in one
 * swipe. Bounded at both ends because the proportion alone is useless in the extremes: a
 * minute of travel across a short clip is unusable precision, and ten minutes is as far as one
 * swipe should ever take you.
 */
internal fun scrubSecondsFor(dx: Float, width: Float, durationSeconds: Double): Double {
    if (width <= 0f || durationSeconds <= 0.0) return 0.0
    val span = (durationSeconds * 0.1).coerceIn(60.0, 600.0)
    return (dx / width).toDouble() * span
}

/** Travel, in pixels, before a drag is taken to mean anything at all. */
internal const val SURFACE_DRAG_SLOP = 24f
