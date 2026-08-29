package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the player UI needs to know about the video, independent of how it is
 * decoded. Mirrors the desktop PlayerSnapshot; :ui is a Kotlin Multiplatform
 * module and cannot see :desktop, where libmpv lives.
 */
/**
 * How the picture is mapped onto the window.
 *
 * [Fit] is the default and the only one that shows the whole frame: the others
 * either crop or distort, which is a choice the viewer makes, never one made for
 * them.
 */
enum class VideoScaling(val label: String, val description: String) {
    Fit("Fit", "Whole picture, bars if the shapes differ"),
    Fill("Fill", "Fills the window, crops the overflow"),
    Zoom("Zoom", "Slightly enlarged"),
    Stretch("Stretch", "Fills the window, distorts the picture"),
}

/**
 * One selectable audio or subtitle track reported by the player.
 *
 * Everything past [selected] is what the track *is*, as opposed to how to ask for it, and all
 * of it is defaulted: a host that cannot describe its tracks that well still constructs one,
 * and the UI shows nothing rather than something wrong. The names follow mpv's `track-list`,
 * which is where they all come from.
 */
data class MediaTrack(
    val id: Int,
    val kind: TrackKind,
    val title: String,
    val language: String?,
    val selected: Boolean,
    /** A readable codec name — mpv's `codec-desc`, falling back to the bare `codec`. */
    val codec: String = "",
    /** The channel layout as a person names it: "stereo", "5.1". Audio only. */
    val channels: String = "",
    val sampleRateHz: Int = 0,
    /** The track the container marks as its default. */
    val isDefault: Boolean = false,
    /** Meant to be shown regardless: signs, songs, and foreign dialogue in a dubbed cut. */
    val forced: Boolean = false,
    /** SDH — dialogue plus sound effects and speaker labels. */
    val hearingImpaired: Boolean = false,
    /** Audio description: a narrator describing what is on screen. */
    val visualImpaired: Boolean = false,
    /** Not in the file — fetched from an addon, or a file the viewer supplied. */
    val external: Boolean = false,
    /**
     * A picture rather than text: PGS, VobSub, DVD subtitles.
     *
     * Worth knowing because none of the appearance settings reach one. mpv can move and
     * scale a bitmap subtitle but cannot restyle it, so a viewer who changes the font and
     * sees nothing happen is looking at one of these.
     */
    val bitmap: Boolean = false,
) {
    val label: String
        get() = listOfNotNull(
            title.takeIf { it.isNotBlank() },
            language?.uppercase()?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { "Track $id" }
}

enum class TrackKind { Audio, Subtitle }

/**
 * One entry of the file's own chapter list. Chapters always drive navigation and
 * seek-bar ticks; recognized semantic titles can also supply labelled segments,
 * with IntroDB filling the kinds the file does not identify.
 */
data class MediaChapter(
    val index: Int,
    val title: String,
    val startSeconds: Double,
) {
    /** Many files number their chapters and title none of them. */
    val label: String get() = title.ifBlank { "Chapter ${index + 1}" }
}

data class PlaybackStatus(
    val hasMedia: Boolean = false,
    val paused: Boolean = true,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    /**
     * 0..[MAX_VOLUME], matching mpv. Not the 0..1 scale AppSettings.defaultVolume uses, and
     * not capped at [NORMAL_VOLUME] — anything above that is amplification.
     */
    val volume: Double = NORMAL_VOLUME,
    /**
     * Independent of [volume]: a muted player at volume 100 is silent. The "start
     * muted" setting mutes at load, so the UI has to read this rather than infer
     * silence from the volume alone.
     */
    val muted: Boolean = false,
    /** How full the player's read-ahead buffer is, 0-100, while it is filling. */
    val bufferingPercent: Int = 0,
    /**
     * The timestamp read-ahead reaches, on the same scale as [positionSeconds].
     * Drawn on the seek bar, where it answers the question the percentage cannot:
     * how far ahead can I jump without waiting.
     */
    val bufferedSeconds: Double = 0.0,
    /** The player has data to decode but is stalled waiting for more. */
    val waitingForData: Boolean = false,
    /** The file is open and its duration is known — not merely accepted for loading. */
    val fileLoaded: Boolean = false,
    /** Whatever the player last reported; the only detail available while opening. */
    val statusMessage: String = "",
    /** Playback ran to the end, as opposed to being paused near it. */
    val endReached: Boolean = false,
    /**
     * libmpv stopped before the reported duration, usually because its network,
     * torrent, or HTTP input disappeared. Kept separate from [endReached] so an
     * interrupted stream cannot be completed or advanced like a finished file.
     */
    val interrupted: Boolean = false,
    /** 1.0 is normal speed. */
    val speed: Double = 1.0,
    val audioTracks: List<MediaTrack> = emptyList(),
    val subtitleTracks: List<MediaTrack> = emptyList(),
    /** Null when subtitles are switched off. */
    val selectedSubtitleId: Int? = null,
    val selectedAudioId: Int? = null,
    /** The file's own chapters, if it has any. Usually empty. */
    val chapters: List<MediaChapter> = emptyList(),
    /** Seconds the subtitles/audio are shifted by; negative is earlier. */
    val subtitleDelaySeconds: Double = 0.0,
    val audioDelaySeconds: Double = 0.0,
    /** Decode diagnostics for the stats overlay; blank or zero until known. */
    val videoCodec: String = "",
    val hardwareDecoder: String = "",
    val renderBackend: String = "",
    /** mpv frame-drop-count: frames discarded by video output because they arrived late. */
    val droppedFrames: Int = 0,
    /** Frames discarded by the decoder itself, excluding slow presentation. */
    val decoderDroppedFrames: Int = 0,
    /** Timing corrections and externally delayed presentation reported by mpv. */
    val mistimedFrames: Int = 0,
    val delayedFrames: Int = 0,
    val estimatedFps: Double = 0.0,
    val videoBitrate: Double = 0.0,
    val bufferedAheadSeconds: Double = 0.0,
    /** Actual player render target and latest native render-call duration. */
    val renderWidth: Int = 0,
    val renderHeight: Int = 0,
    val renderTimeMillis: Double = 0.0,
    val error: String? = null,
) {
    val progressFraction: Float
        get() = if (durationSeconds > 0.0) {
            (positionSeconds / durationSeconds).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
}

/**
 * Result of interpreting libmpv's ambiguous EOF signal.
 *
 * mpv documents EOF for incomplete files and broken network streams as well as
 * natural completion. [positionSeconds] is therefore also the position recovery
 * should use: when mpv jumped from mid-file to the duration while terminating,
 * this rolls that synthetic jump back to [previousPositionSeconds].
 */
data class PlaybackTermination(
    val ended: Boolean,
    val interrupted: Boolean,
    val positionSeconds: Double,
)

/** True only when a terminal position is credibly at the file's natural end. */
fun playbackReachedNaturalEnd(positionSeconds: Double, durationSeconds: Double): Boolean {
    if (!positionSeconds.isFinite() || !durationSeconds.isFinite()) return false
    if (positionSeconds < 0.0 || durationSeconds <= 0.0) return false
    val tolerance = (durationSeconds * 0.01).coerceIn(2.0, 30.0)
    return durationSeconds - positionSeconds <= tolerance
}

/**
 * Classifies one EOF observation using the position immediately before it.
 *
 * Natural playback reports several positions inside the end tolerance. A broken
 * input can instead make keep-open park at the duration in one update; requiring
 * both samples to be near the end distinguishes that jump and preserves the last
 * position the viewer actually reached.
 */
fun classifyPlaybackTermination(
    positionSeconds: Double,
    previousPositionSeconds: Double,
    durationSeconds: Double,
): PlaybackTermination {
    val currentAtEnd = playbackReachedNaturalEnd(positionSeconds, durationSeconds)
    val previousAtEnd = playbackReachedNaturalEnd(previousPositionSeconds, durationSeconds)
    if (currentAtEnd && previousAtEnd) {
        return PlaybackTermination(
            ended = true,
            interrupted = false,
            positionSeconds = positionSeconds.coerceAtLeast(0.0),
        )
    }

    val recoveryPosition = when {
        currentAtEnd && previousPositionSeconds.isFinite() && previousPositionSeconds >= 0.0 ->
            previousPositionSeconds
        positionSeconds.isFinite() && positionSeconds >= 0.0 -> positionSeconds
        previousPositionSeconds.isFinite() && previousPositionSeconds >= 0.0 ->
            previousPositionSeconds
        else -> 0.0
    }
    return PlaybackTermination(
        ended = false,
        interrupted = true,
        positionSeconds = recoveryPosition,
    )
}

/**
 * What is playing, as a person would name it.
 *
 * Separate from [PlaybackStatus] because it answers a different question and arrives from
 * a different direction: the status is everything libmpv can be asked about a file, and a
 * file knows its resolution and its chapter list but not that it is episode three. Only the
 * session that opened it knows that, and until it says so the host has a URL and nothing
 * else — which is the whole reason Android's lock screen used to read "Cove".
 *
 * No duration here on purpose. It is not known when a request opens, it is already published
 * on [PlaybackStatus], and carrying it would mean revising this value every time a file
 * loads, for a field every consumer can already read.
 */
data class NowPlaying(
    val title: String,
    /** The episode, or the extra's own name. Null for a film. */
    val subtitle: String?,
    val artworkUrl: String?,
)

/**
 * Full volume, and the ceiling above it.
 *
 * 130 is mpv's own `volume-max` default, and above [NORMAL_VOLUME] it amplifies rather than
 * attenuates — which is the point: quiet dialogue on laptop speakers is the standing complaint,
 * and the UI used to stop at 100 and offer no way past it. Both hosts pass it to mpv explicitly
 * rather than trusting a default that merely happens to agree.
 *
 * Raising this alone is not enough, and that is the whole trap: the volume passes through four
 * separate clamps on its way to mpv and back — the layer above, the `VideoPlayerHost`, the
 * `DesktopPlayer` beneath it, and the property readback that feeds the slider. Miss the write
 * and nothing gets louder; miss the readback and the slider snaps home a fifth of a second
 * later. Every one of them takes its ceiling from here, so there is one number to change.
 */
const val NORMAL_VOLUME = 100.0
const val MAX_VOLUME = 130.0

/**
 * ffmpeg's HTTP reconnect flags, as one mpv `stream-lavf-o` value.
 *
 * With `keep-open=yes` a read error is otherwise permanent: mpv parks, `interrupted` goes
 * true, and the viewer is told the stream stopped before the end — of a source that would
 * have answered a second request perfectly well. Addon streams drop mid-file often enough
 * that this is ordinary rather than exceptional.
 *
 * `reconnect_streamed` is included because a response without a length is not seekable as
 * far as ffmpeg is concerned, and those are exactly the streams that most need retrying.
 * `reconnect_at_eof` is deliberately absent: it treats a clean end-of-file as an error too,
 * and the natural end is what decides whether the next episode plays. `reconnect_delay_max`
 * bounds the retries, so a genuinely dead upstream still gives up rather than looping.
 *
 * Lives here because both hosts need the identical string and neither module can see the
 * other — the desktop had none of this for a long time while Android did, which is the kind
 * of divergence a shared constant is for.
 */
const val RECONNECT_STREAM_OPTIONS =
    "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=10"

/** The default for a host that never reports one, shared so each does not allocate its own. */
private val NO_MEDIA_PLAYING: StateFlow<NowPlaying?> = MutableStateFlow(null)

/**
 * A video surface plus transport controls, implemented per platform. Desktop and
 * Android both wrap libmpv behind their platform-native surface hosts.
 *
 * [Surface] is a composable rather than a plain view handle because the desktop
 * OpenGL path has to be hosted in a SwingPanel, which only exists inside a
 * composition.
 */
@Stable
interface VideoPlayerHost {
    val status: StateFlow<PlaybackStatus>

    /**
     * What the session last said it was playing, for the parts of a host that are outside
     * the composition and cannot ask: Android's media session and notification, the desktop
     * window title.
     *
     * Defaulted so a host that shows this nowhere — a test double, the TV shell's — does not
     * have to carry a field for it.
     */
    val nowPlaying: StateFlow<NowPlaying?> get() = NO_MEDIA_PLAYING

    /**
     * Names what [load] is about to play, or clears it when playback ends.
     *
     * Called by the session rather than folded into [load] because the two do not happen
     * together: the identity is known while sources are still resolving, and it survives a
     * failover to a different source of the same episode.
     */
    fun setNowPlaying(value: NowPlaying?) = Unit

    /**
     * Video decoders exposed by the host. Android fills this from MediaCodecList;
     * other hosts deliberately leave it unknown so codec heuristics never hide a
     * source on platforms that have not supplied a trustworthy capability probe.
     */
    val videoCodecCapabilities: VideoCodecCapabilities
        get() = VideoCodecCapabilities()

    /**
     * Whether [load] accepts a web page — a YouTube watch link — as well as a
     * direct media URL. TMDB's extras are only ever page links, so a host that
     * says no has them opened in the system browser instead.
     *
     * False by default: this is a capability a player either has or does not, and
     * the safe answer for one that has not declared it is the one that still ends
     * with the video playing somewhere.
     */
    val playsWebVideos: Boolean get() = false

    /**
     * Readies whatever the player needs to open a page URL, and says what stopped
     * it if anything did: null means ready, anything else is a sentence for the
     * viewer. Called before [load] for an extra, never for an ordinary stream.
     *
     * @param mayInstallHelper whether the player may fetch what it is missing.
     *   The desktop player needs yt-dlp; downloading it is the viewer's call, so
     *   the answer comes from settings rather than from the player.
     */
    suspend fun prepareWebVideo(mayInstallHelper: Boolean): String? =
        "This player cannot open web videos."

    /** Starts [url] from [startPositionSeconds]. Replaces whatever was playing. */
    fun load(url: String, startPositionSeconds: Double = 0.0)
    fun setPaused(paused: Boolean)
    fun togglePause()
    fun seek(seconds: Double)

    /**
     * Jumps [deltaSeconds] from where the viewer last asked to be, which is not the
     * same as where the player currently reports being.
     *
     * The player publishes its position on a timer, so the UI's copy is always
     * slightly behind. Computing `position + delta` up here reads that stale value:
     * press the key twice in quick succession and both jumps start from the same
     * place, so the second one goes nowhere. Only the implementation knows about a
     * seek it has issued but not yet arrived at, so only it can add these up correctly.
     */
    fun seekRelative(deltaSeconds: Double)

    /** [volume] is 0..[MAX_VOLUME], matching mpv. Implementations clamp to that, not to 100. */
    fun setVolume(volume: Double)

    /** Toggles the player's own mute flag, which [setVolume] does not affect. */
    fun setMuted(muted: Boolean)

    fun selectAudioTrack(id: Int)

    fun setScaling(scaling: VideoScaling)

    fun setSpeed(speed: Double)

    /** Applied before loading, so the player picks the right tracks first time. */
    fun applyPreferences(preferences: PlaybackPreferences)

    /**
     * Whether this player can run audio filters, which is what volume normalisation is
     * built from.
     *
     * False by default, and the default is the load-bearing one. Android's bundled libmpv
     * links a libavfilter with no audio filters in it at all, and mpv answers a filter it
     * cannot build by *ending the file* — "Audio filter initialized failed", and playback
     * stops — rather than by carrying on unfiltered. So a host that has not said it can do
     * this must never be handed a filter, and the control that would set one is absent
     * where this is false rather than present and inert, as [playsWebVideos] and
     * `canLoadSubtitleFile` already are.
     */
    val supportsAudioFilters: Boolean get() = false

    /**
     * Restyles the subtitles without disturbing anything else.
     *
     * Safe to call while a file is playing, which is the whole point of it: appearance is
     * adjusted by eye, and a viewer moving the outline slider needs to see the outline move.
     * [applyPreferences] would also apply the style, but it re-selects tracks and re-applies
     * mute along the way, which would throw away whatever the viewer picked in the player's
     * own menus.
     *
     * Defaulted to a no-op so a host that draws no subtitles of its own — a test double, a
     * future target — does not have to implement it.
     */
    fun applySubtitleStyle(style: SubtitleStyle) = Unit

    /**
     * Adds an external subtitle; it then appears in [PlaybackStatus.subtitleTracks].
     *
     * [select] switches to it at once. That is what a file the viewer supplied means,
     * and what a fetched one must not do: mpv's own select/auto distinction, where
     * auto leaves the choice to the language preferences applied before the load.
     */
    fun addSubtitle(url: String, title: String, language: String, select: Boolean = false)

    /** Null switches subtitles off. */
    fun selectSubtitleTrack(id: Int?)

    /**
     * Steps to the next or previous chapter. A no-op in a file without chapters,
     * which is most of them — [PlaybackStatus.chapters] says whether to offer it.
     */
    fun stepChapter(delta: Int)

    /** Steps one frame. Only meaningful while paused, which is where it is offered. */
    fun stepFrame(delta: Int)

    /**
     * Shifts the subtitles against the picture, in seconds; negative is earlier.
     * Absolute rather than relative so the value the UI shows is the value it sets.
     */
    fun setSubtitleDelay(seconds: Double)

    /** As [setSubtitleDelay], for the audio track. */
    fun setAudioDelay(seconds: Double)

    /** Writes a still of the current frame, subtitles included, wherever mpv is configured to. */
    fun takeScreenshot()

    fun stop()

    @Composable
    fun Surface(modifier: Modifier)
}

/**
 * Null wherever a host does not provide a player. The Watch button reports that
 * playback is unavailable rather than crashing, so previews and future targets
 * can keep building against the same :ui module.
 */
val LocalVideoPlayerHost = staticCompositionLocalOf<VideoPlayerHost?> { null }

/**
 * Window-level fullscreen, which only a desktop window has. Null everywhere
 * else — mobile players are already fullscreen, so the control is simply absent
 * rather than present and inert.
 */
@Stable
interface FullscreenController {
    val isFullscreen: StateFlow<Boolean>
    fun toggle()
}

val LocalFullscreenController = staticCompositionLocalOf<FullscreenController?> { null }
