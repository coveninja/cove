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

/**
 * Cove's palette, in one place, shared by every host.
 *
 * This module is `commonMain`, so desktop and Android read the same values — there is no
 * per-platform palette to keep in step. The one value that *is* duplicated outside Kotlin is
 * the Android window background in `app/mobile/src/main/res/values/themes.xml`, which the
 * platform applies before any Compose code runs; it must match [Neutral.Background] or a seam
 * appears wherever the window shows through.
 *
 * Organised as ramps rather than a flat list of role names. A role ("border", "muted") answers
 * one question and then has to be duplicated the moment something needs the same colour for a
 * different reason; a ramp lets a component pick the step it needs and keeps the greys
 * genuinely consistent. The semantic groups below name colours by *what they mean* — a
 * category, a segment, a status — because those are the ones that must stay distinguishable
 * from each other.
 */
object CoveColors {

    /**
     * The greys, darkest to lightest.
     *
     * Cove is a dark-only surface stack: five near-blacks separated by a few points of
     * lightness each, which is what lets a raised card read as raised without a border doing
     * all the work.
     */
    object Neutral {
        /** The page behind everything. */
        val Background = Color(0xFF0A0A0A)
        /** Default surface, and the lowest container step. */
        val Surface = Color(0xFF101010)
        /** A card lifted off the page. */
        val SurfaceHigh = Color(0xFF171717)
        /** The standard raised container — menus, cards, sheets. */
        val SurfaceRaised = Color(0xFF1A1A1A)
        /** The topmost step, for something raised above an already-raised surface. */
        val SurfaceHighest = Color(0xFF1C1C1C)

        /** Hairlines and outlines. */
        val Border = Color(0xFF6F6F6F)
        /** Text that is present but secondary. */
        val Muted = Color(0xFFA3A3A3)
        /** Muted a step further — disabled, or deliberately unemphasised. */
        val MutedDim = Color(0xFF959595)
        /** Primary text. Not pure white; pure white on near-black shimmers. */
        val Text = Color(0xFFFAFAFA)
    }

    /**
     * The brand accent and what stays legible on top of it.
     *
     * One accent, used for primary and tertiary alike — Cove leans on a single green rather
     * than a primary/secondary pair, so mapping both roles to it keeps Material components
     * from introducing a second brand colour of their own.
     */
    object Brand {
        val Accent = Color(0xFF4ADE80)
        /** Deep green-black; the accent is bright enough that dark text is the legible choice. */
        val OnAccent = Color(0xFF071B10)
        /** The accent at container weight, for a filled area rather than a glyph. */
        val AccentContainer = Color(0xFF13351F)
    }

    /**
     * Status colours.
     *
     * These are the app's opinions about outcomes, and they are deliberately the same hues as
     * the library categories that mean the same thing — [Category.Finished] is [Success],
     * [Category.Dropped] is [Danger]. Keeping one hue per meaning is what stops "green" from
     * meaning two different things on two screens.
     */
    object Status {
        val Success = Color(0xFF2DAA24)
        val Info = Color(0xFF0E94BD)
        val Warning = Color(0xFFE5851E)
        val Danger = Color(0xFFC11A16)
        /** Danger at container weight, for an error surface rather than error text. */
        val DangerContainer = Color(0xFF3A0F0E)

        /** Ratings, which are gold everywhere by convention. */
        val Rating = Color(0xFFFFC107)
        /** The same gold, brightened for a filled or hovered star. */
        val RatingBright = Color(0xFFFFD54F)
    }

    /**
     * Library category accents.
     *
     * Five categories that appear side by side in the nav bar's drag targets, so these have to
     * stay mutually distinguishable — that constraint is asserted in `CoveColorsTest`.
     */
    object Category {
        val Watching = Status.Info
        val WatchLater = Status.Warning
        val Finished = Status.Success
        val Dropped = Status.Danger
        val NotInterested = Neutral.MutedDim
    }

    /**
     * Player timeline segment accents.
     *
     * A separate family from [Status] on purpose: these mark stretches of a video, not
     * outcomes, and they sit adjacent on the seek bar where reusing the status hues would
     * imply a judgement about the content.
     */
    object Segment {
        val Recap = Color(0xFF7C6CF0)
        val Intro = Color(0xFFEFA33C)
        val Credits = Color(0xFF35B98A)
        val Preview = Color(0xFF3FA9D6)
    }

