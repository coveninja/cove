package com.coveninja.cove.ui.components.player

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.shared.model.LabelledSegment
import com.coveninja.cove.shared.model.SegmentKind
import com.coveninja.cove.ui.components.menu.CMenuItem
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.MediaChapter
import com.coveninja.cove.ui.state.MediaTrack
import com.coveninja.cove.ui.state.PlaybackStatus
import com.coveninja.cove.ui.platform.hasHardwareKeyboard
import com.coveninja.cove.ui.platform.hasPointerHover
import com.coveninja.cove.ui.state.VideoScaling
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Which picker is open, if any.
 *
 * Hoisted out of the individual buttons for two reasons: opening one closes the
 * others, and the layer above needs to know that *something* is open so it does
 * not hide the controls out from under it.
 */
private enum class PlayerMenu { Subtitles, Audio, Scaling, Episodes, Speed, Overflow }

/** Fixed so every gap looks identical regardless of how long the segment is. */
private val SEGMENT_GAP = 1.5.dp
private val SEGMENT_RADIUS = 3.dp

/** Below this the transport, the clock and five menus stop fitting on one line. */
private val COMPACT_CONTROLS_WIDTH = 720.dp

/** Chapter divisions: a hairline, because there can be dozens on a feature film. */
private val CHAPTER_MARK_WIDTH = 2.dp

/**
 * A highlight travelling along the buffered stretch, positioned by [sweep] (0..1).
 *
 * Built as explicit stops rather than an animated offset because the band has to wrap
 * cleanly: the bright point is clamped away from both ends so the gradient never
 * degenerates into stops out of order, which throws rather than merely looking wrong.
 */
private fun shimmerStops(sweep: Float): Array<Pair<Float, Color>> {
    val dim = Color.White.copy(alpha = 0.22f)
    val bright = Color.White.copy(alpha = 0.44f)
    val centre = sweep.coerceIn(0.08f, 0.92f)
    return arrayOf(
        0f to dim,
        (centre - 0.08f).coerceAtLeast(0.001f) to dim,
        centre to bright,
        (centre + 0.08f).coerceAtMost(0.999f) to dim,
        1f to dim,
    )
}

/**
 * Transport controls drawn over the video.
 *
 * No close button: Escape leaves, and a permanent X sitting over the picture was
 * the easiest thing to hit by accident.
 */
