package com.coveninja.cove.ui.onboarding.steps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.onboarding.OnboardingController
import com.coveninja.cove.ui.onboarding.OnboardingCountBadge
import com.coveninja.cove.ui.onboarding.OnboardingGenreBubble
import com.coveninja.cove.ui.onboarding.OnboardingGenres
import com.coveninja.cove.ui.onboarding.OnboardingPick
import com.coveninja.cove.ui.onboarding.OnboardingPosterTile
import com.coveninja.cove.ui.onboarding.rankByGenre
import com.coveninja.cove.ui.pages.common.ShimmerBlock
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalMotionPolicy

/**
 * The step that makes the rest of the app work.
 *
 * `DiscoveryService` builds its taste profile from the library, so on a fresh install every
 * personalized rail on Home and Explore is empty until the viewer saves something. This is
 * where they save the first few, without being told that is what they are doing — they are
 * picking things they like, and Home is personalized when they arrive at it.
 *
 * The genre bubbles above the wall do two things at once: they are the cheap, instant version
 * of the same signal, and choosing one visibly *reorders* the posters underneath, which is the
 * feedback that makes the wall feel like it is listening. See `rankByGenre` for why it reorders
 * rather than filters.
 */
@Composable
internal fun TasteStep(
    controller: OnboardingController,
    modifier: Modifier = Modifier,
) {
    val homeState by LocalAppGraph.current.content.home.collectAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion

    val catalog: List<Media> = remember(homeState) {
        (homeState as? HomeState.Ready)?.items
            ?.map { it.toUiMedia() }
            ?.filter { !it.posterUrl.isNullOrBlank() }
            ?.distinctBy { it.id }
            .orEmpty()
    }
    val ordered = remember(catalog, controller.draft.likedGenreIds) {
        rankByGenre(
            items = catalog,
            likedGenreIds = controller.draft.likedGenreIds,
            genreIdsOf = Media::genreIds,
        ).take(WALL_SIZE)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            OnboardingGenres.forEach { genre ->
                OnboardingGenreBubble(
                    genre = genre,
                    selected = genre.id in controller.draft.likedGenreIds,
                    onClick = { controller.toggleGenre(genre.id) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Seen something you like?",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(
                visible = controller.draft.likedTitles.isNotEmpty(),
                enter = if (reducedMotion) fadeIn(snap()) else fadeIn(tween(180)),
                exit = if (reducedMotion) fadeOut(snap()) else fadeOut(tween(140)),
            ) {
                OnboardingCountBadge(
                    icon = "lucide:bookmark-check",
                    count = controller.draft.likedTitles.size,
                    label = "picked",
                )
            }
        }

        when {
            ordered.isNotEmpty() -> PosterWall(controller = controller, media = ordered)
            homeState is HomeState.Failed -> Text(
                text = "Couldn't reach the catalog just now — you can pick titles later, " +
                    "and the genres above still count.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> PosterWallSkeleton()
        }
    }
}

/**
 * The wall itself, laid out by hand rather than with a lazy grid.
 *
 * It sits inside the scaffold's vertical scroll, and a `LazyVerticalGrid` nested in a scrolling
 * parent has no height to measure against — it either throws or collapses to nothing. The item
 * count is fixed and small, so chunked rows cost nothing and behave.
 */
@Composable
private fun PosterWall(
    controller: OnboardingController,
    media: List<Media>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = wallColumnsFor(maxWidth)
        Column(verticalArrangement = Arrangement.spacedBy(WallGap)) {
            media.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WallGap),
                ) {
                    row.forEach { item ->
                        OnboardingPosterTile(
                            media = item,
                            picked = controller.isPicked(item.id),
                            onClick = { controller.togglePick(item.toPick()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keeps the last row's tiles the same width as every other row's, instead
                    // of letting three items stretch across a five-column grid.
                    repeat(columns - row.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterWallSkeleton(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = wallColumnsFor(maxWidth)
        Column(verticalArrangement = Arrangement.spacedBy(WallGap)) {
            repeat(2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WallGap),
                ) {
                    repeat(columns) {
                        ShimmerBlock(
                            corner = 14.dp,
                            modifier = Modifier.weight(1f).aspectRatio(PosterAspect),
                        )
                    }
                }
            }
        }
    }
}

/**
 * How many posters fit across.
 *
 * Sized so a tile stays a comfortable press target rather than by a fixed count: three across
 * on a phone is already a 100 dp-wide poster, and four would be smaller than a thumb.
 */
private fun wallColumnsFor(width: Dp): Int = when {
    width < 420.dp -> 3
    width < 640.dp -> 4
    else -> 5
}

/** Two rows of five is enough to choose from without becoming a browsing session. */
private const val WALL_SIZE = 10
private val WallGap = 10.dp

/** Poster proportions, shared so the skeleton cannot drift from the real tiles. */
private const val PosterAspect = 2f / 3f

private fun Media.toPick(): OnboardingPick = OnboardingPick(
    id = id,
    tmdbId = tmdbId,
    type = type,
    title = title ?: name ?: "Untitled",
    posterUrl = posterUrl.orEmpty(),
    voteAverage = rating ?: 0.0,
)
