package com.coveninja.cove.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Cove's mark, as the artwork actually is.
 *
 * Generated from `packaging/icons/cove.svg` — the same file the desktop package, the installer
 * and the site are built from — rather than approximated in code, so the logo on a television
 * cannot drift away from the logo everywhere else. Four paths, each with its own gradient: the
 * bright outer C and the three darker folds that give it depth.
 *
 * Built once and held, because parsing four path strings on every recomposition of a navigation
 * rail would be work repeated for a picture that never changes.
 */
val CoveLogoVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "CoveLogo",
        defaultWidth = 40.dp,
        defaultHeight = 40.dp,
        viewportWidth = 500f,
        viewportHeight = 500f,
    ).apply {
        addPath(
            // Deliberately one unbroken string. Splitting path data across concatenated lines
            // is how this file was first generated, and it silently ate the space at every
            // break — merging two coordinates into one number and drawing a mark that was
            // recognisably wrong but not obviously corrupt.
            pathData = PathParser().parsePathString("M202.613 249.269C202.613 223.372 176.209 202.286 190.777 181.081C205.345 159.876 223.276 139.622 251.077 139.622C254.152 139.622 256.827 142.175 259.823 142.539C236.692 145.696 221.204 162.052 208.131 181.081C193.564 202.286 219.966 223.372 219.966 249.269C219.966 275.166 193.564 296.252 208.131 317.457C221.204 336.486 238.989 353.394 262.121 356.552C259.125 356.915 254.153 358.129 251.077 358.129C223.276 358.128 205.345 338.662 190.777 317.457C176.209 296.252 202.613 275.166 202.613 249.269Z").toNodes(),
            fill = Brush.linearGradient(
                colors = listOf(Color(0xFF064D39), Color(0xFF063225)),
                start = Offset(223.107f, 249.269f),
                end = Offset(223.107f, 141.988f),
            ),
        )
        addPath(
            // Deliberately one unbroken string. Splitting path data across concatenated lines
            // is how this file was first generated, and it silently ate the space at every
            // break — merging two coordinates into one number and drawing a mark that was
            // recognisably wrong but not obviously corrupt.
            pathData = PathParser().parsePathString("M185.259 249.269H202.613C202.613 275.166 176.21 296.252 190.778 317.457C203.851 336.487 220.058 354.183 243.19 357.34C240.194 357.704 236.434 356.551 233.358 356.551C205.557 356.551 187.991 338.663 173.423 317.457C158.856 296.252 185.259 275.166 185.259 249.269Z").toNodes(),
            fill = Brush.linearGradient(
                colors = listOf(Color(0xFF168969), Color(0xFF0A382B)),
                start = Offset(205.754f, 249.701f),
                end = Offset(205.754f, 357.411f),
            ),
        )
        addPath(
            // Deliberately one unbroken string. Splitting path data across concatenated lines
            // is how this file was first generated, and it silently ate the space at every
            // break — merging two coordinates into one number and drawing a mark that was
            // recognisably wrong but not obviously corrupt.
            pathData = PathParser().parsePathString("M185.259 249.269H202.613C202.613 223.373 176.21 202.287 190.778 181.081C203.851 162.052 220.058 144.356 243.19 141.199C240.194 140.836 236.434 141.988 233.358 141.988C205.557 141.988 187.991 159.876 173.423 181.081C158.856 202.287 185.259 223.373 185.259 249.269Z").toNodes(),
            fill = Brush.linearGradient(
                colors = listOf(Color(0xFF168968), Color(0xFF0A382B)),
                start = Offset(205.754f, 249.269f),
                end = Offset(205.754f, 141.988f),
            ),
        )
        addPath(
            // Deliberately one unbroken string. Splitting path data across concatenated lines
            // is how this file was first generated, and it silently ate the space at every
            // break — merging two coordinates into one number and drawing a mark that was
            // recognisably wrong but not obviously corrupt.
            pathData = PathParser().parsePathString("M110.359 249.269C110.359 205.988 71.9634 165.875 93.6243 132.947C127.998 80.6947 184.904 46.539 249.287 46.5388C316.648 46.5388 375.821 83.9276 409.571 140.311C410.953 142.62 410.185 145.597 407.894 147.009L339.155 189.346C336.772 190.814 333.656 190.031 332.123 187.689C313.877 159.808 283.579 141.569 249.287 141.569C217.901 141.569 198.07 159.527 181.623 180.815C165.176 202.103 194.985 223.271 194.985 249.269C194.985 275.268 165.176 296.436 181.623 317.724C198.07 339.012 217.901 356.97 249.287 356.97C283.579 356.97 313.877 338.73 332.123 310.85C333.656 308.508 336.772 307.725 339.155 309.193L407.894 351.53C410.185 352.941 410.953 355.918 409.571 358.227C375.821 414.611 316.648 452 249.287 452C184.904 452 127.998 417.844 93.6243 365.591C71.9634 332.664 110.359 292.551 110.359 249.269Z").toNodes(),
            fill = Brush.linearGradient(
                colors = listOf(Color(0xFF27EFB6), Color(0xFF168969)),
                start = Offset(249.5f, 46.5388f),
                end = Offset(249.5f, 452.0f),
            ),
        )
    }.build()
}

/** The mark at whatever size the caller gives it; the vector scales to its bounds. */
@Composable
fun CoveLogo(modifier: Modifier = Modifier) {
    Image(
        imageVector = CoveLogoVector,
        contentDescription = "Cove",
        modifier = modifier,
    )
}
