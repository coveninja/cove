package com.coveninja.cove.ui.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.focus.tvFocusTarget

private val CardShape = RoundedCornerShape(12.dp)

/**
 * A poster in a row.
 *
 * The title sits under the artwork at all times rather than appearing on focus. Revealing it
 * would make every unfocused card anonymous, and unlike the phone there is no way to glance —
 * a viewer scanning a row from across the room is reading the labels, not the posters.
 */
@Composable
internal fun TvMediaCard(
    media: Media,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watchFraction: Float? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    TvPosterCard(
        posterUrl = media.posterUrl,
        label = media.title ?: media.name.orEmpty(),
        onClick = onClick,
        modifier = modifier,
        watchFraction = watchFraction,
        onFocusChanged = onFocusChanged,
    )
}

/**
 * The poster card without a [Media] behind it.
 *
 * Recommendations arrive as `MediaRecommendation` — an id, a title and a poster, with none of
 * the rest of a title's data fetched yet — so the card has to be able to draw from those three
 * things alone rather than demanding a whole object it would only take three fields from.
 */
@Composable
internal fun TvPosterCard(
    posterUrl: String?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    watchFraction: Float? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val dimens = TvTheme.dimens
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val labelColor by animateColorAsState(
        targetValue = if (focused) CoveColors.Neutral.Text else CoveColors.Neutral.Muted,
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvCardLabel",
    )

    Column(modifier = modifier.width(width ?: dimens.posterWidth)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .tvFocusTarget(
                    shape = CardShape,
                    onClick = onClick,
                    interactionSource = interactionSource,
                    onFocusChanged = onFocusChanged,
                )
                .clip(CardShape)
                .background(CoveColors.Neutral.SurfaceHigh),
        ) {
            CoveAsyncImage(
                model = posterUrl,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            watchFraction?.let { fraction ->
                TvProgressBar(
                    fraction = fraction,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * The landscape card: what a row uses when the image is a frame from the thing itself rather
 * than its poster — carrying on with an episode, or a backlog waiting to be watched.
 *
 * Wider than a poster because it has a caption to carry. On the phone this shape is what
 * `HomeWideCards` draws; here it also has to hold a focus ring without the two lines of text
 * below it shifting, hence the fixed caption line rather than a wrapping one.
 */
@Composable
internal fun TvWideCard(
    imageUrl: String?,
    title: String,
    caption: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watchFraction: Float? = null,
    badge: String? = null,
    /** False when the art is a poster standing in for a missing backdrop; it needs cropping. */
    wideArt: Boolean = true,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val dimens = TvTheme.dimens
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val titleColor by animateColorAsState(
        targetValue = if (focused) CoveColors.Neutral.Text else CoveColors.Neutral.Muted,
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvWideCardTitle",
    )

    Column(modifier = modifier.width(dimens.wideCardWidth)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .tvFocusTarget(
                    shape = CardShape,
                    onClick = onClick,
                    interactionSource = interactionSource,
                    onFocusChanged = onFocusChanged,
                )
                .clip(CardShape)
                .background(CoveColors.Neutral.SurfaceHigh),
        ) {
            CoveAsyncImage(
                model = imageUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                // A poster in a landscape frame is cropped to its middle rather than letterboxed:
                // a smear of background around a portrait image reads as a broken card.
                contentScale = ContentScale.Crop,
                alignment = if (wideArt) Alignment.Center else Alignment.TopCenter,
            )
            badge?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = CoveColors.Brand.OnAccent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(CoveColors.Brand.Accent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            watchFraction?.let { fraction ->
                TvProgressBar(
                    fraction = fraction,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.labelMedium,
            color = CoveColors.Neutral.MutedDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Where the viewer got to, drawn over the foot of the artwork. */
@Composable
private fun TvProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, CoveColors.Scrim.copy(alpha = 0.55f)),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(5.dp)
                .background(CoveColors.Brand.Accent),
        )
    }
}

/** A row heading: an icon, the name, and what the row is for. */
@Composable
internal fun TvRowHeader(
    title: String,
    subtitle: String?,
    icon: String?,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        icon?.let {
            IconifyIcon(icon = it, tint = CoveColors.Brand.Accent, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = CoveColors.Neutral.Text,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = CoveColors.Neutral.MutedDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
