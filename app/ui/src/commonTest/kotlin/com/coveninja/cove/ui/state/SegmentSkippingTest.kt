package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.LabelledSegment
import com.coveninja.cove.shared.model.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentSkippingTest {

    private val intro = LabelledSegment(SegmentKind.Intro, 60.0, 90.0)

    // Mutation applied to verify: made the upper bound inclusive → test failed,
    // the playhead was still "inside" the intro at its very end, which would have
    // re-triggered the skip it had just performed.
    @Test
    fun `the end of a segment is outside it`() {
        assertEquals(intro, segmentAt(60.0, listOf(intro)))
        assertEquals(intro, segmentAt(89.9, listOf(intro)))
        assertNull(segmentAt(90.0, listOf(intro)))
        assertNull(segmentAt(59.9, listOf(intro)))
    }

    // Mutation applied to verify: used firstOrNull → test failed, the outer
    // segment won over the one that actually started most recently.
    @Test
    fun `the most recently started segment wins an overlap`() {
        val recap = LabelledSegment(SegmentKind.Recap, 0.0, 120.0)

        assertEquals(intro, segmentAt(70.0, listOf(recap, intro)))
    }

    // A credits roll that runs to the last frame is the normal case, and seeking
    // to its end just ends the episode.
    // Mutation applied to verify: removed the end-of-file guard → test failed,
    // skipping the credits jumped to the end of the file.
    @Test
    fun `a segment running to the end of the file is not skipped`() {
        val credits = LabelledSegment(SegmentKind.Credits, 1400.0, 1440.0)

        assertNull(skipTarget(credits, positionSeconds = 1405.0, durationSeconds = 1441.0))
    }

    // Mutation applied to verify: removed the minimum-jump guard → test failed,
    // a seek was issued to move the playhead by a tenth of a second.
    @Test
    fun `a jump too small to notice is not made`() {
        assertNull(skipTarget(intro, positionSeconds = 89.5, durationSeconds = 1440.0))
        assertEquals(90.0, skipTarget(intro, positionSeconds = 61.0, durationSeconds = 1440.0))
    }

    // Mutation applied to verify: mapped every kind to autoSkipIntro → test
    // failed, disabling only the intro disabled all four.
    @Test
    fun `each segment kind reads its own setting`() {
        val onlyCredits = AppSettings(
            autoSkipIntro = false,
            autoSkipRecap = false,
            autoSkipCredits = true,
            autoSkipPreview = false,
        )

        assertTrue(onlyCredits.skipsAutomatically(SegmentKind.Credits))
        assertTrue(!onlyCredits.skipsAutomatically(SegmentKind.Intro))
        assertTrue(!onlyCredits.skipsAutomatically(SegmentKind.Recap))
        assertTrue(!onlyCredits.skipsAutomatically(SegmentKind.Preview))
    }

    // Two segments of the same kind in one episode must be tracked separately, or
    // skipping the first would suppress the second.
    // Mutation applied to verify: keyed identity on kind alone → test failed,
    // both intros shared one key.
    @Test
    fun `segments of the same kind are told apart`() {
        val second = LabelledSegment(SegmentKind.Intro, 600.0, 630.0)

        assertTrue(intro.identity() != second.identity())
    }
}
