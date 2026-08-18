package com.coveninja.cove.ui.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.pages.home.HomeHero
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.focus.tvFocusGroup

/**
 * The title Home leads with, at television scale.
 *
 * The artwork runs full-bleed behind the rail and under the first row of cards, with the copy
 * held inside the safe area. Two scrims do the work of making white text legible over an
 * arbitrary frame: one from the left, because that is where the copy sits, and one from the
 * bottom, which also blends the image into the row beneath instead of ending on a hard edge.
 *
 * Its buttons are the page's first focus stop, so this is where a viewer's first press lands.
 */
@Composable
internal fun TvHero(
    hero: HomeHero,
    /** The same title with its artwork resolved, once `HomeController` has fetched it. */
    art: Media,
    onPlay: () -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
    playFocusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val dimens = TvTheme.dimens
    val title = art.title ?: art.name ?: hero.media.title ?: hero.media.name.orEmpty()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(dimens.heroHeight)
            .onFocusChanged { focus -> onFocusChanged(focus.hasFocus) },
    ) {
        CoveAsyncImage(
            model = art.backdropUrl ?: art.posterUrl,
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to CoveColors.Neutral.Background,
                        0.45f to CoveColors.Neutral.Background.copy(alpha = 0.82f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to CoveColors.Neutral.Background,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .widthIn(max = dimens.width * 0.52f)
                .padding(
                    start = dimens.overscanHorizontal,
                    end = dimens.overscanHorizontal,
                    bottom = 28.dp,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconifyIcon(
                    icon = hero.kind.icon,
                    tint = CoveColors.Brand.Accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = hero.kind.kicker,
                    style = MaterialTheme.typography.labelMedium,
                    color = CoveColors.Brand.Accent,
                )
            }

            // A title logo is artwork the studio designed to be read at a glance; where one
            // exists it beats setting the name in the app's own type.
            val logo = art.logoUrl
            if (!logo.isNullOrBlank()) {
                CoveAsyncImage(
                    model = logo,
                    contentDescription = title,
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .heightIn(max = 108.dp)
                        .widthIn(max = dimens.width * 0.4f),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    color = CoveColors.Neutral.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            if (hero.caption.isNotBlank()) {
                Text(
                    text = hero.caption,
                    style = MaterialTheme.typography.bodyLarge,
                    color = CoveColors.Neutral.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            hero.watchFraction?.let { fraction ->
                Box(
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .width(280.dp)
                        .height(6.dp)
                        .background(
                            CoveColors.Neutral.Text.copy(alpha = 0.24f),
                            RoundedCornerShape(3.dp),
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(6.dp)
                            .background(CoveColors.Brand.Accent, RoundedCornerShape(3.dp)),
                    )
                }
            }

            art.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoveColors.Neutral.MutedDim,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Row(
                modifier = Modifier
                    .padding(top = 20.dp)
                    .tvFocusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (hero.playable) {
                    TvButton(
                        label = hero.playLabel,
                        onClick = onPlay,
                        icon = "lucide:play",
                        primary = true,
                        modifier = playFocusRequester
                            ?.let { Modifier.focusRequester(it) }
                            ?: Modifier,
                    )
                }
                TvButton(
                    label = "More info",
                    onClick = onOpenDetails,
                    icon = "lucide:info",
                    // Where there is nothing to play, this is the only thing to press, so it
                    // takes both the emphasis and the initial focus.
                    primary = !hero.playable,
                    modifier = if (!hero.playable && playFocusRequester != null) {
                        Modifier.focusRequester(playFocusRequester)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}