@Composable
fun PlayerControls(
    title: String,
    status: PlaybackStatus,
    segments: List<LabelledSegment>,
    onTogglePause: () -> Unit,
    onSeek: (Double) -> Unit,
    /** Opens the keyboard map. Nothing else advertises that it exists. */
    onShowShortcuts: () -> Unit,
    onSetVolume: (Double) -> Unit,
    onSetMuted: (Boolean) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectSubtitle: (Int?) -> Unit,
    onSetSubtitleDelay: (Double) -> Unit,
    /**
     * Loads a subtitle file the viewer picks. Null where the platform has no files to
     * pick from, which is also what keeps the entry out of the menu there.
     */
    onLoadSubtitleFile: (() -> Unit)? = null,
    onSetAudioDelay: (Double) -> Unit,
    scaling: VideoScaling,
    onSelectScaling: (VideoScaling) -> Unit,
    onSelectSpeed: (Double) -> Unit,
    canChangeSource: Boolean,
    onChangeSource: () -> Unit,
    /** Null for a film, which has no episodes to pick between. */
    episodeBrowser: EpisodeBrowser?,
    /**
     * True while the controls are being used — a picker is open, or the pointer
     * is over the bar. The auto-hide timer is driven by pointer movement over the
     * video, and a dropdown is a popup in its own composition, so moving inside
     * one never reaches that handler and never counts as activity.
     */
    onInteractingChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var openMenu by remember { mutableStateOf<PlayerMenu?>(null) }
    val barInteraction = remember { MutableInteractionSource() }
    val barHovered by barInteraction.collectIsHoveredAsState()

    LaunchedEffect(openMenu, barHovered) {
        onInteractingChange(openMenu != null || barHovered)
    }
    // Clear interaction state when hidden so the parent can resume auto-hide.
    DisposableEffect(Unit) {
        onDispose { onInteractingChange(false) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(barInteraction)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                ),
            )
            .padding(horizontal = 26.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SegmentedSeekBar(
            positionSeconds = status.positionSeconds,
            durationSeconds = status.durationSeconds,
            segments = segments,
            bufferedSeconds = status.bufferedSeconds,
            chapters = status.chapters,
            buffering = status.waitingForData || status.bufferingPercent in 1..99,
            onSeek = onSeek,
        )

        // Drop the redundant volume strip first on narrow layouts.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < COMPACT_CONTROLS_WIDTH
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
            ) {
                PlayPauseButton(
                    paused = status.paused,
                    ended = status.endReached,
                    onClick = onTogglePause,
                )

                Text(
                    text = "${formatDuration(status.positionSeconds)}  /  " +
                        formatDuration(status.durationSeconds),
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )

                if (!compact) {
                    VolumeControl(
                        volume = status.volume,
                        muted = status.muted,
                        onSetVolume = onSetVolume,
                        onSetMuted = onSetMuted,
                    )
                } else {
                    ControlButton(
                        icon = if (status.muted || status.volume <= 0.0) {
                            "lucide:volume-x"
                        } else {
                            "lucide:volume-2"
                        },
                        onClick = { onSetMuted(!status.muted) },
                    )
                }

                Box(modifier = Modifier.weight(1f))

                // Keep the menu available when there are no tracks so a file can still be added.
                if (status.subtitleTracks.isNotEmpty() || onLoadSubtitleFile != null) {
                    TrackMenuButton(
                        icon = "lucide:captions",
                        tracks = status.subtitleTracks,
                        selectedId = status.selectedSubtitleId,
                        allowOff = true,
                        expanded = openMenu == PlayerMenu.Subtitles,
                        onExpandedChange = { openMenu = if (it) PlayerMenu.Subtitles else null },
                        onSelect = onSelectSubtitle,
                        delaySeconds = status.subtitleDelaySeconds,
                        onSetDelay = onSetSubtitleDelay,
                        onLoadFile = onLoadSubtitleFile,
                    )
                }
                if (status.audioTracks.size > 1) {
                    TrackMenuButton(
                        icon = "lucide:audio-lines",
                        tracks = status.audioTracks,
                        selectedId = status.selectedAudioId,
                        allowOff = false,
                        expanded = openMenu == PlayerMenu.Audio,
                        onExpandedChange = { openMenu = if (it) PlayerMenu.Audio else null },
                        onSelect = { id -> id?.let(onSelectAudio) },
                        delaySeconds = status.audioDelaySeconds,
                        onSetDelay = onSetAudioDelay,
                    )
                }
                if (!compact && hasHardwareKeyboard) {
                    ControlButton(icon = "lucide:keyboard", onClick = onShowShortcuts)
                }

                if (!compact) {
                    SpeedMenuButton(
                        speed = status.speed,
                        expanded = openMenu == PlayerMenu.Speed,
                        onExpandedChange = { openMenu = if (it) PlayerMenu.Speed else null },
                        onSelect = onSelectSpeed,
                    )

                    ScalingMenuButton(
                        selected = scaling,
                        expanded = openMenu == PlayerMenu.Scaling,
                        onExpandedChange = { openMenu = if (it) PlayerMenu.Scaling else null },
                        onSelect = onSelectScaling,
                    )
                } else {
                    OverflowMenuButton(
                        expanded = openMenu == PlayerMenu.Overflow,
                        onExpandedChange = { openMenu = if (it) PlayerMenu.Overflow else null },
                        speed = status.speed,
                        onSelectSpeed = onSelectSpeed,
                        scaling = scaling,
                        onSelectScaling = onSelectScaling,
                    )
                }

                if (episodeBrowser != null) {
                    EpisodeMenuButton(
                        browser = episodeBrowser,
                        expanded = openMenu == PlayerMenu.Episodes,
                        onExpandedChange = { openMenu = if (it) PlayerMenu.Episodes else null },
                    )
                } else if (canChangeSource) {
                    ControlButton(icon = "lucide:list-video", onClick = onChangeSource)
                }
            }
        }
    }
}

/**
 * Speed and framing behind one button, for windows too narrow to show both.
 *
 * A flat list rather than nested menus: there are ten entries between the two, and a
 * submenu inside a dropdown over a video is a lot of pointer travel to change the
 * aspect ratio.
 */
