package com.coveninja.cove.ui.tv.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaCastMember
import com.coveninja.cove.ui.model.MediaEpisode
import com.coveninja.cove.ui.model.MediaRecommendation
import com.coveninja.cove.ui.model.MediaSeason
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.components.TvButton
import com.coveninja.cove.ui.tv.components.TvMediaRow
import com.coveninja.cove.ui.tv.components.TvPosterCard
import com.coveninja.cove.ui.tv.components.TvWideCard
import com.coveninja.cove.ui.tv.focus.FocusOnAppear
import com.coveninja.cove.ui.tv.focus.TvSectionScroll
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import kotlin.math.roundToInt

/**
 * A title, filling the screen.
 *
 * The phone shows this as a sheet over the page it was opened from, which works because the
 * page underneath is still meaningful context you can swipe back to. On a television there is
 * no swipe and no glance: the viewer pressed OK on a card and the whole screen is now about
 * that title, so a sheet would only be a smaller version of the same thing with the page
 * showing uselessly around its edges.
 *
 * Actions come first and everything else is scrolled to, because the overwhelmingly common
 * reason to be here is to press Play.
 */
@Composable
internal fun TvDetailsScreen(
    media: Media,
    listCategory: MyListCategory?,
    rating: Int?,
    onPlay: () -> Unit,
    onChooseSource: () -> Unit,
    onSetListCategory: (MyListCategory) -> Unit,
    onRemoveFromList: () -> Unit,
    onSetRating: (Int) -> Unit,
    onPlayEpisode: (MediaSeason, MediaEpisode) -> Unit,
    onLoadEpisodes: suspend (MediaSeason) -> List<MediaEpisode>,
    onOpenRecommendation: (MediaRecommendation) -> Unit,
    onOpenPerson: (MediaCastMember) -> Unit,
    modifier: Modifier = Modifier,
    watchLabel: String = "Watch",
    /** True while the person screen or the player is drawn over this one. */
    covered: Boolean = false,
) {
    val dimens = TvTheme.dimens
    val listState = rememberLazyListState()
    val playFocusRequester = remember { FocusRequester() }
    var focusedSection by remember { mutableStateOf<Int?>(null) }

    val sections = remember(media) {
        buildList {
            add(TvDetailsSection.Header)
            if (media.seasons.isNotEmpty()) add(TvDetailsSection.Episodes)
            if (media.cast.isNotEmpty()) add(TvDetailsSection.Cast)
            if (media.moreLikeThis.isNotEmpty()) add(TvDetailsSection.MoreLikeThis)
        }
    }

    TvSectionScroll(
        state = listState,
        focusedIndex = focusedSection,
        margin = dimens.focusScrollMargin,
    )
    // Re-armed whenever this screen becomes the top layer again, which is the other half of
    // not losing focus: closing the player removes the node that held it, and without this the
    // sheet underneath would come back with nothing focused and no way to press anything.
    FocusOnAppear(playFocusRequester, enabled = !covered)

    Box(modifier = modifier.fillMaxSize().background(CoveColors.Neutral.Background)) {
        // Artwork behind everything, faded out well before the rows start: it is atmosphere
        // here, not content, and a backdrop still legible under a row of episode cards makes
        // both unreadable.
        CoveAsyncImage(
            model = media.backdropUrl ?: media.posterUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.height * 0.78f),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.height * 0.78f)
                .background(
                    Brush.verticalGradient(
                        0f to CoveColors.Neutral.Background.copy(alpha = 0.35f),
                        0.5f to CoveColors.Neutral.Background.copy(alpha = 0.86f),
                        1f to CoveColors.Neutral.Background,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to CoveColors.Neutral.Background.copy(alpha = 0.92f),
                        0.7f to Color.Transparent,
                    ),
                ),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = dimens.overscanVertical + 24.dp,
                bottom = dimens.overscanVertical + 32.dp,
            ),
        ) {
            itemsIndexed(
                items = sections,
                key = { _, section -> section.name },
            ) { position, section ->
                val report: (Boolean) -> Unit = { focused ->
                    if (focused) focusedSection = position
                }
                when (section) {
                    TvDetailsSection.Header -> TvDetailsHeader(
                        media = media,
                        listCategory = listCategory,
                        rating = rating,
                        watchLabel = watchLabel,
                        playFocusRequester = playFocusRequester,
                        onPlay = onPlay,
                        onChooseSource = onChooseSource,
                        onSetListCategory = onSetListCategory,
                        onRemoveFromList = onRemoveFromList,
                        onSetRating = onSetRating,
                        onFocusChanged = report,
                    )

                    TvDetailsSection.Episodes -> TvSeasonBrowser(
                        media = media,
                        onPlayEpisode = onPlayEpisode,
                        onLoadEpisodes = onLoadEpisodes,
                        onFocusChanged = report,
                        modifier = Modifier.padding(top = dimens.sectionSpacing),
                    )

                    TvDetailsSection.Cast -> TvMediaRow(
                        title = "Cast",
                        icon = "lucide:users",
                        items = media.cast,
                        key = MediaCastMember::tmdbId,
                        onFocusChanged = report,
                        modifier = Modifier.padding(top = dimens.sectionSpacing),
                    ) { member ->
                        TvPosterCard(
                            posterUrl = member.profileUrl,
                            label = member.character?.takeIf { it.isNotBlank() } ?: member.name,
                            onClick = { onOpenPerson(member) },
                        )
                    }

                    TvDetailsSection.MoreLikeThis -> TvMediaRow(
                        title = "More like this",
                        icon = "lucide:sparkles",
                        items = media.moreLikeThis,
                        key = MediaRecommendation::id,
                        onFocusChanged = report,
                        modifier = Modifier.padding(top = dimens.sectionSpacing),
                    ) { recommendation ->
                        TvRecommendationCard(
                            recommendation = recommendation,
                            onClick = { onOpenRecommendation(recommendation) },
                        )
                    }
                }
            }
        }
    }
}

