package com.coveninja.cove.ui.tv.player

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.model.LabelledSegment
import com.coveninja.cove.shared.model.SegmentKind
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.player.SeekChunk
import com.coveninja.cove.ui.components.player.seekChunks
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.state.PlaybackRequest
import com.coveninja.cove.ui.state.PlaybackStatus
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.focus.TvFocusDefaults
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import com.coveninja.cove.ui.tv.focus.tvFocusTarget

/**
 * The control surface, and everything that makes it feel like part of the film rather than a
 * dialog laid on top of one.
 *
 * Three bands — what is playing, where you are, and what you can do — arriving in that order
 * rather than together. The stagger is small enough not to be waited on and does one useful
 * thing beyond looking considered: it draws the eye down to the transport row, which is where
 * focus has just landed and where the next press will go.
 */
@Composable
internal fun AnimatedVisibilityScope.TvPlayerChrome(
    request: PlaybackRequest,
    status: PlaybackStatus,
    segments: List<LabelledSegment>,
    playFocusRequester: FocusRequester,
    seekStep: Double,
    onTogglePause: () -> Unit,
    onSeek: (Double) -> Unit,
    onCycleSubtitles: () -> Unit,
    onCycleAudio: () -> Unit,
    onClose: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val dimens = TvTheme.dimens
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    var barFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                // Taller and softer than a single step: the picture has to survive underneath
                // it, and a hard-edged panel is what makes controls read as pasted on.
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to CoveColors.Scrim.copy(alpha = 0.45f),
                    1f to CoveColors.Scrim.copy(alpha = 0.94f),
                ),
            )
            .padding(
                start = dimens.overscanHorizontal,
                end = dimens.overscanHorizontal,
                top = 96.dp,
                bottom = dimens.overscanVertical + 8.dp,
            )
            .onFocusChanged { focus ->
                barFocused = focus.hasFocus
                onFocusChanged(focus.hasFocus)
            },
    ) {
        TvNowPlaying(
            request = request,
            modifier = Modifier.animateEnterExit(
                enter = staggeredEnter(reducedMotion, delayMillis = 0),
                exit = fadeOut(tween(120)),
            ),
        )

        TvSegmentedSeekBar(
            status = status,
            segments = segments,
            expanded = barFocused,
            modifier = Modifier
                .padding(top = 18.dp)
                .animateEnterExit(
                    enter = staggeredEnter(reducedMotion, delayMillis = 55),
                    exit = fadeOut(tween(120)),
                ),
        )

        TvTransportRow(
            status = status,
            seekStep = seekStep,
            playFocusRequester = playFocusRequester,
            onTogglePause = onTogglePause,
            onSeek = onSeek,
            onCycleSubtitles = onCycleSubtitles,
            onCycleAudio = onCycleAudio,
            onClose = onClose,
            modifier = Modifier
                .padding(top = 20.dp)
                .animateEnterExit(
                    enter = staggeredEnter(reducedMotion, delayMillis = 110),
                    exit = fadeOut(tween(120)),
                ),
        )
    }
}

/** Rise-and-fade, offset per band. Reduced motion keeps the order but drops the movement. */
private fun staggeredEnter(reducedMotion: Boolean, delayMillis: Int) = if (reducedMotion) {
    fadeIn(snap())
} else {
    fadeIn(tween(durationMillis = 220, delayMillis = delayMillis)) +
        slideInVertically(
            animationSpec = tween(durationMillis = 300, delayMillis = delayMillis),
            initialOffsetY = { it / 3 },
        )
}

