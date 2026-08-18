package com.coveninja.cove.ui.components.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.coveninja.cove.ui.state.PlaybackStatus

/**
 * How far a session has got towards actually playing.
 *
 * Both flags are latched, because neither question is asked of the current instant. mpv
 * drops `hasMedia` again at the end of a file and briefly whenever it reloads, and it
 * stalls for cache in the middle of a torrent — read live, an opening overlay would slide
 * back over a session that is playing perfectly well, and controls would vanish from under
 * a viewer's hand every time the buffer ran short. Both describe a threshold the session
 * has crossed, and a session crosses each of them once.
 *
 * Reads go through state rather than through captured booleans, and that is load-bearing.
 * Gesture detectors live inside `pointerInput`, whose block is not re-run for an ordinary
 * recomposition: a plain `Boolean` read into one of those closures is the value from
 * whenever the block last started — for a player, always `false`, since the block starts
 * with the layer and the film starts later. Holding the state itself means the closure
 * reads today's answer instead of the one from before playback began.
 */
@Stable
internal class PlaybackStart internal constructor(
    private val openedState: MutableState<Boolean>,
    private val startedState: MutableState<Boolean>,
) {
    /** The file is open. Enough to stop standing in for the picture with artwork. */
    val opened: Boolean get() = openedState.value

    /** Frames are moving. Enough to let the controls drive them. */
    val started: Boolean get() = startedState.value
}

/**
 * True once the player is doing something a control could act on.
 *
 * "Playing" as a phase means only that a URL was handed over; the file may still be
 * opening, and on a torrent the first pieces can be a minute away. Media plus a buffer
 * that is not empty is the earliest point at which pausing, seeking or picking a track
 * is a request the player can answer rather than one it will drop.
 */
internal fun playbackHasStarted(status: PlaybackStatus): Boolean =
    status.hasMedia && !status.waitingForData

/**
 * Watches [status] for those two thresholds and remembers them for the rest of the item.
 *
 * [keys] identify what is playing — reset them and the latches reset with them, which is
 * what makes the next episode open with its own loading state instead of inheriting this
 * one's.
 */
@Composable
internal fun rememberPlaybackStart(status: PlaybackStatus, vararg keys: Any?): PlaybackStart {
    val opened = remember(*keys) { mutableStateOf(false) }
    val started = remember(*keys) { mutableStateOf(false) }
    // Latched from an effect rather than assigned during composition, which would be a
    // write to state the same pass is reading. The frame it costs is invisible: until the
    // latch catches up, every reader still has the live status saying the same thing.
    LaunchedEffect(status.hasMedia) {
        if (status.hasMedia) opened.value = true
    }
    LaunchedEffect(playbackHasStarted(status)) {
        if (playbackHasStarted(status)) started.value = true
    }
    return remember(*keys) { PlaybackStart(opened, started) }
}
