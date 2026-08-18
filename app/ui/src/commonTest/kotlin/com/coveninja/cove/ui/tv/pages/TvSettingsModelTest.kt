package com.coveninja.cove.ui.tv.pages

import kotlin.test.Test
import kotlin.test.assertEquals

class TvSettingsModelTest {

    // Mutation applied to verify: dropped the modulo so the last option stepped past the end →
    // test failed with an index out of bounds, which on a television is a setting that can be
    // changed exactly as many times as it has values and then crashes.
    @Test
    fun `stepping past the last option comes back to the first`() {
        assertEquals("quality", cycleOption(StreamSelectionModes, "balanced"))
        assertEquals("seeders", cycleOption(StreamSelectionModes, "quality"))
        assertEquals("balanced", cycleOption(StreamSelectionModes, "seeders"))
    }

    // Reachable in practice: settings sync between devices and versions, so a newer build can
    // write a mode this one has never heard of. Staying put would look like a broken control.
    // Mutation applied to verify: returned `current` for an unknown value → test failed, the
    // setting became unchangeable.
    @Test
    fun `a value this build does not know resolves to the first option`() {
        assertEquals("balanced", cycleOption(StreamSelectionModes, "some-future-mode"))
    }

    // Mutation applied to verify: removed the isEmpty guard → test failed with an exception
    // rather than leaving the value alone.
    @Test
    fun `with nothing to choose from the value is left alone`() {
        assertEquals("balanced", cycleOption(emptyList(), "balanced"))
    }

    // The label is what the row reads out, and an unrecognised mode is shown as itself rather
    // than silently relabelled — the setting should read as unfamiliar, not as lost.
    // Mutation applied to verify: mapped the else branch to "Balanced" → test failed.
    @Test
    fun `every known mode has a label and an unknown one keeps its own name`() {
        assertEquals("Balanced", streamSelectionLabel("balanced"))
        assertEquals("Quality first", streamSelectionLabel("quality"))
        assertEquals("Most seeded", streamSelectionLabel("seeders"))
        assertEquals("some-future-mode", streamSelectionLabel("some-future-mode"))
    }

    // An empty string is what an unset setting reads as, and "Balanced" is its documented
    // default — showing a blank value would be a row with nothing on the right of it.
    // Mutation applied to verify: dropped the ifBlank fallback → test failed.
    @Test
    fun `an unset mode reads as the default rather than as nothing`() {
        assertEquals("Balanced", streamSelectionLabel(""))
    }

    @Test
    fun `numeric choices cycle and label in their own units`() {
        assertEquals(10.0, cycleOption(SeekStepChoices, 5.0))
        assertEquals(5.0, cycleOption(SeekStepChoices, 30.0))
        assertEquals("10 seconds", seekStepLabel(10.0))
        assertEquals("125%", subtitleSizeLabel(125.0))
    }
}
