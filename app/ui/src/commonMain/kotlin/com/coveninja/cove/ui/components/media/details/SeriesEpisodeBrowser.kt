package com.coveninja.cove.ui.components.media.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.HorizontalLazyListScrollbar
import com.coveninja.cove.ui.components.media.card.EpisodeCard
import com.coveninja.cove.ui.model.MediaEpisode
import com.coveninja.cove.ui.model.MediaSeason
import com.coveninja.cove.ui.icons.IconifyIcon

@Composable
fun SeriesEpisodeBrowser(
    seasons: List<MediaSeason>,
    modifier: Modifier = Modifier,
    initialSeasonNumber: Int? = null,
    initiallyExpanded: Boolean = false,
    onLoadEpisodes: suspend (MediaSeason) -> List<MediaEpisode> = { it.episodes },
    onEpisodeSelected: (MediaSeason, MediaEpisode) -> Unit = { _, _ -> },
    onEpisodeWatchedChange: (
        season: MediaSeason,
        episode: MediaEpisode,
        watched: Boolean,
    ) -> Unit = { _, _, _ -> },
) {
    if (seasons.isEmpty()) return

    val initialSeason = seasons.firstOrNull {
        it.number == initialSeasonNumber
    } ?: seasons.first()

    var expanded by remember(seasons) {
        mutableStateOf(initiallyExpanded)
    }
    var selectedSeasonNumber by remember(seasons, initialSeasonNumber) {
        mutableIntStateOf(initialSeason.number)
    }
    val selectedSeason = seasons.firstOrNull {
        it.number == selectedSeasonNumber
    } ?: initialSeason
    val episodesBySeason = remember(seasons) {
        mutableStateMapOf<Int, List<MediaEpisode>>().apply {
            seasons.forEach { season ->
                if (season.episodes.isNotEmpty()) {
                    put(season.number, season.episodes)
                }
            }
        }
    }
    var loadingSeasonNumber by remember(seasons) {
        mutableStateOf<Int?>(null)
    }
    val selectedEpisodes = episodesBySeason[selectedSeason.number]
        ?: selectedSeason.episodes

    LaunchedEffect(expanded, selectedSeason.number) {
        if (!expanded || episodesBySeason.containsKey(selectedSeason.number)) {
            return@LaunchedEffect
        }

        loadingSeasonNumber = selectedSeason.number
        episodesBySeason[selectedSeason.number] = runCatching {
            onLoadEpisodes(selectedSeason)
        }.getOrDefault(emptyList())
        loadingSeasonNumber = null
    }
    val watchedEpisodes = remember(seasons) {
        mutableStateMapOf<String, Boolean>().apply {
            seasons.forEach { season ->
                season.episodes.forEach { episode ->
                    put(episode.id, episode.watched)
                }
            }
        }
    }
    val seasonListState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme
    val disclosureInteractionSource = remember { MutableInteractionSource() }
    val disclosureHovered by
        disclosureInteractionSource.collectIsHoveredAsState()
    val disclosureColor by animateColorAsState(
        targetValue = if (disclosureHovered) {
            colors.surfaceContainerHighest
        } else {
            colors.surfaceContainerHigh
        },
        animationSpec = tween(120),
        label = "EpisodeDisclosureColor",
    )
    val disclosureBorderColor by animateColorAsState(
        targetValue = when {
            disclosureHovered -> colors.tertiary.copy(alpha = 0.52f)
            expanded -> colors.tertiary.copy(alpha = 0.30f)
            else -> colors.outlineVariant.copy(alpha = 0.32f)
        },
        animationSpec = tween(120),
        label = "EpisodeDisclosureBorder",
    )
    val chevronContainerColor by animateColorAsState(
        targetValue = when {
            disclosureHovered -> colors.tertiary.copy(alpha = 0.18f)
            expanded -> colors.tertiary.copy(alpha = 0.12f)
            else -> colors.onSurface.copy(alpha = 0.06f)
        },
        animationSpec = tween(120),
        label = "EpisodeDisclosureChevronContainer",
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "EpisodeDisclosureChevron",
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val useCompactCards = maxWidth < 560.dp
        val totalEpisodeCount = seasons.sumOf { season ->
            season.episodeCount.takeIf { it > 0 } ?: season.episodes.size
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .hoverable(disclosureInteractionSource)
                    .clickable(
                        interactionSource = disclosureInteractionSource,
                        indication = null,
                        onClick = {
                            expanded = !expanded
                        },
                    ),
                shape = RoundedCornerShape(14.dp),
                color = disclosureColor,
                border = BorderStroke(
                    width = 1.dp,
                    color = disclosureBorderColor,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = 14.dp,
                        vertical = 11.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Episodes",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (expanded) {
                                "${selectedSeason.title} · " +
                                    "${selectedSeason.episodeCount.takeIf { it > 0 } ?: selectedEpisodes.size} episodes"
                            } else {
                                "${seasons.size} seasons · " +
                                    "$totalEpisodeCount episodes"
                            },
                            modifier = Modifier.padding(top = 2.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(chevronContainerColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconifyIcon(
                            icon = "lucide:chevron-down",
                            modifier = Modifier
                                .size(19.dp)
                                .graphicsLayer {
                                    rotationZ = chevronRotation
                                },
                            tint = if (disclosureHovered || expanded) {
                                colors.tertiary
                            } else {
                                colors.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(140)) + expandVertically(
                    animationSpec = tween(180),
                    expandFrom = Alignment.Top,
                ),
                exit = fadeOut(tween(100)) + shrinkVertically(
                    animationSpec = tween(150),
                    shrinkTowards = Alignment.Top,
                ),
            ) {
                Column {
                    LazyRow(
                        state = seasonListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(
                            items = seasons,
                            key = { season -> season.number },
                        ) { season ->
                            SeasonChip(
                                season = season,
                                selected =
                                    season.number == selectedSeason.number,
                                onClick = {
                                    selectedSeasonNumber = season.number
                                },
                            )
                        }
                    }

                    HorizontalLazyListScrollbar(
                        state = seasonListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    )

                    Column(
                        modifier = Modifier.padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (loadingSeasonNumber == selectedSeason.number) {
                            Text(
                                text = "Loading episodes…",
                                modifier = Modifier.padding(vertical = 20.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else if (selectedEpisodes.isEmpty()) {
                            Text(
                                text = "No episode information is available for this season.",
                                modifier = Modifier.padding(vertical = 20.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        selectedEpisodes.forEach { episode ->
                            val watched =
                                watchedEpisodes[episode.id] ?: episode.watched

                            EpisodeCard(
                                episode = episode,
                                seasonNumber = selectedSeason.number,
                                watched = watched,
                                compact = useCompactCards,
                                onClick = {
                                    onEpisodeSelected(
                                        selectedSeason,
                                        episode,
                                    )
                                },
                                onWatchedChange = { newWatched ->
                                    watchedEpisodes[episode.id] = newWatched
                                    onEpisodeWatchedChange(
                                        selectedSeason,
                                        episode,
                                        newWatched,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonChip(
    season: MediaSeason,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            selected -> 1f
            isHovered -> 1.04f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "SeasonChipScale",
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.tertiary
            isHovered -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(120),
        label = "SeasonChipColor",
    )

    Surface(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = CircleShape,
        color = containerColor,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onTertiary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            },
        ),
    ) {
        Text(
            text = season.title,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
