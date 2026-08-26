package com.coveninja.cove.ui.tv.pages

import com.coveninja.cove.ui.state.AUDIO_LANGUAGE_ORIGINAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvSettingsModelTest {

    @Test
    fun `stepping past the last option comes back to the first`() {
        assertEquals("quality", cycleOption(StreamSelectionModes, "balanced"))
        assertEquals("seeders", cycleOption(StreamSelectionModes, "quality"))
        assertEquals("balanced", cycleOption(StreamSelectionModes, "seeders"))
    }

    // Reachable in practice: settings sync between devices and versions, so a newer build can
    // write a mode this one has never heard of. Staying put would look like a broken control.
    @Test
    fun `a value this build does not know resolves to the first option`() {
        assertEquals("balanced", cycleOption(StreamSelectionModes, "some-future-mode"))
    }

    @Test
    fun `with nothing to choose from the value is left alone`() {
        assertEquals("balanced", cycleOption(emptyList(), "balanced"))
    }

    // The label is what the row reads out, and an unrecognised mode is shown as itself rather
    // than silently relabelled — the setting should read as unfamiliar, not as lost.
    @Test
    fun `every known mode has a label and an unknown one keeps its own name`() {
        assertEquals("Balanced", streamSelectionLabel("balanced"))
        assertEquals("Quality first", streamSelectionLabel("quality"))
        assertEquals("Most seeded", streamSelectionLabel("seeders"))
        assertEquals("some-future-mode", streamSelectionLabel("some-future-mode"))
    }

    // An empty string is what an unset setting reads as, and "Balanced" is its documented
    // default — showing a blank value would be a row with nothing on the right of it.
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

    // The default has to be one of the offered steps or the control cannot return to it: cycle
    // away from a value that is not in the list and there is no press that brings it back.
    @Test
    fun `the stored subtitle position default is one the control can reach`() {
        assertTrue(8.0 in SubtitlePositionChoices)
        assertEquals(14.0, cycleOption(SubtitlePositionChoices, 8.0))
        assertEquals(2.0, cycleOption(SubtitlePositionChoices, 20.0))
    }

    // A desktop slider writes any value it likes and it syncs here. Naming the percentage keeps
    // the row honest rather than showing a preset name for a position that is not that preset.
    @Test
    fun `a position with no preset name is described by its number`() {
        assertEquals("Standard", subtitlePositionLabel(8.0))
        assertEquals("11% up", subtitlePositionLabel(11.0))
    }

    @Test
    fun `languages cycle and unknown codes keep their own name`() {
        assertEquals("es", cycleOption(LanguageChoices, "en"))
        assertEquals(AUDIO_LANGUAGE_ORIGINAL, cycleOption(LanguageChoices, "ru"))
        assertEquals("English", languageLabel("en"))
        assertEquals("Japanese", languageLabel("ja"))
        assertEquals("SV", languageLabel("sv"))
    }

    // Original means "whatever the title was made in" and is the option most people want — a
    // subtitled film stays subtitled instead of opening on an English dub.
    //
    // Leaving it out of the list did more than hide it. `cycleOption` resolves an unrecognised
    // value to the first entry, so a profile that chose Original on a phone displayed a raw
    // "ORIGINAL" here and was silently converted to English by one press of the row. Both
    // halves are pinned: that it is offered at all, and that it survives being cycled onto.
    @Test
    fun `original is offered and is not destroyed by cycling onto it`() {
        assertTrue(AUDIO_LANGUAGE_ORIGINAL in LanguageChoices)
        assertEquals("Original", languageLabel(AUDIO_LANGUAGE_ORIGINAL))
        assertEquals("en", cycleOption(LanguageChoices, AUDIO_LANGUAGE_ORIGINAL))
    }

    // It leads the list for the same reason it leads the phone's, and because a control that
    // cycles has no other way to make one option easier to reach than the rest.
    @Test
    fun `original leads the list`() {
        assertEquals(AUDIO_LANGUAGE_ORIGINAL, LanguageChoices.first())
    }

    // An unset language is the field's own default of English rather than an empty right-hand
    // side, matching how an unset selection mode reads as Balanced.
    @Test
    fun `an unset language reads as the default rather than as nothing`() {
        assertEquals("English", languageLabel(""))
    }
}
