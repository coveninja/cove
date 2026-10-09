package com.coveninja.cove.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceAudioTest {

    // ── Parsing ──────────────────────────────────────────────────────────────

    @Test
    fun `short words in a title are not read as language codes`() {
        val hints = parseAudioHints("El Laberinto de el Fauno 1080p it is here")

        assertTrue(hints.languages.isEmpty(), "was: ${hints.languages}")
    }

    @Test
    fun `language names inside other words do not count`() {
        val hints = parseAudioHints("A History of England 2160p")

        assertTrue(hints.languages.isEmpty(), "was: ${hints.languages}")
    }

    // Guards the deliberate omission of "vostfr" from the language map rather
    // than any branch: it marks original audio with French subtitles, so reading
    // it as French audio would demote the very sources an original-audio viewer
    // wants.
    @Test
    fun `vostfr means original audio, not french audio`() {
        val hints = parseAudioHints("Le Samourai 1967 1080p VOSTFR BluRay")

        assertTrue(hints.languages.isEmpty(), "was: ${hints.languages}")
        assertTrue(!hints.multi)
    }

    @Test
    fun `dual and multi releases are recognised`() {
        assertTrue(parseAudioHints("Show.S01E01.1080p.Dual-Audio.WEB-DL").multi)
        assertTrue(parseAudioHints("Film.2019.MULTI.2160p.UHD").multi)
        assertTrue(parseAudioHints("Fight Club (1999) - 1080p - BD AV1 Opus MULTi3").multi)
    }

    @Test
    fun `multiple subtitles say nothing about the audio`() {
        assertFalse(parseAudioHints("Breaking Bad S01 1080p\n👤 35\nMulti Subs / 🇬🇧").multi)
        assertFalse(parseAudioHints("Film.2019.1080p.MultiSubs").multi)
    }

    @Test
    fun `iso codes and full names both parse`() {
        assertEquals(listOf("ja"), parseAudioHints("Anime.S01E12.1080p.JPN.AAC").languages)
        assertEquals(listOf("en"), parseAudioHints("Show.S01E01.English.1080p").languages)
        assertEquals(
            listOf("ja", "en"),
            parseAudioHints("Anime.S01E12.[JPN+ENG].1080p").languages,
        )
    }

    // A language tag at the end of the release line used to fuse with the next line.
    @Test
    fun `a tag at the end of a line is still read`() {
        assertEquals(listOf("it"), parseAudioHints("Film.2019.1080p.ITA\n👤 12 💾 2 GB").languages)
    }

    @Test
    fun `provider flags name the languages`() {
        val hints = parseAudioHints("I.Griffin.S04E03.1080p\n👤 11 💾 970 MB ⚙️ 1337x\n🇬🇧 / 🇮🇹")

        assertEquals(setOf("en", "it"), hints.languages.toSet())
    }

    @Test
    fun `brazilian releases are recognised however they spell it`() {
        assertEquals(listOf("pt"), parseAudioHints("Breaking Bad Full 1080p PT-BR").languages)
        assertEquals(listOf("pt"), parseAudioHints("Filme.2020.1080p.DUBLADO").languages)
    }

    // ── Scoring ──────────────────────────────────────────────────────────────

    @Test
    fun `a dub is pushed well down and a wanted language nudged up`() {
        val wanted = listOf("ja")

        assertTrue(languageScore(parseAudioHints("Anime.S01E12.JPN.1080p"), wanted) > 0.0)
        assertTrue(languageScore(parseAudioHints("Anime.S01E12.ENG.Dubbed.1080p"), wanted) <= -2.0)
    }

    // Most releases say nothing, and demoting all of them would rank by noise.
    @Test
    fun `an unmarked release scores neutral rather than being punished`() {
        assertEquals(0.0, languageScore(parseAudioHints("Show.S01E01.1080p.WEB-DL.x264"), listOf("ja")))
    }

    @Test
    fun `a release naming a wanted language beside another gives up only a little`() {
        val mixed = languageScore(parseAudioHints("Film.2019.1080p.ITA.ENG"), listOf("en", "sv"))
        val dub = languageScore(parseAudioHints("Film.2019.1080p.ITA"), listOf("en", "sv"))

        assertTrue(mixed < 0.0)
        assertTrue(mixed > dub)
    }

    @Test
    fun `any wanted language counts, not just the first`() {
        assertTrue(languageScore(parseAudioHints("Film.2019.1080p.SWEDISH"), listOf("en", "sv")) > 0.0)
    }

    @Test
    fun `no preference means audio does not affect the order`() {
        assertEquals(0.0, languageScore(parseAudioHints("Anime.JPN.1080p"), emptyList()))
    }
}
