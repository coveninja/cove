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

    // ── Selection mode ───────────────────────────────────────────────────────

    /** A torrent, whose peer count only ever arrives inside the display text. */
    private fun torrent(name: String, size: Long, seeders: Int) = StreamSource(
        name = name,
        title = "$name\n👤 $seeders 💾 whatever ⚙️ Example",
        infoHash = "0".repeat(40),
        sizeBytes = size,
    )

    // The case that started this: a 4K remux nobody is seeding used to lead the
    // list purely by being the biggest file.
    //
    // The whole order is asserted rather than just the winner, because a list
    // this shape is the only way to tell the peer count apart from "prefer the
    // smaller file" — those two agree at the top and disagree in the middle,
    // where the 17 GB release outranks two leaner ones on seeders alone.
    // Mutation applied to verify: dropped the swarmRank term from Balanced →
    // the order came back sorted purely by ascending size.
    @Test
    fun `balanced ranks by peers, not by size`() {
        val ranked = rankSources(
            sources = listOf(
                torrent("2160p.REMUX", size = 54_300_000_000, seeders = 7),
                torrent("1080p.WEB", size = 3_200_000_000, seeders = 220),
                torrent("720p.WEB", size = 1_100_000_000, seeders = 12),
                torrent("2160p.WEB", size = 17_000_000_000, seeders = 31),
                torrent("1080p.HDTV", size = 2_100_000_000, seeders = 48),
            ),
            mode = StreamSelectionMode.Balanced,
        )

        assertEquals(
            listOf("1080p.WEB", "1080p.HDTV", "2160p.WEB", "720p.WEB", "2160p.REMUX"),
            ranked.map { it.name },
        )
    }

    // The same list under the mode that does want the biggest file, so the two
    // settings are demonstrably not the same code path. Mutation applied to
    // verify: made Quality fall through to the Balanced comparator → the 1080p
    // came first.
    @Test
    fun `quality first still leads with the biggest file`() {
        val ranked = rankSources(
            sources = listOf(
                torrent("Show.S01E01.1080p.WEB", size = 3_200_000_000, seeders = 220),
                torrent("Show.S01E01.2160p.REMUX", size = 54_000_000_000, seeders = 7),
            ),
            mode = StreamSelectionMode.Quality,
        )

        assertEquals("Show.S01E01.2160p.REMUX", ranked.first().name)
    }

    // Balanced prefers the leaner file only where it weighed a swarm against it.
    // Debrid and direct links report no peers at all, and ordering those
    // smallest-first would hand a debrid viewer the worst copy every time.
    // Mutation applied to verify: removed the `seederCount() == null` branch
    // from balancedSizeKey → the 2 GB link came first.
    @Test
    fun `balanced does not turn a list of direct links upside down`() {
        val ranked = rankSources(
            sources = listOf(
                source("Show.S01E01.1080p.small", size = 2_000_000_000),
                source("Show.S01E01.2160p.large", size = 9_000_000_000),
            ),
            mode = StreamSelectionMode.Balanced,
        )

        assertEquals("Show.S01E01.2160p.large", ranked.first().name)
    }

    // Providers that report no size at all must not win the leaner-file
    // tie-break by being zero. Mutation applied to verify: keyed an unknown size
    // as 0 rather than MAX_VALUE → the sizeless release came first.
    @Test
    fun `an unknown size does not count as the leanest file`() {
        val ranked = rankSources(
            sources = listOf(
                torrent("Show.S01E01.sizeless", size = 0, seeders = 40),
                torrent("Show.S01E01.known", size = 3_000_000_000, seeders = 40),
            ),
            mode = StreamSelectionMode.Balanced,
        )

        assertEquals("Show.S01E01.known", ranked.first().name)
    }

    // The stored setting is a free-form string, and an unreadable one must land
    // on the same default the settings screen shows. Mutation applied to verify:
    // made the else branch return Quality → the null and garbage cases failed.
    @Test
    fun `an unknown stored mode falls back to balanced`() {
        assertEquals(StreamSelectionMode.Quality, StreamSelectionMode.from("quality"))
        assertEquals(StreamSelectionMode.Seeders, StreamSelectionMode.from("seeders"))
        assertEquals(StreamSelectionMode.Balanced, StreamSelectionMode.from(null))
        assertEquals(StreamSelectionMode.Balanced, StreamSelectionMode.from("best"))
    }
}