@Composable
private fun TvNowPlaying(request: PlaybackRequest, modifier: Modifier = Modifier) {
    val title = request.media.title ?: request.media.name.orEmpty()
    val episode = request.season?.let { season ->
        request.episode?.let { number -> "S$season E$number" }
    }

    Column(modifier = modifier) {
        // The episode marker leads as a small accent line, so the title below it is the one
        // large thing in the band and reads first from across a room.
        listOfNotNull(episode, request.episodeTitle?.takeIf { it.isNotBlank() })
            .joinToString("  ·  ")
            .takeIf { it.isNotBlank() }
            ?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = CoveColors.Brand.Accent,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = CoveColors.Neutral.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The timeline, drawn as the pieces it is actually made of.
 *
 * Labelled stretches — a recap, an intro, the credits — are their own coloured pieces rather
 * than blocks painted over one continuous track, which is what lets every boundary get the same
 * gap and rounding. It is the same `seekChunks` the desktop bar is built from, so a title's
 * intro sits in exactly the same place on both.
 *
 * The bar swells when the transport row takes focus. That is the whole affordance for "this is
 * live now": there is no cursor to hover it with, so the only way it can announce itself is to
 * respond when attention arrives.
 */
@Composable
private fun TvSegmentedSeekBar(
    status: PlaybackStatus,
    segments: List<LabelledSegment>,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val chunks = remember(status.durationSeconds, segments) {
        seekChunks(status.durationSeconds, segments)
    }
    val height by animateDpAsState(
        targetValue = if (expanded) 12.dp else 7.dp,
        animationSpec = if (reducedMotion) snap() else spring(stiffness = Spring.StiffnessMedium),
        label = "TvSeekBarHeight",
    )
    val knob by animateDpAsState(
        targetValue = if (expanded) 22.dp else 0.dp,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
        },
        label = "TvSeekBarKnob",
    )
    val playedFraction = if (status.durationSeconds > 0.0) {
        (status.positionSeconds / status.durationSeconds).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.fillMaxWidth().height(height),
                horizontalArrangement = Arrangement.spacedBy(CHUNK_GAP),
            ) {
                chunks.forEach { chunk ->
                    TvSeekChunk(
                        chunk = chunk,
                        position = status.positionSeconds,
                        buffered = status.bufferedSeconds,
                        modifier = Modifier
                            .weight(chunk.lengthSeconds.toFloat().coerceAtLeast(0.0001f))
                            .fillMaxHeight(),
                    )
                }
            }
            // The knob rides the played fraction across the full width. Laid out by hand
            // rather than with padding so it can sit exactly on the boundary at either end
            // instead of being pushed off it.
            if (knob > 0.dp) {
                KnobAt(fraction = playedFraction, size = knob)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatClock(status.positionSeconds),
                style = MaterialTheme.typography.labelMedium,
                color = CoveColors.Neutral.Text,
            )
            Text(
                // What is left, not what has been: at the end of an evening the useful number
                // is how much longer this goes on for.
                text = "−" + formatClock(status.durationSeconds - status.positionSeconds),
                style = MaterialTheme.typography.labelMedium,
                color = CoveColors.Neutral.MutedDim,
            )
        }
    }
}

@Composable
private fun TvSeekChunk(
    chunk: SeekChunk,
    position: Double,
    buffered: Double,
    modifier: Modifier = Modifier,
) {
    val track = chunk.kind?.segmentColor()?.copy(alpha = 0.34f)
        ?: CoveColors.Neutral.Text.copy(alpha = 0.20f)
    val fill = chunk.kind?.segmentColor() ?: CoveColors.Brand.Accent
    val shape = RoundedCornerShape(50)

    Box(modifier = modifier.clip(shape).background(track)) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(chunk.fillFraction(buffered))
                .background(CoveColors.Neutral.Text.copy(alpha = 0.28f)),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(chunk.fillFraction(position))
                .background(fill),
        )
    }
}

/** Places a dot at [fraction] of the available width, centred on that exact point. */
@Composable
private fun KnobAt(fraction: Float, size: Dp) {
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(CoveColors.Neutral.Text, CircleShape),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, placeable.height) {
            val x = ((constraints.maxWidth - placeable.width) * fraction).toInt()
            placeable.place(x, 0)
        }
    }
}