@Composable
private fun OverflowMenuButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    speed: Double,
    onSelectSpeed: (Double) -> Unit,
    scaling: VideoScaling,
    onSelectScaling: (VideoScaling) -> Unit,
) {
    Box {
        ControlButton(
            icon = "lucide:ellipsis",
            onClick = { onExpandedChange(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.heightIn(max = 420.dp),
        ) {
            LanguageHeader("Speed")
            SPEED_STEPS.forEach { option ->
                CMenuItem(
                    text = formatSpeed(option),
                    iconName = if (option == speed) "lucide:check" else "lucide:gauge",
                    accent = option == speed,
                    onClick = {
                        onSelectSpeed(option)
                        onExpandedChange(false)
                    },
                )
            }
            LanguageHeader("Framing")
            VideoScaling.entries.forEach { option ->
                CMenuItem(
                    text = option.label,
                    iconName = if (option == scaling) "lucide:check" else "lucide:proportions",
                    accent = option == scaling,
                    onClick = {
                        onSelectScaling(option)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

// ── Seek bar ─────────────────────────────────────────────────────────────────

/**
 * Progress plus the labelled stretches of the episode.
 *
 * The segments come from IntroDB and recognized file chapters, so an intro or a
 * credits roll is visible before you reach it. Anything outside stays neutral.
 */
@Composable
private fun SegmentedSeekBar(
    positionSeconds: Double,
    durationSeconds: Double,
    segments: List<LabelledSegment>,
    bufferedSeconds: Double,
    chapters: List<MediaChapter>,
    /** True while the read-ahead is still filling, which drives the shimmer. */
    buffering: Boolean,
    onSeek: (Double) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    var trackWidth by remember { mutableStateOf(0) }
    // While dragging, follow the pointer rather than mpv: the property poll runs
    // on a 200 ms timer, so echoing it back would fight the drag.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var hoverFraction by remember { mutableStateOf<Float?>(null) }

    val played = if (durationSeconds > 0) {
        (positionSeconds / durationSeconds).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }
    val shown = dragFraction ?: played
    val active = hovered || dragFraction != null

    val barHeight by animateDpAsState(
        targetValue = if (active) 7.dp else 4.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "SeekBarHeight",
    )
    val thumbSize by animateDpAsState(
        targetValue = if (active) 14.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "SeekBarThumb",
    )

    fun seekTo(fraction: Float) {
        if (durationSeconds > 0.0) onSeek(fraction.coerceIn(0f, 1f) * durationSeconds)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .hoverable(interactionSource)
            .onSizeChanged { trackWidth = it.width }
            .pointerInput(durationSeconds) {
                detectTapGestures { offset ->
                    if (trackWidth > 0) seekTo(offset.x / trackWidth)
                }
            }
            .pointerInput(durationSeconds) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (trackWidth > 0) dragFraction = (offset.x / trackWidth).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragFraction?.let { seekTo(it) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    if (trackWidth > 0) {
                        dragFraction = (change.position.x / trackWidth).coerceIn(0f, 1f)
                    }
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val position = event.changes.lastOrNull()?.position?.x
                        hoverFraction = position
                            ?.takeIf { trackWidth > 0 }
                            ?.let { (it / trackWidth).coerceIn(0f, 1f) }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val chunks = remember(durationSeconds, segments) {
            seekChunks(durationSeconds, segments)
        }
        val playedSeconds = shown * durationSeconds

        // Animate only while the cache is growing.
        val filling = buffering
        val shimmerTransition = rememberInfiniteTransition(label = "BufferShimmer")
        val shimmer by shimmerTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = LinearEasing),
            ),
            label = "BufferShimmerSweep",
        )

        if (chunks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(SEGMENT_RADIUS))
                    .background(Color.White.copy(alpha = 0.22f)),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(barHeight),
                horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
            ) {
                chunks.forEach { chunk ->
                    val inThisChunk = chunk.kind != null &&
                        playedSeconds >= chunk.startSeconds &&
                        playedSeconds < chunk.endSeconds
                    val baseAlpha by animateFloatAsState(
                        targetValue = if (inThisChunk) 0.58f else 0.32f,
                        animationSpec = tween(durationMillis = 420),
                        label = "SegmentPulse",
                    )
                    val base = chunk.kind?.color()?.copy(alpha = baseAlpha)
                        ?: Color.White.copy(alpha = 0.22f)
                    val played = chunk.kind?.color()
                        ?: MaterialTheme.colorScheme.onSurface
                    val fill = chunk.fillFraction(playedSeconds)
                    val buffered = chunk.fillFraction(bufferedSeconds)

                    Box(
                        modifier = Modifier
                            .weight(chunk.lengthSeconds.toFloat())
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(SEGMENT_RADIUS))
                            .background(base),
                    ) {
                        if (buffered > fill) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(buffered)
                                    .fillMaxHeight()
                                    .background(
                                        if (filling) {
                                            Brush.horizontalGradient(
                                                colorStops = shimmerStops(shimmer),
                                            )
                                        } else {
                                            SolidColor(Color.White.copy(alpha = 0.28f))
                                        },
                                    ),
                            )
                        }
                        if (fill > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fill)
                                    .fillMaxHeight()
                                    .background(played),
                            )
                        }
                    }
                }
            }
        }

        // Chapter and semantic-segment boundaries can overlap independently.
        val marks = remember(chapters, durationSeconds) {
            chapterMarks(chapters, durationSeconds)
        }
        marks.forEach { mark ->
            Box(
                modifier = Modifier
                    .offset { IntOffset((trackWidth * mark).toInt(), 0) }
                    .width(CHAPTER_MARK_WIDTH)
                    .height(barHeight)
                    .background(Color.Black.copy(alpha = 0.55f)),
            )
        }

        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .offset {
                    val x = (trackWidth * shown.coerceIn(0f, 1f)).toInt() -
                        with(density) { thumbSize.roundToPx() } / 2
                    androidx.compose.ui.unit.IntOffset(x, 0)
                }
                .size(thumbSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface),
        )

        val preview = hoverFraction
        if (active && preview != null && durationSeconds > 0.0) {
            val previewSeconds = preview * durationSeconds
            // Prefer a semantic segment label, then the media chapter title.
            val label = segments
                .firstOrNull { previewSeconds >= it.startSeconds && previewSeconds <= it.endSeconds }
                ?.kind
                ?.label()
                ?: chapterAt(previewSeconds, chapters)?.label
            var pillWidth by remember { mutableStateOf(0) }
            Box(
                modifier = Modifier
                    .onSizeChanged { pillWidth = it.width }
                    .offset {
                        // Clamp the centred preview inside the seek track.
                        val centred = (trackWidth * preview).toInt() - pillWidth / 2
                        val bounded = if (trackWidth > pillWidth) {
                            centred.coerceIn(0, trackWidth - pillWidth)
                        } else {
                            centred
                        }
                        IntOffset(bounded, with(density) { (-26).dp.roundToPx() })
                    }
                    .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = listOfNotNull(formatDuration(previewSeconds), label).joinToString("  ·  "),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun IntOffsetX(fraction: Double, trackWidth: Int) =
    androidx.compose.ui.unit.IntOffset((trackWidth * fraction).toInt(), 0)

private fun SegmentKind.color(): Color = when (this) {
    SegmentKind.Recap -> CoveColors.Segment.Recap
    SegmentKind.Intro -> CoveColors.Segment.Intro
    SegmentKind.Credits -> CoveColors.Segment.Credits
    SegmentKind.Preview -> CoveColors.Segment.Preview
}

private fun SegmentKind.label(): String = when (this) {
    SegmentKind.Recap -> "Recap"
    SegmentKind.Intro -> "Intro"
    SegmentKind.Credits -> "Credits"
    SegmentKind.Preview -> "Preview"
}

// ── Controls ─────────────────────────────────────────────────────────────────

/** The three states the transport button can be in, and the glyph for each. */
private enum class TransportIcon(val icon: String) {
    Play("iconamoon:player-play"),
    Pause("iconamoon:player-pause"),
    Replay("lucide:rotate-ccw"),
}

/**
 * @param ended playback ran to the end. The button restarts from the beginning there,
 *   so it says so — mpv parked on the last frame cannot simply resume, and a play
 *   triangle promising something the press will not do is worse than no affordance.
 */
@Composable
private fun PlayPauseButton(paused: Boolean, ended: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else if (hovered) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "PlayPauseScale",
    )

    val container by animateColorAsState(
        targetValue = when {
            hovered -> Color.White.copy(alpha = 0.2f)
            else -> Color.White.copy(alpha = 0.1f)
        },
        animationSpec = tween(140),
        label = "ControlButtonContainer",
    )


    Box(
        modifier = Modifier
            .size(if (hasPointerHover) 44.dp else 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(container)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = if (ended) TransportIcon.Replay
                else if (paused) TransportIcon.Play
                else TransportIcon.Pause,
            transitionSpec = {
                (fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.6f))
                    .togetherWith(fadeOut(tween(90)) + scaleOut(tween(120), targetScale = 0.6f))
            },
            label = "PlayPauseIcon",
        ) { icon ->
            IconifyIcon(
                icon = icon.icon,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * @param muted the player's own mute flag. Silence has two independent causes — a zero
 *   volume and a muted player — and the icon has to report either, or a player muted at
 *   load looks unmuted at full volume and no amount of dragging the slider helps.
 */
@Composable
private fun VolumeControl(
    volume: Double,
    muted: Boolean,
    onSetVolume: (Double) -> Unit,
    onSetMuted: (Boolean) -> Unit,
) {
    val silent = muted || volume <= 0.0
    var trackWidth by remember { mutableStateOf(0) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val barHeight by animateDpAsState(
        targetValue = if (hovered) 6.dp else 4.dp,
        animationSpec = tween(140),
        label = "VolumeBarHeight",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ControlButton(
            icon = if (silent) "lucide:volume-x" else "lucide:volume-2",
            onClick = {
                if (silent) {
                    onSetMuted(false)
                    // Restore a zero-volume player to full when unmuting.
                    if (volume <= 0.0) onSetVolume(100.0)
                } else {
                    onSetMuted(true)
                }
            },
        )

        Box(
            modifier = Modifier
                .width(92.dp)
                .height(22.dp)
                .hoverable(interactionSource)
                .onSizeChanged { trackWidth = it.width }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (trackWidth > 0) {
                            onSetVolume((offset.x / trackWidth).coerceIn(0f, 1f) * 100.0)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        if (trackWidth > 0) {
                            onSetVolume((change.position.x / trackWidth).coerceIn(0f, 1f) * 100.0)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.22f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth((volume / 100.0).coerceIn(0.0, 1.0).toFloat())
                    .height(barHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = if (silent) 0.3f else 0.9f)),
            )
        }

        AnimatedVisibility(
            visible = hovered,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(160)),
        ) {
            Text(
                text = if (silent) "0" else volume.roundToLong().toString(),
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Audio or subtitle picker, grouped by language.
 *
 * Subtitles get an explicit Off entry; audio does not, since something has to
 * play.
 */
@Composable
private fun TrackMenuButton(
    icon: String,
    tracks: List<MediaTrack>,
    selectedId: Int?,
    allowOff: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (Int?) -> Unit,
    /** Current offset against the picture, and where to send a new one. */
    delaySeconds: Double,
    onSetDelay: (Double) -> Unit,
    /** Subtitles only, and only where the platform has files to offer. */
    onLoadFile: (() -> Unit)? = null,
) {
    val groups = remember(tracks) { groupTracksByLanguage(tracks) }

    Box {
        ControlButton(
            icon = icon,
            active = selectedId != null,
            onClick = { onExpandedChange(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.heightIn(max = 420.dp),
        ) {
            if (allowOff) {
                CMenuItem(
                    text = "Off",
                    iconName = if (selectedId == null) "lucide:check" else "lucide:x",
                    accent = selectedId == null,
                    onClick = {
                        onSelect(null)
                        onExpandedChange(false)
                    },
                )
            }
            groups.forEach { group ->
                LanguageHeader(group.languageLabel)
                group.tracks.forEach { track ->
                    CMenuItem(
                        text = track.detailLabel(),
                        iconName = if (track.id == selectedId) {
                            "lucide:check"
                        } else {
                            "lucide:circle-dot"
                        },
                        accent = track.id == selectedId,
                        onClick = {
                            onSelect(track.id)
                            onExpandedChange(false)
                        },
                    )
                }
            }
            onLoadFile?.let { loadFile ->
                LanguageHeader("Your own")
                CMenuItem(
                    text = "Load subtitle file…",
                    iconName = "lucide:upload",
                    onClick = {
                        onExpandedChange(false)
                        loadFile()
                    },
                )
            }
            // Addon subtitles may need timing correction for a different release cut.
            DelayStepper(
                delaySeconds = delaySeconds,
                onSetDelay = onSetDelay,
            )
        }
    }
}

/**
 * Nudges a track against the picture, a tenth of a second at a time.
 *
 * The menu stays open while this is used: getting a subtitle in sync takes several
 * presses and watching the result between each, and a menu that closed on every press
 * would make that ten interactions instead of one.
 */
@Composable
private fun DelayStepper(
    delaySeconds: Double,
    onSetDelay: (Double) -> Unit,
) {
    LanguageHeader("Timing")
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepperButton(
            icon = "lucide:minus",
            onClick = { onSetDelay(delaySeconds - DELAY_STEP_SECONDS) },
        )
        Box(
            modifier = Modifier.width(62.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = formatDelay(delaySeconds),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        StepperButton(
            icon = "lucide:plus",
            onClick = { onSetDelay(delaySeconds + DELAY_STEP_SECONDS) },
        )
        StepperButton(
            icon = "lucide:rotate-ccw",
            onClick = { onSetDelay(0.0) },
        )
    }
}

@Composable
private fun StepperButton(icon: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "StepperScale",
    )

    Box(
        modifier = Modifier
            .size(26.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(
            icon = icon,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Signed, because which way it is off is the whole question. */
private fun formatDelay(seconds: Double): String {
    val tenths = (seconds * 10).roundToLong()
    if (tenths == 0L) return "0.0s"
    val sign = if (tenths > 0) "+" else "-"
    val magnitude = abs(tenths)
    return "$sign${magnitude / 10}.${magnitude % 10}s"
}

private const val DELAY_STEP_SECONDS = 0.1

@Composable
private fun LanguageHeader(label: String) {
    Text(
        text = label.uppercase(),
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * Playback rate. mpv corrects pitch by default, so speech stays intelligible
 * rather than turning into a chipmunk.
 */
@Composable
private fun SpeedMenuButton(
    speed: Double,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (Double) -> Unit,
) {
    val normal = speed in 0.99..1.01

    Box {
        ControlButton(
            icon = "lucide:gauge",
            active = !normal,
            onClick = { onExpandedChange(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
        ) {
            SPEED_STEPS.forEach { step ->
                val selected = speed in (step - 0.01)..(step + 0.01)
                CMenuItem(
                    text = if (step == 1.0) "Normal" else "${formatSpeed(step)}×",
                    iconName = if (selected) "lucide:check" else "lucide:gauge",
                    accent = selected,
                    onClick = {
                        onSelect(step)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

/** Trims the trailing zero so 1.5 reads as "1.5" and 2.0 as "2". */
private fun formatSpeed(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private val SPEED_STEPS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

/** How the picture fills the window. Fit is the default and shows everything. */
@Composable
private fun ScalingMenuButton(
    selected: VideoScaling,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (VideoScaling) -> Unit,
) {
    Box {
        ControlButton(
            icon = "lucide:scaling",
            active = selected != VideoScaling.Fit,
            onClick = { onExpandedChange(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
        ) {
            VideoScaling.entries.forEach { mode ->
                CMenuItem(
                    text = mode.label,
                    iconName = if (mode == selected) "lucide:check" else "lucide:scaling",
                    accent = mode == selected,
                    onClick = {
                        onSelect(mode)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

@Composable
internal fun ControlButton(
    icon: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else if (hovered) 1.09f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "ControlButtonScale",
    )
    val container by animateColorAsState(
        targetValue = when {
            active && hovered -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
            active -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
            hovered -> Color.White.copy(alpha = 0.2f)
            else -> Color.White.copy(alpha = 0.1f)
        },
        animationSpec = tween(140),
        label = "ControlButtonContainer",
    )

    Box(
        modifier = Modifier
            .size(if (hasPointerHover) 38.dp else 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(container)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(
            icon = icon,
            modifier = Modifier.size(18.dp),
            tint = if (active) MaterialTheme.colorScheme.tertiary else Color.White,
        )
    }
}

/**
 * Mirrors the desktop player's own formatter, which is internal to that module
 * and cannot be shared across the module boundary.
 */
internal fun formatDuration(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0.0) return "--:--"
    val total = seconds.roundToLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "$hours:${minutes.pad()}:${secs.pad()}"
    } else {
        "$minutes:${secs.pad()}"
    }
}

private fun Long.pad(): String = if (this < 10) "0$this" else toString()
