package com.coveninja.cove.ui.model

import com.coveninja.cove.shared.model.PersonCredit
import com.coveninja.cove.shared.model.PersonDetails
import com.coveninja.cove.shared.model.MediaCastMember as DomainCastMember
import com.coveninja.cove.shared.model.MediaCrewMember as DomainCrewMember

/**
 * A person, thin or enriched — the same one-type-two-shapes arrangement [Media] uses.
 *
 * A title's credits give the thin shape (name, billing, photo), which is what the cast
 * row draws and what the person sheet opens with; the person fetch then replaces it with
 * everything else. Because the thin shape is already on screen, the sheet has a name and
 * a face from its first frame and never renders empty.
 */
data class Person(
    val id: String,
    val tmdbId: Int,
    val name: String,
    /** Character or job, as billed on the title this person was reached from. */
    val role: String? = null,
    val profileUrl: String? = null,
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    val placeOfBirth: String? = null,
    val knownForDepartment: String? = null,
    val alsoKnownAs: List<String> = emptyList(),
    /**
     * Every credit TMDB returned, cast and crew alike, mapped one for one and otherwise
     * untouched — the same title can legitimately appear twice. See
     * [com.coveninja.cove.ui.model.filmographyOf] for the merged, ordered view the sheet shows.
     */
    val credits: List<PersonCreditEntry> = emptyList(),
) {
    val initial: String
        get() = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** One title in a person's filmography. */
data class PersonCreditEntry(
    val id: String,
    val tmdbId: Int,
    val title: String,
    val posterUrl: String?,
    val type: MediaType?,
    val rating: Double?,
    /** Release or first-air year, or null for something undated (usually unreleased). */
    val year: String?,
    val character: String? = null,
    val job: String? = null,
    val episodeCount: Int? = null,
    val popularity: Double = 0.0,
) {
    /** What to bill this credit as: the character if they acted in it, else the job. */
    val role: String? get() = character?.takeIf { it.isNotBlank() } ?: job?.takeIf { it.isNotBlank() }
}

fun PersonDetails.toUiPerson(): Person = Person(
    id = uiPersonId(id),
    tmdbId = id,
    name = name,
    role = null,
    profileUrl = displayImageUrl(profilePath, "w500"),
    biography = biography.takeIf { it.isNotBlank() },
    birthday = birthday?.takeIf { it.isNotBlank() },
    deathday = deathday?.takeIf { it.isNotBlank() },
    placeOfBirth = placeOfBirth?.takeIf { it.isNotBlank() },
    knownForDepartment = knownForDepartment.takeIf { it.isNotBlank() },
    alsoKnownAs = alsoKnownAs.filter { it.isNotBlank() },
    credits = (combinedCredits.cast + combinedCredits.crew).mapNotNull { it.toUiCredit() },
)

/**
 * Null for a credit nothing can be done with: no media type means no details fetch is
 * possible, and an untitled entry has nothing to show on a row.
 */
fun PersonCredit.toUiCredit(): PersonCreditEntry? {
    val uiType = mediaType.toUiType() ?: return null
    val name = displayTitle.takeIf { it.isNotBlank() } ?: return null
    return PersonCreditEntry(
        id = uiMediaId(id, mediaType),
        tmdbId = id,
        title = name,
        posterUrl = displayImageUrl(posterPath, "w500"),
        type = uiType,
        rating = voteAverage.takeIf { it > 0.0 },
        year = displayDate?.take(4)?.takeIf { it.length == 4 },
        character = character?.takeIf { it.isNotBlank() },
        job = job?.takeIf { it.isNotBlank() },
        episodeCount = episodeCount.takeIf { it > 0 },
        popularity = popularity,
    )
}

/**
 * The [Media] a credit stands for. Thin on purpose — opening it goes through the same
 * `MediaCatalog.domainFor` → `toDomainMedia` path as a recommendation card, and the
 * details fetch fills in everything else.
 */
fun PersonCreditEntry.toMedia(): Media = Media(
    id = id,
    tmdbId = tmdbId,
    title = title,
    name = title,
    overview = null,
    released = year,
    firstAirDate = null,
    posterUrl = posterUrl,
    logoUrl = null,
    backdropUrl = null,
    rating = rating,
    type = type,
    popularity = popularity,
    adult = null,
    originalLanguage = null,
)

/** The thin person behind a billed cast entry. */
fun MediaCastMember.toPerson(): Person = Person(
    id = uiPersonId(tmdbId),
    tmdbId = tmdbId,
    name = name,
    role = character,
    profileUrl = profileUrl,
)

fun DomainCastMember.toUiPerson(): Person = Person(
    id = uiPersonId(id),
    tmdbId = id,
    name = name,
    role = character.ifBlank { null },
    profileUrl = displayImageUrl(profilePath, "w185"),
)

/** Crew have no photo in a title's credits — the card falls back to their initial. */
fun DomainCrewMember.toUiPerson(): Person = Person(
    id = uiPersonId(id),
    tmdbId = id,
    name = name,
    role = job.ifBlank { null },
)

fun uiPersonId(tmdbId: Int): String = "Person:$tmdbId"
