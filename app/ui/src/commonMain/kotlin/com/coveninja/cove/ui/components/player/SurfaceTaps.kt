package com.coveninja.cove.ui.components.player

/**
 * Whether a tap on the picture should toggle the transport, or spend itself revealing
 * the controls instead.
 *
 * A mouse and a finger do not have the same reach. A pointer keeps the controls under it
 * whenever it moves, so a click over the picture is always free to mean pause, the way it
 * does in every desktop player. A finger cannot hover: the only way to bring the chrome
 * back is to touch the picture, and the press that does it must not also flip playback —
 * reaching for a hidden seek bar would pause the film every time. Once the chrome is up a
 * tap means what it means everywhere else.
 *
 * [controlsShown] is the state at press time, not at tap time: the press itself wakes the
 * controls, and a tap resolves only after the double-tap window closes, by which point they
 * are always up and the question can no longer be asked.
 */
internal fun tapTogglesPause(fromTouch: Boolean, controlsShown: Boolean): Boolean =
    !fromTouch || controlsShown
