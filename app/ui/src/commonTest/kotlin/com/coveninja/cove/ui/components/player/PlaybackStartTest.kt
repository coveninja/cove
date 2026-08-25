package com.coveninja.cove.ui.components.player

import com.coveninja.cove.ui.state.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the controls become worth offering.
 *
 * The phase says "Playing" as soon as a URL is handed over, so this is the only thing
 * standing between a viewer and a seek bar for a file that has not opened.
 */
class PlaybackStartTest {

    @Test
    fun `a file that has not opened has not started`() {
        assertFalse(playbackHasStarted(PlaybackStatus(hasMedia = false)))
    }

    // The reported case: opened, but still filling its first buffer.
    @Test
    fun `an open file still filling its buffer has not started`() {
        assertFalse(
            playbackHasStarted(PlaybackStatus(hasMedia = true, waitingForData = true)),
        )
    }

    @Test
    fun `media plus a buffer that is not empty has started`() {
        assertTrue(
            playbackHasStarted(
                PlaybackStatus(hasMedia = true, waitingForData = false, paused = true),
            ),
        )
    }
}
