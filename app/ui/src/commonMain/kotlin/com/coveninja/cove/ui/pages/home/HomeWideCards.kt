package com.coveninja.cove.ui.pages.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.components.media.card.MediaContextMenu
import com.coveninja.cove.ui.components.media.card.MenuSectionDivider
import com.coveninja.cove.ui.components.menu.CMenuItem
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.tmdbImageSize
import com.coveninja.cove.ui.pages.mylist.calendar.episodeMarker
import com.coveninja.cove.ui.platform.hasPointerHover
import com.coveninja.cove.ui.platform.onSecondaryClick
import kotlin.math.roundToInt
import com.coveninja.cove.ui.model.MediaType as UiMediaType

/**
 * The wide cards Home uses for things the viewer already owns.
 *
 * Landscape rather than the poster shape used everywhere else, and that is the point: a
 * poster is how you advertise something unseen, while a still frame is how you say "you were
 * here". The two rails that use these — carry on watching, and episodes waiting — are the
 * only places in the app showing content the viewer has already committed to.
 */

/**
 * A title to carry on with: where you got to, and one press to keep going.
 *
 * [stillUrl] is the frame from the episode itself and always wins when it has arrived — a
 * still says "you were here" in a way a show's promotional backdrop cannot. It is fetched
 * rather than read, so it is null on the first frame and for every film; the backdrop and
 * then the poster stand in until and unless it lands.
 */
@Composable
fun ContinueCard(
    row: ContinueRow,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    menu: WideCardMenuActions,
    modifier: Modifier = Modifier,
    stillUrl: String? = null,
) {
    // The episode the card stands for, which is the one every action here acts on. Null for a
    // film and for an unstarted show, and the action list is built to match — see
    // [continueCardActions].
    val episode = row.thumbnailEpisode()

    WideCard(
        artUrl = stillUrl ?: row.artUrl,
        // A still is 16:9 like the frame it goes in, so it needs no crop bias even when the
        // row's own art would have.
        wideArt = stillUrl != null || row.hasWideArt,
        contentDescription = "${row.displayTitle} artwork",
        fallbackIcon = if (row.media.type == UiMediaType.Movie) "lucide:film" else "lucide:tv",
        title = row.displayTitle,
        caption = row.caption,
        badge = if (row.hasNewEpisodes) "NEW" else null,
        watchFraction = row.watchFraction,
        playDescription = "Resume ${row.displayTitle}",
        onOpen = onOpen,
        onPlay = onPlay,
        modifier = modifier,
    ) { expanded, dismiss ->
        WideCardMenu(
            expanded = expanded,
            title = row.displayTitle,
            subtitle = row.caption,
            actions = remember(row) { continueCardActions(row) },
            listCategory = menu.listCategory(row.media),
            onDismiss = dismiss,
            onSetListCategory = { category -> menu.setListCategory(row.media, category) },
            onRemoveFromList = { menu.removeFromList(row.media) },
        ) { kind ->
            when (kind) {
                WideCardActionKind.Play -> onPlay()
                WideCardActionKind.OpenDetails -> onOpen()
                WideCardActionKind.PlayFromStart ->
                    menu.playFromStart(row.media, episode?.season, episode?.episode)
                WideCardActionKind.ChooseSource ->
                    menu.chooseSource(row.media, episode?.season, episode?.episode)
                // The duration the resume point already knows, so ticking an episode off does
                // not have to fetch the season to find out how long it was.
                WideCardActionKind.MarkWatched -> menu.markWatched(
                    row.media,
                    episode?.season,
                    episode?.episode,
                    row.progress?.durationSeconds,
                )
                WideCardActionKind.ClearProgress ->
                    menu.clearProgress(row.media, episode?.season, episode?.episode)
            }
        }
    }
}

/**
 * An episode that aired while the viewer was away.
 *
 * The badge counts the backlog rather than saying "new": with four unwatched episodes the
 * useful fact is how far behind you are, not that something happened.
 */
