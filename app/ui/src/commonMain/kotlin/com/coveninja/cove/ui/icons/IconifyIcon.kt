package com.coveninja.cove.ui.icons

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Renders a real Iconify icon from the compile-time-generated [CoveIcons] data table.
 *
 * **Colour:** any path that uses `currentColor` (the SVG convention for "inherit from
 * surrounding text colour") is resolved to the effective colour — [tint] when specified,
 * otherwise [LocalContentColor.current] — and baked into the ImageVector. [Color.Unspecified]
 * is then passed to [Icon] so nothing double-tints. This means untinted icons follow content
 * colour, whereas the old Material-icon shim drew them in each vector's own fixed black.
 *
 * **Missing key:** [verifyIcons] (wired to all Kotlin compile tasks) makes an unknown icon
 * unreachable from a normal build. If one somehow appears at runtime we draw nothing and
 * return rather than showing a misleading placeholder glyph.
 */
@Composable
fun IconifyIcon(
    icon: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val spec = CoveIcons[icon] ?: return

    // Resolve the effective colour once. LocalContentColor is Material's ambient icon colour.
    val contentColor = LocalContentColor.current
    val effectiveColor = if (tint != Color.Unspecified) tint else contentColor

    val vector = remember(icon, effectiveColor) { buildVector(spec, effectiveColor) }

    Icon(
        imageVector = vector,
        contentDescription = null,
        modifier = modifier,
        // Colour is already baked into each path's brush; Unspecified prevents double-tinting.
        tint = Color.Unspecified,
    )
}

private fun buildVector(spec: IconSpec, currentColor: Color): ImageVector =
    ImageVector.Builder(
        defaultWidth = spec.width.dp,
        defaultHeight = spec.height.dp,
        viewportWidth = spec.width,
        viewportHeight = spec.height,
    ).apply {
        for (p in spec.paths) {
            // PathParser converts the compact SVG path string to the node list that
            // addPath (ImageVector.Builder method) accepts as its "pathData" parameter.
            val nodes = PathParser().parsePathString(p.pathData).toNodes()
            addPath(
                pathData = nodes,
                fill = resolveColor(p.fill, currentColor)?.let { SolidColor(it) },
                stroke = resolveColor(p.stroke, currentColor)?.let { SolidColor(it) },
                strokeLineWidth = p.strokeWidth,
                strokeLineCap = when (p.strokeCap) {
                    "round"  -> StrokeCap.Round
                    "square" -> StrokeCap.Square
                    else     -> StrokeCap.Butt
                },
                strokeLineJoin = when (p.strokeJoin) {
                    "round" -> StrokeJoin.Round
                    "bevel" -> StrokeJoin.Bevel
                    else    -> StrokeJoin.Miter
                },
                pathFillType = if (p.fillEvenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            )
        }
    }.build()

private fun resolveColor(value: String?, currentColor: Color): Color? = when (value) {
    null           -> null
    "currentColor" -> currentColor
    else           -> parseHexColor(value)
}

private fun parseHexColor(value: String): Color? {
    if (!value.startsWith("#")) return null
    val digits = value.removePrefix("#")
    return try {
        when (digits.length) {
            // Prepend FF for full opacity when only RGB is given.
            6 -> Color(("FF$digits").toLong(16).toInt())
            8 -> Color(digits.toLong(16).toInt())
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }
}
