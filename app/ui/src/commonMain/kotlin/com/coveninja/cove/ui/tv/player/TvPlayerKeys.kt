package com.coveninja.cove.ui.tv.player

import com.coveninja.cove.ui.tv.focus.TvDirection

/** What an arrow press means while something is playing. */
internal enum class TvPlayerArrowOutcome {
    /** Left and right jump through the film. */
    Seek,

    /** Up and down bring the controls back rather than doing anything to playback. */
    RevealControls,

    /** The controls are up and have focus, so the arrows belong to them. */
    Navigate,
}

/**
 * The one decision that makes a D-pad work over a video.
 *
 * Four arrows have to serve two jobs — steering the film and steering the controls — and which
 * job they are doing cannot be guessed from the key. It is decided by whether the control bar
 * is both visible *and* holding focus.
 *
 * The second half of that condition is the part worth stating. The previous TV player checked
 * visibility alone, and there is a window right after the controls appear where they are on
 * screen but focus is still on the player behind them: a second press in that window fell
 * through to the page's focus engine and moved focus somewhere off the video entirely. Asking
 * for both is what closes it.
 */
internal fun tvPlayerArrowOutcome(
    direction: TvDirection,
    controlsVisible: Boolean,
    barHasFocus: Boolean,
    panelOpen: Boolean,
): TvPlayerArrowOutcome {
    // The panel is a list to be walked, and it is the only thing on screen that can be. It
    // takes the arrows outright rather than waiting to hold focus the way the control bar
    // does: the bar shares the screen with a running film that Left and Right still steer,
    // whereas a Left that seeked out from under an open panel would move the picture the
    // viewer is using to judge the very setting they are changing.
    if (panelOpen) return TvPlayerArrowOutcome.Navigate
    if (controlsVisible && barHasFocus) return TvPlayerArrowOutcome.Navigate
    return when (direction) {
        TvDirection.Left, TvDirection.Right -> TvPlayerArrowOutcome.Seek
        TvDirection.Up, TvDirection.Down -> TvPlayerArrowOutcome.RevealControls
    }
}

/** Seconds a single left or right press moves, when the settings do not say otherwise. */
internal const val TV_SEEK_STEP_SECONDS = 10.0

/** How long the controls stay up with nothing pressed. */
internal const val TV_CONTROLS_HIDE_DELAY_MILLIS = 4_500L

/**
 * How long a transient notice stays readable.
 *
 * Longer than the phone's four seconds: a television is across the room, and the notices it
 * shows arrive unannounced rather than as the answer to something just pressed.
 */
internal const val TV_NOTICE_MILLIS = 6_000L

/**
 * Whether the centre button should skip the segment on screen rather than summon the controls.
 *
 * The skip hint is not focusable, on purpose: it appears in the middle of a film, and something
 * that grabbed focus away from the picture — or that had to be navigated to before it could be
 * used — would be worse than not offering the skip at all. So while the hint is up, centre means
 * skip, and it goes back to meaning "show me the controls" the moment the segment ends.
 *
 * Not while the controls are already up: there the centre button belongs to whatever is focused,
 * and stealing it would make the transport row unpressable during an intro.
 *
 * Nor while the panel is open, for the same reason and more strongly — every row in it is
 * activated with centre, and an intro starting underneath would otherwise turn the next press
 * into a skip instead of the track the viewer was selecting.
 */
internal fun tvSelectSkipsSegment(
    controlsVisible: Boolean,
    skipAvailable: Boolean,
    panelOpen: Boolean,
): Boolean = skipAvailable && !controlsVisible && !panelOpen
