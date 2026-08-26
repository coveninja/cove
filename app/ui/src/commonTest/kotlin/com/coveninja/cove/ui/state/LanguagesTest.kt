package com.coveninja.cove.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The consolidated language table.
 *
 * Four separate lists used to answer these questions, and the tests that matter here are the
 * ones about them agreeing: a code offered by the settings picker must be nameable by the
 * track menu, and every code either of them offers must expand to the three-letter forms mpv
 * needs. When the lists were separate none of that was true, and nothing said so.
 */
class LanguagesTest {

    // Every language the picker offers has to survive the whole round trip, or a viewer
    // chooses a language the player is never actually asked for. This is the property the
    // four separate tables could not have.
    @Test
    fun `every offered language is nameable and expandable`() {
        LANGUAGES.forEach { language ->
            assertTrue(
                languageName(language.code).isNotBlank(),
                "${language.code} has no English name",
            )
            assertTrue(
                languageNativeName(language.code).isNotBlank(),
                "${language.code} has no native name",
            )
            assertEquals(
                language.code,
                languageAliases(language.code).first(),
                "${language.code} does not lead its own alias list",
            )
        }
    }

    // The table is a fact about languages, so no two rows may claim the same code and no
    // alias may belong to two languages — an alias that did would make the player's language
    // matching depend on which row was written first.
    @Test
    fun `no code or alias is claimed twice`() {
        val codes = LANGUAGES.map { it.code }
        assertEquals(codes.size, codes.distinct().size, "duplicate code in the table")

        val aliases = LANGUAGES.flatMap { it.aliases }
        assertEquals(aliases.size, aliases.distinct().size, "an alias belongs to two languages")
    }

    // Both ISO 639-2 forms, because releases use both: German tracks are tagged "ger" about
    // as often as "deu", and only one of them is a prefix match for "de".
    @Test
    fun `a language with two three-letter codes offers both`() {
        assertTrue(languageAliases("de").containsAll(listOf("de", "deu", "ger")))
        assertTrue(languageAliases("fr").containsAll(listOf("fr", "fra", "fre")))
        assertTrue(languageAliases("zh").containsAll(listOf("zh", "zho", "chi")))
    }

    // A media file names its tracks in 639-2, so the menu has to recognise that form too —
    // otherwise every track in a file would be filed under a language called JPN.
    @Test
    fun `a three-letter code from a file is named like its two-letter form`() {
        assertEquals("Japanese", languageName("jpn"))
        assertEquals("German", languageName("ger"))
        assertEquals("German", languageName("deu"))
        assertEquals("Japanese", languageName("ja"))
    }

    // Shown as itself rather than hidden or guessed at, so a code from a newer build or an
    // unusual release reads as understood-but-unfamiliar instead of as lost data.
    @Test
    fun `an unlisted code is shown as itself`() {
        assertEquals("MI", languageName("mi"))
        assertEquals("MI", languageNativeName("mi"))
        assertEquals(listOf("mi"), languageAliases("mi"))
    }

    // A track with no language at all is honest about it rather than being filed under
    // whichever language happens to sort first.
    @Test
    fun `no language reads as unknown`() {
        assertEquals(UNKNOWN_LANGUAGE, languageName(""))
        assertEquals(UNKNOWN_LANGUAGE, languageName("   "))
    }

    @Test
    fun `codes are normalised before lookup`() {
        assertEquals("Japanese", languageName(" JA "))
        assertEquals("ja", languageAliases(" JA ").first())
    }

    @Test
    fun `a blank language asks for nothing`() {
        assertTrue(languageAliases("").isEmpty())
        assertTrue(languageAliases("   ").isEmpty())
    }

    // Original is a rule rather than a language — "whatever the title was made in" — so it
    // leads the list the settings offer but must never be offered as a subtitle language.
    @Test
    fun `original leads the table and is excluded from the selectable languages`() {
        assertEquals(AUDIO_LANGUAGE_ORIGINAL, LANGUAGES.first().code)
        assertTrue(SELECTABLE_LANGUAGES.none { it.code == AUDIO_LANGUAGE_ORIGINAL })
        assertEquals(LANGUAGES.size - 1, SELECTABLE_LANGUAGES.size)
    }

    // knownLanguageTag is what keeps a subtitle file's release name from being read as a
    // language. The table grew from forty entries to seventy in this change, so the risk of
    // a release-name word colliding with a language code grew with it.
    @Test
    fun `a release name segment is not mistaken for a language`() {
        assertNull(knownLanguageTag("web"))
        assertNull(knownLanguageTag("1080p"))
        assertNull(knownLanguageTag("hdr"))
        assertNull(knownLanguageTag("dvdrip"))
        assertNotNull(knownLanguageTag("en"))
        assertNotNull(knownLanguageTag("eng"))
    }

    // The tokens that turn up in a *subtitle* file's name specifically, which is the only
    // place knownLanguageTag is asked anything. These are the realistic collisions: they sit
    // in exactly the position a language tag does, as in `Movie.2024.en.sdh.srt`.
    @Test
    fun `a subtitle file's own qualifiers are not read as languages`() {
        assertNull(knownLanguageTag("sdh"))
        assertNull(knownLanguageTag("cc"))
        assertNull(knownLanguageTag("forced"))
        assertNull(knownLanguageTag("full"))
        assertNull(knownLanguageTag("signs"))
    }

    // A region subtag is worth keeping — pt-BR and pt-PT are different enough to show — and
    // only the part before it has to name a language.
    @Test
    fun `a region subtag is kept and only its language half is checked`() {
        assertEquals("pt-BR", knownLanguageTag("pt-BR"))
        assertEquals("es-419", knownLanguageTag("es-419"))
        assertNull(knownLanguageTag("web-dl"))
    }
}
