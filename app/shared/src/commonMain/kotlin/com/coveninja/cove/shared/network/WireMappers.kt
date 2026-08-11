package com.coveninja.cove.shared.network

import com.coveninja.cove.shared.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DTOs for response shapes that don't map 1:1 to a domain model.
// Domain models (Media, LibraryEntry, AppSettings, etc.) are already @Serializable
// with the correct @SerialName annotations and are deserialized directly by CoveApi.

// /api/search/multi wraps movies and tv in separate arrays; the UI collapses them.
@Serializable
data class SearchResultsDto(
    val movies: List<Media> = emptyList(),
    val tv: List<Media> = emptyList(),
)

// /api/library/{id}/{type} bundles entry + all progress records + dismissed flag.
@Serializable
data class LibraryDetailDto(
    val entry: LibraryEntry? = null,
    val progress: List<WatchProgress> = emptyList(),
    val dismissed: Boolean = false,
)

// /api/profiles wraps the list with an active profile pointer.
@Serializable
data class ProfilesResponseDto(
    val profiles: List<Profile> = emptyList(),
    @SerialName("active_profile_id") val activeProfileId: String = "",
)

// ── Auth ────────────────────────────────────────────────────────────────────
// Defined here rather than beside the backend's AuthService so the embedded host
// and the HTTP client that talks to it cannot drift apart.

/** Every error body the embedded host returns has this shape. */
@Serializable
data class ErrorResponseDto(val error: String = "")

@Serializable
data class RegisterRequest(
    val email: String = "",
    val password: String = "",
    @SerialName("profile_name") val profileName: String = "",
)

@Serializable
data class ConfirmRegistrationRequest(
    val email: String = "",
    val token: String = "",
    val password: String = "",
    @SerialName("profile_name") val profileName: String = "",
)

@Serializable
data class LoginRequest(val email: String = "", val password: String = "")

@Serializable
data class EmailRequest(val email: String = "")

@Serializable
data class VerifyOtpRequest(val email: String = "", val token: String = "")

@Serializable
data class ProfileNameRequest(val name: String = "")

// /api/auth/me
@Serializable
data class AuthMeResponse(
    // Null only while the profile store is still loading or has failed.
    val profile: Profile? = null,
    val linked: Boolean = false,
    val authenticated: Boolean = false,
    val email: String = "",
    @SerialName("user_id") val userId: String = "",
)

// /api/auth/login, /api/auth/verify-otp
@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    val profiles: List<Profile> = emptyList(),
    val active: Profile? = null,
    @SerialName("onboarding_done") val onboardingDone: Boolean = false,
)

// /api/auth/register/confirm, and the completed branch of /api/auth/register.
@Serializable
data class AuthSessionResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    val profile: Profile? = null,
)

/**
 * /api/auth/register answers with one of two shapes — a session, or a bare
 * `confirmation_required` flag — so the client reads a union of both and decides
 * on the flag.
 */
@Serializable
data class RegisterResponse(
    @SerialName("confirmation_required") val confirmationRequired: Boolean = false,
    @SerialName("access_token") val accessToken: String = "",
    val profile: Profile? = null,
)

// /api/auth/sync
@Serializable
data class SyncResponse(
    val status: String = "",
    @SerialName("library_generation") val libraryGeneration: Long = 0,
    @SerialName("push_error") val pushError: String = "",
)

// /api/discover/genres, /people, /keywords — a ranked taste entry. The score is the raw
// profile weight and is deliberately not shown to anyone; it only fixes the order.
@Serializable
data class DiscoveryTasteDto(
    val id: Int = 0,
    val name: String = "",
    val score: Double = 0.0,
)

// /api/discover/favorites. `title` was added later and so has to tolerate its own absence
// from an older host, which is what the empty default is for.
@Serializable
data class FavoriteSignalDto(
    @SerialName("tmdb_id") val tmdbId: Int = 0,
    @SerialName("media_type") val mediaType: MediaType = MediaType.Movie,
    val weight: Double = 0.0,
    val title: String = "",
)

// /api/update/check
@Serializable
data class UpdateCheckDto(
    val available: Boolean = false,
    @SerialName("current_version") val currentVersion: String = "",
    @SerialName("latest_version") val latestVersion: String = "",
    @SerialName("release_name") val releaseName: String = "",
)

// Request bodies

@Serializable
data class AddLibraryRequest(
    @SerialName("tmdb_id")           val tmdbId: Int,
    @SerialName("media_type")        val mediaType: MediaType,
    val title: String,
    @SerialName("poster_path")       val posterPath: String = "",
    val status: LibraryStatus = LibraryStatus.WatchLater,
    @SerialName("vote_average")      val voteAverage: Double = 0.0,
    @SerialName("last_air_date")     val lastAirDate: String = "",
    @SerialName("last_aired_season") val lastAiredSeason: Int? = null,
    @SerialName("last_aired_episode")val lastAiredEpisode: Int? = null,
)

@Serializable
data class PatchStatusRequest(val status: String)

@Serializable
data class PatchRatingRequest(val rating: Double?)

@Serializable
data class DismissLibraryRequest(
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("media_type") val mediaType: MediaType,
)

@Serializable
data class WatchProgressRequest(
    @SerialName("tmdb_id")            val tmdbId: Int,
    @SerialName("media_type")         val mediaType: MediaType,
    val title: String = "",
    @SerialName("poster_path")        val posterPath: String = "",
    @SerialName("vote_average")       val voteAverage: Double = 0.0,
    @SerialName("last_air_date")      val lastAirDate: String = "",
    @SerialName("last_aired_season")  val lastAiredSeason: Int? = null,
    @SerialName("last_aired_episode") val lastAiredEpisode: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("position_seconds")   val positionSeconds: Double = 0.0,
    @SerialName("duration_seconds")   val durationSeconds: Double = 0.0,
    val completed: Boolean = false,
)

// Both the addon and Nuvio "add" routes take the same single-field body.
@Serializable
data class AddAddonRequest(val url: String)

@Serializable
data class ToggleEnabledRequest(val enabled: Boolean)
