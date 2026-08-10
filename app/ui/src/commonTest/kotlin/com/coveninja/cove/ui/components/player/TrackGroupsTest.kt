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

    // The case that prompted this: one release carrying Latin American and
    // European Spanish, which a flat list shows as two identical-looking rows.
    // Mutation applied to verify: dropped the substringBefore('-') so the region
    // stayed in the key → test failed with two Spanish groups.
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
    // Mutation applied to verify: sorted only by label → test failed, Unknown
    // came before Vietnamese.
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
    // Mutation applied to verify: sorted group members by title → test failed,
    // "Signs & Songs" came before "Full".
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

    // Mutation applied to verify: mapped only two-letter codes → test failed,
    // the three-letter forms fell through to their uppercased code.
    @Test
    fun `two and three letter codes name the same language`() {
        val groups = groupTracksByLanguage(listOf(track(1, "jpn"), track(2, "fre")))

        assertEquals(listOf("French", "Japanese"), groups.map { it.languageLabel })
    }

    // Hiding an unrecognised code behind a guess would be worse than showing it.
    // Mutation applied to verify: made the else branch return "Unknown" → test
    // failed, the code was lost and it sorted to the end.
    @Test
    fun `an unrecognised code is shown as itself`() {
        val groups = groupTracksByLanguage(listOf(track(1, "qq")))

        assertEquals("QQ", groups.single().languageLabel)
    }

    // Mutation applied to verify: returned the title unconditionally → test
    // failed with an empty label for the untitled track.
    @Test
    fun `a track with no title falls back to something identifying`() {
        assertEquals("Track 3", track(3, "en").detailLabel())
        assertEquals("pt-BR", track(4, "pt-BR").detailLabel())
        assertEquals("Brazil", track(5, "pt-BR", "Brazil").detailLabel())
    }
}
