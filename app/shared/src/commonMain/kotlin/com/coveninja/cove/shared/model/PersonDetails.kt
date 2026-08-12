package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * TMDB `/person/{id}?append_to_response=combined_credits`, modelled the same way
 * [MediaDetails] models `/movie|tv/{id}`: a deliberate subset with non-null defaults,
 * so a field TMDB omits for one person never fails the parse for everybody.
 */
@Serializable
data class PersonDetails(
    val id: Int = 0,
    val name: String = "",
    val biography: String = "",
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String = "",
    @SerialName("profile_path") val profilePath: String = "",
    @SerialName("also_known_as") val alsoKnownAs: List<String> = emptyList(),
    val popularity: Double = 0.0,
    @SerialName("combined_credits") val combinedCredits: PersonCredits = PersonCredits(),
    /**
     * TMDB's own pick of what this person is known for. Only `/search/person` fills it in;
     * `/person/{id}` answers with [combinedCredits] instead, which is the whole filmography.
     * Modelled on the same type so a person from a search result and a person from the
     * person endpoint are the same shape with different amounts filled in.
     */
    @SerialName("known_for") val knownFor: List<PersonCredit> = emptyList(),
)

@Serializable
data class PersonCredits(
    val cast: List<PersonCredit> = emptyList(),
    val crew: List<PersonCredit> = emptyList(),
)

/**
 * One entry of a person's combined credits. It is a title, not a person: `character`
 * is set on acting credits and `job` on crew ones, and [mediaType] is what tells the
 * two halves of `combined_credits` apart once they are merged.
 */
@Serializable
data class PersonCredit(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    @SerialName("media_type") val mediaType: MediaType? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    val popularity: Double = 0.0,
    val character: String? = null,
    val job: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: name.orEmpty()
    val displayDate: String? get() = releaseDate?.takeIf { it.isNotBlank() } ?: firstAirDate
}
