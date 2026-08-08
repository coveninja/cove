package com.coveninja.cove.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Design tokens — intentionally minimal; the user will redesign this.
// Colors are kept here so a single-file swap replaces the entire palette.
object CoveColors {
    val background   = Color(0xFF0A0A0A)
    val surface      = Color(0xFF1F1F1F)
    val surfaceRaised = Color(0xFF2C2C2C)
    val accent       = Color(0xFF4ADE80)
    val text         = Color(0xFFFAFAFA)
    val muted        = Color(0xFFA3A3A3)
    val border       = Color.White.copy(alpha = 0.10f)
}

object CoveSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

private val CoveTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

private val CoveShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun CoveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background       = CoveColors.background,
            surface          = CoveColors.surface,
            surfaceContainer = CoveColors.surfaceRaised,
            surfaceContainerLow = Color(0xFF181919),
            surfaceContainerHigh = Color(0xFF272929),
            surfaceContainerHighest = Color(0xFF303333),
            primary          = CoveColors.accent,
            onPrimary        = CoveColors.background,
            tertiary         = CoveColors.accent,
            onTertiary       = Color(0xFF071B10),
            onBackground     = CoveColors.text,
            onSurface        = CoveColors.text,
            onSurfaceVariant = CoveColors.muted,
            secondary        = CoveColors.muted,
            onSecondary      = CoveColors.background,
            outline          = CoveColors.border,
            outlineVariant   = Color.White.copy(alpha = 0.16f),
        ),
        typography = CoveTypography,
        shapes = CoveShapes,
        content = content,
    )
}