@Composable
fun BacklogCard(
    row: BacklogRow,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    menu: WideCardMenuActions,
    modifier: Modifier = Modifier,
) {
    WideCard(
        artUrl = calendarWideImageUrl(row.item),
        // Already sized by the helper, and calendar paths are bare rather than proxied, so
        // this must not be run through the resizer again.
        resize = false,
        wideArt = row.item.stillPath.isNotBlank(),
        contentDescription = "${row.displayTitle} artwork",
        fallbackIcon = if (row.item.type == MediaType.Movie) "lucide:film" else "lucide:tv",
        title = row.displayTitle,
        caption = row.caption,
        badge = row.badge,
        watchFraction = null,
        playDescription = "Play ${row.displayTitle}",
        onOpen = onOpen,
        onPlay = onPlay,
        modifier = modifier,
    ) { expanded, dismiss ->
        WideCardMenu(
            expanded = expanded,
            title = row.displayTitle,
            subtitle = "${row.badge}  ·  ${row.caption}",
            actions = remember(row) { backlogCardActions(row) },
            listCategory = menu.listCategory(row.media),
            onDismiss = dismiss,
            onSetListCategory = { category -> menu.setListCategory(row.media, category) },
            onRemoveFromList = { menu.removeFromList(row.media) },
        ) { kind ->
            when (kind) {
                WideCardActionKind.Play -> onPlay()
                WideCardActionKind.OpenDetails -> onOpen()
                // Title-level, matching this card's play button rather than the episode the
                // entry names: the two must resolve to the same thing, or "choose a source"
                // hands over a source for something else.
                WideCardActionKind.ChooseSource -> menu.chooseSource(row.media, null, null)
                WideCardActionKind.MarkWatched -> menu.markWatched(
                    row.media,
                    row.item.seasonNumber,
                    row.item.episodeNumber,
                    // Nothing has been played, so no duration is known; the store falls back
                    // to a nominal one, which only ever feeds "completed".
                    null,
                )
                WideCardActionKind.PlayFromStart, WideCardActionKind.ClearProgress -> Unit
            }
        }
    }
}

/**
 * @param contextMenu the card's own menu, anchored where the press landed. Wide cards have no
 *   drag gesture competing for a long press, which is why they can offer one where a poster
 *   card — whose long press starts a drag to another list — cannot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WideCard(
    artUrl: String?,
    wideArt: Boolean,
    contentDescription: String,
    fallbackIcon: String,
    title: String,
    caption: String,
    badge: String?,
    watchFraction: Float?,
    playDescription: String,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    resize: Boolean = true,
    contextMenu: @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.98f
            hovered -> 1.03f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "WideCardScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (hovered) 14.dp else 2.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "WideCardElevation",
    )

    val shape = RoundedCornerShape(14.dp)
    val colors = MaterialTheme.colorScheme

    // Kept on both hosts, unlike the poster card's: a long press is the only way to this menu
    // on a touch screen, and a rail holds a dozen cards rather than a grid's worth.
    var menuVisible by remember { mutableStateOf(false) }
    var menuPosition by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                .hoverable(interaction)
                .onSecondaryClick { position ->
                    menuPosition = position
                    menuVisible = true
                }
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onLongClick = {
                        // A touch has no cursor to anchor to, so the menu hangs off the card's
                        // own corner rather than from wherever the finger happened to land.
                        menuPosition = Offset(16f, 16f)
                        menuVisible = true
                    },
                    onClick = onOpen,
                ),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .shadow(elevation = elevation, shape = shape, clip = false)
                    .clip(shape)
                    .background(colors.surfaceContainer),
            ) {
                if (artUrl == null) {
                    // A tinted glyph rather than an empty box, so "no art for this title" does
                    // not read as "the image failed to load".
                    IconifyIcon(
                        icon = fallbackIcon,
                        modifier = Modifier.align(Alignment.Center).size(28.dp),
                        tint = colors.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                } else {
                    CoveAsyncImage(
                        model = if (resize) tmdbImageSize(artUrl, "w780") else artUrl,
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        // A poster dropped into a landscape frame is cropped hard; biasing the
                        // crop upwards keeps faces and titles rather than centring on a torso.
                        alignment = if (wideArt) Alignment.Center else Alignment.TopCenter,
                    )
                }

                // A permanent floor under the badge and the progress bar, so neither depends on
                // how light the frame behind it happens to be.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.35f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.55f),
                                ),
                            ),
                        ),
                )

                badge?.let {
                    CardBadge(
                        text = it,
                        modifier = Modifier.align(Alignment.TopStart).padding(9.dp),
                    )
                }

                PlayAffordance(
                    // Pinned on touch, where there is no hover and a hidden control is an
                    // unreachable one.
                    visible = hovered || !hasPointerHover,
                    description = playDescription,
                    onClick = onPlay,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(9.dp),
                )

                watchFraction?.let { fraction ->
                    CardProgress(
                        fraction = fraction,
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = colors.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = caption,
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = menuPosition.x.roundToInt(),
                        y = menuPosition.y.roundToInt(),
                    )
                }
                .size(1.dp),
        ) {
            contextMenu(menuVisible) { menuVisible = false }
        }
    }
}

/**
 * Everything a wide card's menu does to the thing it is showing, in one object.
 *
 * Passed down whole rather than as eight lambdas threaded through the page and the rail: both
 * cards want the same set, and every one of them is the page's business rather than the
 * card's — the card knows which episode it stands for and nothing about the library.
 */
