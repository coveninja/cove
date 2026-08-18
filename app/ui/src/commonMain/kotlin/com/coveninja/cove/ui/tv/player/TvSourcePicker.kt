package com.coveninja.cove.ui.tv.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.player.displayLabel
import com.coveninja.cove.ui.components.player.formatBytes
import com.coveninja.cove.ui.components.player.qualityLabel
import com.coveninja.cove.ui.state.StreamChoice
import com.coveninja.cove.ui.state.VideoDecoderSupport
import com.coveninja.cove.ui.state.seederCount
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.focus.FocusOnAppear
import com.coveninja.cove.ui.tv.focus.TvFocusDefaults
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import com.coveninja.cove.ui.tv.focus.tvFocusTarget

/**
 * Which copy of the film to play.
 *
 * A column rather than the desktop picker's table of dropdown filters. Sorting and filtering
 * controls are a poor trade on a remote — each one is a focus stop between the viewer and the
 * thing they came to press — and the list arrives already ranked by the same source ordering
 * the other hosts use, so walking down it is the filter.
 *
 * There is no cancel button. Back is the cancel, and a television viewer already knows that.
 */
@Composable
internal fun TvSourcePicker(
    sources: List<StreamChoice>,
    onChoose: (StreamChoice) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = TvTheme.dimens
    val firstFocus = remember { FocusRequester() }
    FocusOnAppear(firstFocus, enabled = sources.isNotEmpty())

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CoveColors.Neutral.Background.copy(alpha = 0.94f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = dimens.width * 0.66f)
                .padding(
                    start = dimens.overscanHorizontal,
                    top = dimens.overscanVertical,
                    bottom = dimens.overscanVertical,
                ),
        ) {
            Text(
                text = "Choose a source",
                style = MaterialTheme.typography.headlineMedium,
                color = CoveColors.Neutral.Text,
            )
            Text(
                text = "${sources.size} available  ·  Back to cancel",
                style = MaterialTheme.typography.bodyMedium,
                color = CoveColors.Neutral.MutedDim,
                modifier = Modifier.padding(top = 6.dp),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
                    .tvFocusGroup(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    items = sources,
                    key = { index, choice -> "$index:${choice.source.rowIdentity()}" },
                ) { index, choice ->
                    TvSourceRow(
                        choice = choice,
                        onClick = { onChoose(choice) },
                        modifier = if (index == 0) {
                            Modifier.focusRequester(firstFocus)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }

    // Nothing to choose from is still a state the viewer has to be able to leave.
    if (sources.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val cancelFocus = remember { FocusRequester() }
            FocusOnAppear(cancelFocus)
            com.coveninja.cove.ui.tv.components.TvButton(
                label = "Nothing playable — go back",
                onClick = onCancel,
                primary = true,
                modifier = Modifier.focusRequester(cancelFocus),
            )
        }
    }
}

@Composable
private fun TvSourceRow(
    choice: StreamChoice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = choice.source
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val reducedMotion = com.coveninja.cove.ui.state.LocalMotionPolicy.current.reducedMotion
    val shape = RoundedCornerShape(12.dp)
    val background by animateColorAsState(
        targetValue = if (focused) {
            CoveColors.Neutral.Text
        } else {
            CoveColors.Neutral.SurfaceRaised
        },
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvSourceRowBackground",
    )
    val primary by animateColorAsState(
        targetValue = if (focused) CoveColors.Neutral.Background else CoveColors.Neutral.Text,
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvSourceRowText",
    )
    val secondary by animateColorAsState(
        targetValue = if (focused) {
            CoveColors.Neutral.Background.copy(alpha = 0.72f)
        } else {
            CoveColors.Neutral.MutedDim
        },
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvSourceRowDetail",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusTarget(
                shape = shape,
                onClick = onClick,
                // An unsupported codec is listed rather than hidden — knowing a 4K copy exists
                // and cannot be decoded here is worth more than a shorter list — but it is not
                // selectable, so pressing it would only fail.
                enabled = choice.compatibility.selectable,
                scale = TvFocusDefaults.ControlScale,
                ringColor = Color.Transparent,
                interactionSource = interactionSource,
            )
            .background(background, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        source.qualityLabel()?.let { quality ->
            Text(
                text = quality,
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) CoveColors.Neutral.Background else CoveColors.Brand.Accent,
                modifier = Modifier
                    .padding(end = 14.dp)
                    .background(
                        if (focused) {
                            CoveColors.Brand.Accent
                        } else {
                            CoveColors.Brand.AccentContainer
                        },
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = source.displayLabel(),
                style = MaterialTheme.typography.titleMedium,
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sourceDetail(choice),
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** The line under a source's name: where it came from, how big it is, how healthy. */
private fun sourceDetail(choice: StreamChoice): String = buildList {
    choice.source.addonName?.takeIf { it.isNotBlank() }?.let(::add)
    if (choice.source.cached) add("Cached")
    choice.source.sizeBytes.takeIf { it > 0 }?.let { add(formatBytes(it)) }
    choice.source.seederCount()?.let { add("$it seeders") }
    choice.compatibility.codecLabel?.takeIf { it.isNotBlank() }?.let(::add)
    when (choice.compatibility.support) {
        VideoDecoderSupport.SoftwareOnly -> add("Software decode")
        VideoDecoderSupport.Unsupported -> add("Not supported here")
        else -> Unit
    }
}.joinToString("  ·  ")

/** Enough to tell two entries apart in a list key; sources carry no id of their own. */
private fun StreamSource.rowIdentity(): String =
    infoHash ?: url ?: "${addonName.orEmpty()}:${title.orEmpty()}:$sizeBytes"
