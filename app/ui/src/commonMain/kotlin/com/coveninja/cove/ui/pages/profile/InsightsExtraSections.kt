package com.coveninja.cove.ui.pages.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.model.ActivityStats
import com.coveninja.cove.shared.model.DiscoveryInsights
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.TitlePlayCount
import com.coveninja.cove.shared.model.TrackerProvider
import com.coveninja.cove.shared.model.TrackerStats
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.insights.MiniBars
import com.coveninja.cove.ui.components.insights.RankedBars
import com.coveninja.cove.ui.components.insights.rememberChartReveal
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

// The cards added after the first pass. Kept apart from InsightsSections so the shape of
// the original page stays readable; these are all small, self-contained, and sit in the
// two-column grid rather than in the page's main reading order.

// ── You against the crowd ────────────────────────────────────────────────────

/**
 * Whether the viewer agrees with everyone else.
 *
 * The headline is the average gap, signed, because the direction is the interesting part —
 * "you rate half a point above the crowd" says something about a person in a way that a
 * list of their scores does not. Underneath, the titles they disagreed with hardest, which
 * is where the character actually shows.
 */
@Composable
internal fun CrowdSection(comparison: RatingComparison) {
    val generous = comparison.averageDelta > 0
    val rounded = (abs(comparison.averageDelta) * 10).roundToInt() / 10.0

    SettingsCard(
        title = "You against the crowd",
        iconName = "lucide:star",
        description = "How your ratings compare with everyone else's.",
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = RowPadding)
                .padding(top = InsightsCardTop, bottom = 18.dp)
                .semantics {
                    contentDescription = "Across ${comparison.rated} rated titles you score " +
                        "$rounded points ${if (generous) "above" else "below"} the public average."
                },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = (if (generous) "+" else "−") + "$rounded",
                    color = if (generous) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        CoveColors.Status.Warning
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "points vs the crowd, across ${comparison.rated} rated",
                    modifier = Modifier.padding(bottom = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CountFact("${comparison.higher}", "rated above")
                CountFact("${comparison.lower}", "rated below")
            }

            if (comparison.gaps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubSectionLabel("Where you disagree most")
                    comparison.gaps.take(4).forEach { gap ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = gap.title,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "you ${trim(gap.yours)} · them ${trim(gap.crowd)}",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun trim(value: Double): String = ((value * 10).roundToInt() / 10.0).toString()

// ── Finishing what you start ─────────────────────────────────────────────────

/**
 * How often things get finished, and what is sitting half-watched.
 *
 * The stalled list is the useful half — it is the only place in the app that answers "what
 * did I put down and forget about", which is a question the library itself cannot show
 * because a part-watched title looks identical to an untouched one in a grid of posters.
 */
@Composable
internal fun FinishSection(stats: FinishStats) {
    val percent = (stats.rate * 100).roundToInt()

    SettingsCard(
        title = "Finishing what you start",
        iconName = "lucide:badge-check",
        description = "Of everything you have begun, how much you saw through.",
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = RowPadding)
                .padding(top = InsightsCardTop, bottom = 18.dp)
                .semantics {
                    contentDescription = "You finish $percent percent of what you start, " +
                        "${stats.finished} of ${stats.started}."
                },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "$percent%",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${stats.finished} finished of ${stats.started} started",
                    modifier = Modifier.padding(bottom = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            val reveal = rememberChartReveal(stats)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((stats.rate * reveal).coerceIn(0f, 1f))
                        .height(9.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }

            if (stats.stalled.isNotEmpty()) {
                Text(
                    text = "${stalledLabel(stats.stalled.size)} waiting where you left off",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun stalledLabel(count: Int): String =
    if (count == 1) "1 title" else "$count titles"

// ── Library growth ───────────────────────────────────────────────────────────

/** When titles were saved, as opposed to when they were watched. */
@Composable
internal fun GrowthSection(entries: List<LibraryEntry>, today: LocalDate) {
    val growth = libraryGrowth(entries, today)
    val total = growth.sumOf { it.added }

    SettingsCard(
        title = "How your library grew",
        iconName = "lucide:bookmark-plus",
        description = "Titles saved per month over the past year.",
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = RowPadding)
                .padding(top = InsightsCardTop, bottom = 18.dp)
                .semantics {
                    contentDescription = "$total titles saved in the past twelve months."
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MiniBars(
                values = growth.map { it.fraction },
                labels = growth.map { it.label },
            )
            Text(
                text = "$total saved in the past year",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ── All time ─────────────────────────────────────────────────────────────────

/** Every year on record — the chart the by-year series was always for. */
@Composable
internal fun AllTimeSection(stats: ActivityStats) {
    val bars = yearBars(stats.byYear)
    val best = bars.maxByOrNull { it.seconds }

    SettingsCard(
        title = "Every year on record",
        iconName = "lucide:calendar-clock",
        description = best?.let { "${it.year} was your biggest year." }
            ?: "Watch time by year.",
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = RowPadding)
                .padding(top = InsightsCardTop, bottom = 18.dp)
                .semantics {
                    contentDescription = bars.joinToString(", ") {
                        "${it.year}: ${formatWatchDuration(it.seconds)}"
                    }
                },
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            bars.forEach { bar ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = bar.year,
                        modifier = Modifier.width(42.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(bar.fraction.coerceIn(0f, 1f))
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    if (bar == best) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)
                                    },
                                ),
                        )
                    }
                    Text(
                        text = formatWatchDuration(bar.seconds),
                        modifier = Modifier.width(72.dp).padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ── Decades and languages ────────────────────────────────────────────────────

/** Which eras the library is drawn from. */
@Composable
internal fun DecadesSection(profile: DiscoveryInsights) {
    val peak = profile.decades.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    val scaled = profile.decades.take(6).map { decade ->
        TasteBar(
            name = decadeLabel(decade.decade),
            score = decade.count.toDouble(),
            fraction = decade.count.toFloat() / peak,
        )
    }

    SettingsCard(
        title = "The eras you watch",
        iconName = "lucide:clock-3",
        description = profile.averageRuntimeMinutes
            .takeIf { it > 0 }
            ?.let { "Typically ${it}m at a sitting." }
            ?: "Release decades across your library.",
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = RowPadding)
                .padding(top = InsightsCardTop, bottom = 18.dp)
                .semantics {
                    contentDescription = scaled.joinToString(", ") { it.name }
                },
        ) {
            RankedBars(bars = scaled)
        }
    }
}

/** Where the library comes from, by original language. */
@Composable
internal fun LanguagesSection(profile: DiscoveryInsights) {
    // Merged by name first: zh and cn are both Chinese, and grouping by code listed it twice.
    val merged = namedLanguages(profile.languages)
    val peak = merged.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val bars = merged.take(6).map { (name, count) ->
        TasteBar(name, count.toDouble(), count.toFloat() / peak)
    }

    SettingsCard(
        title = "Where it comes from",
        iconName = "lucide:languages",
        description = "Original languages across your library.",
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = RowPadding)
                .padding(top = InsightsCardTop, bottom = 18.dp)
                .semantics { contentDescription = bars.joinToString(", ") { it.name } },
        ) {
            RankedBars(bars = bars)
        }
    }
}

// ── Rewatches ────────────────────────────────────────────────────────────────

/** The titles worth going back to. */
@Composable
internal fun RewatchSection(titles: List<TitlePlayCount>, onOpenMedia: (Media) -> Unit) {
    SettingsCard(
        title = "Worth going back to",
        iconName = "lucide:rotate-ccw",
        description = "Titles you have started more than once.",
    ) {
        Column(
            modifier = Modifier
                .padding(top = InsightsCardTop, bottom = 18.dp)
                .semantics {
                    contentDescription = titles.joinToString(", ") {
                        "${it.title}, ${it.plays} times"
                    }
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = RowPadding),
            ) {
                items(titles, key = { "${it.tmdbId}:${it.mediaType}" }) { title ->
                    Column(
                        modifier = Modifier.width(84.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RewatchPoster(title = title, onOpenMedia = onOpenMedia)
                        Text(
                            text = "${title.plays}×",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RewatchPoster(title: TitlePlayCount, onOpenMedia: (Media) -> Unit) {
    // Reuses the contributor poster rather than a third near-identical card; the only
    // difference here is the caption underneath, which the caller supplies.
    ContributorPoster(
        title = title.title,
        posterPath = title.posterPath,
        tmdbId = title.tmdbId,
        wireType = title.mediaType,
        onOpenMedia = onOpenMedia,
    )
}

// ── Trackers ─────────────────────────────────────────────────────────────────

/**
 * One tracker's own totals, clearly attributed.
 *
 * Deliberately not folded into the numbers above. A tracker may carry years of history
 * from before Cove was installed, so these figures will usually disagree with the rest of
 * the page — presenting them as that account's answer rather than Cove's is the honest
 * arrangement, and the disagreement is itself the reason to show them. Two linked trackers
 * get a card each for the same reason: they will disagree with each other too.
 */
@Composable
internal fun TrackerSection(stats: TrackerStats) {
    val totalHours = (stats.movieMinutes + stats.episodeMinutes) / 60
    val label = TrackerProvider.fromKey(stats.provider)?.label ?: stats.provider

    SettingsCard(
        title = "All time on $label",
        iconName = "lucide:cloud-check",
        description = "Totals from your linked $label account, including anything " +
            "watched before Cove.",
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = RowPadding)
                .padding(top = InsightsCardTop, bottom = 18.dp)
                .semantics {
                    contentDescription = "$label reports ${stats.moviesWatched} movies and " +
                        "${stats.episodesWatched} episodes, ${totalHours} hours in total."
                },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                CountFact("${stats.moviesWatched}", "movies")
                CountFact("${stats.showsWatched}", "shows")
                CountFact("${stats.episodesWatched}", "episodes")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                CountFact("${totalHours}h", "watched")
                // Simkl publishes no ratings total, so this reads 0 there rather than
                // being hidden — an absent row would look like a layout bug beside Trakt's.
                CountFact("${stats.ratings}", "ratings")
            }
        }
    }
}

// ── Shared ───────────────────────────────────────────────────────────────────

/** A number over its caption — the compact form used where a whole tile is too much. */
@Composable
internal fun CountFact(value: String, caption: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