private enum class TvDetailsSection { Header, Episodes, Cast, MoreLikeThis }

@Composable
private fun TvDetailsHeader(
    media: Media,
    listCategory: MyListCategory?,
    rating: Int?,
    watchLabel: String,
    playFocusRequester: FocusRequester,
    onPlay: () -> Unit,
    onChooseSource: () -> Unit,
    onSetListCategory: (MyListCategory) -> Unit,
    onRemoveFromList: () -> Unit,
    onSetRating: (Int) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val dimens = TvTheme.dimens
    val title = media.title ?: media.name.orEmpty()
    var rating0to10Open by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(horizontal = dimens.overscanHorizontal)
            .widthIn(max = dimens.width * 0.62f)
            .onFocusChanged { focus -> onFocusChanged(focus.hasFocus) },
    ) {
        val logo = media.logoUrl
        if (!logo.isNullOrBlank()) {
            CoveAsyncImage(
                model = logo,
                contentDescription = title,
                modifier = Modifier
                    .heightIn(max = 116.dp)
                    .widthIn(max = dimens.width * 0.42f),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                color = CoveColors.Neutral.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val facts = remember(media) { detailFacts(media) }
        if (facts.isNotBlank()) {
            Text(
                text = facts,
                style = MaterialTheme.typography.bodyLarge,
                color = CoveColors.Neutral.Muted,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        media.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            Text(
                text = overview,
                style = MaterialTheme.typography.bodyMedium,
                color = CoveColors.Neutral.MutedDim,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 14.dp),
            )
        }

        Row(
            modifier = Modifier
                .padding(top = 22.dp)
                .tvFocusGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvButton(
                label = watchLabel,
                onClick = onPlay,
                icon = "lucide:play",
                primary = true,
                modifier = Modifier.focusRequester(playFocusRequester),
            )
            TvButton(
                label = "Choose source",
                onClick = onChooseSource,
                icon = "lucide:list-video",
            )
            if (listCategory == null) {
                TvButton(
                    label = "Add to list",
                    onClick = { onSetListCategory(MyListCategory.WatchLater) },
                    icon = "lucide:bookmark-plus",
                )
            } else {
                TvButton(
                    label = listCategory.label,
                    onClick = onRemoveFromList,
                    icon = "lucide:bookmark-check",
                    selected = true,
                )
            }
            TvButton(
                label = rating?.let { "Rated $it" } ?: "Rate",
                onClick = { rating0to10Open = !rating0to10Open },
                icon = "lucide:star",
                selected = rating != null,
            )
        }

        // Ten permanent focus stops for something pressed once in a title's lifetime would be
        // ten stops in the way of Play every other time. They appear only when asked for.
        if (rating0to10Open) {
            TvRatingStrip(
                current = rating,
                onSelect = { value ->
                    onSetRating(value)
                    rating0to10Open = false
                },
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun TvRatingStrip(
    current: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    FocusOnAppear(focusRequester)
    Row(
        modifier = modifier.tvFocusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (1..10).forEach { value ->
            TvButton(
                label = value.toString(),
                onClick = { onSelect(value) },
                selected = value == current,
                // Focus opens on the rating already given, so nudging a 7 to an 8 is one press.
                modifier = if (value == (current ?: 8)) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
        }
    }
}

/** The one-line summary under the title: year, kind, runtime, score, certificate. */
private fun detailFacts(media: Media): String = buildList {
    (media.released ?: media.firstAirDate)?.take(4)?.takeIf { it.isNotBlank() }?.let(::add)
    media.type?.label?.let(::add)
    media.runtimeMinutes?.takeIf { it > 0 }?.let { add("$it min") }
    media.certification?.takeIf { it.isNotBlank() }?.let(::add)
    media.rating?.takeIf { it > 0 }?.let { add("★ ${(it * 10).roundToInt() / 10.0}") }
    if (media.seasons.isNotEmpty()) add("${media.seasons.size} seasons")
}.joinToString("  ·  ")

@Composable
private fun TvRecommendationCard(
    recommendation: MediaRecommendation,
    onClick: () -> Unit,
) {
    TvPosterCard(
        posterUrl = recommendation.posterUrl,
        label = recommendation.title,
        onClick = onClick,
    )
}

/**
 * Seasons as a strip of chips, with the chosen season's episodes underneath.
 *
 * Episodes are fetched per season and only when a season is actually chosen — the phone's
 * browser does the same, and it matters more here because a remote walks along the strip and
 * would otherwise fire a request for every season it passed through.
 */
@Composable
private fun TvSeasonBrowser(
    media: Media,
    onPlayEpisode: (MediaSeason, MediaEpisode) -> Unit,
    onLoadEpisodes: suspend (MediaSeason) -> List<MediaEpisode>,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = TvTheme.dimens
    var selectedSeason by remember(media.id) {
        mutableStateOf(media.seasons.firstOrNull()?.number)
    }
    var episodes by remember(media.id) { mutableStateOf<List<MediaEpisode>>(emptyList()) }
    var loading by remember(media.id) { mutableStateOf(false) }

    val season = media.seasons.firstOrNull { it.number == selectedSeason }
    LaunchedEffect(media.id, selectedSeason) {
        val target = season ?: return@LaunchedEffect
        loading = true
        episodes = runCatching { onLoadEpisodes(target) }.getOrDefault(emptyList())
        loading = false
    }

    Column(modifier = modifier.onFocusChanged { focus -> onFocusChanged(focus.hasFocus) }) {
        Row(
            modifier = Modifier
                .padding(horizontal = dimens.overscanHorizontal)
                .tvFocusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            media.seasons.forEach { entry ->
                TvButton(
                    label = entry.title,
                    onClick = { selectedSeason = entry.number },
                    selected = entry.number == selectedSeason,
                )
            }
        }

        when {
            loading && episodes.isEmpty() -> Text(
                text = "Loading episodes…",
                style = MaterialTheme.typography.bodyMedium,
                color = CoveColors.Neutral.MutedDim,
                modifier = Modifier.padding(
                    horizontal = dimens.overscanHorizontal,
                    vertical = 18.dp,
                ),
            )

            episodes.isEmpty() -> Text(
                text = "No episodes listed for this season.",
                style = MaterialTheme.typography.bodyMedium,
                color = CoveColors.Neutral.MutedDim,
                modifier = Modifier.padding(
                    horizontal = dimens.overscanHorizontal,
                    vertical = 18.dp,
                ),
            )

            else -> TvMediaRow(
                title = season?.title.orEmpty(),
                subtitle = "${episodes.size} episodes",
                icon = "lucide:film",
                items = episodes,
                key = MediaEpisode::id,
                modifier = Modifier.padding(top = 6.dp),
            ) { episode ->
                TvWideCard(
                    imageUrl = episode.stillUrl ?: media.backdropUrl,
                    title = "${episode.number}. ${episode.title}",
                    caption = episodeCaption(episode),
                    badge = if (episode.watched) "Watched" else null,
                    onClick = { season?.let { onPlayEpisode(it, episode) } },
                )
            }
        }
    }
}

private fun episodeCaption(episode: MediaEpisode): String = buildList {
    episode.airDate?.takeIf { it.isNotBlank() }?.let(::add)
    episode.runtimeMinutes?.takeIf { it > 0 }?.let { add("$it min") }
    if (isEmpty()) add("Episode ${episode.number}")
}.joinToString("  ·  ")
