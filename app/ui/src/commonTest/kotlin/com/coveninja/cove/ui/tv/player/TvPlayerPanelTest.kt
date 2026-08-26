package com.coveninja.cove.ui.tv.player

import kotlin.test.Test
import kotlin.test.assertEquals

class TvPlayerPanelTest {

    // "1×" in a column of values reads as a number somebody set rather than as the absence of a
    // setting, which is what normal speed is.
    @Test
    fun `normal speed is named rather than numbered`() {
        assertEquals("Normal", speedLabel(1.0))
    }

    @Test
    fun `whole and fractional speeds both read cleanly`() {
        assertEquals("2×", speedLabel(2.0))
        assertEquals("1.5×", speedLabel(1.5))
        assertEquals("0.75×", speedLabel(0.75))
        assertEquals("1.25×", speedLabel(1.25))
    }

    // Mutation check: drop the trailing-zero trim and 1.5 renders as "1.50×"; drop the whole
    // check and 2.0 renders as "2.0×". Both are wrong in the same small, permanent way.
    @Test
    fun `a speed never shows a trailing zero`() {
        assertEquals("1.5×", speedLabel(1.50))
        assertEquals("2×", speedLabel(2.00))
    }

    // The sign is the whole point. "0.4" says nothing about whether subtitles were pushed later
    // or pulled earlier, and getting it backwards means four presses the wrong way before the
    // picture says so.
    @Test
    fun `a delay always carries its direction`() {
        assertEquals("+0.4s", formatDelay(0.4))
        assertEquals("-0.4s", formatDelay(-0.4))
    }

    // Zero is the absence of an adjustment, not an adjustment of nothing — and "+0.0s" beside a
    // reset button that is hidden at zero would read as a delay that refused to clear.
    @Test
    fun `no delay reads as none rather than as zero`() {
        assertEquals("None", formatDelay(0.0))
    }

    @Test
    fun `a delay past a second keeps both parts`() {
        assertEquals("+1.2s", formatDelay(1.2))
        assertEquals("-2.5s", formatDelay(-2.5))
    }
}
