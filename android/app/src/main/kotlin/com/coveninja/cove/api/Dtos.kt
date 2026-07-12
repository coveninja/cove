package com.coveninja.cove.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Media(
    val id: Int = 0,
    val title: String = "",
    val name: String = "",
    val overview: String = "",
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("first_air_date") val firstAirDate: String = "",
    @SerialName("poster_path") val posterPath: String = "",
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("media_type") val mediaType: String = "",
    val popularity: Double = 0.0,
    @SerialName("original_language") val originalLanguage: String? = null,
)

// Display helpers
val Media.displayTitle: String get() = name.ifBlank { title }
val Media.year: String get() = (releaseDate.ifBlank { firstAirDate }).take(4)
val Media.isMovie: Boolean get() = mediaType == "movie"

@Serializable
data class Genre(
    val id: Int = 0,
    val name: String = "",
)

@Serializable
data class CastMember(
    val id: Int = 0,
    val name: String = "",
    val order: Int = 0,
)

@Serializable
data class CreditsDto(
    val cast: List<CastMember> = emptyList(),
)

@Serializable
data class Details(
    val overview: String = "",
    val genres: List<Genre> = emptyList(),
    val runtime: Int = 0,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int = 0,
    val seasons: List<TvSeason> = emptyList(),
    val credits: CreditsDto? = null,
)

@Serializable
data class TvSeason(
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
    val name: String = "",
    @SerialName("poster_path") val posterPath: String = "",
)

@Serializable
data class TvEpisode(
    @SerialName("episode_number") val episodeNumber: Int = 0,
    val name: String = "",
    val overview: String = "",
    @SerialName("still_path") val stillPath: String = "",
    @SerialName("air_date") val airDate: String = "",
    val runtime: Int = 0,
)

// Stream: camelCase JSON keys from Go (infoHash, addonName, sizeBytes, headers).
@Serializable
data class Stream(
    val name: String = "",
    val title: String = "",
    val url: String = "",
    val infoHash: String = "",
    val addonName: String = "",
    val sizeBytes: Long = 0L,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class LibraryEntry(
    val id: String = "",
    @SerialName("tmdb_id") val tmdbId: Int = 0,
    @SerialName("media_type") val mediaType: String = "",
    val title: String = "",
    @SerialName("poster_path") val posterPath: String = "",
    val status: String = "",
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    @SerialName("last_watched_season") val lastWatchedSeason: Int? = null,
    @SerialName("last_watched_episode") val lastWatchedEpisode: Int? = null,
    @SerialName("added_at") val addedAt: String = "",
)

@Serializable
data class WatchProgress(
    val id: String = "",
    @SerialName("tmdb_id") val tmdbId: Int = 0,
    @SerialName("media_type") val mediaType: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("position_seconds") val positionSeconds: Double = 0.0,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
    val completed: Boolean = false,
    @SerialName("watched_at") val watchedAt: String = "",
)

@Serializable
data class LibraryDetail(
    val entry: LibraryEntry? = null,
    val progress: List<WatchProgress> = emptyList(),
    val dismissed: Boolean = false,
)

@Serializable
data class SearchResults(
    val movies: List<Media> = emptyList(),
    val tv: List<Media> = emptyList(),
)

// Settings mirrors the Go settings.Settings struct (full object — PUT is whole-replace).
// All fields have defaults matching the Go defaultSettings so a partial JSON response
// never leaves fields null-valued.
@Serializable
data class Settings(
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
    val showStreamDetails: Boolean = true,
    val hideSpoilers: Boolean = false,
    val autoSkipIntro: Boolean = false,
    val autoSkipRecap: Boolean = false,
    val autoSkipCredits: Boolean = false,
    val autoSkipPreview: Boolean = false,
    val onboardingDone: Boolean = false,
    val discoveryAlgorithm: String = "smart",
    val customAlgorithmUrl: String = "",
    val prefetchStreams: Boolean = true,
    val prefetchNextEpisode: Boolean = true,
    val remoteAccessEnabled: Boolean = false,
    val remoteAccessToken: String = "",
    val updatedAt: String = "",
)

// AddonEntry mirrors the Go addons.AddonEntry shape.
// ── Auth / profiles ────────────────────────────────────────────────────────────

/**
 * Mirrors the Go profiles.Profile struct.
 * supabase_uid is null for local-only (guest) profiles.
 */
@Serializable
data class Profile(
    val id: String = "",
    val name: String = "",
    @SerialName("is_primary") val isPrimary: Boolean = false,
    @SerialName("supabase_uid") val supabaseUid: String? = null,
)

/**
 * Response from POST /api/auth/login and POST /api/auth/verify-otp.
 */
@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    val profiles: List<Profile> = emptyList(),
    val active: Profile? = null,
)

/**
 * Response from POST /api/auth/register.
 *
 * Supabase can respond two ways:
 *  - Email confirmation disabled: full session (access_token is non-empty).
 *  - Email confirmation enabled: { confirmation_required: true, access_token: "" }.
 */
@Serializable
data class RegisterResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    val profile: Profile? = null,
    @SerialName("confirmation_required") val confirmationRequired: Boolean = false,
)

/**
 * Response from POST /api/auth/register/confirm.
 */
@Serializable
data class ConfirmRegisterResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    val profile: Profile? = null,
)

/**
 * Response from GET /api/auth/me.
 */
@Serializable
data class MeResponse(
    val profile: Profile? = null,
    val linked: Boolean = false,
)

/**
 * Response from POST /api/auth/sync.
 */
@Serializable
data class SyncResponse(
    val status: String = "",
    @SerialName("library_generation") val libraryGeneration: Long? = null,
)

/**
 * Response from GET /api/profiles.
 */
@Serializable
data class ProfilesListResponse(
    val profiles: List<Profile> = emptyList(),
    @SerialName("active_profile_id") val activeProfileId: String = "",
)

@Serializable
data class AddonManifest(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
)

@Serializable
data class AddonEntry(
    val id: String = "",
    val url: String? = null,
    val manifest: AddonManifest = AddonManifest(),
    val kind: String = "",
    val source: String = "",
    val enabled: Boolean = false,
)

@Serializable
data class MediaImageObject(
    @SerialName("file_path") val filePath: String = "",
    val url: String = "",
    @SerialName("iso_639_1") val iso: String = "",
    @SerialName("aspect_ratio") val aspectRatio: Float = 0f,
    @SerialName("vote_average") val voteAverage: Float = 0f,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class MediaImages(
    val backdrops: List<MediaImageObject> = emptyList(),
    val logos: List<MediaImageObject> = emptyList(),
    val posters: List<MediaImageObject> = emptyList(),
)

@Serializable
data class MediaVideoObject(
    @SerialName("iso_639_1") val iso6391: String = "",
    val name: String = "",
    val key: String = "",
    val site: String = "",
    val size: Int = 0,
    val type: String = "",
    val official: Boolean = false,
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("embed_url") val embedUrl: String = "",
)

@Serializable
data class MediaVideos(
    val results: List<MediaVideoObject> = emptyList(),
)

@Serializable
data class SubtitleDto(
    val id: String = "",
    val url: String = "",
    val lang: String = "",
)

@Serializable
data class TimestampSegment(
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
)

@Serializable
data class TimestampData(
    val intro: List<TimestampSegment>? = null,
    val recap: List<TimestampSegment>? = null,
    val credits: List<TimestampSegment>? = null,
    val preview: List<TimestampSegment>? = null,
)
