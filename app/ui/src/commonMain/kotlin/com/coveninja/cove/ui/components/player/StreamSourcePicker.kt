package com.coveninja.cove.ui.components.player

import com.coveninja.cove.ui.state.bestForPicker
import com.coveninja.cove.ui.state.byResolution
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.SeederHealth
import com.coveninja.cove.ui.state.StreamChoice
import com.coveninja.cove.ui.state.ReleaseMatch
import com.coveninja.cove.ui.state.SourceAssessment
import com.coveninja.cove.ui.state.eligibleForAutomaticPlayback
import com.coveninja.cove.ui.state.resolutionTier
import com.coveninja.cove.ui.state.StreamCompatibility
import com.coveninja.cove.ui.state.displayLabel
import com.coveninja.cove.ui.state.distinctFileName
import com.coveninja.cove.ui.state.qualityLabel
import com.coveninja.cove.ui.state.VideoDecoderSupport
import com.coveninja.cove.ui.state.audioHints
import com.coveninja.cove.ui.state.seederCount
import com.coveninja.cove.ui.state.seederHealth
import kotlinx.coroutines.delay

/**
 * Shown when the viewer needs to choose. Rows are listed by resolution, highest first, and
 * ranked within each; the list opens on the recommended row — what Watch would play — so
 * higher resolutions are a scroll up and lower ones a scroll down.
 */
