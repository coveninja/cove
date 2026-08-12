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
import coil3.compose.AsyncImage
import com.coveninja.cove.ui.components.common.HorizontalLazyListScrollbar
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.components.media.action.ChooseSourceButton
import com.coveninja.cove.ui.components.media.action.MyListButton
import com.coveninja.cove.ui.components.media.action.PrimaryWatchButton
import com.coveninja.cove.ui.components.media.action.RatingButton
import com.coveninja.cove.ui.components.media.card.MediaCard
import com.coveninja.cove.ui.components.media.card.MediaCastCard
import com.coveninja.cove.ui.components.media.drag.MediaDragPayload
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaEpisode
import com.coveninja.cove.ui.model.MediaSeason
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.MediaVideo
import com.coveninja.cove.ui.model.tmdbImageSize
import com.coveninja.cove.ui.model.toMedia
import com.coveninja.cove.ui.icons.IconifyIcon
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val FullHeightOverlayBreakpoint = 600.dp

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.MediaDetailsSharedOverlay(
    media: Media?,
    visible: Boolean,
    onDismiss: () -> Unit,
    currentListCategory: MyListCategory? = null,
    currentRating: Int? = null,
    onWatch: (Media) -> Unit = {},
    onChooseSource: (Media) -> Unit = {},
    onListCategorySelected: (Media, MyListCategory) -> Unit = { _, _ -> },
    onRatingSelected: (Media, Int) -> Unit = { _, _ -> },
    onMediaSelected: (Media) -> Unit = {},
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
                    .animateEnterExit(
                        enter = fadeIn(
                            animationSpec = tween(durationMillis = 180),
                        ),
                        exit = fadeOut(
                            animationSpec = tween(durationMillis = 140),
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
                modifier = Modifier
                    .sharedBounds(
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
                        AsyncImage(
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
                     * No delayed fade: title and badges are present from the
                     * first frame while the card is expanding.
                     */
                    MediaDetailsHeroContent(
                        media = currentMedia,
                        scrollState = detailsScrollState,
                        currentListCategory = currentListCategory,
                        currentRating = currentRating,
                        onWatch = { onWatch(currentMedia) },
                        onChooseSource = { onChooseSource(currentMedia) },
                        onListCategorySelected = { category ->
                            onListCategorySelected(currentMedia, category)
                        },
                        onRatingSelected = { rating ->
                            onRatingSelected(currentMedia, rating)
                        },
                        onMediaSelected = onMediaSelected,
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
    scrollState: androidx.compose.foundation.ScrollState,
    currentListCategory: MyListCategory?,
    currentRating: Int?,
    onWatch: () -> Unit,
    onChooseSource: () -> Unit,
    onListCategorySelected: (MyListCategory) -> Unit,
    onRatingSelected: (Int) -> Unit,
    onMediaSelected: (Media) -> Unit,
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
                AsyncImage(
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
                        MediaCastCard(castMember)
                    }
                }
                HorizontalLazyListScrollbar(
                    state = castListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }

            MediaFacts(media)

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
internal fun DetailsSectionTitle(
    title: String,
    iconName: String,
    count: Int? = null,
) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.tertiary.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = iconName,
                modifier = Modifier.size(16.dp),
                tint = colors.tertiary,
            )
        }

        Text(
            text = title,
            modifier = Modifier.padding(start = 10.dp),
            color = colors.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        count?.let {
            Text(
                text = it.toString(),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHighest)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        HorizontalDivider(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
            color = colors.outlineVariant.copy(alpha = 0.38f),
        )
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
private fun MediaFacts(media: Media) {
    val facts = buildList {
        media.directors
            .takeIf { it.isNotEmpty() }
            ?.let {
                add(
                    DetailFactData(
                        "Director",
                        it.joinToString(),
                        "lucide:clapperboard",
                    )
                )
            }
        media.writers
            .takeIf { it.isNotEmpty() }
            ?.let {
                add(
                    DetailFactData(
                        "Writer",
                        it.joinToString(),
                        "lucide:pen-line",
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

private data class DetailFactData(
    val label: String,
    val value: String,
    val iconName: String,
)

@Composable
private fun DetailFact(
    fact: DetailFactData,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.015f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "DetailFactScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (isHovered) 5.dp else 0.dp,
        animationSpec = tween(140),
        label = "DetailFactElevation",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isHovered) {
            colors.surfaceContainerHighest
        } else {
            colors.surfaceContainerHigh
        },
        animationSpec = tween(120),
        label = "DetailFactColor",
    )

    Surface(
        modifier = modifier
            .heightIn(min = 82.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .hoverable(interactionSource),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        shadowElevation = elevation,
        border = BorderStroke(
            width = 1.dp,
            color = if (isHovered) {
                colors.tertiary.copy(alpha = 0.40f)
            } else {
                colors.outlineVariant.copy(alpha = 0.35f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconifyIcon(
                    icon = fact.iconName,
                    modifier = Modifier.size(15.dp),
                    tint = if (isHovered) {
                        colors.tertiary
                    } else {
                        colors.onSurfaceVariant
                    },
                )
                Text(
                    text = fact.label,
                    modifier = Modifier.padding(start = 7.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                text = fact.value,
                modifier = Modifier.padding(top = 7.dp),
                color = colors.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DetailsDismissDragHandle(
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var isDragging by remember { mutableStateOf(false) }
    val handleWidth by animateDpAsState(
        targetValue = when {
            isDragging -> 68.dp
            isHovered -> 56.dp
            else -> 44.dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "DetailsDismissHandleWidth",
    )
    val handleColor by animateColorAsState(
        targetValue = when {
            isDragging -> MaterialTheme.colorScheme.tertiary
            isHovered -> Color.White.copy(alpha = 0.78f)
            else -> Color.White.copy(alpha = 0.46f)
        },
        animationSpec = tween(120),
        label = "DetailsDismissHandleColor",
    )

    Box(
        modifier = modifier
            .height(64.dp)
            .hoverable(interactionSource)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        isDragging = true
                        onDragStart()
                    },
                    onDragEnd = {
                        isDragging = false
                        onDragEnd()
                    },
                    onDragCancel = {
                        isDragging = false
                        onDragCancel()
                    },
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 11.dp)
                .width(handleWidth)
                .height(if (isDragging) 6.dp else 5.dp)
                .clip(CircleShape)
                .background(handleColor),
        )
    }
}

@Composable
private fun OverlayCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.88f
            isHovered -> 1.08f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "DetailsCloseScale",
    )
    val rotation by animateFloatAsState(
        targetValue = if (isHovered) 90f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "DetailsCloseRotation",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isHovered) {
            colors.tertiary
        } else {
            Color.Black.copy(alpha = 0.48f)
        },
        animationSpec = tween(130),
        label = "DetailsCloseColor",
    )

    Surface(
        modifier = modifier
            .size(44.dp)
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
        shadowElevation = if (isHovered) 9.dp else 3.dp,
        border = BorderStroke(
            width = 1.dp,
            color = Color.White.copy(
                alpha = if (isHovered) 0.28f else 0.16f,
            ),
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            IconifyIcon(
                icon = "iconamoon:close",
                modifier = Modifier
                    .size(23.dp)
                    .graphicsLayer {
                        rotationZ = rotation
                    },
                tint = if (isHovered) colors.onTertiary else Color.White,
            )
        }
    }
}

private fun formatRuntime(minutes: Int): String {
    val safeMinutes = minutes.coerceAtLeast(0)
    val hours = safeMinutes / 60
    val remainingMinutes = safeMinutes % 60

    return when {
        hours == 0 -> "${remainingMinutes}m"
        remainingMinutes == 0 -> "${hours}h"
        else -> "${hours}h ${remainingMinutes}m"
    }
}

@Composable
private fun DetailsBadge(
    text: String,
    emphasized: Boolean = false,
) {
    val containerColor = if (emphasized) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }

    val contentColor = if (emphasized) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .padding(
                horizontal = 10.dp,
                vertical = 5.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