private fun SegmentKind.segmentColor(): Color = when (this) {
    SegmentKind.Recap -> CoveColors.Segment.Recap
    SegmentKind.Intro -> CoveColors.Segment.Intro
    SegmentKind.Credits -> CoveColors.Segment.Credits
    SegmentKind.Preview -> CoveColors.Segment.Preview
}

@Composable
private fun TvTransportRow(
    status: PlaybackStatus,
    seekStep: Double,
    playFocusRequester: FocusRequester,
    onTogglePause: () -> Unit,
    onSeek: (Double) -> Unit,
    onCycleSubtitles: () -> Unit,
    onCycleAudio: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().tvFocusGroup(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvTransportButton(
            icon = "lucide:rotate-ccw",
            label = "Back ${seekStep.toInt()}s",
            onClick = { onSeek(-seekStep) },
        )
        TvTransportButton(
            icon = if (status.paused) "iconamoon:player-play" else "iconamoon:player-pause",
            label = if (status.paused) "Play" else "Pause",
            primary = true,
            onClick = onTogglePause,
            modifier = Modifier.focusRequester(playFocusRequester),
        )
        TvTransportButton(
            icon = "lucide:rotate-cw",
            label = "Forward ${seekStep.toInt()}s",
            onClick = { onSeek(seekStep) },
        )

        Spacer(modifier = Modifier.weight(1f))

        if (status.subtitleTracks.isNotEmpty()) {
            TvTransportButton(
                icon = "lucide:captions",
                label = status.subtitleTracks
                    .firstOrNull { it.id == status.selectedSubtitleId }
                    ?.label
                    ?: "Subtitles off",
                active = status.selectedSubtitleId != null,
                onClick = onCycleSubtitles,
            )
        }
        if (status.audioTracks.size > 1) {
            TvTransportButton(
                icon = "lucide:audio-lines",
                label = status.audioTracks
                    .firstOrNull { it.id == status.selectedAudioId }
                    ?.label
                    ?: "Audio",
                onClick = onCycleAudio,
            )
        }
        TvTransportButton(icon = "lucide:x", label = "Stop", onClick = onClose)
    }
}

/**
 * A round transport control that names itself only while it holds focus.
 *
 * Six permanently labelled buttons is a row of words competing with the film behind them. One
 * label, on the one thing about to be pressed, says everything a viewer needs and leaves the
 * picture alone the rest of the time — and because focus is the only way to reach a control
 * here, there is never a moment where the label is missing when it was wanted.
 */
@Composable
private fun TvTransportButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    active: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val diameter = if (primary) 68.dp else 54.dp

    val background by animateColorAsState(
        targetValue = when {
            focused -> CoveColors.Neutral.Text
            primary -> CoveColors.Brand.Accent
            else -> CoveColors.Neutral.Text.copy(alpha = 0.14f)
        },
        animationSpec = if (reducedMotion) snap() else tween(160),
        label = "TvTransportBackground",
    )
    val content by animateColorAsState(
        targetValue = when {
            focused -> CoveColors.Neutral.Background
            primary -> CoveColors.Brand.OnAccent
            active -> CoveColors.Brand.Accent
            else -> CoveColors.Neutral.Text
        },
        animationSpec = if (reducedMotion) snap() else tween(160),
        label = "TvTransportContent",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvTransportLabel",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(diameter)
                .tvFocusTarget(
                    shape = CircleShape,
                    onClick = onClick,
                    scale = if (primary) 1.12f else TvFocusDefaults.CardScale,
                    ringColor = Color.Transparent,
                    interactionSource = interactionSource,
                )
                .background(background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = icon,
                tint = content,
                modifier = Modifier.size(if (primary) 30.dp else 24.dp),
            )
        }
        // Reserved height, so a label appearing never nudges the row it is under.
        Box(modifier = Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = CoveColors.Neutral.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .graphicsLayer { alpha = labelAlpha },
            )
        }
    }
}

private val CHUNK_GAP = 3.dp
