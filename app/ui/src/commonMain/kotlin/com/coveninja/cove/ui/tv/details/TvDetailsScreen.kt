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
import com.coveninja.cove.ui.model.MediaVideo
import com.coveninja.cove.ui.model.VideoCategory
import com.coveninja.cove.ui.model.sortedForDisplay
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
    onToggleWatched: () -> Unit,
    onSetRating: (Int) -> Unit,
    onPlayEpisode: (MediaSeason, MediaEpisode) -> Unit,
    onSetEpisodeWatched: (MediaSeason, MediaEpisode, Boolean) -> Unit,
    onPlayVideo: (MediaVideo) -> Unit,
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

    // Trailers and teasers only. The pointer shells offer every category behind a filter row,
    // which on a remote would be a row of focus stops in front of the one video anybody came
    // for — and a television is where a trailer is actually worth watching.
    val trailers = remember(media) {
        media.videos
            .filter { it.category == VideoCategory.Trailer || it.category == VideoCategory.Teaser }
            .sortedForDisplay()
            .take(TRAILER_LIMIT)
    }

    val sections = remember(media, trailers) {
        buildList {
            add(TvDetailsSection.Header)
            if (media.seasons.isNotEmpty()) add(TvDetailsSection.Episodes)
            if (trailers.isNotEmpty()) add(TvDetailsSection.Trailers)
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
                        onToggleWatched = onToggleWatched,
                        onSetRating = onSetRating,
                        onFocusChanged = report,
                    )

                    TvDetailsSection.Episodes -> TvSeasonBrowser(
                        media = media,
                        onPlayEpisode = onPlayEpisode,
                        onSetEpisodeWatched = onSetEpisodeWatched,
                        onLoadEpisodes = onLoadEpisodes,
                        onFocusChanged = report,
                        modifier = Modifier.padding(top = dimens.sectionSpacing),
                    )

                    TvDetailsSection.Trailers -> TvMediaRow(
                        title = "Trailers",
                        icon = "lucide:clapperboard",
                        items = trailers,
                        key = MediaVideo::id,
                        onFocusChanged = report,
                        modifier = Modifier.padding(top = dimens.sectionSpacing),
                    ) { video ->
                        TvWideCard(
                            imageUrl = video.thumbnailUrl,
                            title = video.title,
                            caption = listOfNotNull(
                                video.category.label,
                                video.duration?.takeIf { it.isNotBlank() },
                            ).joinToString("  ·  "),
                            wideArt = true,
                            onClick = { onPlayVideo(video) },
                        )
                    }

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

private enum class TvDetailsSection { Header, Episodes, Trailers, Cast, MoreLikeThis }

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
    onToggleWatched: () -> Unit,
    onSetRating: (Int) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val dimens = TvTheme.dimens
    val title = media.title ?: media.name.orEmpty()
    var rating0to10Open by remember { mutableStateOf(false) }
    var categoryOpen by remember { mutableStateOf(false) }
    val watched = listCategory == MyListCategory.Finished

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

        // Its own line rather than more entries on the fact line, which is already at the
        // length a viewer will read from a sofa. Three at most for the same reason.
        val supporting = remember(media) { supportingFacts(media) }
        if (supporting.isNotBlank()) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = CoveColors.Neutral.MutedDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
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
            // Opens the pile picker rather than hard-coding one. Adding always landed in
            // Watch Later and the button then only ever removed, so a title could not be
            // moved between piles from a television at all — and moving one is most of what
            // the list is for.
            TvButton(
                label = listCategory?.label ?: "Add to list",
                onClick = {
                    categoryOpen = !categoryOpen
                    rating0to10Open = false
                },
                icon = if (listCategory == null) "lucide:bookmark-plus" else "lucide:bookmark-check",
                selected = listCategory != null,
            )
            TvButton(
                label = if (watched) "Watched" else "Mark watched",
                onClick = onToggleWatched,
                icon = if (watched) "lucide:eye" else "lucide:eye-off",
                selected = watched,
            )
            TvButton(
                label = rating?.let { "Rated $it" } ?: "Rate",
                onClick = {
                    rating0to10Open = !rating0to10Open
                    categoryOpen = false
                },
                icon = "lucide:star",
                selected = rating != null,
            )
        }

        if (categoryOpen) {
            TvCategoryStrip(
                current = listCategory,
                onSelect = { category ->
                    onSetListCategory(category)
                    categoryOpen = false
                },
                onRemove = {
                    onRemoveFromList()
                    categoryOpen = false
                },
                modifier = Modifier.padding(top = 14.dp),
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

/**
 * The piles, as a strip, with Remove at the end where it cannot be hit by accident.
 *
 * Same rule as the rating strip: five permanent focus stops in front of Play, for something
 * pressed once or twice in a title's life, is a worse trade than one press to reveal them.
 */
@Composable
private fun TvCategoryStrip(
    current: MyListCategory?,
    onSelect: (MyListCategory) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    FocusOnAppear(focusRequester)
    Row(
        modifier = modifier.tvFocusGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MyListCategory.entries.forEach { category ->
            TvButton(
                label = category.label,
                onClick = { onSelect(category) },
                icon = category.icon,
                selected = category == current,
                // Opens on the pile the title is already in, so moving it is one press.
                modifier = if (category == (current ?: MyListCategory.WatchLater)) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
        }
        if (current != null) {
            TvButton(label = "Remove", onClick = onRemove, icon = "lucide:x")
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

/**
 * Genres, and who made it.
 *
 * The director is the one credit worth a line of its own on a television — it is how people
 * choose a film across a room — and the rest of the crew the pointer shells list is detail
 * nobody reads from there.
 */
private fun supportingFacts(media: Media): String = buildList {
    media.genres.take(MAX_GENRES).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
    media.directors.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let { add("Directed by $it") }
}.joinToString("  ·  ")

/** More than this and the line wraps or truncates, and a truncated genre reads as a typo. */
private const val MAX_GENRES = 3

/** How many trailers are worth a row; past this it is a catalogue rather than a choice. */
private const val TRAILER_LIMIT = 6

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
    onSetEpisodeWatched: (MediaSeason, MediaEpisode, Boolean) -> Unit,
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
    // Sticky rather than live: the button naming this episode is itself a focus stop, so the
    // moment it is reached the row has lost focus and a live value would name nothing. Keeping
    // the last episode focused is what lets one control serve a whole row of them.
    var markTarget by remember(media.id, selectedSeason) {
        mutableStateOf<MediaEpisode?>(null)
    }

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

            // Marking an episode watched is a context-menu item on the pointer shells, which
            // is precisely the affordance a remote does not have. One button naming the
            // episode last focused gives the same correction for one focus stop, rather than
            // a toggle per card that would double the presses needed to walk a season.
            markTarget?.let { episode ->
                val season = media.seasons.firstOrNull { it.number == selectedSeason }
                TvButton(
                    label = if (episode.watched) {
                        "Unmark ${episode.number}"
                    } else {
                        "Mark ${episode.number} watched"
                    },
                    onClick = {
                        season?.let { onSetEpisodeWatched(it, episode, !episode.watched) }
                        // Reflected locally at once: the library write is a round trip, and a
                        // button that kept saying "Mark watched" until it landed reads as a
                        // press that did nothing.
                        episodes = episodes.map {
                            if (it.id == episode.id) it.copy(watched = !episode.watched) else it
                        }
                        markTarget = episode.copy(watched = !episode.watched)
                    },
                    icon = if (episode.watched) "lucide:eye-off" else "lucide:eye",
                    selected = episode.watched,
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
                    onFocusChanged = { focused -> if (focused) markTarget = episode },
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
