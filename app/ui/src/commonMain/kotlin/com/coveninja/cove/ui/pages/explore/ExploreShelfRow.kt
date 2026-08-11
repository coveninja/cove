package com.coveninja.cove.ui.pages.explore

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.pages.common.MediaRail
import com.coveninja.cove.ui.pages.common.RailDefaults

/**
 * One Explore rail.
 *
 * Everything about how a rail looks and behaves lives in [MediaRail], which Home uses too.
 * What is left here is the one thing that is genuinely Explore's: deciding whether "See all"
 * can lead anywhere honest.
 */
@Composable
fun ExploreShelfRow(
    shelf: ExploreShelf,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onSeeAll: (ExploreShelf) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only rails the grid can actually reproduce offer it. A personalized rail has no
    // equivalent filter, and a "See all" that quietly showed something else would be
    // worse than no link.
    val seeAll = if (shelf.genreId != null || shelf.kind != ShelfKind.BecauseYouWatched) {
        { onSeeAll(shelf) }
    } else {
        null
    }

    MediaRail(
        title = shelf.title,
        subtitle = shelf.subtitle,
        icon = shelf.icon,
        items = shelf.media,
        key = Media::id,
        modifier = modifier,
        onSeeAll = seeAll,
        itemContent = mediaCard,
    )
}

internal val HORIZONTAL_PADDING = RailDefaults.HorizontalPadding
