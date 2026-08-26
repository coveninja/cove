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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.shared.model.LabelledSegment
import com.coveninja.cove.shared.model.SegmentKind
import com.coveninja.cove.ui.components.menu.CMenuItem
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.MAX_VOLUME
import com.coveninja.cove.ui.state.MediaChapter
import com.coveninja.cove.ui.state.NORMAL_VOLUME
import com.coveninja.cove.ui.state.MediaTrack
import com.coveninja.cove.ui.state.PlaybackStatus
import com.coveninja.cove.ui.state.SLEEP_TIMER_MINUTES
import com.coveninja.cove.ui.state.SleepTimer
import com.coveninja.cove.ui.state.SleepTimerChoice
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
internal val COMPACT_CONTROLS_WIDTH = 720.dp

/**
 * What the control bar has to work with: how much room, and what is pointing at it.
 *
 * The two are genuinely independent and the bar had only ever consulted the first, which
 * quietly meant it never adapted to a phone at all. A handset is *forced landscape* in the
 * player (see PlayerOrientation on Android), so it arrives here 780–900dp wide — comfortably
 * past any width threshold — and took the pointer layout every time: a volume slider sized
 * for a mouse, whose readout only appears on hover and therefore never appeared, sitting
 * where a skip button should be.
 */
internal data class ControlsLayout(
    /** Too little width for one row of everything; the extras fold into the overflow menu. */
    val narrow: Boolean,
    /** A finger rather than a pointer: bigger targets, no hover-only affordances. */
    val touch: Boolean,
)

internal fun controlsLayout(width: Dp, pointerHover: Boolean): ControlsLayout = ControlsLayout(
    narrow = width < COMPACT_CONTROLS_WIDTH,
    touch = !pointerHover,
)

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
    /** How far the skip buttons jump, from settings. */
    seekStepSeconds: Double,
    /** A jump relative to where the viewer last asked to be — see VideoPlayerHost.seekRelative. */
    onSkip: (Double) -> Unit,
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
    onTakeScreenshot: () -> Unit,
    /** Null where there is nothing to lock — a pointer can simply stop touching the screen. */
    onLockControls: (() -> Unit)? = null,
    sleepTimer: SleepTimer,
    onSetSleepTimer: (SleepTimerChoice) -> Unit,
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

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val layout = controlsLayout(maxWidth, hasPointerHover)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (layout.narrow) 6.dp else 12.dp),
            ) {
                // Skip buttons flank the transport on every shell now. The television has had
                // them all along; touch and pointer had the jump on a key and an undocumented
                // double-tap at the edge of the picture, which is no affordance at all.
                SkipButton(
                    forward = false,
                    seconds = seekStepSeconds,
                    onClick = { onSkip(-seekStepSeconds) },
                )
                PlayPauseButton(
                    paused = status.paused,
                    ended = status.endReached,
                    onClick = onTogglePause,
                )
                SkipButton(
                    forward = true,
                    seconds = seekStepSeconds,
                    onClick = { onSkip(seekStepSeconds) },
                )

                Text(
                    text = "${formatDuration(status.positionSeconds)}  /  " +
                        formatDuration(status.durationSeconds),
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )

                // The strip is a mouse control twice over: it is dragged, and its readout
                // only appears on hover. A phone has hardware keys for this and the swipe
                // gesture over the picture, both of which show the overlay instead.
                if (!layout.narrow && !layout.touch) {
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
                        label = if (status.muted) "Unmute" else "Mute",
                        onClick = { onSetMuted(!status.muted) },
                    )
                }

                Box(modifier = Modifier.weight(1f))

                // Keep the menu available when there are no tracks so a file can still be added.
                if (status.subtitleTracks.isNotEmpty() || onLoadSubtitleFile != null) {
                    TrackMenuButton(
                        icon = "lucide:captions",
                        label = "Subtitles",
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
                        label = "Audio track",
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
                if (!layout.narrow && hasHardwareKeyboard) {
                    ControlButton(
                        icon = "lucide:keyboard",
                        label = "Keyboard shortcuts",
                        onClick = onShowShortcuts,
                    )
                }

                if (!layout.narrow) {
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
                }

                // Always present, unlike before: it is the only home the chapter list, the
                // screenshot, the sleep timer and the lock have, and folding speed and framing
                // back into it when there is no room for their own buttons is the smaller half
                // of what it does now.
                OverflowMenuButton(
                    expanded = openMenu == PlayerMenu.Overflow,
                    onExpandedChange = { openMenu = if (it) PlayerMenu.Overflow else null },
                    showSpeedAndFraming = layout.narrow,
                    touch = layout.touch,
                    speed = status.speed,
                    onSelectSpeed = onSelectSpeed,
                    scaling = scaling,
                    onSelectScaling = onSelectScaling,
                    chapters = status.chapters,
                    positionSeconds = status.positionSeconds,
                    onSeek = onSeek,
                    onTakeScreenshot = onTakeScreenshot,
                    onLockControls = onLockControls,
                    sleepTimer = sleepTimer,
                    onSetSleepTimer = onSetSleepTimer,
                )

                if (episodeBrowser != null) {
                    EpisodeMenuButton(
                        browser = episodeBrowser,
                        expanded = openMenu == PlayerMenu.Episodes,
                        onExpandedChange = { openMenu = if (it) PlayerMenu.Episodes else null },
                    )
                } else if (canChangeSource) {
                    ControlButton(
                        icon = "lucide:list-video",
                        label = "Change source",
                        onClick = onChangeSource,
                    )
                }
            }
        }
    }
}