@Immutable
class WideCardMenuActions(
    val playFromStart: (Media, Int?, Int?) -> Unit,
    val chooseSource: (Media, Int?, Int?) -> Unit,
    val markWatched: (Media, Int?, Int?, Double?) -> Unit,
    val clearProgress: (Media, Int?, Int?) -> Unit,
    val listCategory: (Media) -> MyListCategory?,
    val setListCategory: (Media, MyListCategory) -> Unit,
    val removeFromList: (Media) -> Unit,
)

/**
 * The menu itself: the card's own actions above the My List block every card shares.
 *
 * The shared block, the chrome and the header all come from [MediaContextMenu], which the
 * poster cards and the episode rows already use. Its title-level watched action is turned off
 * here because these cards carry their own, scoped to the one episode they stand for — two
 * entries reading "mark as watched" that meant different things would be worse than either.
 */
@Composable
private fun WideCardMenu(
    expanded: Boolean,
    title: String,
    subtitle: String,
    actions: List<WideCardAction>,
    listCategory: MyListCategory?,
    onDismiss: () -> Unit,
    onSetListCategory: (MyListCategory) -> Unit,
    onRemoveFromList: () -> Unit,
    onAction: (WideCardActionKind) -> Unit,
) {
    MediaContextMenu(
        expanded = expanded,
        title = title,
        subtitle = subtitle,
        // The caption already carries the episode and how much of it is left; a rating on the
        // end of that line is the least useful thing a card about resuming could say.
        rating = null,
        currentListCategory = listCategory,
        isWatched = false,
        showWatchedAction = false,
        onDismissRequest = onDismiss,
        onSetMyListCategory = onSetListCategory,
        onRemoveFromMyList = onRemoveFromList,
        onToggleWatched = {},
        leadingActions = {
            actions.forEach { action ->
                // Splits the menu where its meaning changes: above, what to play; below, what
                // the library should think happened.
                if (action.kind == WideCardActionKind.MarkWatched) MenuSectionDivider()

                CMenuItem(
                    text = action.label,
                    iconName = action.icon,
                    accent = action.kind == WideCardActionKind.Play,
                    onClick = {
                        onDismiss()
                        onAction(action.kind)
                    },
                )
            }
        },
    )
}

@Composable
private fun CardBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlayAffordance(
    visible: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(140)) + scaleIn(
            initialScale = 0.6f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ),
        exit = fadeOut(tween(110)) + scaleOut(targetScale = 0.6f),
    ) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val colors = MaterialTheme.colorScheme

        Box(
            modifier = Modifier
                .size(if (hasPointerHover) 38.dp else 48.dp)
                .clip(CircleShape)
                .background(if (hovered) colors.tertiary else Color.Black.copy(alpha = 0.72f))
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                // IconifyIcon draws with a null contentDescription, so the label has to live
                // on the button itself for a screen reader to find anything here.
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = "lucide:play",
                modifier = Modifier.size(17.dp),
                tint = if (hovered) colors.onTertiary else Color.White,
            )
        }
    }
}

@Composable
private fun CardProgress(fraction: Float, modifier: Modifier = Modifier) {
    val grown = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        grown.animateTo(
            targetValue = fraction.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(pivotFractionX = 0f, pivotFractionY = 0.5f)
                    scaleX = grown.value
                }
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}
