package com.coveninja.cove.ui.pages.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coveninja.cove.shared.model.ActivityStats
import com.coveninja.cove.shared.model.ActivityTitle
import com.coveninja.cove.shared.model.ContributingTitle
import com.coveninja.cove.shared.model.DiscoveryTaste
import com.coveninja.cove.shared.model.StudioEntry
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.shared.model.DecadeCount
import com.coveninja.cove.ui.components.insights.PosterWall
import com.coveninja.cove.ui.components.insights.rememberCountUp
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.pages.common.PageLayoutDefaults
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.shared.model.Media as DomainMedia
import com.coveninja.cove.shared.model.MediaType as DomainMediaType

// Larger Insights sections and their shared UI pieces.

// ── Hero ─────────────────────────────────────────────────────────────────────

/**
 * The one moment on the page.
 *
 * Total watch time is the number people actually want, so it gets display-scale type, the
 * accent, and the page's only gradient wash. Everything below this is deliberately calmer:
 * if two things shout, neither is the headline.
 *
 * It sits on [PosterWall] — the viewer's own most-watched artwork — which is what makes the
 * page feel like theirs before a single number has been read. The wall is decoration and
 * says so: it carries no content description, and the scrims inside it mean the text does
 * not depend on which posters happened to land under it.
 */
