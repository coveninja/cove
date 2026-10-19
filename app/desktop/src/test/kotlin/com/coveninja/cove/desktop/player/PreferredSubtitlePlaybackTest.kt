package com.coveninja.cove.desktop.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.PlaybackRepository
import com.coveninja.cove.shared.data.SettingsRepository
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.fixture.FixtureAppGraph
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.MediaTimestamps
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.model.SubtitleSource
import com.coveninja.cove.shared.model.TorrentProgress
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.state.PlaybackPhase
import com.coveninja.cove.ui.state.PlaybackSession
import com.coveninja.cove.ui.state.PlaybackStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.coveninja.cove.ui.model.MediaType as UiMediaType

// The whole path the reported problem took — session, desktop host and the real libmpv — with
// a generated picture in place of a stream. An addon lists the viewer's languages after a dozen
// others, as OpenSubtitles listed Swedish for every title checked, and one of their links is dead.
//
// Skips rather than fails where libmpv is missing or cannot generate the test picture, as the
// software render smoke test does.
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class PreferredSubtitlePlaybackTest {

    @Test
    fun `the viewer's first language is loaded and shown from an addon that lists it last`() =
        playing(AppSettings(subtitlesEnabled = true, subtitleLanguages = listOf("sv", "eng"))) { status ->
            val external = status.subtitleTracks.filter { it.external }
            assertEquals(MAX_ADDED_SUBTITLES - 1, external.size, "the dead link is the only one missing: $external")
            assertEquals("swe", status.subtitleTracks.single { it.id == status.selectedSubtitleId }.language)
            assertNull(status.error, "a subtitle that could not be fetched is not a playback error")
        }

    @Test
    fun `limited to the viewer's languages, nothing else is loaded`() =
        playing(
            AppSettings(subtitlesEnabled = true, subtitleLanguages = listOf("sv", "eng"), subtitleLanguagesOnly = true),
        ) { status ->
            assertEquals(setOf("swe", "eng"), status.subtitleTracks.mapNotNull { it.language }.toSet())
            assertEquals("swe", status.subtitleTracks.single { it.id == status.selectedSubtitleId }.language)
        }

    private fun playing(settings: AppSettings, check: (PlaybackStatus) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("cove-subtitles-")
        val host = MpvVideoPlayerHost()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var scene: ImageComposeScene? = null
        try {
            val session = PlaybackSession(graph(settings, subtitles(dir)), scope, host)
            // Opening releases whatever handle there was; the player's surface mounting
            // afterwards is what creates the next one, as the fullscreen player's does.
            session.open(movie())
            scene = ImageComposeScene(width = 320, height = 180) { host.Surface(Modifier.fillMaxSize()) }
                .also { it.render() }
            val phase = withTimeoutOrNull(10_000) {
                while (session.phase !is PlaybackPhase.Choosing && session.phase !is PlaybackPhase.Playing) {
                    delay(20)
                }
                session.phase
            }
            if (phase is PlaybackPhase.Choosing) session.choose(phase.sources.single())

            val opened = withTimeoutOrNull(15_000) { host.status.first { it.hasMedia || it.error != null } }
                ?: error("the generated picture neither opened nor failed: ${host.status.value}")
            if (!opened.hasMedia) {
                println("SKIP: libmpv could not open the generated picture: ${opened.error}")
                return@runBlocking
            }
            // The dead link is refused, offered again a second later and refused again; by then
            // everything else is in and the choice made.
            val settled = withTimeoutOrNull(15_000) {
                host.status.first { status ->
                    val external = status.subtitleTracks.count { it.external }
                    external >= expectedTracks(settings) && status.selectedSubtitleId != null
                }
            }
            delay(1_500)
            check(settled?.let { host.status.value } ?: error("subtitles never settled: ${host.status.value}"))
            session.close()
        } finally {
            scope.cancel()
            host.dispose()
            scene?.close()
            dir.deleteRecursively()
        }
    }

    private fun expectedTracks(settings: AppSettings): Int =
        if (settings.subtitleLanguagesOnly) 2 else MAX_ADDED_SUBTITLES - 1

    /** The order OpenSubtitles answered in: a dozen other languages, then Swedish and English. */
    private fun subtitles(dir: Path): List<SubtitleSource> {
        fun file(lang: String, index: Int): String =
            dir.resolve("$index.$lang.srt").apply { writeText("1\n00:00:00,000 --> 00:10:00,000\n$lang $index\n") }
                .toString()
        val others = listOf("ara", "nld", "ell", "fin", "srp", "ind", "pob", "pob", "spa", "tur", "hrv", "dan")
        return others.mapIndexed { index, lang -> SubtitleSource("${10_000 + index}", file(lang, index), lang) } +
            SubtitleSource("20000", dir.resolve("gone.swe.srt").toString(), "swe") +
            SubtitleSource("20001", file("swe", 20), "swe") +
            SubtitleSource("20002", file("eng", 21), "eng")
    }

    private fun graph(settings: AppSettings, subtitles: List<SubtitleSource>): AppGraph {
        val fixtures = FixtureAppGraph()
        return AppGraph(
            content = fixtures.content,
            library = fixtures.library,
            settings = object : SettingsRepository {
                override val settings: StateFlow<SettingsState> = MutableStateFlow(SettingsState.Ready(settings))
                override suspend fun update(settings: AppSettings) = Unit
            },
            playback = GeneratedPlayback(subtitles),
            addons = fixtures.addons,
        )
    }

    private class GeneratedPlayback(private val subtitles: List<SubtitleSource>) : PlaybackRepository {
        override suspend fun streams(
            tmdbId: Int,
            type: MediaType,
            season: Int?,
            episode: Int?,
            refresh: Boolean,
        ) = listOf(StreamSource(name = "Generated", title = "Test Film 2024 1080p", url = PICTURE))

        override fun playUrl(source: StreamSource, season: Int?, episode: Int?): String = source.url!!
        override suspend fun timestamps(tmdbId: Int, season: Int?, episode: Int?) = MediaTimestamps.None
        override suspend fun subtitles(tmdbId: Int, type: MediaType, season: Int?, episode: Int?) = subtitles
        override suspend fun aliveUrls(urls: List<String>): Set<String> = urls.toSet()
        override suspend fun torrentProgress(hash: String): TorrentProgress? = null
    }

    private fun movie() = Media(
        id = "Movie:990001",
        tmdbId = 990_001,
        title = "Test Film",
        name = null,
        overview = null,
        released = "2024-01-01",
        firstAirDate = null,
        posterUrl = null,
        logoUrl = null,
        backdropUrl = null,
        rating = null,
        type = UiMediaType.Movie,
        popularity = null,
        adult = null,
        originalLanguage = "en",
    )

    private companion object {
        const val PICTURE = "av://lavfi:testsrc=size=320x180:rate=10:duration=600"

        /** Twelve go into the player, and the dead Swedish link is one of them. */
        const val MAX_ADDED_SUBTITLES = 12
    }
}