    /**
     * The green ramp, deepest to palest.
     *
     * An ordered ramp rather than a set of roles, because nothing here means anything — it is
     * the family the onboarding backdrop draws its wall of cards from, and what it needs is
     * shades that read as *varied* while still reading as one colour. Cove is a green app; a
     * backdrop cycling through seven unrelated hues looked like a colour test.
     *
     * Two of the six are the app's existing greens rather than near-misses of them:
     * [Brand.Accent] and [Segment.Credits] sit at steps 4 and 3. Picking new values a few points
     * away from those would have put three almost-identical greens in the palette with nothing
     * to say which was which.
     *
     * Ordered by luminance, and `CoveColorsTest` holds that: a ramp that does not climb is not a
     * ramp, and a consumer picking "two steps lighter" would silently get something darker.
     */
    object Seafoam {
        /** Deep pine, nearly submerged against the page. */
        val Deepest = Color(0xFF146B4A)
        val Deep = Color(0xFF1E8E63)
        /** The player's credits marker: green with the first hint of teal. */
        val Mid = Segment.Credits
        /** The brand accent itself. */
        val Bright = Brand.Accent
        /** Where the ramp turns properly seafoam. */
        val Pale = Color(0xFF6EE7B7)
        /** Pale mint; the lightest step that still has colour in it. */
        val Palest = Color(0xFFA7F3D0)

        /** The ramp in order, for anything that wants to walk or sample it. */
        val ramp: List<Color> = listOf(Deepest, Deep, Mid, Bright, Pale, Palest)
    }

    /** Scrim behind a modal surface. Artwork gradients build their own from this. */
    val Scrim = Color(0xFF000000)
}

/**
 * The Material mapping.
 *
 * Every role is filled. Anything left unset falls through to Material's own defaults, which
 * are a light-theme purple family — `error` and `errorContainer` were doing exactly that
 * despite being read in 17 places, so error states rendered in a red that was not Cove's.
 */
private val CoveColorScheme = darkColorScheme(
    primary = CoveColors.Brand.Accent,
    onPrimary = CoveColors.Brand.OnAccent,
    primaryContainer = CoveColors.Brand.AccentContainer,
    onPrimaryContainer = CoveColors.Brand.Accent,
    inversePrimary = CoveColors.Brand.OnAccent,

    secondary = CoveColors.Neutral.Muted,
    onSecondary = CoveColors.Neutral.Background,
    secondaryContainer = CoveColors.Neutral.SurfaceHigh,
    onSecondaryContainer = CoveColors.Neutral.Text,

    tertiary = CoveColors.Brand.Accent,
    onTertiary = CoveColors.Brand.OnAccent,
    tertiaryContainer = CoveColors.Brand.AccentContainer,
    onTertiaryContainer = CoveColors.Brand.Accent,

    background = CoveColors.Neutral.Background,
    onBackground = CoveColors.Neutral.Text,

    surface = CoveColors.Neutral.Surface,
    onSurface = CoveColors.Neutral.Text,
    surfaceVariant = CoveColors.Neutral.SurfaceRaised,
    onSurfaceVariant = CoveColors.Neutral.Muted,
    surfaceTint = CoveColors.Brand.Accent,

    surfaceContainerLowest = CoveColors.Neutral.Background,
    surfaceContainerLow = CoveColors.Neutral.Surface,
    surfaceContainer = CoveColors.Neutral.SurfaceRaised,
    surfaceContainerHigh = CoveColors.Neutral.SurfaceHigh,
    surfaceContainerHighest = CoveColors.Neutral.SurfaceHighest,

    inverseSurface = CoveColors.Neutral.Text,
    inverseOnSurface = CoveColors.Neutral.Background,

    error = CoveColors.Status.Danger,
    onError = CoveColors.Neutral.Text,
    errorContainer = CoveColors.Status.DangerContainer,
    onErrorContainer = CoveColors.Neutral.Text,

    outline = CoveColors.Neutral.Border,
    outlineVariant = Color.White.copy(alpha = 0.16f),
    scrim = CoveColors.Scrim,
)

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
        colorScheme = CoveColorScheme,
        typography = CoveTypography,
        shapes = CoveShapes,
        content = content,
    )
}
