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
    val defaultProvider: String = "",
    val autoSelectStream: Boolean = false,
    val streamSelectionMode: String = "balanced",
    val measuredBandwidthMbps: Double = 0.0,
    val sourcePreference: String = "",
    val subtitlesEnabled: Boolean = false,
    val defaultSubtitleLang: String = "en",
    val defaultAudioLang: String = "en",
    val subtitleSize: Double = 100.0,
    val subtitlePosition: Double = 8.0,
    val subtitleBackground: Boolean = true,
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
    // Device-local security config, deliberately excluded from Supabase sync on
    // the embedded HTTP boundary. Round-tripped verbatim — regenerating the token would orphan
    // every device already paired against it.
    val remoteAccessEnabled: Boolean = false,
    val remoteAccessToken: String = "",
    val allowLanStreamSources: Boolean = false,
    val traktScrobbleEnabled: Boolean = true,
    val traktSyncEnabled: Boolean = false,
    // Off means Cove only syncs when asked. On, it also syncs at launch, on a
    // timer, and once local changes settle — background work the user must be
    // able to decline.
    val autoSyncEnabled: Boolean = true,
    // Server-stamped on every write; the backend ignores whatever a client
    // sends. Carried as an opaque string purely so a round-trip is lossless.
    val updatedAt: String? = null,
)
