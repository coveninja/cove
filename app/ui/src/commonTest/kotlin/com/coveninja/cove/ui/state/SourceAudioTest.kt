package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.StreamSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceAudioTest {

    private fun source(name: String, size: Long = 0, cached: Boolean = false) =
        StreamSource(name = name, url = "https://example.com/x.mkv", sizeBytes = size, cached = cached)

    // ── Parsing ──────────────────────────────────────────────────────────────

    // No single mutation kills this, and that is worth stating: the length filter
    // and the absence of two-letter entries in the map are two guards against the
    // same mistake, so removing either alone changes nothing. The test pins the
    // behaviour both exist to protect.
    @Test
    fun `short words in a title are not read as language codes`() {
        val hints = parseAudioHints("El Laberinto de el Fauno 1080p it is here")

        assertTrue(hints.languages.isEmpty(), "was: ${hints.languages}")
    }

    // Mutation applied to verify: matched on substrings instead of tokens → test
    // failed, "England" produced English audio.
    @Test
    fun `language names inside other words do not count`() {
        val hints = parseAudioHints("A History of England 2160p")

        assertTrue(hints.languages.isEmpty(), "was: ${hints.languages}")
    }

    // Guards the deliberate omission of "vostfr" from the language map rather
    // than any branch: it marks original audio with French subtitles, so reading
    // it as French audio would demote the very sources an original-audio viewer
    // wants. Mutation applied to verify: added "vostfr" to "fr" → test failed.
    @Test
    fun `vostfr means original audio, not french audio`() {
        val hints = parseAudioHints("Le Samourai 1967 1080p VOSTFR BluRay")

        assertTrue(hints.languages.isEmpty(), "was: ${hints.languages}")
        assertTrue(!hints.multi)
    }

    // Mutation applied to verify: removed the MULTI_MARKERS branch → test failed,
    // dual audio was not recognised.
    @Test
    fun `dual and multi releases are recognised`() {
        assertTrue(parseAudioHints("Show.S01E01.1080p.Dual-Audio.WEB-DL").multi)
        assertTrue(parseAudioHints("Film.2019.MULTI.2160p.UHD").multi)
    }

    // Mutation applied to verify: dropped the three-letter entries, keeping only
    // full names → test failed, JPN and ENG were not recognised.
    @Test
    fun `iso codes and full names both parse`() {
        assertEquals(listOf("ja"), parseAudioHints("Anime.S01E12.1080p.JPN.AAC").languages)
        assertEquals(listOf("en"), parseAudioHints("Show.S01E01.English.1080p").languages)
        assertEquals(
            listOf("ja", "en"),
            parseAudioHints("Anime.S01E12.[JPN+ENG].1080p").languages,
        )
    }

    // ── Scoring ──────────────────────────────────────────────────────────────

    // The case that prompted this: original audio wanted, a dub on offer.
    // Mutation applied to verify: returned 0 for named-but-unwanted languages
    // → test failed, the dub scored equal to the original.
    @Test
    fun `a dub is demoted when the original is wanted`() {
        val japanese = parseAudioHints("Anime.S01E12.JPN.1080p")
        val englishDub = parseAudioHints("Anime.S01E12.ENG.Dubbed.1080p")

        val wantOriginal = { hints: AudioHints ->
            audioScore(hints, AUDIO_LANGUAGE_ORIGINAL, originalLanguage = "ja")
        }

        assertTrue(wantOriginal(japanese) > wantOriginal(englishDub))
        assertEquals(2, wantOriginal(japanese))
        assertEquals(-2, wantOriginal(englishDub))
    }

    // Most releases say nothing, and demoting all of them would rank by noise.
    // Mutation applied to verify: scored unmarked releases negative → test
    // failed, an unmarked source ranked below a known dub.
    @Test
    fun `an unmarked release scores neutral rather than being punished`() {
        val unmarked = parseAudioHints("Show.S01E01.1080p.WEB-DL.x264")
        val dub = parseAudioHints("Show.S01E01.1080p.GER.WEB-DL")

        assertEquals(0, audioScore(unmarked, AUDIO_LANGUAGE_ORIGINAL, "ja"))
        assertTrue(
            audioScore(unmarked, AUDIO_LANGUAGE_ORIGINAL, "ja") >
                audioScore(dub, AUDIO_LANGUAGE_ORIGINAL, "ja"),
        )
    }

    // Mutation applied to verify: ignored the multi flag → test failed, a
    // dual-audio release scored the same as an unmarked one.
    @Test
    fun `dual audio sits between a match and an unmarked release`() {
        val dual = parseAudioHints("Anime.S01E12.Dual-Audio.1080p")

        assertEquals(1, audioScore(dual, AUDIO_LANGUAGE_ORIGINAL, "ja"))
    }

    // Mutation applied to verify: resolved Original against the preference
    // instead of the title's language → test failed, nothing matched.
    @Test
    fun `original resolves against the title's own language`() {
        val french = parseAudioHints("Film.2019.FRENCH.1080p")

        assertEquals(2, audioScore(french, AUDIO_LANGUAGE_ORIGINAL, originalLanguage = "fr"))
        assertEquals(-2, audioScore(french, AUDIO_LANGUAGE_ORIGINAL, originalLanguage = "ja"))
    }

    // Mutation applied to verify: fell back to the original language when no
    // preference was set → test failed, an unset preference started reordering.
    @Test
    fun `no preference means audio does not affect the order`() {
        val japanese = parseAudioHints("Anime.JPN.1080p")
        val english = parseAudioHints("Anime.ENG.1080p")

        assertEquals(0, audioScore(japanese, null, "ja"))
        assertEquals(0, audioScore(english, null, "ja"))
    }

    // ── Ranking ──────────────────────────────────────────────────────────────

    // Mutation applied to verify: ordered by size before audio → test failed, the
    // larger dub came first.
    @Test
    fun `matching audio outranks a bigger dub`() {
        val ranked = rankSources(
            sources = listOf(
                source("Anime.S01E12.ENG.Dubbed.1080p", size = 8_000_000_000),
                source("Anime.S01E12.JPN.1080p", size = 1_000_000_000),
            ),
            preferredAudioLanguage = AUDIO_LANGUAGE_ORIGINAL,
            originalLanguage = "ja",
        )

        assertEquals("Anime.S01E12.JPN.1080p", ranked.first().name)
    }

    // With nothing to choose between on audio, the old order still applies.
    // Mutation applied to verify: removed the cached tiebreak → test failed, the
    // uncached larger file came first.
    @Test
    fun `cached then size still decide when audio is equal`() {
        val ranked = rankSources(
            sources = listOf(
                source("Show.S01E01.1080p.big", size = 9_000_000_000),
                source("Show.S01E01.1080p.cached", size = 2_000_000_000, cached = true),
            ),
            preferredAudioLanguage = AUDIO_LANGUAGE_ORIGINAL,
            originalLanguage = "en",
        )

        assertEquals("Show.S01E01.1080p.cached", ranked.first().name)
    }
}
