package com.coveninja.cove.ui.components.person

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.coveninja.cove.ui.components.media.card.MediaCard
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.CreditFilter
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.Person
import com.coveninja.cove.ui.model.PersonCreditEntry
import com.coveninja.cove.ui.model.ageOf
import com.coveninja.cove.ui.model.creditCountsOf
import com.coveninja.cove.ui.model.filmographyOf
import com.coveninja.cove.ui.model.knownForOf
import com.coveninja.cove.ui.model.tmdbImageSize
import com.coveninja.cove.ui.model.toMedia
import com.coveninja.cove.ui.model.yearOf
import com.coveninja.cove.ui.state.LocalMotionPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** How many filmography rows are shown before the list asks to be expanded. */
private const val CollapsedCreditRows = 12

/** How many lines of biography are shown before "Show more". */
private const val CollapsedBiographyLines = 8

/**
 * The person sheet: the same surface as the title sheet, showing a person instead.
 *
 * It replaces the title sheet rather than stacking on it — one sheet at a time — and the
 * title sheet is left selected underneath, so dismissing this one puts it straight back
 * without refetching anything.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.PersonDetailsSharedOverlay(
    person: Person?,
    visible: Boolean,
    onDismiss: () -> Unit,
    /** The title this person was opened from, for the back affordance. */
    backTitle: String? = null,
    onMediaSelected: (Media) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && person != null,
        modifier = modifier.fillMaxSize(),
        enter = EnterTransition.None,
        exit = ExitTransition.None,
    ) {
        val currentPerson = person ?: return@AnimatedVisibility
        val reducedMotion = LocalMotionPolicy.current.reducedMotion
        val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            val dismissScope = rememberCoroutineScope()
            var dismissOffsetPx by remember(currentPerson.id) { mutableFloatStateOf(0f) }
            var dismissAnimationJob by remember { mutableStateOf<Job?>(null) }
            val dismissDistancePx = with(density) { maxHeight.toPx() }
            val dismissThresholdPx = minOf(
                dismissDistancePx * 0.22f,
                with(density) { 180.dp.toPx() },
            )
            val dismissProgress = if (dismissThresholdPx > 0f) {
                (dismissOffsetPx / dismissThresholdPx).coerceIn(0f, 1f)
            } else {
                0f
            }

            val isCompactWidth = maxWidth < FullHeightOverlayBreakpoint
            val surfaceShape = if (isCompactWidth) {
                RoundedCornerShape(0.dp)
            } else {
                RoundedCornerShape(12.dp)
            }

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
                        Color.Black.copy(alpha = 0.68f * (1f - dismissProgress * 0.62f)),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
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
                            key = PersonSharedKey(
                                personId = currentPerson.id,
                                part = PersonSharedPart.Container,
                            ),
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

                LaunchedEffect(currentPerson.id) {
                    detailsScrollState.scrollTo(0)
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    /*
                     * A person has no backdrop, so the hero is their own portrait blown up
                     * and blurred behind the crisp one. Modifier.blur is a no-op below
                     * Android 12; there it simply reads as a soft-edged crop.
                     */
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .graphicsLayer {
                                translationY = -detailsScrollState.value.toFloat()
                            },
                    ) {
                        if (currentPerson.profileUrl != null) {
                            CoveAsyncImage(
                                model = tmdbImageSize(currentPerson.profileUrl, "w780"),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(28.dp),
                                contentScale = ContentScale.Crop,
                                filterQuality = FilterQuality.Low,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.16f)),
                        )

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

                    PersonDetailsContent(
                        person = currentPerson,
                        scrollState = detailsScrollState,
                        portraitModifier = if (reducedMotion) Modifier else Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState(
                                key = PersonSharedKey(
                                    personId = currentPerson.id,
                                    part = PersonSharedPart.Portrait,
                                ),
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
                        onMediaSelected = onMediaSelected,
                        modifier = Modifier.fillMaxSize(),
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .graphicsLayer {
                                val progress = if (detailsScrollState.maxValue > 0) {
                                    detailsScrollState.value.toFloat() / detailsScrollState.maxValue
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
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f),
                                    ),
                                )
                            ),
                    )

                    DetailsDismissDragHandle(
                        onDragStart = { dismissAnimationJob?.cancel() },
                        onDrag = { dragAmount ->
                            dismissOffsetPx = (dismissOffsetPx + dragAmount)
                                .coerceIn(0f, dismissDistancePx)
                        },
                        onDragEnd = {
                            dismissAnimationJob?.cancel()

                            if (dismissOffsetPx >= dismissThresholdPx) {
                                dismissAnimationJob = dismissScope.launch {
                                    animate(
                                        initialValue = dismissOffsetPx,
                                        targetValue = dismissDistancePx,
                                        animationSpec = tween(durationMillis = 220),
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

                    // Back and close both dismiss: the title sheet is still selected
                    // underneath, so leaving here is what puts it back on screen.
                    backTitle?.let { title ->
                        OverlayBackButton(
                            title = title,
                            compact = isCompactWidth,
                            onClick = onDismiss,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(14.dp),
                        )
                    }

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
private fun PersonDetailsContent(
    person: Person,
    scrollState: ScrollState,
    portraitModifier: Modifier,
    onMediaSelected: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val colors = MaterialTheme.colorScheme
        val isCompactWidth = maxWidth < FullHeightOverlayBreakpoint
        val horizontalPadding = if (isCompactWidth) 20.dp else 40.dp
        val heroTopPadding = if (isCompactWidth) 96.dp else 120.dp
        val portraitSize = if (isCompactWidth) 128.dp else 148.dp

        val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
        val age = remember(person.birthday, person.deathday, today) {
            ageOf(person.birthday, person.deathday, today)
        }
        var filter by remember(person.id) { mutableStateOf(CreditFilter.All) }
        var creditsExpanded by remember(person.id) { mutableStateOf(false) }

        val counts = remember(person.credits) { creditCountsOf(person.credits) }
        val knownFor = remember(person.credits) { knownForOf(person.credits) }
        val filmography = remember(person.credits, filter) {
            filmographyOf(person.credits, filter)
        }

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
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(portraitSize)
                    .then(portraitModifier)
                    .clip(CircleShape)
                    .background(colors.outlineVariant.copy(alpha = 0.55f))
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (person.profileUrl != null) {
                    CoveAsyncImage(
                        model = tmdbImageSize(person.profileUrl, "w500"),
                        contentDescription = person.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.High,
                    )
                } else {
                    Text(
                        text = person.initial,
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Text(
                text = person.name,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 16.dp),
                color = colors.onSurface,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            person.role?.takeIf { it.isNotBlank() }?.let { role ->
                Text(
                    text = role,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 6.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
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
                    person.knownForDepartment?.let { DetailsBadge(text = it) }
                    yearOf(person.birthday)?.let { year ->
                        DetailsBadge(text = if (person.deathday != null) {
                            "$year – ${yearOf(person.deathday)}"
                        } else {
                            "Born $year"
                        })
                    }
                    age?.let { years ->
                        DetailsBadge(
                            text = if (person.deathday != null) "$years at death" else "$years years",
                        )
                    }
                    counts[CreditFilter.All]
                        ?.takeIf { it > 0 }
                        ?.let { total ->
                            DetailsBadge(
                                text = if (total == 1) "1 credit" else "$total credits",
                                emphasized = true,
                            )
                        }
                }
            }

            person.biography?.let { biography ->
                var expanded by remember(person.id) { mutableStateOf(false) }

                DetailsSectionTitle(title = "Biography", iconName = "lucide:align-left")
                Text(
                    text = biography,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .widthIn(max = 720.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else CollapsedBiographyLines,
                    overflow = TextOverflow.Ellipsis,
                )
                InlineTextButton(
                    // Cheap proxy for "is it actually clipped": a biography short enough
                    // to fit is never long enough to need the toggle.
                    visible = biography.length > 420,
                    label = if (expanded) "Show less" else "Show more",
                    onClick = { expanded = !expanded },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (knownFor.isNotEmpty()) {
                val knownForState = rememberLazyListState()

                DetailsSectionTitle(
                    title = "Known for",
                    iconName = "lucide:sparkles",
                    count = knownFor.size,
                )
                LazyRow(
                    state = knownForState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(222.dp)
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(items = knownFor, key = { credit -> credit.id }) { credit ->
                        MediaCard(
                            media = credit.toMedia(),
                            modifier = Modifier.width(140.dp),
                            onClick = { onMediaSelected(credit.toMedia()) },
                        )
                    }
                }
                HorizontalLazyListScrollbar(
                    state = knownForState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }

            PersonFacts(person = person, age = age, creditCount = counts[CreditFilter.All] ?: 0)

            if (person.credits.isNotEmpty()) {
                DetailsSectionTitle(
                    title = "Filmography",
                    iconName = "lucide:film",
                    count = counts[CreditFilter.All] ?: 0,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CreditFilter.entries.forEach { entry ->
                        val count = counts[entry] ?: 0
                        if (count > 0) {
                            CreditFilterChip(
                                label = entry.label,
                                count = count,
                                selected = filter == entry,
                                onClick = {
                                    filter = entry
                                    creditsExpanded = false
                                },
                            )
                        }
                    }
                }

                // A plain Column, not a LazyColumn: this whole sheet is one
                // verticalScroll, and nesting another vertical scroller in it measures
                // against an infinite height.
                val visibleCredits = if (creditsExpanded) {
                    filmography
                } else {
                    filmography.take(CollapsedCreditRows)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    visibleCredits.forEach { credit ->
                        FilmographyRow(
                            credit = credit,
                            onClick = { onMediaSelected(credit.toMedia()) },
                        )
                    }
                }

                InlineTextButton(
                    visible = filmography.size > CollapsedCreditRows,
                    label = if (creditsExpanded) {
                        "Show less"
                    } else {
                        "Show all ${filmography.size} credits"
                    },
                    onClick = { creditsExpanded = !creditsExpanded },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun PersonFacts(
    person: Person,
    age: Int?,
    creditCount: Int,
) {
    val facts = buildList {
        person.birthday?.let { birthday ->
            add(
                DetailFactData(
                    label = "Born",
                    value = if (age != null && person.deathday == null) {
                        "$birthday ($age)"
                    } else {
                        birthday
                    },
                    iconName = "lucide:calendar-days",
                )
            )
        }
        person.deathday?.let { deathday ->
            add(
                DetailFactData(
                    label = "Died",
                    value = if (age != null) "$deathday (aged $age)" else deathday,
                    iconName = "lucide:clock",
                )
            )
        }
        person.placeOfBirth?.let {
            add(DetailFactData("Place of birth", it, "lucide:globe-2"))
        }
        person.knownForDepartment?.let {
            add(DetailFactData("Known for", it, "lucide:clapperboard"))
        }
        creditCount.takeIf { it > 0 }?.let {
            add(DetailFactData("Credits", it.toString(), "lucide:list-video"))
        }
        person.alsoKnownAs
            .takeIf { it.isNotEmpty() }
            ?.let {
                add(DetailFactData("Also known as", it.take(3).joinToString(), "lucide:languages"))
            }
    }

    if (facts.isEmpty()) return

    DetailsSectionTitle(title = "Details", iconName = "lucide:info")
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
                        DetailFact(fact = fact, modifier = Modifier.weight(1f))
                    }
                    repeat(columnCount - rowFacts.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** One title in the filmography list: poster, what they did on it, and when. */
@Composable
private fun FilmographyRow(
    credit: PersonCreditEntry,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val containerColor by animateColorAsState(
        targetValue = if (isHovered) {
            colors.surfaceContainerHighest.copy(alpha = 0.75f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(130),
        label = "FilmographyRowContainer",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(66.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (credit.posterUrl != null) {
                CoveAsyncImage(
                    model = tmdbImageSize(credit.posterUrl, "w185"),
                    contentDescription = credit.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                )
            } else {
                IconifyIcon(
                    icon = if (credit.type == MediaType.Series) "lucide:tv" else "lucide:film",
                    modifier = Modifier.size(16.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = credit.title,
                color = colors.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val subtitle = buildList {
                credit.character?.let { add("as $it") }
                credit.job?.let(::add)
                credit.episodeCount
                    ?.takeIf { credit.type == MediaType.Series }
                    ?.let { add(if (it == 1) "1 episode" else "$it episodes") }
            }.joinToString(" · ")

            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    modifier = Modifier.padding(top = 2.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        credit.year?.let { year ->
            Text(
                text = year,
                modifier = Modifier.padding(start = 10.dp),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        IconifyIcon(
            icon = "lucide:chevron-right",
            modifier = Modifier
                .padding(start = 8.dp)
                .size(15.dp),
            tint = if (isHovered) colors.tertiary else colors.outlineVariant,
        )
    }
}

@Composable
private fun CreditFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val containerColor by animateColorAsState(
        targetValue = when {
            selected -> colors.tertiaryContainer
            isHovered -> colors.surfaceContainerHighest
            else -> colors.surfaceContainerHigh
        },
        animationSpec = tween(130),
        label = "CreditFilterChipColor",
    )
    val scale by animateFloatAsState(
        targetValue = if (isHovered && !selected) 1.045f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "CreditFilterChipScale",
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(containerColor)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$label · $count",
            color = if (selected) colors.onTertiaryContainer else colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/** The "Show more" / "Show all N credits" affordance. */
@Composable
private fun InlineTextButton(
    visible: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val contentColor by animateColorAsState(
        targetValue = if (isHovered) colors.tertiary else colors.onSurfaceVariant,
        animationSpec = tween(120),
        label = "InlineTextButtonColor",
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconifyIcon(
            icon = "lucide:chevron-down",
            modifier = Modifier
                .padding(start = 4.dp)
                .size(14.dp),
            tint = contentColor,
        )
    }
}

/** Back to the title this person was opened from. */
@Composable
private fun OverlayBackButton(
    title: String,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val containerColor by animateColorAsState(
        targetValue = if (isHovered) colors.tertiary else Color.Black.copy(alpha = 0.48f),
        animationSpec = tween(130),
        label = "OverlayBackColor",
    )
    val contentColor = if (isHovered) colors.onTertiary else Color.White

    Surface(
        modifier = modifier
            .height(44.dp)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = CircleShape,
        color = containerColor,
        shadowElevation = if (isHovered) 9.dp else 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 11.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconifyIcon(
                icon = "lucide:chevron-left",
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
            // The title is dropped on a narrow window: at that width it would push the
            // pill across most of the sheet.
            if (!compact) {
                Text(
                    text = title,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .widthIn(max = 220.dp),
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
