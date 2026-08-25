package com.coveninja.cove.ui.tv.focus

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvDpadTest {

    @Test
    fun `each arrow resolves to its own direction`() {
        assertEquals(TvKeyAction.Move(TvDirection.Up), tvKeyAction(Key.DirectionUp))
        assertEquals(TvKeyAction.Move(TvDirection.Down), tvKeyAction(Key.DirectionDown))
        assertEquals(TvKeyAction.Move(TvDirection.Left), tvKeyAction(Key.DirectionLeft))
        assertEquals(TvKeyAction.Move(TvDirection.Right), tvKeyAction(Key.DirectionRight))
    }

    // A remote's centre button, a TV keyboard's Enter and the desktop harness's Enter and space
    // all have to reach the same place, or a control is activatable on one input and not another.
    @Test
    fun `every way of pressing OK resolves to Select`() {
        listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar).forEach { key ->
            assertEquals(TvKeyAction.Select, tvKeyAction(key), "$key should select")
        }
    }

    // Escape is the remote's Back button in the desktop `--tv` harness, which is where the
    // shell gets driven during development; losing it there costs the whole dev loop.
    @Test
    fun `Back and Escape both mean Back`() {
        assertEquals(TvKeyAction.Back, tvKeyAction(Key.Back))
        assertEquals(TvKeyAction.Back, tvKeyAction(Key.Escape))
    }

    // Remotes vary: some send a single toggle, others separate play and pause buttons, and a
    // viewer pressing the one their remote has should not find it inert.
    @Test
    fun `all three transport toggles resolve to PlayPause`() {
        listOf(Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause).forEach { key ->
            assertEquals(TvKeyAction.PlayPause, tvKeyAction(key), "$key should toggle transport")
        }
    }

    @Test
    fun `seek and skip keys stay distinct from each other`() {
        assertEquals(TvKeyAction.FastForward, tvKeyAction(Key.MediaFastForward))
        assertEquals(TvKeyAction.Rewind, tvKeyAction(Key.MediaRewind))
        assertEquals(TvKeyAction.Next, tvKeyAction(Key.MediaNext))
        assertEquals(TvKeyAction.Previous, tvKeyAction(Key.MediaPrevious))
    }

    // The shell consumes what this returns, so anything it claims is a key the app has taken
    // away from whatever is focused. A TV keyboard typing into a search field goes through here.
    @Test
    fun `keys the shell has no use for are left alone`() {
        listOf(Key.A, Key.Zero, Key.Tab, Key.Backspace, Key.ShiftLeft).forEach { key ->
            assertNull(tvKeyAction(key), "$key should pass through")
        }
    }

    @Test
    fun `directions map onto the matching Compose focus direction`() {
        assertEquals(FocusDirection.Up, TvDirection.Up.toFocusDirection())
        assertEquals(FocusDirection.Down, TvDirection.Down.toFocusDirection())
        assertEquals(FocusDirection.Left, TvDirection.Left.toFocusDirection())
        assertEquals(FocusDirection.Right, TvDirection.Right.toFocusDirection())
    }
}