@Composable
fun StreamSourcePicker(
    ranked: List<StreamChoice>,
    onSelect: (StreamChoice) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    /** `AppSettings.showStreamDetails`: quality, size, peers and provider on each row. */
    showDetails: Boolean = true,
) {
    val sources = remember(ranked) { ranked.byResolution() }
    // Rows animate in once each. LazyColumn disposes what scrolls away, so
    // without this the entrance would replay every time a row came back.
    val entered = remember(sources) { mutableSetOf<String>() }
    // The same check the automatic pick uses, so "best" is always what Watch would have played.
    val recommendedIndex = remember(ranked, sources) {
        ranked.bestForPicker()?.let { best -> sources.indexOfFirst { it === best } } ?: -1
    }
    val listState = rememberLazyListState()
    val peek = with(LocalDensity.current) { RECOMMENDED_PEEK.toPx() }
    LaunchedEffect(sources, recommendedIndex) {
        if (recommendedIndex > 0) {
            listState.scrollToItem(recommendedIndex)
            // A sliver of the row above stays in view, so it is plain there is more up there.
            listState.scrollBy(-peek)
        }
    }

    Surface(
        modifier = modifier.widthIn(max = 660.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        shadowElevation = 18.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconifyIcon(
                        icon = "lucide:list-video",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = "Choose a source",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = title?.takeIf { it.isNotBlank() }
                            ?: "${sources.size} found, by resolution",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val rowKeys = remember(sources) { sources.rowKeys() }
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(top = 16.dp).heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(sources, key = { index, _ -> rowKeys[index] }) { index, choice ->
                    // Evaluated once per row instance rather than on every
                    // recomposition, so scrolling a row back into view does not
                    // replay its entrance — and so the set is not mutated from
                    // inside composition itself.
                    val animateIn = remember(rowKeys[index]) {
                        entered.add(rowKeys[index])
                    }
                    SourceRow(
                        choice = choice,
                        recommended = index == recommendedIndex,
                        showDetails = showDetails,
                        animateIn = animateIn,
                        // Capped, so a fifty-source list does not cascade for
                        // two seconds before the last row lands.
                        entranceDelayMillis = (index * 35L).coerceAtMost(280L),
                        onClick = { onSelect(choice) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    choice: StreamChoice,
    recommended: Boolean,
    showDetails: Boolean,
    animateIn: Boolean,
    entranceDelayMillis: Long,
    onClick: () -> Unit,
) {
    val source = choice.source
    val compatibility = choice.compatibility
    val enabled = compatibility.selectable
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()

    var visible by remember { mutableStateOf(!animateIn) }
    LaunchedEffect(Unit) {
        if (animateIn) delay(entranceDelayMillis)
        visible = true
    }
    val entrance by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(260),
        label = "SourceRowEntrance",
    )

    val container by animateColorAsState(
        targetValue = when {
            !enabled -> colors.surfaceContainerHighest.copy(alpha = 0.32f)
            hovered -> colors.surfaceContainerHighest.copy(alpha = 1f)
            else -> colors.surfaceContainerHighest.copy(alpha = 0.6f)
        },
        animationSpec = tween(140),
        label = "SourceRowContainer",
    )
    val outline by animateColorAsState(
        targetValue = if (enabled && hovered) {
            colors.tertiary.copy(alpha = 0.55f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(140),
        label = "SourceRowOutline",
    )
    val playFilled = enabled && (hovered || pressed)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entrance * if (enabled) 1f else 0.58f
                translationY = (1f - entrance) * 14f
            }
            .clip(RoundedCornerShape(13.dp))
            .background(container)
            .border(1.dp, outline, RoundedCornerShape(13.dp))
            .hoverable(interactionSource, enabled = enabled)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showDetails) QualityBadge(source.qualityLabel(), highlighted = enabled && (hovered || recommended))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = source.displayLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    // Two lines, because a release name is where the answer usually is and one
                    // line of it ends in an ellipsis on every row of a 660.dp sheet.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // One tag, not two: a row that is both the last one used and the best match is
                // better described by the reason the viewer recognises.
                if (choice.remembered) LastUsedTag() else if (recommended) BestMatchTag()
            }

            // Only when it says something the line above does not. Most providers put the file
            // name in the title and repeating it would cost two lines per row for nothing; the
            // ones that do not are exactly the ones where it answers which episode, which dub
            // or whose encode is on offer.
            source.distinctFileName()?.let { fileName ->
                Text(
                    text = fileName,
                    modifier = Modifier.padding(top = 3.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (showDetails || source.cached) Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Cached is the one qualifier that changes what happens when you
                // press play — everything else only describes the file — so it
                // stays a chip while the rest collapse into one quiet line.
                if (source.cached) CachedChip()
                if (showDetails) {
                    Text(
                        text = source.qualifiers(compatibility),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            (compatibility.warningLabel() ?: choice.assessment.warningLabel())?.let { warning ->
                Text(
                    text = warning,
                    modifier = Modifier.padding(top = 5.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // The two numbers a choice actually turns on, in a fixed lane so they
        // line up down the list and can be compared by scanning one column
        // rather than by reading every row.
        if (showDetails) StatLane(source = source, enabled = enabled)

        if (enabled) {
            PlayAffordance(filled = playFilled, colors = colors)
        } else {
            Box(
                modifier = Modifier.size(30.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconifyIcon(
                    icon = "lucide:lock",
                    modifier = Modifier.size(15.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Size over peers, right-aligned in a fixed lane.
 *
 * The width is reserved whether or not a row has either number, because the
 * point of the lane is that the values sit on the same vertical line in every
 * row; letting it collapse would put each row's figures in a different place.
 */
@Composable
private fun StatLane(source: StreamSource, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val seeders = source.seederCount()

    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (source.sizeBytes > 0) {
            Text(
                text = formatBytes(source.sizeBytes),
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        seeders?.let { count ->
            // Zero is shown rather than hidden, in the error colour: a dead
            // torrent is the single most useful thing this number can say.
            val tint = when (seederHealth(count)) {
                SeederHealth.Dead -> colors.error
                SeederHealth.Thin -> colors.onSurfaceVariant
                SeederHealth.Healthy -> colors.tertiary
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconifyIcon(
                    icon = "lucide:users",
                    modifier = Modifier.size(11.dp),
                    tint = tint,
                )
                Text(
                    text = count.toString(),
                    color = tint,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Always drawn, because there is no hover on a phone: pointer hosts get the
 * outline filling in under the cursor, touch hosts get a chevron that is at
 * least visibly the thing you are about to press.
 */
@Composable
private fun PlayAffordance(filled: Boolean, colors: androidx.compose.material3.ColorScheme) {
    val background by animateColorAsState(
        targetValue = if (filled) colors.tertiary else Color.Transparent,
        animationSpec = tween(140),
        label = "SourceRowPlayBackground",
    )
    val border by animateColorAsState(
        targetValue = if (filled) Color.Transparent else colors.onSurface.copy(alpha = 0.18f),
        animationSpec = tween(140),
        label = "SourceRowPlayBorder",
    )

    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(
            icon = "lucide:play",
            modifier = Modifier.size(13.dp),
            tint = if (filled) colors.onTertiary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun QualityBadge(label: String?, highlighted: Boolean) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        targetValue = if (highlighted) {
            colors.tertiary.copy(alpha = 0.22f)
        } else {
            colors.onSurface.copy(alpha = 0.07f)
        },
        animationSpec = tween(140),
        label = "QualityBadgeBackground",
    )

    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (label == null) {
            IconifyIcon(
                icon = "lucide:film",
                modifier = Modifier.size(16.dp),
                tint = colors.onSurfaceVariant,
            )
        } else {
            Text(
                text = label,
                color = if (highlighted) colors.tertiary else colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun BestMatchTag() {
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "BEST",
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Marks the source this title was last played from.
 *
 * Worth a tag of its own because it is the one piece of provenance the list cannot otherwise
 * show: everything else in a row describes the file, while this says "you chose this before".
 */
@Composable
private fun LastUsedTag() {
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "LAST USED",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CachedChip() {
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                RoundedCornerShape(5.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = "Cached",
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Everything about a source that describes the file rather than the choice,
 * as one dot-separated line.
 *
 * These used to be separate tags at equal weight, which put the provider name,
 * the codec and three language codes in the same visual register as the size —
 * seven items of identical colour and size that had to be read rather than
 * scanned. Folded together they become one quiet caption under the name.
 */
private fun StreamSource.qualifiers(compatibility: StreamCompatibility): String {
    val parts = mutableListOf<String>()
    addonName?.takeIf { it.isNotBlank() }?.let(parts::add)
    compatibility.codecLabel?.let(parts::add)
    // Audio read out of the release name — the stream list carries no track
    // data, so this is a hint, and absent when the name says nothing rather
    // than guessed at.
    val audio = audioHints()
    if (audio.multi) parts += "Dual audio"
    audio.languages.take(3).forEach { parts += it.uppercase() }
    // Nothing else distinguishes a torrent from a direct link, and the two
    // behave very differently on first play.
    if (url.isNullOrBlank()) parts += "Torrent"
    return parts.joinToString("  ·  ")
}

private fun StreamCompatibility.warningLabel(): String? = when (support) {
    VideoDecoderSupport.SoftwareOnly -> "Software decoding only · playback may stutter"
    VideoDecoderSupport.Unsupported -> "Unsupported video codec on this device"
    else -> null
}

/** Why ranking passed this one over; it can still be chosen. */
private fun SourceAssessment.warningLabel(): String? = when {
    deadSwarm -> "Nobody is seeding this torrent"
    match == ReleaseMatch.WrongEpisode -> "Named as a different episode"
    match == ReleaseMatch.WrongYear -> "Named with a different year · may be another film"
    theatricalCopy -> "Cinema recording"
    else -> null
}

/**
 * Row keys that stay unique when two addons offer the same release.
 *
 * The same torrent routinely comes back from several addons at once — the same
 * info hash from a debrid addon and a plain one — so url and info hash alone are
 * not unique across the list, and LazyColumn throws on a repeated key rather than
 * rendering the duplicate. Repeats are suffixed by their occurrence, which leaves
 * the first instance's key exactly what it was and keeps every row stable across
 * reorders.
 */
private fun List<StreamChoice>.rowKeys(): List<String> {
    val seen = mutableMapOf<String, Int>()
    return map { choice ->
        val base = choice.source.rowKey()
        val occurrence = (seen[base] ?: 0) + 1
        seen[base] = occurrence
        if (occurrence == 1) base else "$base#$occurrence"
    }
}

/** Stable across reorders; url or hash identifies a candidate, its position does not. */
private fun StreamSource.rowKey(): String =
    url?.takeIf { it.isNotBlank() }
        ?: infoHash?.takeIf { it.isNotBlank() }
        ?: "${name.orEmpty()}|${title.orEmpty()}"

/** Binary units, matching how release names quote sizes. */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val rounded = (value * 10).toLong() / 10.0
    return if (unit == 0) "$bytes B" else "$rounded ${units[unit]}"
}

/** How much of the row above the recommended one stays visible when the picker opens. */
private val RECOMMENDED_PEEK = 30.dp
