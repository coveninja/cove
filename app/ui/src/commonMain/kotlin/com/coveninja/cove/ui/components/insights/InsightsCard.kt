package com.coveninja.cove.ui.components.insights

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.pages.profile.RowPadding

/**
 * The insights page's own container language.
 *
 * Deliberately not `SettingsCard`. That component is the *settings* visual language — icon
 * chip, bold title, grey description, inset divider, one weight for everything — and while
 * the insights page was built out of it the page read as a settings screen with charts in
 * it: fifteen identical bordered panels in which the viewer's total watch time carried
 * exactly as much weight as the count of titles they had dropped.
 *
 * Three weights fix that, and the choice between them is editorial rather than technical:
 *
 * - [Feature] is for the two or three things worth stopping on. No border, a wash of the
 *   chapter's accent, and the headline at reading size.
 * - [Standard] is for a chart that needs a container to sit in.
 * - [Quiet] has no container at all. A single number and the sentence explaining it look
 *   absurd inside a bordered panel the size of a heatmap, and a page where every fact is
 *   boxed is a page with no hierarchy.
 */
internal enum class InsightsTier { Feature, Standard, Quiet }

/**
 * The hue of the chapter currently being drawn.
 *
 * A CompositionLocal rather than a parameter on every chart because the charts are several
 * layers down from the chapter that decides the colour, and threading it through would mean
 * every intermediate composable growing a parameter it does not itself use. Charts read
 * this instead of `colorScheme.tertiary`, which is what used to make the whole page one
 * shade of green — and a page where everything is the accent is a page where nothing is.
 */
internal val LocalInsightsAccent = compositionLocalOf { CoveColors.Brand.Accent }

/**
 * The chapters the page is divided into, and the hue each one carries.
 *
 * Drawn from tokens that already exist in `CoveTheme` rather than invented here, so the
 * page cannot introduce a colour the rest of the app has never heard of. Cove's green
 * stays on the opening chapter — it is still the brand, and it still owns the total.
 */
internal enum class InsightsChapterKind(val label: String, val accent: Color) {
    Year("Your year", CoveColors.Insight.Year),
    Moments("The moments", CoveColors.Insight.Moments),
    Rhythm("Your rhythm", CoveColors.Insight.Rhythm),
    Library("Your library", CoveColors.Insight.Library),
    Taste("Your taste", CoveColors.Insight.Taste),
}

/**
 * A chapter rule, and everything under it drawn in that chapter's accent.
 *
 * The [summary] is optional and sits at the far end of the rule — a place for the one fact
 * that would otherwise need a card of its own.
 */
@Composable
internal fun InsightsChapter(
    kind: InsightsChapterKind,
    modifier: Modifier = Modifier,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalInsightsAccent provides kind.accent) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ChapterGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = ChapterTop),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = kind.label.uppercase(),
                    color = kind.accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = ChapterTracking,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(
                            // Fades out rather than stopping, so the rule reads as a
                            // division of the page and not as the top edge of a table.
                            Brush.horizontalGradient(
                                listOf(
                                    kind.accent.copy(alpha = 0.30f),
                                    kind.accent.copy(alpha = 0.03f),
                                ),
                            ),
                        ),
                )
                summary?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            content()
        }
    }
}

/**
 * One section of the page: a fixed label, a sentence about the viewer, and its chart.
 *
 * [eyebrow] is what the section used to carry as its title — "ACROSS THE YEAR" — and still
 * does the orienting job for someone scanning. [headline] is the part that is actually read:
 * a fact computed from the viewer's own numbers, from the headline functions in
 * `InsightsModel.kt`. Keeping the label means nothing is lost to the new voice; a reader
 * looking for the monthly chart can still find it by its label rather than by remembering
 * which month happened to be their biggest.
 *
 * The card imposes no padding on its content, exactly as `SettingsCard` did not. The
 * sections below it are charts, rings and poster rows that each need a different gutter —
 * several of them bleed deliberately to the card edge — so the padding stays where the
 * section that knows about it lives. [InsightsCardTop] is the shared gap below the header.
 */
@Composable
internal fun InsightsCard(
    eyebrow: String,
    headline: String,
    modifier: Modifier = Modifier,
    tier: InsightsTier = InsightsTier.Standard,
    support: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = LocalInsightsAccent.current
    val header = @Composable {
        InsightsCardHeader(
            eyebrow = eyebrow,
            headline = headline,
            support = support,
            accent = accent,
            tier = tier,
        )
    }

    when (tier) {
        InsightsTier.Quiet -> Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            header()
            Column(content = content)
        }

        InsightsTier.Standard -> Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            ),
        ) {
            Column {
                header()
                Column(content = content)
            }
        }

        InsightsTier.Feature -> Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            // No border. The wash below separates it from the page, and a hairline as well
            // would put it back in the same family as the standard cards it outranks.
            border = null,
        ) {
            Box(
                modifier = Modifier.background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.10f), Color.Transparent),
                    ),
                ),
            ) {
                Column {
                    header()
                    Column(content = content)
                }
            }
        }
    }
}

@Composable
private fun InsightsCardHeader(
    eyebrow: String,
    headline: String,
    support: String?,
    accent: Color,
    tier: InsightsTier,
) {
    Column(
        modifier = Modifier.padding(
            start = if (tier == InsightsTier.Quiet) 0.dp else RowPadding,
            end = if (tier == InsightsTier.Quiet) 0.dp else RowPadding,
            top = if (tier == InsightsTier.Quiet) 0.dp else 16.dp,
            bottom = 2.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = eyebrow.uppercase(),
            // The accent is spent on the eyebrow rather than the headline: the headline is
            // a sentence and wants to read as text, while the label is the part that
            // benefits from being findable without being read.
            color = if (tier == InsightsTier.Standard) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                accent
            },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = EyebrowTracking,
        )
        Text(
            text = headline,
            color = MaterialTheme.colorScheme.onSurface,
            style = if (tier == InsightsTier.Feature) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.Bold,
        )
        support?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The gap between a chapter's rule and its first card, and between its cards. */
private val ChapterGap = 14.dp

/**
 * Space above a chapter rule.
 *
 * Larger than the gap below it on purpose: a heading belongs to what follows it, and equal
 * space on both sides leaves it floating between two chapters instead of opening one.
 */
private val ChapterTop = 18.dp

private val ChapterTracking = 1.6.sp
private val EyebrowTracking = 1.1.sp
