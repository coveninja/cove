package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.StreamBehaviorHints
import com.coveninja.cove.shared.model.StreamSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a source row leads with, and when it is worth a second line.
 *
 * The second line exists because the file name answers questions the release title cannot —
 * which episode of a season pack, whose encode, which dub — and it is suppressed where the two
 * amount to the same string, since two identical lines per row is worse than one.
 */
class SourceRowNamingTest {
    @Test
    fun `a file name the heading does not already carry is shown`() {
        val source = StreamSource(
            name = "Provider",
            title = "Show Season 2 · 1080p\n👤 48 💾 21.4 GB",
            behaviorHints = StreamBehaviorHints(filename = "Show.S02E04.1080p.WEB-DL.mkv"),
        )

        // The case the feature is for: the title names the pack and only the file names the
        // episode. Fails if the row shows the heading alone, which is what it used to do.
        assertEquals("Show.S02E04.1080p.WEB-DL.mkv", source.distinctFileName())
    }

    @Test
    fun `a file name the heading already is gets no second line`() {
        // Most providers put the file name in the title, so this is the common case and the
        // reason for comparing at all. Fails without the comparison: every row in a Torrentio
        // list would carry the same string twice.
        assertNull(
            StreamSource(
                title = "Show.S02E04.1080p.WEB-DL.mkv",
                behaviorHints = StreamBehaviorHints(filename = "Show.S02E04.1080p.WEB-DL.mkv"),
            ).distinctFileName(),
        )
        // Spelled differently, meaning the same thing: dots against spaces, and the extension
        // present on one side only. Fails on a plain string comparison.
        assertNull(
            StreamSource(
                title = "Show S02E04 1080p WEB-DL",
                behaviorHints = StreamBehaviorHints(filename = "show.s02e04.1080p.web-dl.mkv"),
            ).distinctFileName(),
        )
        // The heading is the first line of the title; the file name sitting inside the rest of
        // it is still a repeat.
        assertNull(
            StreamSource(
                title = "Show.S02E04.1080p.WEB-DL.mkv\n👤 48 💾 2.1 GB",
                behaviorHints = StreamBehaviorHints(filename = "Show.S02E04.1080p.WEB-DL.mkv"),
            ).distinctFileName(),
        )
    }

    @Test
    fun `a source that names no file has no second line`() {
        assertNull(StreamSource(title = "Show S02E04 1080p").distinctFileName())
    }

    @Test
    fun `the quality badge reads the file name too`() {
        // The badge is what the row is scanned by. Fails if quality is read from name and title
        // alone: a provider whose display line is only "👤 48 💾 61 GB" would show the generic
        // film icon beside a 4K release that says so in its file name.
        assertEquals(
            "4K",
            StreamSource(
                name = "Provider",
                title = "👤 48 💾 61.4 GB",
                behaviorHints = StreamBehaviorHints(filename = "Film.2024.2160p.WEB-DL.mkv"),
            ).qualityLabel(),
        )
    }
}
