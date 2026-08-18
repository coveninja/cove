package com.coveninja.cove.ui.tv.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.icons.IconifyIcon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.model.TorrentProgress
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.PlaybackSession
import com.coveninja.cove.ui.state.PlaybackStatus
import kotlinx.coroutines.delay
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.tv.components.TvButton
import com.coveninja.cove.ui.tv.focus.FocusOnAppear
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * The stall indicator.
 *
 * Reports the buffer's own progress where it has one, because "loading" with no number is the
 * part of every player that feels broken — a percentage that is climbing says the wait is
 * finite, and one that is stuck says something a spinner never can.
 */
@Composable
internal fun TvBufferingIndicator(percent: Int, modifier: Modifier = Modifier) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val transition = rememberInfiniteTransition(label = "TvBuffering")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "TvBufferingSpin",
    )

    Row(
        modifier = modifier
            .background(CoveColors.Scrim.copy(alpha = 0.72f), RoundedCornerShape(18.dp))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(
            icon = "lucide:loader-circle",
            tint = CoveColors.Brand.Accent,
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer { if (!reducedMotion) rotationZ = spin },
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = if (percent in 1..99) "Buffering $percent%" else "Buffering",
            style = MaterialTheme.typography.titleMedium,
            color = CoveColors.Neutral.Text,
        )
    }
}

/**
 * "Skip intro", and the button that does it.
 *
 * Shows the key rather than being one. It is not focusable — see [tvSelectSkipsSegment] — so it
 * has to say what to press, which on a remote is the centre button and nothing else.
 */
@Composable
internal fun TvSkipHint(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(CoveColors.Neutral.Text, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(CoveColors.Neutral.Background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = "lucide:circle-dot",
                tint = CoveColors.Neutral.Text,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = CoveColors.Neutral.Background,
        )
    }
}

/**
 * What comes next, offered as the credits arrive.
 *
 * Shown whether or not autoplay is on, because it is the useful control either way: with it off
 * this is how you carry on, and with it on this is how you stop it happening. The countdown bar
 * only appears in the autoplay case — a progress bar that ran out and did nothing would be a
 * promise the card never made.
 *
 * It takes focus, deferred a frame. Nothing else on screen wants it during credits, and a card
 * that had to be navigated to would be missed by exactly the viewer it is for.
 */
@Composable
internal fun TvUpNextCard(
    season: Int,
    episode: Int,
    autoAdvance: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val focusRequester = remember { FocusRequester() }
    FocusOnAppear(focusRequester)

    val transition = rememberInfiniteTransition(label = "TvUpNext")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "TvUpNextSweep",
    )

    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .background(CoveColors.Neutral.SurfaceRaised, RoundedCornerShape(18.dp))
            .padding(22.dp)
            .tvFocusGroup(),
    ) {
        Text(
            text = "Up next",
            style = MaterialTheme.typography.labelMedium,
            color = CoveColors.Brand.Accent,
        )
        Text(
            text = "Season $season, episode $episode",
            style = MaterialTheme.typography.titleLarge,
            color = CoveColors.Neutral.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (autoAdvance) {
            Box(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .height(4.dp)
                    .background(
                        CoveColors.Neutral.Text.copy(alpha = 0.2f),
                        RoundedCornerShape(2.dp),
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidthFraction(if (reducedMotion) 1f else sweep)
                        .height(4.dp)
                        .background(CoveColors.Brand.Accent, RoundedCornerShape(2.dp)),
                )
            }
        }

        Box(modifier = Modifier.padding(top = 16.dp)) {
            TvButton(
                label = "Play now",
                onClick = onPlay,
                icon = "lucide:play",
                primary = true,
                modifier = Modifier.focusRequester(focusRequester),
            )
        }
    }
}

/** `fillMaxWidth` refuses a zero fraction; a countdown starts at exactly that. */
@Composable
private fun Modifier.fillMaxWidthFraction(fraction: Float): Modifier =
    this.then(
        if (fraction <= 0f) {
            Modifier.width(0.dp)
        } else {
            Modifier.fillMaxWidth(fraction.coerceAtMost(1f))
        },
    )

/**
 * The wait between handing a URL to the player and the first frame arriving.
 *
 * This window is why the screen used to go black: playback has "started" as far as the session
 * is concerned, so the pre-playback states are gone, but a torrent may still be collecting its
 * first pieces and there is nothing to draw. Saying what is being waited on — and showing it
 * move — is the difference between a slow start and an app that looks broken.
 */
@Composable
internal fun TvStartingStage(
    media: Media,
    source: StreamSource,
    status: PlaybackStatus,
    torrent: TorrentProgress?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    FocusOnAppear(focusRequester)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TvBufferingIndicator(percent = status.bufferingPercent)

            Text(
                text = media.title ?: media.name.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = CoveColors.Neutral.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = startingDetail(source, torrent),
                style = MaterialTheme.typography.bodyMedium,
                color = CoveColors.Neutral.MutedDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )

            Box(modifier = Modifier.padding(top = 24.dp)) {
                TvButton(
                    label = "Cancel",
                    onClick = onCancel,
                    icon = "lucide:x",
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
        }
    }
}

/**
 * What is actually being waited on.
 *
 * Peers and rate for a torrent, because those are the numbers that say whether the wait will
 * end; the provider's name for a direct stream, where there is nothing to count.
 */
private fun startingDetail(
    source: StreamSource,
    torrent: TorrentProgress?,
): String {
    if (torrent != null) {
        val rate = torrent.downloadRate / 1_000
        return buildList {
            add("${torrent.peers} peers")
            if (rate > 0) add("$rate kB/s")
        }.joinToString("  ·  ")
    }
    return source.addonName?.takeIf { it.isNotBlank() }
        ?.let { "Opening from $it" }
        ?: "Opening the stream"
}

/**
 * Polls torrent progress while the stage is up, and only for a real torrent.
 *
 * A source with its own URL is served directly and has no swarm to report on; asking about one
 * would be a request per second and a half for a number that does not exist.
 */
@Composable
internal fun rememberTvTorrentProgress(
    session: PlaybackSession,
    source: StreamSource,
): TorrentProgress? {
    val hash = source.infoHash?.takeIf { it.isNotBlank() && source.url.isNullOrBlank() }
        ?: return null
    val graph = LocalAppGraph.current
    var progress by remember(hash) {
        mutableStateOf<TorrentProgress?>(
            null,
        )
    }
    LaunchedEffect(hash) {
        while (true) {
            progress = runCatching { graph.playback.torrentProgress(hash) }.getOrNull()
            delay(TV_TORRENT_POLL_MILLIS)
        }
    }
    return progress
}

private const val TV_TORRENT_POLL_MILLIS = 1_500L
