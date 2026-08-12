package com.coveninja.cove.backend.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidPlaybackMediaSupportTest {
    @Test
    fun `range parser supports complete explicit open and suffix requests`() {
        assertEquals(0L..999L, parsePlaybackRange(null, 1_000))
        assertEquals(100L..199L, parsePlaybackRange("bytes=100-199", 1_000))
        assertEquals(900L..999L, parsePlaybackRange("bytes=-100", 1_000))
        assertEquals(750L..999L, parsePlaybackRange("bytes=750-", 1_000))
    }

    @Test
    fun `range parser rejects multiple and out of bounds requests`() {
        assertFailsWith<IllegalArgumentException> {
            parsePlaybackRange("bytes=0-1,4-5", 1_000)
        }
        assertFailsWith<IllegalArgumentException> {
            parsePlaybackRange("bytes=1000-", 1_000)
        }
    }

    @Test
    fun `srt conversion emits webvtt timestamps`() {
        val converted = convertSrtToVtt(
            "1\n00:00:01,250 --> 00:00:03,500\nHello\n",
        )
        assertTrue(converted.startsWith("WEBVTT\n\n"))
        assertTrue("00:00:01.250 --> 00:00:03.500" in converted)
    }
}
