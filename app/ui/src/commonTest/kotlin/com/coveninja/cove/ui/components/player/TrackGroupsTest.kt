package com.coveninja.cove.ui.components.player

import com.coveninja.cove.ui.state.MediaTrack
import com.coveninja.cove.ui.state.TrackKind
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackGroupsTest {

    private fun track(
        id: Int,
        language: String?,
        title: String = "",
    ) = MediaTrack(
        id = id,
        kind = TrackKind.Subtitle,
        title = title,
        language = language,
        selected = false,
    )

    private fun audio(
        id: Int,
        language: String?,
        title: String = "",
        codec: String = "",
        channels: String = "",
    ) = MediaTrack(
        id = id,
        kind = TrackKind.Audio,
        title = title,
        language = language,
        selected = false,
        codec = codec,
        channels = channels,
    )

    // The case that prompted this: one release carrying Latin American and
    // European Spanish, which a flat list shows as two identical-looking rows.
    @Test
    fun `regional variants collapse into one language`() {
        val groups = groupTracksByLanguage(
            listOf(
                track(8, "es-419", "Latin America"),
                track(9, "es-ES", "Spain"),
            ),
        )

        assertEquals(1, groups.size, "was: $groups")
        assertEquals("Spanish", groups.single().languageLabel)
        assertEquals(listOf("Latin America", "Spain"), groups.single().tracks.map { it.detailLabel() })
    }

    // Vietnamese is here on purpose: it sorts after "Unknown" alphabetically, so
    // plain alphabetical ordering and the unknown-last rule disagree. An earlier
    // version used Russian, where both orderings happen to agree, and deleting
    // the rule changed nothing.
    @Test
    fun `unknown languages sort last`() {
        val groups = groupTracksByLanguage(
            listOf(
                track(1, null),
                track(2, "vi"),
                track(3, "en"),
                track(4, ""),
            ),
        )

        assertEquals(listOf("English", "Vietnamese", "Unknown"), groups.map { it.languageLabel })
    }

    // A file lists its tracks in a deliberate order; reordering within a language
    // would put the full subtitle below the signs-only one for no reason.
    @Test
    fun `order within a language follows the file`() {
        val groups = groupTracksByLanguage(
            listOf(
                track(1, "en", "Full"),
                track(2, "en", "Signs & Songs"),
                track(3, "en", "SDH"),
            ),
        )

        assertEquals(
            listOf("Full", "Signs & Songs", "SDH"),
            groups.single().tracks.map { it.detailLabel() },
        )
    }

    @Test
    fun `two and three letter codes name the same language`() {
        val groups = groupTracksByLanguage(listOf(track(1, "jpn"), track(2, "fre")))

        assertEquals(listOf("French", "Japanese"), groups.map { it.languageLabel })
    }

    // Hiding an unrecognised code behind a guess would be worse than showing it.
    @Test
    fun `an unrecognised code is shown as itself`() {
        val groups = groupTracksByLanguage(listOf(track(1, "qq")))

        assertEquals("QQ", groups.single().languageLabel)
    }

    @Test
    fun `a track with no title falls back to something identifying`() {
        assertEquals("Track 3", track(3, "en").detailLabel())
        assertEquals("pt-BR", track(4, "pt-BR").detailLabel())
        assertEquals("Brazil", track(5, "pt-BR", "Brazil").detailLabel())
    }

    // The case the codec and channel columns exist for: two untitled English audio tracks,
    // which the menu used to offer as "Track 1" and "Track 2" — a choice made by guessing.
    @Test
    fun `untitled audio tracks are told apart by codec and layout`() {
        val surround = audio(1, "eng", codec = "E-AC-3", channels = "5.1(side)")
        val stereo = audio(2, "eng", codec = "AAC", channels = "stereo")

        assertEquals("E-AC-3 5.1", surround.detailLabel())
        assertEquals("AAC stereo", stereo.detailLabel())
    }

    // A title is still the most useful thing when there is one; the technical detail goes
    // after it rather than replacing it.
    @Test
    fun `a titled track keeps its title and gains the detail`() {
        assertEquals(
            "Director's commentary · AAC stereo",
            audio(1, "eng", "Director's commentary", "AAC", "stereo").detailLabel(),
        )
    }

    // FFmpeg spells layouts with a speaker-placement qualifier nobody picks a track by, and
    // "unknown" is not a layout at all — printing either would be noise beside the codec.
    @Test
    fun `layout noise is not shown`() {
        assertEquals("AAC 7.1", audio(1, "eng", codec = "AAC", channels = "7.1(wide)").detailLabel())
        assertEquals("AAC", audio(2, "eng", codec = "AAC", channels = "unknown").detailLabel())
        assertEquals("AAC", audio(3, "eng", codec = "AAC", channels = "").detailLabel())
    }

    // These are the facts a release puts in flags rather than in the title, and picking wrong
    // between a forced track and a full one means missing every line of dialogue in the film.
    @Test
    fun `the flags a release does not write in the title become badges`() {
        assertEquals(listOf("Forced"), track(1, "en").copy(forced = true).badges())
        assertEquals(listOf("SDH"), track(2, "en").copy(hearingImpaired = true).badges())
        assertEquals(
            listOf("Audio description"),
            track(3, "en").copy(visualImpaired = true).badges(),
        )
        assertEquals(listOf("Add-on"), track(4, "en").copy(external = true).badges())
        assertEquals(listOf("Default"), track(5, "en").copy(isDefault = true).badges())
    }

    // Ordered by how much each changes what the viewer would get, so a track that is several
    // things at once still leads with the one worth reading first.
    @Test
    fun `several badges keep a stable, meaningful order`() {
        val track = track(1, "en").copy(
            forced = true,
            hearingImpaired = true,
            external = true,
            isDefault = true,
        )

        assertEquals(listOf("Forced", "SDH", "Add-on", "Default"), track.badges())
    }

    // Worth saying because it explains a control that appears broken: none of the appearance
    // settings reach a bitmap subtitle, since mpv can move and scale a picture but not
    // restyle it.
    @Test
    fun `a bitmap subtitle says so`() {
        assertEquals(listOf("Image"), track(1, "en").copy(bitmap = true).badges())
    }

    @Test
    fun `an ordinary track carries no badges`() {
        assertEquals(emptyList(), track(1, "en", "Full").badges())
    }
}
