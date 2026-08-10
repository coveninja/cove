package com.coveninja.cove.ui.components.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.model.LabelledSegment
import com.coveninja.cove.shared.model.SegmentKind
import com.coveninja.cove.ui.components.menu.CMenuItem
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.MediaEpisode
import com.coveninja.cove.ui.state.MediaTrack
import com.coveninja.cove.ui.state.PlaybackStatus
import com.coveninja.cove.ui.state.VideoScaling
import kotlin.math.roundToLong

/**
 * Which picker is open, if any.
 *
 * Hoisted out of the individual buttons for two reasons: opening one closes the
 * others, and the layer above needs to know that *something* is open so it does
 * not hide the controls out from under it.
 */
private enum class PlayerMenu { Subtitles, Audio, Scaling, Episodes, Speed }

/** Fixed so every gap looks identical regardless of how long the segment is. */
private val SEGMENT_GAP = 1.5.dp
private val SEGMENT_RADIUS = 3.dp

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
    onSetVolume: (Double) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectSubtitle: (Int?) -> Unit,
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
            onSeek = onSeek,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayPauseButton(paused = status.paused, onClick = onTogglePause)

            Text(
                text = "${formatDuration(status.positionSeconds)}  /  " +
                    formatDuration(status.durationSeconds),
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelLarge,
            )

            VolumeControl(volume = status.volume, onSetVolume = onSetVolume)

            Box(modifier = Modifier.weight(1f))

            if (status.subtitleTracks.isNotEmpty()) {
                TrackMenuButton(
                    icon = "lucide:captions",
                    tracks = status.subtitleTracks,
                    selectedId = status.selectedSubtitleId,
                    allowOff = true,
                    expanded = openMenu == PlayerMenu.Subtitles,
                    onExpandedChange = { openMenu = if (it) PlayerMenu.Subtitles else null },
                    onSelect = onSelectSubtitle,
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
                )
            }
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

            // A series shows its episodes here; a film has none, so the slot
            // keeps the source picker instead of sitting empty.
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

// ── Seek bar ─────────────────────────────────────────────────────────────────

/**
 * Progress plus the labelled stretches of the episode.
 *
 * The segments come from IntroDB, so an intro or a credits roll is visible on
 * the bar before you reach it — which is the whole point of knowing where they
 * are. Anything outside a segment stays neutral.
 */
@Composable
private fun SegmentedSeekBar(
    positionSeconds: Double,
    durationSeconds: Double,
    segments: List<LabelledSegment>,
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

        if (chunks.isEmpty()) {
            // No duration yet: a single plain track, so the bar does not pop into
            // existence once the file loads.
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
                    val base = chunk.kind?.color()?.copy(alpha = 0.32f)
                        ?: Color.White.copy(alpha = 0.22f)
                    val played = chunk.kind?.color()
                        ?: MaterialTheme.colorScheme.tertiary
                    // How much of this piece is behind the playhead. Each piece
                    // fills itself, so the progress never bridges a gap.
                    val fill = ((playedSeconds - chunk.startSeconds) / chunk.lengthSeconds)
                        .coerceIn(0.0, 1.0)
                        .toFloat()

                    Box(
                        modifier = Modifier
                            .weight(chunk.lengthSeconds.toFloat())
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(SEGMENT_RADIUS))
                            .background(base),
                    ) {
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

        // Thumb, positioned by the same fraction as the fill.
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
                .background(MaterialTheme.colorScheme.tertiary),
        )

        // Scrub preview: what time the pointer is over, and what happens there.
        val preview = hoverFraction
        if (active && preview != null && durationSeconds > 0.0) {
            val previewSeconds = preview * durationSeconds
            val label = segments
                .firstOrNull { previewSeconds >= it.startSeconds && previewSeconds <= it.endSeconds }
                ?.kind
                ?.label()
            Box(
                modifier = Modifier
                    .offset {
                        androidx.compose.ui.unit.IntOffset(
                            ((trackWidth * preview).toInt() - 46),
                            with(density) { (-26).dp.roundToPx() },
                        )
                    }
                    .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = listOfNotNull(formatDuration(previewSeconds), label).joinToString("  ·  "),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun IntOffsetX(fraction: Double, trackWidth: Int) =
    androidx.compose.ui.unit.IntOffset((trackWidth * fraction).toInt(), 0)

private fun SegmentKind.color(): Color = when (this) {
    SegmentKind.Recap -> Color(0xFF7C6CF0)
    SegmentKind.Intro -> Color(0xFFEFA33C)
    SegmentKind.Credits -> Color(0xFF35B98A)
    SegmentKind.Preview -> Color(0xFF3FA9D6)
}

private fun SegmentKind.label(): String = when (this) {
    SegmentKind.Recap -> "Recap"
    SegmentKind.Intro -> "Intro"
    SegmentKind.Credits -> "Credits"
    SegmentKind.Preview -> "Preview"
}

// ── Controls ─────────────────────────────────────────────────────────────────

@Composable
private fun PlayPauseButton(paused: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else if (hovered) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "PlayPauseScale",
    )

    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Crossfaded and scaled rather than swapped, so the state change is
        // something you see happen.
        AnimatedContent(
            targetState = paused,
            transitionSpec = {
                (fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.6f))
                    .togetherWith(fadeOut(tween(90)) + scaleOut(tween(120), targetScale = 0.6f))
            },
            label = "PlayPauseIcon",
        ) { isPaused ->
            IconifyIcon(
                icon = if (isPaused) "lucide:play" else "lucide:pause",
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onTertiary,
            )
        }
    }
}

@Composable
private fun VolumeControl(volume: Double, onSetVolume: (Double) -> Unit) {
    val muted = volume <= 0.0
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
            icon = if (muted) "lucide:volume-x" else "lucide:volume-2",
            // Restore to full rather than to the pre-mute level: tracking that
            // would need state this control does not own.
            onClick = { onSetVolume(if (muted) 100.0 else 0.0) },
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
                    .background(Color.White.copy(alpha = 0.9f)),
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
            // A release with ten subtitle languages otherwise runs off the screen.
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
        }
    }
}

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
            .size(38.dp)
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
