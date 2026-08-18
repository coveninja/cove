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

    // Mutation applied to verify: returned true whenever a status existed → test failed,
    // which is the bar live over the "finding the first pieces" stage.
    @Test
    fun `a file that has not opened has not started`() {
        assertFalse(playbackHasStarted(PlaybackStatus(hasMedia = false)))
    }

    // The reported case: opened, but still filling its first buffer.
    // Mutation applied to verify: dropped the waitingForData term → test failed, so the
    // controls would have come alive during the opening stall.
    @Test
    fun `an open file still filling its buffer has not started`() {
        assertFalse(
            playbackHasStarted(PlaybackStatus(hasMedia = true, waitingForData = true)),
        )
    }

    // Mutation applied to verify: required !paused as well → test failed, and a title that
    // opens paused — a resumed one, or one paused before the first frame — would have had
    // controls that could never be used to start it.
    @Test
    fun `media plus a buffer that is not empty has started`() {
        assertTrue(
            playbackHasStarted(
                PlaybackStatus(hasMedia = true, waitingForData = false, paused = true),
            ),
        )
    }
}
