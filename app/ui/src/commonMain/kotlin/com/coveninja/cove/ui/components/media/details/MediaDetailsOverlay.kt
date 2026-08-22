package com.coveninja.cove.ui.components.media.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.components.common.DetailFact
import com.coveninja.cove.ui.components.common.DetailFactData
import com.coveninja.cove.ui.components.common.DetailsBadge
import com.coveninja.cove.ui.components.common.DetailsDismissDragHandle
import com.coveninja.cove.ui.components.common.DetailsSectionTitle
import com.coveninja.cove.ui.components.common.FullHeightOverlayBreakpoint
import com.coveninja.cove.ui.components.common.HorizontalLazyListScrollbar
import com.coveninja.cove.ui.components.common.OverlayCloseButton
import com.coveninja.cove.ui.components.common.formatRuntime
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.components.media.action.ChooseSourceButton
import com.coveninja.cove.ui.components.media.action.MyListButton
import com.coveninja.cove.ui.components.media.action.PrimaryWatchButton
import com.coveninja.cove.ui.components.media.action.RatingButton
import com.coveninja.cove.ui.components.media.card.MediaCard
import com.coveninja.cove.ui.components.media.card.PersonCard
import com.coveninja.cove.ui.components.media.drag.MediaDragPayload
import com.coveninja.cove.ui.components.person.PersonSharedKey
import com.coveninja.cove.ui.components.person.PersonSharedPart
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaEpisode
import com.coveninja.cove.ui.model.MediaSeason
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.MediaVideo
import com.coveninja.cove.ui.model.Person
import com.coveninja.cove.ui.model.tmdbImageSize
import com.coveninja.cove.ui.model.toMedia
import com.coveninja.cove.ui.model.toPerson
import com.coveninja.cove.ui.state.LocalMotionPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.MediaDetailsSharedOverlay(
    media: Media?,
    visible: Boolean,
    onDismiss: () -> Unit,
    currentListCategory: MyListCategory? = null,
    currentRating: Int? = null,
    watchLabel: String = "Watch",
    onWatch: (Media) -> Unit = {},
    onChooseSource: (Media) -> Unit = {},
    onListCategorySelected: (Media, MyListCategory) -> Unit = { _, _ -> },
    onRatingSelected: (Media, Int) -> Unit = { _, _ -> },
    onMediaSelected: (Media) -> Unit = {},
    /** A face in the cast row, or a name in the Details facts, opening the person sheet. */
    onPersonSelected: (Person) -> Unit = {},
    onVideoSelected: (Media, MediaVideo) -> Unit = { _, _ -> },
    /** Rendered inside the Videos section while one of them is playing embedded. */
    videoPlayer: @Composable ((Modifier) -> Unit)? = null,
    onLoadEpisodes: suspend (MediaSeason) -> List<MediaEpisode> = { it.episodes },
    onEpisodeSelected: (
        media: Media,
        season: MediaSeason,
        episode: MediaEpisode,
    ) -> Unit = { _, _, _ -> },
    onEpisodeChooseSource: (
        media: Media,
        season: MediaSeason,
        episode: MediaEpisode,
    ) -> Unit = { _, _, _ -> },
    onEpisodeWatchedChange: (
        media: Media,
        season: MediaSeason,
        episode: MediaEpisode,
        watched: Boolean,
    ) -> Unit = { _, _, _, _ -> },
    onMediaDragStart: (MediaDragPayload, Offset) -> Unit = { _, _ -> },
    onMediaDrag: (Offset) -> Unit = {},
    onMediaDragEnd: () -> Unit = {},
    onMediaDragCancel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && media != null,
        modifier = modifier.fillMaxSize(),
        enter = EnterTransition.None,
        exit = ExitTransition.None,
    ) {
        val currentMedia = media ?: return@AnimatedVisibility
        val reducedMotion = LocalMotionPolicy.current.reducedMotion
        val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            val dismissScope = rememberCoroutineScope()
            var dismissOffsetPx by remember(currentMedia.id) {
                mutableFloatStateOf(0f)
            }
            var dismissAnimationJob by remember {
                mutableStateOf<Job?>(null)
            }
            val dismissDistancePx = with(density) {
                maxHeight.toPx()
            }
            val dismissThresholdPx = minOf(
                dismissDistancePx * 0.22f,
                with(density) { 180.dp.toPx() },
            )
            val dismissProgress = if (dismissThresholdPx > 0f) {
                (dismissOffsetPx / dismissThresholdPx)
                    .coerceIn(0f, 1f)
            } else {
                0f
            }

            val isCompactWidth = maxWidth < FullHeightOverlayBreakpoint
            val surfaceShape = if (isCompactWidth) {
                RoundedCornerShape(0.dp)
            } else {
                RoundedCornerShape(12.dp)
            }

            // Only the background scrim fades.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (reducedMotion) Modifier else Modifier.animateEnterExit(
                            enter = fadeIn(animationSpec = tween(durationMillis = 180)),
                            exit = fadeOut(animationSpec = tween(durationMillis = 140)),
                        ),
                    )
                    .background(
                        Color.Black.copy(
                            alpha = 0.68f *
                                (1f - dismissProgress * 0.62f),
                        ),
                    )
                    .clickable(
                        interactionSource = remember {
                            MutableInteractionSource()
                        },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )

            Surface(
                modifier = (if (reducedMotion) {
                    Modifier
                } else {
                    Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(
                            key = MediaSharedKey(
                                mediaId = currentMedia.id,
                                part = MediaSharedPart.Container,
                            ),
                        ),
                        animatedVisibilityScope = this@AnimatedVisibility,
                        boundsTransform = { _, _ ->
                            spring(
                                dampingRatio = 0.82f,
                                stiffness = Spring.StiffnessMediumLow,
                            )
                        },
                        resizeMode =
                            SharedTransitionScope.ResizeMode.RemeasureToBounds,

                        renderInOverlayDuringTransition = false,
                    )
                })
                    .widthIn(max = 1100.dp)
                    .fillMaxWidth()
                    .then(
                        if (isCompactWidth) {
                            Modifier.fillMaxHeight()
                        } else {
                            Modifier
                                .fillMaxHeight(0.90f)
                                .heightIn(max = 850.dp)
                        }
                    )
                    .graphicsLayer {
                        translationY = dismissOffsetPx
                        val scale = 1f - dismissProgress * 0.012f
                        scaleX = scale
                        scaleY = scale
                    },
                shape = surfaceShape,
                color = surfaceColor,
                shadowElevation = 28.dp,
                tonalElevation = 4.dp,
            ) {
                val detailsScrollState = rememberScrollState()

                LaunchedEffect(currentMedia.id) {
                    detailsScrollState.scrollTo(0)
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    /*
                     * Keep the poster out of the shared transition overlay.
                     * Otherwise it is drawn above the gradient/title/badges
                     * until the shared transition finishes.
                     */
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(430.dp)
                            .graphicsLayer {
                                translationY = -detailsScrollState.value.toFloat()
                            },
                    ) {
                        CoveAsyncImage(
                            model = tmdbImageSize(
                                currentMedia.backdropUrl,
                                "w1280",
                            ),
                            contentDescription = "${currentMedia.title} backdrop",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.High,
                        )

                        // Slightly darken the hero image.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Color.Black.copy(alpha = 0.16f),
                                ),
                        )

                        // Fade the image into the overlay surface.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0.00f to Color.Transparent,
                                            0.42f to Color.Transparent,
                                            0.60f to surfaceColor.copy(alpha = 0.22f),
                                            0.76f to surfaceColor.copy(alpha = 0.62f),
                                            0.90f to surfaceColor.copy(alpha = 0.92f),
                                            1.00f to surfaceColor,
                                        ),
                                    ),
                                ),
                        )

                        // Blend the left/right edges into the panel too.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        colorStops = arrayOf(
                                            0.00f to surfaceColor.copy(alpha = 0.22f),
                                            0.14f to Color.Transparent,
                                            0.86f to Color.Transparent,
                                            1.00f to surfaceColor.copy(alpha = 0.22f),
                                        ),
                                    ),
                                ),
                    )
                }

                    /*
                     * The cast card is the person sheet's origin, so it carries the
                     * same shared keys the sheet binds to. Both live in this sheet's
                     * AnimatedVisibility, which is disposed in the frame the person
                     * sheet appears — the bounds survive that, exactly as they do for
                     * a page card expanding into this sheet.
                     */
                    val castCard: @Composable (Person, () -> Unit) -> Unit = { person, onClick ->
                        PersonCard(
                            person = person,
                            modifier = if (reducedMotion) Modifier else Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(
                                    key = PersonSharedKey(person.id, PersonSharedPart.Container),
                                ),
                                animatedVisibilityScope = this@AnimatedVisibility,
                                boundsTransform = { _, _ ->
                                    spring(
                                        dampingRatio = 0.82f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    )
                                },
                                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                renderInOverlayDuringTransition = false,
                            ),
                            avatarModifier = if (reducedMotion) Modifier else Modifier.sharedElement(
                                sharedContentState = rememberSharedContentState(
                                    key = PersonSharedKey(person.id, PersonSharedPart.Portrait),
                                ),
                                animatedVisibilityScope = this@AnimatedVisibility,
                                boundsTransform = { _, _ ->
                                    spring(
                                        dampingRatio = 0.82f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    )
                                },
                                renderInOverlayDuringTransition = false,
                            ),
                            onClick = onClick,
                        )
                    }

                    /*
                     * No delayed fade: title and badges are present from the
                     * first frame while the card is expanding.
                     */
                    MediaDetailsHeroContent(
                        media = currentMedia,
                        castCard = castCard,
                        scrollState = detailsScrollState,
                        currentListCategory = currentListCategory,
                        currentRating = currentRating,
                        watchLabel = watchLabel,
                        onWatch = { onWatch(currentMedia) },
                        onChooseSource = { onChooseSource(currentMedia) },
                        onListCategorySelected = { category ->
                            onListCategorySelected(currentMedia, category)
                        },
                        onRatingSelected = { rating ->
                            onRatingSelected(currentMedia, rating)
                        },
                        onMediaSelected = onMediaSelected,
                        onPersonSelected = onPersonSelected,
                        onVideoSelected = { video ->
                            onVideoSelected(currentMedia, video)
                        },
                        videoPlayer = videoPlayer,
                        onLoadEpisodes = onLoadEpisodes,
                        onEpisodeSelected = { season, episode ->
                            onEpisodeSelected(
                                currentMedia,
                                season,
                                episode,
                            )
                        },
                        onEpisodeChooseSource = { season, episode ->
                            onEpisodeChooseSource(
                                currentMedia,
                                season,
                                episode,
                            )
                        },
                        onEpisodeWatchedChange = { season, episode, watched ->
                            onEpisodeWatchedChange(
                                currentMedia,
                                season,
                                episode,
                                watched,
                            )
                        },
                        onMediaDragStart = onMediaDragStart,
                        onMediaDrag = onMediaDrag,
                        onMediaDragEnd = onMediaDragEnd,
                        onMediaDragCancel = onMediaDragCancel,
                        modifier = Modifier.fillMaxSize(),
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .graphicsLayer {
                                val progress = if (
                                    detailsScrollState.maxValue > 0
                                ) {
                                    detailsScrollState.value.toFloat() /
                                        detailsScrollState.maxValue
                                } else {
                                    0f
                                }

                                transformOrigin = TransformOrigin(
                                    pivotFractionX = 0f,
                                    pivotFractionY = 0.5f,
                                )
                                scaleX = progress
                                alpha = if (progress > 0f) 0.92f else 0f
                            }
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.tertiary,
                                        MaterialTheme.colorScheme.tertiary.copy(
                                            alpha = 0.45f,
                                        ),
                                    ),
                                )
                            ),
                    )

                    DetailsDismissDragHandle(
                        onDragStart = {
                            dismissAnimationJob?.cancel()
                        },
                        onDrag = { dragAmount ->
                            dismissOffsetPx = (
                                dismissOffsetPx + dragAmount
                            ).coerceIn(0f, dismissDistancePx)
                        },
                        onDragEnd = {
                            dismissAnimationJob?.cancel()

                            if (dismissOffsetPx >= dismissThresholdPx) {
                                dismissAnimationJob = dismissScope.launch {
                                    animate(
                                        initialValue = dismissOffsetPx,
                                        targetValue = dismissDistancePx,
                                        animationSpec = tween(
                                            durationMillis = 220,
                                        ),
                                    ) { value, _ ->
                                        dismissOffsetPx = value
                                    }
                                    onDismiss()
                                }
                            } else {
                                dismissAnimationJob = dismissScope.launch {
                                    animate(
                                        initialValue = dismissOffsetPx,
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = 0.74f,
                                            stiffness = Spring.StiffnessMedium,
                                        ),
                                    ) { value, _ ->
                                        dismissOffsetPx = value
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            dismissAnimationJob?.cancel()
                            dismissAnimationJob = dismissScope.launch {
                                animate(
                                    initialValue = dismissOffsetPx,
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = 0.78f,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                ) { value, _ ->
                                    dismissOffsetPx = value
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                    )

                    OverlayCloseButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaDetailsHeroContent(
    media: Media,
    /** Supplied by the caller because only it can bind the shared-element keys. */
    castCard: @Composable (Person, () -> Unit) -> Unit,
    scrollState: androidx.compose.foundation.ScrollState,
    currentListCategory: MyListCategory?,
    currentRating: Int?,
    watchLabel: String,
    onWatch: () -> Unit,
    onChooseSource: () -> Unit,
    onListCategorySelected: (MyListCategory) -> Unit,
    onRatingSelected: (Int) -> Unit,
    onMediaSelected: (Media) -> Unit,
    onPersonSelected: (Person) -> Unit,
    onVideoSelected: (MediaVideo) -> Unit,
    videoPlayer: @Composable ((Modifier) -> Unit)?,
    onLoadEpisodes: suspend (MediaSeason) -> List<MediaEpisode>,
    onEpisodeSelected: (MediaSeason, MediaEpisode) -> Unit,
    onEpisodeChooseSource: (MediaSeason, MediaEpisode) -> Unit,
    onEpisodeWatchedChange: (
        MediaSeason,
        MediaEpisode,
        Boolean,
    ) -> Unit,
    onMediaDragStart: (MediaDragPayload, Offset) -> Unit,
    onMediaDrag: (Offset) -> Unit,
    onMediaDragEnd: () -> Unit,
    onMediaDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val isCompactWidth = maxWidth < FullHeightOverlayBreakpoint
        val horizontalPadding = if (isCompactWidth) 20.dp else 40.dp
        val heroTopPadding = if (isCompactWidth) 245.dp else 285.dp
        val logoWidth = if (isCompactWidth) 220.dp else 256.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = heroTopPadding,
                    bottom = 40.dp,
                ),
        ) {
            if (media.logoUrl != null) {
                CoveAsyncImage(
                    model = tmdbImageSize(
                        media.logoUrl,
                        "w500",
                    ),
                    contentDescription = "${media.title} logo",
                    modifier = Modifier
                        .width(logoWidth)
                        .align(Alignment.CenterHorizontally),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                )
            } else {
                Text(
                    text = media.title ?: media.name ?: "Untitled",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

            media.tagline
                ?.takeIf { it.isNotBlank() }
                ?.let { tagline ->
                    Text(
                        text = tagline,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        textAlign = TextAlign.Center,
                    )
                }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DetailsBadge(
                        text = media.type?.label ?: "Unknown",
                    )

                    media.released?.let { year ->
                        DetailsBadge(text = year)
                    }

                    media.runtimeMinutes?.let { runtime ->
                        DetailsBadge(text = formatRuntime(runtime))
                    }

                    media.certification?.let { certification ->
                        DetailsBadge(text = certification)
                    }

                    media.rating?.let { rating ->
                        DetailsBadge(
                            text = "★ ${"%.1f".format(rating)}",
                            emphasized = true,
                        )
                    }
                }
            }

            if (media.genres.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        media.genres.forEach { genre ->
                            GenreChip(genre)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrimaryWatchButton(
                    label = watchLabel,
                    onClick = onWatch,
                    modifier = Modifier
                        .weight(2f)
                        .height(48.dp),
                )

                // Watch resolves a source on its own; this is the way past that
                // for anyone who wants to see what is on offer and pick.
                ChooseSourceButton(onClick = onChooseSource)

                MyListButton(
                    currentStatus = currentListCategory,
                    onStatusSelected = onListCategorySelected,
                )
                RatingButton(
                    currentRating = currentRating,
                    onStatusSelected = onRatingSelected,
                )
            }

            media.overview
                ?.takeIf { it.isNotBlank() }
                ?.let { overview ->
                    DetailsSectionTitle(
                        title = "Overview",
                        iconName = "lucide:align-left",
                    )
                    Text(
                        text = overview,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .widthIn(max = 720.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

            if (
                media.type == MediaType.Series &&
                media.seasons.isNotEmpty()
            ) {
                SeriesEpisodeBrowser(
                    seasons = media.seasons,
                    modifier = Modifier.padding(top = 30.dp),
                    onLoadEpisodes = onLoadEpisodes,
                    onEpisodeSelected = onEpisodeSelected,
                    onEpisodeChooseSource = onEpisodeChooseSource,
                    onEpisodeWatchedChange = onEpisodeWatchedChange,
                )
            }

            MediaVideosSection(
                videos = media.videos,
                onVideoSelected = onVideoSelected,
                player = videoPlayer,
            )

            if (media.cast.isNotEmpty()) {
                val castListState = rememberLazyListState()

                DetailsSectionTitle(
                    title = "Cast",
                    iconName = "lucide:users",
                    count = media.cast.size,
                )
                LazyRow(
                    state = castListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(media.cast) { castMember ->
                        val person = castMember.toPerson()
                        castCard(person) { onPersonSelected(person) }
                    }
                }
                HorizontalLazyListScrollbar(
                    state = castListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }

            MediaFacts(media, onPersonSelected)

            if (media.moreLikeThis.isNotEmpty()) {
                val moreLikeListState = rememberLazyListState()

                DetailsSectionTitle(
                    title = "More Like This",
                    iconName = "lucide:sparkles",
                    count = media.moreLikeThis.size,
                )
                LazyRow(
                    state = moreLikeListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(222.dp)
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(
                        items = media.moreLikeThis,
                        key = { recommendation -> recommendation.id },
                    ) { recommendation ->
                        MediaCard(
                            media = recommendation.toMedia(),
                            modifier = Modifier.width(140.dp),
                            onClick = {
                                onMediaSelected(recommendation.toMedia())
                            },
                            onDragStart = onMediaDragStart,
                            onDrag = onMediaDrag,
                            onDragEnd = onMediaDragEnd,
                            onDragCancel = onMediaDragCancel,
                        )
                    }
                }
                HorizontalLazyListScrollbar(
                    state = moreLikeListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GenreChip(genre: String) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.055f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "GenreChipScale",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isHovered) {
            colors.tertiary.copy(alpha = 0.14f)
        } else {
            colors.surfaceContainerHighest
        },
        animationSpec = tween(120),
        label = "GenreChipColor",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isHovered) colors.tertiary else colors.onSurfaceVariant,
        animationSpec = tween(120),
        label = "GenreChipContent",
    )

    Surface(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .hoverable(interactionSource),
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(
            width = 1.dp,
            color = if (isHovered) {
                colors.tertiary.copy(alpha = 0.48f)
            } else {
                colors.outlineVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Text(
            text = genre,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MediaFacts(
    media: Media,
    onPersonSelected: (Person) -> Unit,
) {
    val facts = buildList {
        media.directors
            .takeIf { it.isNotEmpty() }
            ?.let { directors ->
                add(
                    DetailFactData(
                        label = if (directors.size > 1) "Directors" else "Director",
                        value = directors.joinToString { it.name },
                        iconName = "lucide:clapperboard",
                        people = directors,
                    )
                )
            }
        media.writers
            .takeIf { it.isNotEmpty() }
            ?.let { writers ->
                add(
                    DetailFactData(
                        label = if (writers.size > 1) "Writers" else "Writer",
                        value = writers.joinToString { it.name },
                        iconName = "lucide:pen-line",
                        people = writers,
                    )
                )
            }
        media.productionCompanies
            .takeIf { it.isNotEmpty() }
            ?.let {
                add(
                    DetailFactData(
                        "Production",
                        it.joinToString(),
                        "lucide:building-2",
                    )
                )
            }
        media.status?.let {
            add(DetailFactData("Status", it, "lucide:circle-dot"))
        }
        media.runtimeMinutes?.let {
            add(
                DetailFactData(
                    "Runtime",
                    formatRuntime(it),
                    "lucide:clock-3",
                )
            )
        }
        media.rating?.let {
            add(
                DetailFactData(
                    "Rating",
                    "${"%.1f".format(it)} / 10",
                    "lucide:star",
                )
            )
        }
        media.originalLanguage?.let {
            add(
                DetailFactData(
                    "Original language",
                    it,
                    "lucide:languages",
                )
            )
        }
        media.spokenLanguages
            .takeIf { it.isNotEmpty() }
            ?.let {
                add(
                    DetailFactData(
                        "Spoken languages",
                        it.joinToString(),
                        "lucide:message-circle",
                    )
                )
            }
        media.originCountries
            .takeIf { it.isNotEmpty() }
            ?.let {
                add(
                    DetailFactData(
                        "Production countries",
                        it.joinToString(),
                        "lucide:globe-2",
                    )
                )
            }
        media.certification?.let {
            add(
                DetailFactData(
                    "Certification",
                    it,
                    "lucide:badge-check",
                )
            )
        }
    }

    if (facts.isEmpty()) return

    DetailsSectionTitle(
        title = "Details",
        iconName = "lucide:info",
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        val columnCount = when {
            maxWidth >= 760.dp -> 3
            maxWidth >= 460.dp -> 2
            else -> 1
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            facts.chunked(columnCount).forEach { rowFacts ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowFacts.forEach { fact ->
                        DetailFact(
                            fact = fact,
                            modifier = Modifier.weight(1f),
                            onPersonSelected = onPersonSelected,
                        )
                    }
                    repeat(columnCount - rowFacts.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
