package com.coveninja.cove.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Exact hex from oklch conversion of web/src/assets/app.css variables.
private val Background         = Color(0xFF0A0A0A)  // oklch(0.145 0 0)
private val Foreground         = Color(0xFFFAFAFA)  // oklch(0.985 0 0)
private val Card               = Color(0xFF171717)  // oklch(0.205 0 0)
private val Secondary          = Color(0xFF262626)  // oklch(0.269 0 0) secondary/muted
private val MutedForeground    = Color(0xFFA1A1A1)  // oklch(0.708 0 0)
private val AccentGreen        = Color(0xFF26DF6A)  // oklch(0.793 0.212 149.472)
private val AccentForeground   = Color(0xFF171717)  // oklch(0.205 0 0)
private val Destructive        = Color(0xFFFF6467)  // oklch(0.704 0.191 22.216)
private val Ring               = Color(0xFF737373)  // oklch(0.556 0 0)
private val Border             = Color(0x1AFFFFFF)  // oklch(1 0 0 / 10%)
private val BorderVariant      = Color(0x26FFFFFF)  // ~15% white

// A dark greenish container for primary containers (e.g. FAB background).
private val PrimaryContainer   = Color(0xFF0E3320)
// For secondaryContainer: use Card color so the M3 nav-bar indicator pill is
// the same shade as surfaces (effectively invisible), while onSecondaryContainer
// is green so the selected icon/label read as the brand accent color.
private val SecondaryContainer = Card

private val DarkColorScheme = darkColorScheme(
    // ── Backgrounds ──────────────────────────────────────────────────────────
    background            = Background,
    surface               = Background,
    surfaceVariant        = Card,
    surfaceContainer      = Card,
    surfaceContainerLow   = Background,
    surfaceContainerHigh  = Secondary,
    surfaceTint           = Color.Transparent, // prevent green tint on elevated surfaces
    inverseSurface        = Foreground,
    inverseOnSurface      = Background,

    // ── Foregrounds ──────────────────────────────────────────────────────────
    onBackground          = Foreground,
    onSurface             = Foreground,
    onSurfaceVariant      = MutedForeground,

    // ── Primary (brand GREEN — accent color) ─────────────────────────────────
    // Buttons, FABs, progress indicators, FilterChip/Chip selected state
    primary               = AccentGreen,
    onPrimary             = AccentForeground,
    primaryContainer      = PrimaryContainer,
    onPrimaryContainer    = AccentGreen,
    inversePrimary        = Color(0xFF1A6E3C),  // darker green for light-surface inverse

    // ── Secondary (neutral — nav indicator pill + secondary containers) ───────
    // secondaryContainer = Card makes the NavigationBarItem indicator pill
    // blend into the background. onSecondaryContainer = green makes the
    // selected icon/text read as the brand accent, matching desktop text-accent.
    secondary             = Secondary,
    onSecondary           = Foreground,
    secondaryContainer    = SecondaryContainer,
    onSecondaryContainer  = AccentGreen,

    // ── Tertiary ─────────────────────────────────────────────────────────────
    tertiary              = Ring,
    onTertiary            = Foreground,
    tertiaryContainer     = Secondary,
    onTertiaryContainer   = Foreground,

    // ── Error ────────────────────────────────────────────────────────────────
    error                 = Destructive,
    onError               = AccentForeground,
    errorContainer        = Color(0xFF4A1B1D),
    onErrorContainer      = Destructive,

    // ── Outline / scrim ──────────────────────────────────────────────────────
    outline               = Border,
    outlineVariant        = BorderVariant,
    scrim                 = Color(0xFF000000),
)

// Corner radii scaled from --radius: 0.625rem (10dp).
private val CoveShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(10.dp),
    large      = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun CoveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        shapes      = CoveShapes,
        content     = content,
    )
}
