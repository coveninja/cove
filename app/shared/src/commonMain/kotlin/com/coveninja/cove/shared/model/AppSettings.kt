package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Persisted settings contract, defaults included.
//
// Completeness here is a correctness requirement, not tidiness: PUT
// /api/settings is a whole-object replace with no server-side merge, so any
// field missing from this class is written back as its default value. Dropping
// allowUploading alone would silently turn off seeding; dropping
// remoteAccessToken would invalidate every already-paired device. If a field is
// added to storage it must be added here in the same change.
@Serializable
data class AppSettings(
    val openOnMute: Boolean = false,
    val defaultVolume: Double = 1.0,
    val autoPlay: Boolean = false,
    val rememberPosition: Boolean = true,
    /** How far the skip buttons and the arrow keys jump. */
    val seekStepSeconds: Double = 10.0,
    /**
     * Whether the player decodes on the GPU. On by default and worth leaving on —
     * it is the difference between a few percent of one core and most of several on
     * a 4K file — but copy-back decoding misbehaves on some drivers, and until now
     * turning it off meant hand-editing the raw mpv config.
     */
    val hardwareDecoding: Boolean = true,
    /**
     * Carry the volume you last set into the next thing you play, by writing it back
     * over [defaultVolume]. Off means [defaultVolume] is a fixed starting point that
     * only the settings slider changes.
     */
    val rememberVolume: Boolean = true,
    val defaultProvider: String = "",
    val autoSelectStream: Boolean = false,
    val streamSelectionMode: String = "balanced",
    val measuredBandwidthMbps: Double = 0.0,
    val sourcePreference: String = "",
    val subtitlesEnabled: Boolean = false,
    /**
     * The single-language preference, kept as the head of [subtitleLanguages].
     *
     * Not replaced by the list, deliberately. These two fields sync, and a build that
     * predates the list would read one, drop the other, and push back a scalar with a
     * newer timestamp — flattening a considered order to its first entry on every
     * other device. Writers set both; readers prefer the list and fall back to here.
     */
    val defaultSubtitleLang: String = "en",
    val defaultAudioLang: String = "en",
    /**
     * Language codes in preference order, most wanted first. Empty means nothing has
     * chosen an order yet, and the scalar above still decides — which is how every
     * profile written before this field arrives keeps working with no migration.
     */
    val audioLanguages: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    val subtitleSize: Double = 100.0,
    val subtitlePosition: Double = 8.0,
    val subtitleBackground: Boolean = true,
    // Subtitle appearance. Every default below is mpv's own, so a profile that never
    // opens these controls renders exactly as it did before they existed.
    /** Empty means mpv's own sans-serif rather than a font this build picked. */
    val subtitleFont: String = "",
    val subtitleTextColor: String = "#FFFFFFFF",
    val subtitleOutlineColor: String = "#FF000000",
    val subtitleOutlineSize: Double = 1.65,
    val subtitleShadowOffset: Double = 0.0,
    /**
     * The opaque box behind the text — and the drop shadow's colour too. mpv aliases
     * `sub-shadow-color` onto `sub-back-color`, so they are one value however many
     * controls are drawn for them; two would silently overwrite each other.
     */
    val subtitleBackColor: String = "#AF000000",
    val subtitleBold: Boolean = false,
    val subtitleItalic: Boolean = false,
    val subtitleBlur: Double = 0.0,
    /** mpv's sub-ass-override: no|yes|scale|force|strip. How much of a styled subtitle to keep. */
    val subtitleAssOverride: String = "scale",
    /** mpv's sub-align-x: left|center|right. */
    val subtitleAlign: String = "center",
    /**
     * outline-and-shadow|opaque-box|background-box. Empty defers to [subtitleBackground],
     * which is the only thing older profiles and older builds have said about this.
     */
    val subtitleBorderStyle: String = "",
    /**
     * off|normalize|night. Evens out quiet dialogue against loud action.
     *
     * Desktop only, and not for want of a control: the Android libmpv ships a minimal
     * libavfilter with no audio filters in it at all, and mpv answers a filter it cannot
     * build by ending the file rather than by carrying on without it. Hosts declare
     * whether they can take this; see VideoPlayerHost.supportsAudioFilters.
     */
    val audioNormalization: String = "off",
    /** Empty is mpv's auto-safe; "stereo" forces a downmix for headphones and laptop speakers. */
    val audioDownmix: String = "",
    /** Rescales a downmix that would otherwise clip. Only bites while downmixing. */
    val audioNormalizeDownmix: Boolean = false,
    val uiLanguage: String = "",
    val showStreamDetails: Boolean = true,
    val hideSpoilers: Boolean = false,
    val autoSkipIntro: Boolean = false,
    val autoSkipRecap: Boolean = false,
    val autoSkipCredits: Boolean = false,
    val autoSkipPreview: Boolean = false,
    val onboardingDone: Boolean = false,
    val discoveryAlgorithm: String = "smart",
    @SerialName("customAlgorithmUrl") val customAlgorithmUrl: String = "",
    val prefetchStreams: Boolean = true,
    val prefetchNextEpisode: Boolean = true,
    val allowUploading: Boolean = true,
    val probeStreams: Boolean = true,
    /**
     * Whether Cove may fetch and update yt-dlp for itself. Trailers and other
     * extras are YouTube pages, and mpv needs it to turn one into a stream; a
     * yt-dlp the viewer installed is always preferred and nothing is downloaded
     * for them. Off means extras only play where yt-dlp is already installed.
     */
    val manageYtDlp: Boolean = true,
    // Device-local security config, deliberately excluded from Supabase sync on
    // the embedded HTTP boundary. Round-tripped verbatim — regenerating the token would orphan
    // every device already paired against it.
    val remoteAccessEnabled: Boolean = false,
    val remoteAccessToken: String = "",
    val allowLanStreamSources: Boolean = false,
    /**
     * Whether other profiles follow this one's provider addons. Only meaningful
     * on the primary profile's row — that is the profile whose value every
     * other one reads, and the only profile the toggle is offered to. A
     * secondary's own copy of this field is inert.
     */
    val addonsFollowPrimary: Boolean = false,
    val traktScrobbleEnabled: Boolean = true,
    val traktSyncEnabled: Boolean = false,
    val simklScrobbleEnabled: Boolean = true,
    val simklSyncEnabled: Boolean = false,
    // Off means Cove only syncs when asked. On, it also syncs at launch, on a
    // timer, and once local changes settle — background work the user must be
    // able to decline.
    val autoSyncEnabled: Boolean = true,
    /**
     * The order Home draws its sections in, as stable section keys. Empty means
     * the built-in order, which is why it is a list rather than a map: the
     * default is "whatever this build ships", not a set of positions frozen the
     * first time the screen was opened.
     *
     * Keys absent from a saved order are not lost — a section added by a later
     * release, or a catalog from an addon installed since, is anchored back to
     * its default neighbour when the order is resolved. See `orderHomeSections`.
     */
    val homeSectionOrder: List<String> = emptyList(),
    /** Sections the viewer has taken off Home. Not the same as disabling a catalog. */
    val homeSectionsHidden: List<String> = emptyList(),
    /** How many addon catalog rails Home draws. Each one costs a metadata fan-out. */
    val homeCatalogRows: Int = 3,
    val homeContinueRows: Int = 12,
    val homeUpcomingDays: Int = 7,
    // Server-stamped on every write; the backend ignores whatever a client
    // sends. Carried as an opaque string purely so a round-trip is lossless.
    val updatedAt: String? = null,
)
