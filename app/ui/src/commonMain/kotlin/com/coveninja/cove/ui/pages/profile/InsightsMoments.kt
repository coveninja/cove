package com.coveninja.cove.ui.pages.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.model.ActivityStats
import com.coveninja.cove.shared.model.InsightsRange
import com.coveninja.cove.shared.model.WatchMoment
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.components.insights.InsightsCard
import com.coveninja.cove.ui.components.insights.InsightsTier
import com.coveninja.cove.ui.components.insights.LocalInsightsAccent
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia
import kotlinx.datetime.LocalDate

// The moments chapter: the half of the page that names a specific day rather than averaging
// over all of them. Everything here hides itself when its own moment is absent, which is the
// rule the rest of the page already follows — a new profile grows this chapter an entry at a
// time instead of showing four placeholders.

/** Whether there is any moment at all worth opening a chapter for. */
internal fun hasMoments(stats: ActivityStats): Boolean =
    stats.monthlyHeadliners.isNotEmpty() ||
        stats.biggestDay?.isEmpty == false ||
        stats.longestSession?.isEmpty == false ||
        stats.firstWatch?.isEmpty == false

/**
 * The year told as events.
 *
 * The twelve-title strip leads because it is the one thing here that no aggregate can
 * produce and that neither Trakt nor Simkl offers: a year laid out as the twelve titles that
 * actually defined its months, in order, with the artwork doing the remembering.
 */
@Composable
internal fun MomentsSections(
    stats: ActivityStats,
    today: LocalDate,
    range: InsightsRange,
    onOpenMedia: (Media) -> Unit,
) {
    stats.monthlyHeadliners.takeIf { it.size >= MIN_HEADLINERS }?.let { headliners ->
        InsightsCard(
            eyebrow = "Month by month",
            headline = "Your year in ${headliners.size} " +
                if (headliners.size == 1) "title" else "titles",
            tier = InsightsTier.Feature,
            support = "Whatever took the most hours in each month.",
        ) {
            MonthlyHeadlinerRow(headliners = headliners, onOpenMedia = onOpenMedia)
        }
    }

    stats.biggestDay?.takeIf { !it.isEmpty }?.let { moment ->
        biggestDayHeadline(moment, today)?.let { headline ->
            MomentFact(
                eyebrow = "Your biggest day",
                headline = headline,
                support = moment.title.takeIf { it.isNotBlank() }?.let { "Mostly $it." },
                moment = moment,
                onOpenMedia = onOpenMedia,
            )
        }
    }

    stats.longestSession?.takeIf { !it.isEmpty }?.let { moment ->
        longestSessionHeadline(moment)?.let { headline ->
            MomentFact(
                eyebrow = "Longest sitting",
                headline = headline,
                support = formatMomentDate(moment.date, today)?.let { "Starting $it." },
                moment = moment,
                onOpenMedia = onOpenMedia,
            )
        }
    }

    stats.firstWatch?.takeIf { !it.isEmpty }?.let { moment ->
        firstWatchHeadline(moment, today, range)?.let { headline ->
            MomentFact(
                eyebrow = "Where it began",
                headline = headline,
                support = null,
                moment = moment,
                onOpenMedia = onOpenMedia,
            )
        }
    }
}

/**
 * A moment as a quiet fact with its poster beside it.
 *
 * No card. Three of these in bordered panels would put them back in the same family as the
 * charts, and a single sentence does not need a container to be found — the eyebrow and the
 * poster are enough to mark where one fact ends and the next begins.
 */
@Composable
private fun MomentFact(
    eyebrow: String,
    headline: String,
    support: String?,
    moment: WatchMoment,
    onOpenMedia: (Media) -> Unit,
) {
    val media = remember(moment) {
        moment.tmdbId.takeIf { it > 0 }?.let {
            insightMedia(moment.tmdbId, moment.mediaType, moment.title, moment.posterPath)
                .toUiMedia()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$eyebrow. $headline" },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (media?.posterUrl != null) {
            CoveAsyncImage(
                model = media.posterUrl,
                contentDescription = null,
                modifier = Modifier
                    .width(46.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onOpenMedia(media) },
                contentScale = ContentScale.Crop,
            )
        }
        InsightsCard(
            eyebrow = eyebrow,
            headline = headline,
            tier = InsightsTier.Quiet,
            support = support,
            modifier = Modifier.weight(1f),
        ) {}
    }
}

/**
 * Twelve posters, one per month, in order.
 *
 * The month label sits on the poster rather than under it: a caption row under twelve
 * posters is twelve more things to read, and the label only has to be findable at the moment
 * someone is already looking at that poster.
 */
@Composable
private fun MonthlyHeadlinerRow(
    headliners: List<WatchMoment>,
    onOpenMedia: (Media) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = InsightsCardTop, bottom = 16.dp)
            .semantics {
                contentDescription = headliners.joinToString(", ") { moment ->
                    "${momentMonthLabel(moment.date).orEmpty()}: ${moment.title}"
                }
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = RowPadding),
    ) {
        items(headliners, key = { it.date }) { moment ->
            MonthlyHeadliner(moment = moment, onOpenMedia = onOpenMedia)
        }
    }
}

@Composable
private fun MonthlyHeadliner(moment: WatchMoment, onOpenMedia: (Media) -> Unit) {
    val accent = LocalInsightsAccent.current
    val media = remember(moment) {
        insightMedia(moment.tmdbId, moment.mediaType, moment.title, moment.posterPath).toUiMedia()
    }
    Column(
        modifier = Modifier.width(84.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            CoveAsyncImage(
                model = media.posterUrl,
                contentDescription = moment.title.takeIf { it.isNotBlank() },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onOpenMedia(media) },
                contentScale = ContentScale.Crop,
            )
            momentMonthLabel(moment.date)?.let { label ->
                Text(
                    text = label.uppercase(),
                    modifier = Modifier
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(accent)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.surface,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text = moment.title.takeIf { it.isNotBlank() } ?: "—",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Below this the strip is not a year.
 *
 * Two posters laid out as "your year in 2 titles" reads as a broken twelve rather than an
 * honest two, and the leaderboard directly above already covers a profile that thin.
 */
private const val MIN_HEADLINERS = 3
