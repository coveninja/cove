package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.model.StreamBehaviorHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRankingTest {

    private val gigabyte = 1L shl 30
    private val megabyte = 1L shl 20

    /** Torrentio's shape: release line, file line for a pack, stats line, optional flags. */
    private fun torrentio(
        resolution: String,
        release: String,
        seeders: Int,
        size: Long,
        file: String? = null,
        flags: String? = null,
        hash: String = release.hashCode().toUInt().toString(16).padStart(40, '0').take(40),
    ) = StreamSource(
        name = "Torrentio\n$resolution",
        title = listOfNotNull(release, file, "👤 $seeders 💾 x ⚙️ Example", flags).joinToString("\n"),
        infoHash = hash,
        sizeBytes = size,
        addonName = "Torrentio",
    )

    private val english = SourcePreferences(audioLanguages = listOf("en", "sv"))

    private val compatible = StreamCompatibility(codecLabel = null, support = VideoDecoderSupport.Unknown)

    private fun best(
        sources: List<StreamSource>,
        target: ReleaseTarget? = null,
        preferences: SourcePreferences = english,
    ): StreamSource = rankSources(sources, preferences, target)
        .first { it.assessment.automaticallyPlayable }
        .source

    // ── The cases that prompted this ───────────────────────────────────────

    @Test
    fun `a far smaller file wins when the swarms are about the same`() {
        val small = torrentio("1080p", "Film.2020.1080p.WEB-DL.x264-GRP", seeders = 85, size = 2 * gigabyte)
        val large = torrentio("1080p", "Film.2020.1080p.BluRay.x264-OTHER", seeders = 90, size = 9 * gigabyte)

        assertEquals(small, best(listOf(large, small)))
    }

    @Test
    fun `family guy plays the english release, not the italian one with eng in its name`() {
        val target = ReleaseTarget(titles = listOf("Family Guy"), season = 4, episode = 3)
        val italian = torrentio(
            "1080p",
            "I.Griffin.S04E01-30.WEBMux.1080p.x264.iTA.ENG.AAC.Subs-Maleno85.Sylar.T7ST",
            seeders = 11,
            size = 970 * megabyte,
            file = "I.Griffin.S04E03.Ambizione.cieca.WEBMux.1080p.x264.iTA.ENG.AAC.Subs-Maleno85.Sylar.T7ST.mkv",
            flags = "🇬🇧 / 🇮🇹",
        )
        val webDl = torrentio(
            "1080p",
            "Family Guy (1999) 1080p WEB-DL Season 1 - 22",
            seeders = 47,
            size = 950 * megabyte,
            file = "Season 04/Family Guy (1999) - S04E03 - Blind Ambition [DSNP WEBDL-1080p][AAC 2.0][h264]-PHOENiX.mkv",
        )
        val tiny = torrentio(
            "1080p",
            "Family Guy.S01-S21.1080p.H265-Zero00",
            seeders = 17,
            size = 74 * megabyte,
            file = "Family Guy.S04.1080p.H265-Zero00/Family Guy - S04e03 - Blind Ambition.mp4",
        )
        val portuguese = torrentio(
            "720p",
            "Uma Família da Pesada 1ª a 12ª Temporadas 720p",
            seeders = 2,
            size = 185 * megabyte,
            file = "UFDP.S04.720p.Dub 2005-2006/UFDP.S04E03.720p.Ambição Cega.mkv",
            flags = "🇬🇧 / 🇵🇹",
        )

        val ranked = rankSources(listOf(italian, tiny, portuguese, webDl), english, target)

        assertEquals(webDl, ranked.first().source)
        // Without the translated titles to hand these are names the check cannot place:
        // ranked down, never refused.
        assertEquals(ReleaseMatch.Unrecognized, ranked.first { it.source == italian }.assessment.match)
        assertEquals(ReleaseMatch.Unrecognized, ranked.first { it.source == portuguese }.assessment.match)
        assertTrue(ranked.first { it.source == italian }.assessment.automaticallyPlayable)
    }

    @Test
    fun `a release the check cannot place still plays when it is all there is`() {
        val target = ReleaseTarget(titles = listOf("The Dinosaurs"), season = 1, episode = 1)
        val dubbed = torrentio(
            "1080p",
            "Os Dinossauros S01 2026 WEB-DL 1080p x265 DUAL 5.1",
            seeders = 60,
            size = gigabyte,
            file = "Os Dinossauros S01E01 WEB-DL 1080p x265 DUAL 5.1.mkv",
        )

        val ranked = rankSources(listOf(dubbed), english, target)

        assertEquals(ReleaseMatch.Unrecognized, ranked.single().assessment.match)
        assertTrue(ranked.single().assessment.automaticallyPlayable)
    }

    @Test
    fun `a pack named after the film that holds a different film is caught by its file`() {
        val target = ReleaseTarget(titles = listOf("Fight Club"), year = 1999)
        val wrongFile = torrentio(
            "1080p",
            "Fight Club (1999) RM4K (1080p BluRay x265 10bit EAC3 5.1 Celdra)",
            seeders = 173,
            size = 6 * gigabyte,
            file = "Nirvanna the Band the Show the Movie (2026) (1080p BluRay x265 10bit r00t).mkv",
        )
        val yify = torrentio("1080p", "Fight Club (1999) 1080p BrRip x264 - YIFY", seeders = 687, size = 1_990_000_000)
        val spanish = torrentio(
            "4k HDR",
            "El club de la Lucha [4K UHDreescaldo][2160p][HDR10][AC3 5.1-DTS 5.1 Castellano-DTS-HD 5.1-Ingles+Subs][ES-EN]",
            seeders = 11,
            size = 17 * gigabyte,
            flags = "🇬🇧 / 🇪🇸",
        )
        val inACollection = torrentio(
            "1080p",
            "Imdb top 263 movies hindi english gdrive",
            seeders = 14,
            size = 1_990_000_000,
            file = "Fight.Club.10th.Anniversary.Edition.1999.1080p.BrRip.x264.YIFY.mp4",
            flags = "🇬🇧 / 🇮🇳",
        )

        val ranked = rankSources(listOf(wrongFile, spanish, inACollection, yify), english, target)

        assertEquals(yify, ranked.first().source)
        assertEquals(ReleaseMatch.WrongYear, ranked.first { it.source == wrongFile }.assessment.match)
        assertEquals(ReleaseMatch.Unrecognized, ranked.first { it.source == spanish }.assessment.match)
        assertEquals(ReleaseMatch.Match, ranked.first { it.source == inACollection }.assessment.match)
    }

    @Test
    fun `breaking bad prefers the well seeded english encode over a bigger italian first release`() {
        val target = ReleaseTarget(titles = listOf("Breaking Bad"), season = 1, episode = 1)
        val italianFirst = torrentio(
            "1080p",
            "Breaking.Bad.S01E01-07.1080p.NF.WEB-DL.ITA-ENG.DDP5.1.AV1-G66",
            seeders = 979,
            size = 2_230_000_000,
            file = "Breaking.Bad.S01E01.Questione.di.chimica.1080p.NF.WEB-DL.DDP5.1.AV1-G66.mkv",
            flags = "🇬🇧 / 🇮🇹",
        )
        val psa = torrentio(
            "1080p",
            "Breaking.Bad.SEASON.01.S01.COMPLETE.1080p.10bit.BluRay.6CH.x265.HEVC-PSA",
            seeders = 530,
            size = 973_000_000,
            file = "Breaking.Bad.S01E01.Pilot.1080p.10bit.BluRay.6CH.x265.HEVC-PSA.mkv",
        )
        val fewerPeers = torrentio(
            "1080p",
            "Breaking Bad Complete S01-S05 1080p 10bit BluRay x265 HEVC 6CH-MRN",
            seeders = 60,
            size = 880_000_000,
            file = "Breaking.Bad.S01.1080p.10bit.BluRay.x265.HEVC.6CH-MRN/Breaking.Bad.S01E01.Pilot.1080p.10bit.BluRay.x265.HEVC.6CH-MRN.mkv",
        )

        assertEquals(psa, best(listOf(italianFirst, fewerPeers, psa), target))
    }

    // ── Resolution ─────────────────────────────────────────────────────────

    @Test
    fun `the preferred resolution wins over a bigger swarm one step away`() {
        val fullHd = torrentio("1080p", "Film.2020.1080p.WEB", seeders = 40, size = 3 * gigabyte)
        val hd = torrentio("720p", "Film.2020.720p.WEB", seeders = 200, size = gigabyte)

        assertEquals(fullHd, best(listOf(hd, fullHd)))
        assertEquals(hd, best(listOf(hd, fullHd), preferences = english.copy(resolution = PreferredResolution.Hd)))
    }

    @Test
    fun `a barely seeded release at the preferred resolution loses to a healthy one a step lower`() {
        val thin = torrentio("1080p", "Film.2020.1080p.WEB", seeders = 1, size = 3 * gigabyte)
        val healthy = torrentio("720p", "Film.2020.720p.WEB", seeders = 80, size = gigabyte)

        assertEquals(healthy, best(listOf(thin, healthy)))
    }

    @Test
    fun `without the preferred resolution the fallback goes down before up`() {
        val uhd = torrentio("4k", "Film.2020.2160p.WEB", seeders = 50, size = 15 * gigabyte)
        val hd = torrentio("720p", "Film.2020.720p.WEB", seeders = 50, size = gigabyte)

        assertEquals(hd, best(listOf(uhd, hd)))
    }

    @Test
    fun `quality first takes the biggest healthy file at the preferred resolution`() {
        val remux = torrentio("1080p", "Film.2020.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1", seeders = 51, size = 31 * gigabyte)
        val encode = torrentio("1080p", "Film.2020.1080p.BluRay.x264", seeders = 687, size = 2 * gigabyte)
        val uhdRemux = torrentio("4k HDR", "Film.2020.2160p.UHD.BluRay.REMUX", seeders = 75, size = 74 * gigabyte)

        val quality = english.copy(mode = StreamSelectionMode.Quality)

        assertEquals(remux, best(listOf(encode, uhdRemux, remux), preferences = quality))
    }

    @Test
    fun `most seeded takes the biggest swarm at the preferred resolution`() {
        val busy = torrentio("1080p", "Film.2020.1080p.WEB", seeders = 400, size = 6 * gigabyte)
        val lean = torrentio("1080p", "Film.2020.1080p.WEB.x265", seeders = 120, size = 2 * gigabyte)

        assertEquals(busy, best(listOf(lean, busy), preferences = english.copy(mode = StreamSelectionMode.Seeders)))
        assertEquals(lean, best(listOf(lean, busy)))
    }

    @Test
    fun `the provider label decides the resolution before the release name`() {
        val labelled = torrentio("1080p", "Fight Club (1999) RM4K (1080p BluRay x265)", seeders = 1, size = 1)

        assertEquals(ResolutionTier.FullHd, labelled.resolutionTier())
        assertEquals(ResolutionTier.Uhd, StreamSource(name = "Torrentio\n4k DV | HDR").resolutionTier())
        assertEquals(ResolutionTier.Unknown, StreamSource(name = "Torrentio\nWEB-DL").resolutionTier())
    }

    // ── Health and safety ──────────────────────────────────────────────────

    @Test
    fun `transport stream extensions are not telesync recordings`() {
        val target = ReleaseTarget(listOf("Film"), year = 2020)
        for (source in listOf(
            StreamSource(title = "Film.2020.1080p.ts", url = "https://example.com/video"),
            StreamSource(url = "https://example.com/Film.2020.1080p.ts"),
            StreamSource(behaviorHints = StreamBehaviorHints(filename = "Film.2020.1080p.ts")),
        )) {
            assertFalse(assess(source, english, target).theatricalCopy)
        }
        assertTrue(assess(StreamSource(title = "Film.2020.TS.1080p.mkv"), english, target).theatricalCopy)
    }

    @Test
    fun `a resolution after an episode is not an episode range`() {
        val target = ReleaseTarget(listOf("Show"), season = 1, episode = 2)
        for (release in listOf("Show.S01E01.720p.WEB", "Show.S01E01.1080p.WEB", "Show.S01E01-720p.WEB")) {
            assertEquals(ReleaseMatch.WrongEpisode, StreamSource(title = release).releaseMatch(target), release)
        }
        for (release in listOf("Show.S01E01-03.720p", "Show.S01E01E03.720p", "Show.S01E01-E03.720p")) {
            assertEquals(ReleaseMatch.Match, StreamSource(title = release).releaseMatch(target), release)
        }
    }

    @Test
    fun `translated titles do not override explicit audio tags or imply a cinema recording`() {
        val target = ReleaseTarget(listOf("Camera"), translatedTitles = mapOf("Cam" to "it"), year = 2020)
        val translated = StreamSource(title = "Cam.2020.1080p.ENG.WEB.mkv", sizeBytes = gigabyte)
        val original = translated.copy(title = "Camera.2020.1080p.ENG.WEB.mkv")
        assertFalse(assess(translated, english, target).theatricalCopy)
        assertEquals(assess(original, english, target).score, assess(translated, english, target).score)
    }

    @Test
    fun `a torrent nobody seeds is never played automatically but a debrid zero is`() {
        val dead = torrentio("1080p", "Film.2020.1080p.WEB", seeders = 0, size = gigabyte)
        val debrid = StreamSource(
            name = "[RD+] Torrentio\n1080p",
            title = "Film.2020.1080p.WEB\n👤 0 💾 1 GB",
            url = "https://debrid.example/film.mkv",
            infoHash = "b".repeat(40),
            sizeBytes = gigabyte,
        )

        val ranked = rankSources(listOf(dead, debrid), english)

        assertFalse(ranked.first { it.source == dead }.assessment.automaticallyPlayable)
        assertTrue(ranked.first { it.source == debrid }.assessment.automaticallyPlayable)
        assertEquals(debrid, ranked.first().source)
    }

    @Test
    fun `cinema recordings are not played automatically, unless that is the film's name`() {
        val cam = torrentio("1080p", "New.Film.2026.1080p.HDCAM.x264", seeders = 900, size = 2 * gigabyte)
        val camTheFilm = torrentio("1080p", "Cam.2018.1080p.NF.WEB-DL", seeders = 40, size = 2 * gigabyte)

        assertTrue(rankSources(listOf(cam), english).single().assessment.theatricalCopy)
        assertFalse(
            rankSources(listOf(camTheFilm), english, ReleaseTarget(listOf("Cam"), year = 2018))
                .single().assessment.theatricalCopy,
        )
    }

    @Test
    fun `a file named as a different episode is never played automatically`() {
        val target = ReleaseTarget(titles = listOf("Show"), season = 1, episode = 1)
        val wrong = torrentio("1080p", "Show.S01.1080p.WEB", seeders = 99, size = gigabyte, file = "Show.S02E05.1080p.WEB.mkv")
        val pack = torrentio("1080p", "Show.S01E01-07.1080p.WEB", seeders = 99, size = gigabyte)

        val wrongRanked = rankSources(listOf(wrong), english, target).single().assessment
        assertEquals(ReleaseMatch.WrongEpisode, wrongRanked.match)
        assertFalse(wrongRanked.automaticallyPlayable)
        assertEquals(ReleaseMatch.Match, rankSources(listOf(pack), english, target).single().assessment.match)
    }

    @Test
    fun `a film from another year is a mismatch`() {
        val target = ReleaseTarget(titles = listOf("The Lion King"), year = 2019)
        val original = torrentio("1080p", "The.Lion.King.1994.1080p.BluRay", seeders = 300, size = 2 * gigabyte)
        val remake = torrentio("1080p", "The.Lion.King.2019.1080p.BluRay", seeders = 100, size = 2 * gigabyte)

        assertEquals(remake, best(listOf(original, remake), target))
    }

    @Test
    fun `a release name that says nothing is allowed but gives way to one that matches`() {
        val target = ReleaseTarget(titles = listOf("Film"), year = 2020)
        val bare = StreamSource(name = "Some Addon\n1080p", url = "https://example.com/a.mkv", sizeBytes = 2 * gigabyte)
        val named = torrentio("1080p", "Film.2020.1080p.WEB", seeders = 40, size = 2 * gigabyte)

        val ranked = rankSources(listOf(bare, named), english, target)

        assertEquals(ReleaseMatch.Unknown, ranked.first { it.source == bare }.assessment.match)
        assertTrue(ranked.first { it.source == bare }.assessment.automaticallyPlayable)
        assertEquals(named, ranked.first().source)
    }

    @Test
    fun `one torrent offered by two addons is listed once`() {
        val hash = "c".repeat(40)
        val fromTorrentio = torrentio("1080p", "Film.2020.1080p.WEB", seeders = 40, size = 2 * gigabyte, hash = hash)
        val fromOther = StreamSource(name = "Other", title = "Film.2020.1080p.WEB", infoHash = hash)

        assertEquals(listOf(fromTorrentio), rankSources(listOf(fromOther, fromTorrentio), english).map { it.source })
    }

    // ── Title matching ─────────────────────────────────────────────────────

    @Test
    fun `titles match release names across punctuation, articles and abbreviations`() {
        fun matches(title: String, release: String) = titleMatches(matchTokens(title), matchTokens(release))

        assertTrue(matches("Spider-Man: Across the Spider-Verse", "Spider.Man.Across.The.Spider.Verse.2023.1080p"))
        assertTrue(matches("Spider-Man", "SpiderMan.2002.1080p"))
        assertTrue(matches("Schindler's List", "Schindlers.List.1993.1080p"))
        assertTrue(matches("Marvel's Agents of S.H.I.E.L.D.", "Agents.of.SHIELD.S01E01.1080p"))
        assertTrue(matches("The Office", "The.Office.US.S05E03.720p"))
        assertTrue(matches("Amélie", "Amelie.2001.1080p.BluRay"))
        assertTrue(matches("Rocky II", "Rocky.2.1979.1080p"))
        assertTrue(matches("Mission: Impossible – Dead Reckoning Part One", "Mission.Impossible.7.Dead.Reckoning.2023"))
        assertTrue(matches("The Color Purple", "The.Colour.Purple.2023.1080p"))
        assertTrue(matches("Interstellar", "Intersteller.2014.1080p"))
        assertFalse(matches("Breaking Bad", "Breaking.Bed.S01E01"))
        assertFalse(matches("Family Guy", "I.Griffin.S04E03.1080p"))
        assertFalse(matches("The Dinosaurs", "Os Dinossauros S01 2026 WEB-DL 1080p"))
        assertFalse(matches("Up", "Upgrade.2018.1080p"))
    }

    // ── Settings ───────────────────────────────────────────────────────────

    @Test
    fun `stored values that are not understood fall back to the defaults`() {
        assertEquals(StreamSelectionMode.Quality, StreamSelectionMode.from("quality"))
        assertEquals(StreamSelectionMode.Seeders, StreamSelectionMode.from("seeders"))
        assertEquals(StreamSelectionMode.Balanced, StreamSelectionMode.from(null))
        assertEquals(StreamSelectionMode.Balanced, StreamSelectionMode.from("best"))
        assertEquals(PreferredResolution.Hd, PreferredResolution.from("720p"))
        assertEquals(PreferredResolution.FullHd, PreferredResolution.from(null))
        assertEquals(PreferredResolution.FullHd, PreferredResolution.from("8k"))
    }

    @Test
    fun `a singular seed in a title is not a peer count`() {
        assertNull(parseSeederCount("The Bad Seed 2018 1080p"))
        assertEquals(109, parseSeederCount("Seeders: 109"))
    }

    // The next episode from the pack that just played starts with its metadata cached and its
    // peers already connected, and keeps the same release.
    @Test
    fun `the season pack that played the last episode is preferred for the next`() {
        val target = ReleaseTarget(titles = listOf("Show"), season = 1, episode = 2)
        val pack = torrentio("1080p", "Show.S01.1080p.WEB", seeders = 60, size = gigabyte, hash = "a".repeat(40))
        val single = torrentio("1080p", "Show.S01E02.1080p.WEB", seeders = 90, size = gigabyte, hash = "b".repeat(40))

        assertEquals(single, best(listOf(pack, single), target))
        assertEquals(pack, best(listOf(pack, single), target, english.copy(continuityHash = "A".repeat(40))))
    }

    // ── Translated titles ──────────────────────────────────────────────────

    // The Italian release carries English audio too; being named in Italian does not make it
    // another show.
    @Test
    fun `a release named with a translated title is the same show`() {
        val target = ReleaseTarget(
            titles = listOf("Conversations with a Killer: The Ted Bundy Tapes"),
            translatedTitles = mapOf("Conversazioni con un killer: Il caso Bundy" to "it"),
            season = 1,
            episode = 1,
        )
        val italian = torrentio(
            "1080p",
            "Conversazioni.Con.Un.Killer.S01E01-03.DLMux.1080p.E-AC3-AC3.ITA.ENG.SUBS",
            seeders = 1,
            size = 3 * gigabyte,
            flags = "🇬🇧 / 🇮🇹",
        )
        val englishHalf = torrentio("1080p", "Conversations.With.A.Killer.S01E01.1080p.WEB", seeders = 3, size = gigabyte)
        val otherHalf = torrentio("1080p", "The.Ted.Bundy.Tapes.2018.1080p.EP01.x264", seeders = 3, size = gigabyte)

        val ranked = rankSources(listOf(italian, englishHalf, otherHalf), english, target)

        assertTrue(ranked.all { it.assessment.match == ReleaseMatch.Match }, "was: ${ranked.map { it.assessment.match }}")
        assertTrue(ranked.first { it.source == italian }.assessment.automaticallyPlayable)
    }

    // Recognised as Family Guy now, but its name still says Italian, Spanish or Portuguese, and
    // that is what decides where it ranks.
    @Test
    fun `translated releases rank by their language rather than being rejected`() {
        val target = ReleaseTarget(
            titles = listOf("Family Guy"),
            translatedTitles = mapOf("I Griffin" to "it", "Padre de familia" to "es"),
            season = 4,
            episode = 3,
        )
        val english = torrentio("1080p", "Family.Guy.S04E03.1080p.WEB", seeders = 20, size = gigabyte)
        val spanish = torrentio("1080p", "Padre.De.Familia.S04E03.1080p", seeders = 300, size = gigabyte)
        val italian = torrentio("1080p", "I.Griffin.S04E03.1080p.WEBMux.iTA.ENG", seeders = 30, size = gigabyte)

        val ranked = rankSources(listOf(spanish, italian, english), this.english, target)

        assertEquals(english, ranked.first().source)
        assertTrue(ranked.all { it.assessment.match == ReleaseMatch.Match })
        // The Spanish one names no language of its own; its title says Spanish, which is a dub.
        assertEquals(spanish, ranked.last().source)
    }

    // ── Picker order ───────────────────────────────────────────────────────

    @Test
    fun `the picker lists by resolution and opens on what watch would play`() {
        val uhd = torrentio("4k", "Film.2020.2160p.WEB", seeders = 50, size = 15 * gigabyte)
        val fullHd = torrentio("1080p", "Film.2020.1080p.WEB", seeders = 50, size = 2 * gigabyte)
        val hd = torrentio("720p", "Film.2020.720p.WEB", seeders = 50, size = gigabyte)
        val sd = torrentio("480p", "Film.2020.480p.WEB", seeders = 50, size = gigabyte / 2)
        val ranked = rankSources(listOf(sd, hd, uhd, fullHd), english).map { StreamChoice(it.source, compatible, it.assessment) }

        assertEquals(listOf(uhd, fullHd, hd, sd), ranked.byResolution().map { it.source })
        assertEquals(fullHd, ranked.bestForPicker()?.source)
    }

    // Uploaders shorten titles; a name the rules do not follow only costs a little ranking.
    @Test
    fun `initials and opening words name the title`() {
        val target = ReleaseTarget(titles = listOf("How I Met Your Mother"), season = 1, episode = 1)
        fun matchOf(release: String) = rankSources(
            listOf(torrentio("1080p", release, seeders = 10, size = gigabyte)),
            english,
            target,
        ).single().assessment.match

        assertEquals(ReleaseMatch.Match, matchOf("HIMYM.S01E01.1080p.WEB"))
        assertEquals(ReleaseMatch.Match, matchOf("How.I.S01E01.1080p"))
        assertEquals(
            ReleaseMatch.Match,
            rankSources(
                listOf(torrentio("1080p", "F.G.S04E03.1080p", seeders = 10, size = gigabyte)),
                english,
                ReleaseTarget(listOf("Family Guy"), season = 4, episode = 3),
            ).single().assessment.match,
        )
    }

    // Not finding the title is not evidence; a stated year or episode is.
    @Test
    fun `only a named episode is refused, a named year is ranked down`() {
        val film = ReleaseTarget(titles = listOf("Film"), year = 2019)
        val remake = rankSources(listOf(torrentio("1080p", "Film.1994.1080p", seeders = 900, size = gigabyte)), english, film)
            .single().assessment

        assertEquals(ReleaseMatch.WrongYear, remake.match)
        assertTrue(remake.automaticallyPlayable)
    }
}
