package com.coveninja.cove.ui.tv.focus

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key

/** The four ways a remote can move, kept apart from Compose's [FocusDirection] so it is testable. */
enum class TvDirection {
    Up,
    Down,
    Left,
    Right,
}

fun TvDirection.toFocusDirection(): FocusDirection = when (this) {
    TvDirection.Up -> FocusDirection.Up
    TvDirection.Down -> FocusDirection.Down
    TvDirection.Left -> FocusDirection.Left
    TvDirection.Right -> FocusDirection.Right
}

/**
 * Everything a remote can ask for, before anything decides what it means here.
 *
 * A television remote is a small, fixed vocabulary — that is its advantage over a mouse, and the
 * reason the TV shell can route input through one mapping instead of scattering key handling
 * across screens the way the desktop player does.
 */
sealed interface TvKeyAction {
    data class Move(val direction: TvDirection) : TvKeyAction
    data object Select : TvKeyAction
    data object Back : TvKeyAction
    data object PlayPause : TvKeyAction
    data object FastForward : TvKeyAction
    data object Rewind : TvKeyAction
    data object Next : TvKeyAction
    data object Previous : TvKeyAction
}

/**
 * What a key press means to the TV shell, or null if it means nothing here.
 *
 * Callers act on the subset they own and let the rest pass: the shell moves focus and ignores
 * [TvKeyAction.Select] entirely, because Select belongs to whatever is focused — consuming it
 * centrally would stop every card in the app from being openable. The player is the one screen
 * that takes the whole vocabulary, since while it is up there is nothing else to steer.
 *
 * Android delivers system Back through the activity's back dispatcher rather than as a key
 * event, so [Key.Back] here is mostly the desktop `--tv` harness, where Escape stands in for the
 * remote's Back button.
 */
fun tvKeyAction(key: Key): TvKeyAction? = when (key) {
    Key.DirectionUp -> TvKeyAction.Move(TvDirection.Up)
    Key.DirectionDown -> TvKeyAction.Move(TvDirection.Down)
    Key.DirectionLeft -> TvKeyAction.Move(TvDirection.Left)
    Key.DirectionRight -> TvKeyAction.Move(TvDirection.Right)

    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> TvKeyAction.Select
    Key.Back, Key.Escape -> TvKeyAction.Back

    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> TvKeyAction.PlayPause
    Key.MediaFastForward -> TvKeyAction.FastForward
    Key.MediaRewind -> TvKeyAction.Rewind
    Key.MediaNext -> TvKeyAction.Next
    Key.MediaPrevious -> TvKeyAction.Previous

    else -> null
}