@Composable
internal fun InsightsHero(
    activity: ActivityStats,
    thisYear: Int,
    breakdown: LibraryBreakdown,
    decades: List<DecadeCount>,
    modifier: Modifier = Modifier,
) {
    val compact = PageLayoutDefaults.IsCompact
    val accent = MaterialTheme.colorScheme.tertiary
    val delta = yearOverYearDelta(activity.thisYearSeconds, activity.lastYearSeconds)
    val counted = rememberCountUp(activity.totalSeconds)
    val identity = remember(breakdown, activity.byHourOfDay, decades) {
        identityLine(breakdown, activity, decades)
    }
    val posters = remember(activity.titlesWatchedThisYear) {
        activity.titlesWatchedThisYear.map { it.posterPath }.filter { it.isNotBlank() }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
    ) {
        Box {
            PosterWall(
                posterPaths = posters,
                modifier = Modifier.matchParentSize(),
                scrim = MaterialTheme.colorScheme.surfaceContainer,
            )
            // The accent wash stays, on top of the wall rather than instead of it. Without
            // it a hero backed by cool artwork loses its tie to the rest of the page.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                accent.copy(alpha = 0.16f),
                                accent.copy(alpha = 0.04f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Column(
                modifier = Modifier.padding(horizontal = RowPadding, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "YOU HAVE SPENT",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatWatchDuration(counted),
                        style = (
                            if (compact) {
                                MaterialTheme.typography.headlineMedium
                            } else {
                                MaterialTheme.typography.displaySmall
                            }
                            ).copy(
                            brush = Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.92f),
                                    accent,
                                    accent.copy(alpha = 0.78f),
                                ),
                            ),
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    // Keep the badge stable while the count-up changes the number's width.
                    Spacer(modifier = Modifier.weight(1f))
                    delta?.let { DeltaPill(it, thisYear - 1) }
                }
                Text(
                    // Despite its wire name, titlesThisYear follows the selected range.
                    text = "across ${activity.titlesThisYear} " +
                        if (activity.titlesThisYear == 1) "title" else "titles",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                identity?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = 2.dp),
                        color = accent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * The year-over-year badge.
 *
 * Down is grey, not red. Watching less than last year is not a failure, and colouring it as
 * an error would be the page passing judgement on how someone spends their evenings.
 */
@Composable
private fun DeltaPill(delta: YearDelta, comparedYear: Int) {
    val rising = delta.direction == TrendDirection.Up
    val tone = if (rising) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val entrance = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            entrance.snapTo(1f)
        } else {
            entrance.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        }
    }

    Row(
        modifier = Modifier
            .graphicsLayer {
                alpha = entrance.value
                scaleX = 0.7f + 0.3f * entrance.value
                scaleY = 0.7f + 0.3f * entrance.value
            }
            .clip(CircleShape)
            .background(tone.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (delta.direction != TrendDirection.Flat) {
            IconifyIcon(
                icon = if (rising) "lucide:arrow-up" else "lucide:arrow-down",
                modifier = Modifier.size(12.dp),
                tint = tone,
            )
        }
        Text(
            text = "${if (delta.percent > 0) "+" else ""}${delta.percent}% vs $comparedYear",
            color = tone,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ── Leaderboard ──────────────────────────────────────────────────────────────

/**
 * The year's most-watched titles, ranked.
 *
 * A poster row rather than a bar chart: these are things the viewer recognises on sight,
 * and a name in a list is a much weaker cue than the artwork they have already been looking
 * at all year. The share bar under each one restores the comparison a bar chart would have
 * given, without giving up the recognition.
 */
@Composable
internal fun TopTitlesRow(
    titles: List<ActivityTitle>,
    onOpenMedia: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shares = titleShares(titles)

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = RowPadding),
    ) {
        itemsIndexed(
            items = titles,
            key = { _, item -> "${item.tmdbId}:${item.mediaType}" },
        ) { index, title ->
            TopTitleCard(
                title = title,
                rank = index + 1,
                share = shares.getOrElse(index) { 0f },
                onOpenMedia = onOpenMedia,
            )
        }
    }
}

@Composable
private fun TopTitleCard(
    title: ActivityTitle,
    rank: Int,
    share: Float,
    onOpenMedia: (Media) -> Unit,
) {
    val media = remember(title) { title.toDomainMedia().toUiMedia() }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val lift by animateFloatAsState(
        targetValue = if (hovered && !reducedMotion) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "TopTitleLift",
    )
    val accent = MaterialTheme.colorScheme.tertiary
    val medal = medalColour(rank)

    Column(
        modifier = Modifier
            .width(112.dp)
            .hoverable(interaction)
            .graphicsLayer {
                scaleX = 1f + 0.035f * lift
                scaleY = 1f + 0.035f * lift
                translationY = -4f * lift
            }
            .clickable { onOpenMedia(media) },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box {
            CoveAsyncImage(
                model = media.posterUrl,
                contentDescription = title.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        width = 1.5.dp,
                        color = accent.copy(alpha = 0.75f * lift),
                        shape = RoundedCornerShape(10.dp),
                    ),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .size(21.dp)
                    .clip(CircleShape)
                    .background(medal ?: MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = rank.toString(),
                    color = if (medal != null) {
                        Color.Black.copy(alpha = 0.78f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text = title.title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatWatchDuration(title.seconds),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(medal ?: accent),
            )
        }
    }
}

/**
 * The podium colours, or null for anything off it.
 *
 * Kept here rather than in `CoveColors` because they are not app semantics — nothing else
 * in Cove means "second place". Gold reuses the rating gold, which is already the app's one
 * warm metallic, so the podium does not introduce a fourth yellow.
 */
private fun medalColour(rank: Int): Color? = when (rank) {
    1 -> CoveColors.Status.Rating
    2 -> Color(0xFFC7CBD1)
    3 -> Color(0xFFCD7F32)
    else -> null
}

// ── Contributor posters ──────────────────────────────────────────────────────

/** The titles that pushed the taste profile one way or the other. */
@Composable
internal fun ContributorRow(
    titles: List<ContributingTitle>,
    onOpenMedia: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = RowPadding),
    ) {
        items(titles, key = { "${it.tmdbId}:${it.mediaType}" }) { title ->
            ContributorPoster(
                title = title.title,
                posterPath = title.posterPath,
                tmdbId = title.tmdbId,
                wireType = title.mediaType,
                onOpenMedia = onOpenMedia,
                caption = title.title,
            )
        }
    }
}

/**
 * One small poster with an optional caption, opening the details sheet.
 *
 * Shared by the contributor rows and the rewatch row — they differ only in what is written
 * under the artwork, so the hover behaviour, the sizing and the mapping to a `Media` live
 * here once rather than being copied per row.
 */
@Composable
internal fun ContributorPoster(
    title: String,
    posterPath: String,
    tmdbId: Int,
    wireType: String,
    onOpenMedia: (Media) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    val media = remember(tmdbId, wireType, posterPath) {
        insightMedia(tmdbId, wireType, title, posterPath).toUiMedia()
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "ContributorLift",
    )

    Column(
        modifier = modifier
            .width(84.dp)
            .hoverable(interaction)
            .graphicsLayer {
                scaleX = 1f + 0.04f * lift
                scaleY = 1f + 0.04f * lift
            }
            .clickable { onOpenMedia(media) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CoveAsyncImage(
            model = media.posterUrl,
            contentDescription = title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f * lift),
                    shape = RoundedCornerShape(9.dp),
                ),
            contentScale = ContentScale.Crop,
        )
        caption?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Pills ────────────────────────────────────────────────────────────────────

/**
 * Keywords, people and studios as a wrapping field of chips.
 *
 * Sized by rank rather than listed flat: a keyword field where every chip is identical
 * forces the reader to trust the order, which nobody does. Growing the strongest ones makes
 * the shape of the profile readable before a single word has been read.
 */
@Composable
internal fun TasteChips(
    entries: List<TasteChip>,
    modifier: Modifier = Modifier,
    tone: Color = MaterialTheme.colorScheme.tertiary,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        entries.forEach { chip ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val lift by animateFloatAsState(
                targetValue = if (hovered) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                label = "ChipLift",
            )
            Row(
                modifier = Modifier
                    .hoverable(interaction)
                    .graphicsLayer {
                        scaleX = 1f + 0.05f * lift
                        scaleY = 1f + 0.05f * lift
                    }
                    .clip(CircleShape)
                    .background(tone.copy(alpha = 0.08f + 0.16f * chip.weight + 0.14f * lift))
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = chip.label,
                    color = if (chip.weight > 0.55f) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (chip.weight > 0.55f) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    },
                    maxLines = 1,
                )
                chip.trailing?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** A chip's label, its 0f..1f strength, and an optional count on the end. */
internal data class TasteChip(val label: String, val weight: Float, val trailing: String? = null)

/** Ranked taste entries as chips, strongest first. */
internal fun tasteChips(entries: List<DiscoveryTaste>): List<TasteChip> =
    normalizeTaste(entries).map { TasteChip(it.name, it.fraction) }

/** Studios carry a real count, which is a number worth showing — unlike a taste score. */
internal fun studioChips(entries: List<StudioEntry>): List<TasteChip> {
    val peak = entries.maxOfOrNull { it.count } ?: 0
    return entries.map { studio ->
        TasteChip(
            label = studio.name,
            weight = if (peak <= 0) 0f else studio.count.toFloat() / peak,
            trailing = studio.count.toString(),
        )
    }
}

// ── Explainer ────────────────────────────────────────────────────────────────

/**
 * How the taste profile is built, in the viewer's own terms.
 *
 * Collapsed by default because it is reference material, not a finding — but present,
 * because a recommendation engine that will not say what it is doing is the thing people
 * distrust most about them. The weights listed here are the ones `DiscoveryService`
 * actually applies; if those change, this has to change with them.
 */
@Composable
internal fun RecommendationExplainer(
    signalsUsed: Int,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "ExplainerChevron",
    )

    SettingsCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = RowPadding, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconifyIcon(
                icon = "lucide:info",
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = "How your recommendations are built",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            IconifyIcon(
                icon = "lucide:chevron-down",
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = RowPadding,
                    end = RowPadding,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Your profile is built from $signalsUsed title" +
                        (if (signalsUsed == 1) "" else "s") +
                        " you've actively engaged with. Each becomes a like or dislike " +
                        "weight, which is spread across that title's genres and keywords:",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEIGHT_ROWS.forEach { (label, weight) ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = label,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = weight,
                                color = if (weight.startsWith("−")) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Text(
                    text = "Older signals fade over time — a favourite from a year ago " +
                        "still counts at roughly half strength, so what you are watching " +
                        "now leads what you watched then.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Rating a title below ★3 always counts as a dislike, even if " +
                        "you finished it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Mirrors the weights in `DiscoveryService.BuildProfile`. Keep the two in step. */
private val WEIGHT_ROWS = listOf(
    "Finished a title" to "+2.0",
    "Currently watching" to "+1.0",
    "Saved to watch later" to "+0.25",
    "Each ★ above or below 3" to "±1.5",
    "Dropped" to "−2.0",
)

// ── Small shared pieces ──────────────────────────────────────────────────────

/**
 * The gap between a card's header divider and whatever the card draws underneath it.
 *
 * `SettingsCard` deliberately leaves its content slot flush, because on the Settings tab
 * every child is a row that brings its own vertical padding. Nothing on this page is: the
 * children are charts, poster rows and rings that measure to their own edges, so each one
 * needs the gap the settings rows supply for themselves. One constant so they cannot drift
 * apart card by card — which is exactly how the ring and the poster rows ended up sitting
 * against the divider while their neighbours did not.
 */
internal val InsightsCardTop = 14.dp

/** A section heading inside a card, for cards that hold more than one chart. */
@Composable
internal fun SubSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Centred "nothing here yet" text for a section whose own slice of data is empty. */
@Composable
internal fun SectionEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
}

// ── Mappers ──────────────────────────────────────────────────────────────────

// Use the domain mapper so media ids and image URL rewriting stay consistent.

internal fun ActivityTitle.toDomainMedia(): DomainMedia =
    insightMedia(tmdbId, mediaType, title, posterPath)

internal fun ContributingTitle.toDomainMedia(): DomainMedia =
    insightMedia(tmdbId, mediaType, title, posterPath)

internal fun insightMedia(
    tmdbId: Int,
    wireType: String,
    title: String,
    posterPath: String,
): DomainMedia {
    val type = if (wireType == DomainMediaType.Movie.wireName) {
        DomainMediaType.Movie
    } else {
        DomainMediaType.Tv
    }
    return DomainMedia(
        id = tmdbId,
        title = title.takeIf { type == DomainMediaType.Movie },
        name = title.takeIf { type == DomainMediaType.Tv },
        posterPath = posterPath,
        mediaType = type,
    )
}