/**
 * Everything that does not earn a button of its own.
 *
 * A flat list rather than nested menus: a submenu inside a dropdown over a video is a lot of
 * pointer travel to change the aspect ratio, and on a phone it is a target inside a target.
 * Sections are separated by headers instead, which is also how the track menus read.
 *
 * @param showSpeedAndFraming fold those two back in, for a window too narrow to give them
 *   their own buttons. Elsewhere they are on the bar and repeating them here would be two
 *   ways to change one setting, sitting next to each other.
 */
@Composable
private fun OverflowMenuButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    showSpeedAndFraming: Boolean,
    touch: Boolean,
    speed: Double,
    onSelectSpeed: (Double) -> Unit,
    scaling: VideoScaling,
    onSelectScaling: (VideoScaling) -> Unit,
    chapters: List<MediaChapter>,
    positionSeconds: Double,
    onSeek: (Double) -> Unit,
    onTakeScreenshot: () -> Unit,
    onLockControls: (() -> Unit)?,
    sleepTimer: SleepTimer,
    onSetSleepTimer: (SleepTimerChoice) -> Unit,
) {
    Box {
        ControlButton(
            icon = "lucide:ellipsis",
            label = "More",
            active = sleepTimer.armed,
            onClick = { onExpandedChange(true) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.heightIn(max = 420.dp),
        ) {
            if (showSpeedAndFraming) {
                MenuSectionHeader("Speed")
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
                MenuSectionHeader("Framing")
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

            // Chapters have always been on the seek bar as ticks and on Page Up/Down as
            // navigation, and listed nowhere — so a viewer without a keyboard could neither
            // reach them nor learn the file had any.
            if (chapters.isNotEmpty()) {
                MenuSectionHeader("Chapters")
                val current = chapterIndexAt(positionSeconds, chapters)
                chapters.forEach { chapter ->
                    val playing = chapter.index == current
                    CMenuItem(
                        text = "${chapter.label}  ·  ${formatDuration(chapter.startSeconds)}",
                        iconName = if (playing) "lucide:play" else "lucide:bookmark",
                        accent = playing,
                        onClick = {
                            onSeek(chapter.startSeconds)
                            onExpandedChange(false)
                        },
                    )
                }
            }

            MenuSectionHeader("Sleep timer")
            CMenuItem(
                text = "Off",
                iconName = if (!sleepTimer.armed) "lucide:check" else "lucide:x",
                accent = !sleepTimer.armed,
                onClick = {
                    onSetSleepTimer(SleepTimerChoice.Off)
                    onExpandedChange(false)
                },
            )
            CMenuItem(
                text = "After this episode",
                iconName = if (sleepTimer.choice == SleepTimerChoice.AfterThisEpisode) {
                    "lucide:check"
                } else {
                    "lucide:list-end"
                },
                accent = sleepTimer.choice == SleepTimerChoice.AfterThisEpisode,
                onClick = {
                    onSetSleepTimer(SleepTimerChoice.AfterThisEpisode)
                    onExpandedChange(false)
                },
            )
            SLEEP_TIMER_MINUTES.forEach { minutes ->
                val selected = (sleepTimer.choice as? SleepTimerChoice.After)?.minutes == minutes
                CMenuItem(
                    text = "In $minutes minutes",
                    iconName = if (selected) "lucide:check" else "lucide:moon",
                    accent = selected,
                    onClick = {
                        onSetSleepTimer(SleepTimerChoice.After(minutes))
                        onExpandedChange(false)
                    },
                )
            }

            MenuSectionHeader("This screen")
            CMenuItem(
                text = "Take a screenshot",
                iconName = "lucide:camera",
                onClick = {
                    onExpandedChange(false)
                    onTakeScreenshot()
                },
            )
            // Only where a stray touch is possible at all.
            if (touch) {
                onLockControls?.let { lock ->
                    CMenuItem(
                        text = "Lock the screen",
                        iconName = "lucide:lock",
                        onClick = {
                            onExpandedChange(false)
                            lock()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Which chapter [positionSeconds] is inside, or null before the first one starts.
 *
 * Chapters are ordered and contiguous, so the answer is the last one that has begun — a
 * containment test would need each chapter's end, which mpv does not report.
 */
internal fun chapterIndexAt(positionSeconds: Double, chapters: List<MediaChapter>): Int? =
    chapters.lastOrNull { it.startSeconds <= positionSeconds }?.index

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
    // A finger cannot hover, so on touch the bar was a 4dp line with no grab handle until a
    // drag was already under way — the most-used control on a phone and the hardest to hit.
    // Touch therefore holds the state a pointer only gets by hovering.
    val touch = !hasPointerHover
    val active = hovered || dragFraction != null || touch

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
            // 30dp is under every touch-target minimum there is; a pointer needs no such room.
            .height(if (touch) 48.dp else 30.dp)
            .hoverable(interactionSource)
            .semantics {
                contentDescription = "Seek bar"
                if (durationSeconds > 0.0) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = positionSeconds.toFloat(),
                        range = 0f..durationSeconds.toFloat(),
                    )
                    // Without this the bar can be read but not operated, which for the one
                    // control that moves the film is the difference between described and usable.
                    setProgress { target ->
                        onSeek(target.toDouble().coerceIn(0.0, durationSeconds))
                        true
                    }
                }
            }
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
                        // Far enough above the bar that a fingertip is not covering the one
                        // number the drag exists to show.
                        val lift = if (touch) (-56).dp else (-26).dp
                        IntOffset(bounded, with(density) { lift.roundToPx() })
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
            label = if (silent) "Unmute" else "Mute",
            onClick = {
                if (silent) {
                    onSetMuted(false)
                    // Restore a zero-volume player to normal — not to the boosted ceiling,
                    // which is not what "unmute" asks for.
                    if (volume <= 0.0) onSetVolume(NORMAL_VOLUME)
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
                            onSetVolume((offset.x / trackWidth).coerceIn(0f, 1f) * MAX_VOLUME)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        if (trackWidth > 0) {
                            onSetVolume(
                                (change.position.x / trackWidth).coerceIn(0f, 1f) * MAX_VOLUME,
                            )
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
            // The stretch past 100 is drawn apart from the rest, because everything above it
            // is amplification rather than attenuation: it can distort, and a viewer who
            // dragged into it deserves to see that they did.
            Box(
                modifier = Modifier
                    .fillMaxWidth((NORMAL_VOLUME / MAX_VOLUME).toFloat())
                    .height(barHeight)
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth((volume / MAX_VOLUME).coerceIn(0.0, 1.0).toFloat())
                    .height(barHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            silent -> Color.White.copy(alpha = 0.3f)
                            volume > NORMAL_VOLUME -> MaterialTheme.colorScheme.tertiary
                            else -> Color.White.copy(alpha = 0.9f)
                        },
                    ),
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
    label: String,
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
            label = label,
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
                MenuSectionHeader(group.languageLabel)
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
                MenuSectionHeader("Your own")
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
    MenuSectionHeader("Timing")
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepperButton(
            icon = "lucide:minus",
            label = "Earlier",
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
            label = "Later",
            onClick = { onSetDelay(delaySeconds + DELAY_STEP_SECONDS) },
        )
        StepperButton(
            icon = "lucide:rotate-ccw",
            label = "Back in sync",
            onClick = { onSetDelay(0.0) },
        )
    }
}

@Composable
private fun StepperButton(icon: String, label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "StepperScale",
    )

    Box(
        modifier = Modifier
            // Nudging a subtitle into sync takes several presses in a row, so on touch this
            // has to be a real target rather than the 26dp dot a pointer can manage.
            .size(if (hasPointerHover) 26.dp else 44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(
            icon = icon,
            modifier = Modifier.size(if (hasPointerHover) 13.dp else 18.dp),
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


/** Titles a run of menu entries. Named for what it does now: the overflow menu has five. */
@Composable
private fun MenuSectionHeader(label: String) {
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
            label = if (normal) "Playback speed" else "Speed ${formatSpeed(speed)}×",
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

/** Shared with the television's own panel, so the two shells offer the same steps. */
internal val SPEED_STEPS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

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
            label = "Framing: ${selected.label}",
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

/**
 * @param label what this button does, in words. Required rather than optional because it is
 *   the only thing a screen reader has to go on — the player was, until this, two dozen
 *   unlabelled glyphs — and because a pointer has no equivalent of the television shell's
 *   focus label. It is read out as the content description and shown on hover.
 */
@Composable
internal fun ControlButton(
    icon: String,
    label: String,
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
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(
            icon = icon,
            modifier = Modifier.size(18.dp),
            tint = if (active) MaterialTheme.colorScheme.tertiary else Color.White,
        )
        HoverLabel(label = label, visible = hovered)
    }
}

/**
 * Names the control the pointer is resting on, above it.
 *
 * The television shell already does this for whichever button holds focus, and for the same
 * reason: a row of glyphs is unreadable until one of them is about to be pressed. A pointer
 * has hover where a remote has focus, so the pointer UI can finally say the same things.
 * Drawn in a zero-size overlay so appearing never moves the row it sits in.
 */
@Composable
private fun BoxScope.HoverLabel(label: String, visible: Boolean) {
    if (!hasPointerHover) return
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = fadeIn(tween(90)),
        exit = fadeOut(tween(120)),
    ) {
        Box(
            modifier = Modifier
                .offset(y = (-30).dp)
                .background(Color.Black.copy(alpha = 0.86f), RoundedCornerShape(7.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/**
 * Jumps the configured step, in the direction it points.
 *
 * The number rides inside the arrow rather than beside it: the step is a setting, so a bare
 * pair of arrows would be the one control on the bar whose behaviour the viewer cannot see.
 */
@Composable
private fun SkipButton(forward: Boolean, seconds: Double, onClick: () -> Unit) {
    val whole = seconds.roundToLong()
    ControlButton(
        icon = if (forward) "lucide:rotate-cw" else "lucide:rotate-ccw",
        label = if (forward) "Forward ${whole}s" else "Back ${whole}s",
        onClick = onClick,
    )
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
