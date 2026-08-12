package com.coveninja.cove.ui.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.periodUntil

/** Which half of a filmography the sheet is showing. */
enum class CreditFilter(val label: String) {
    All("All"),
    Movies("Movies"),
    Series("Series"),
}

/**
 * The filmography as the sheet shows it: one row per title, newest first.
 *
 * TMDB's `combined_credits` lists a title once per credit, so anyone who both wrote and
 * directed something — or played two parts in it — appears against it twice. Merging is
 * what stops the same poster running down the list three times, and it keeps the acting
 * billing when there is one, because "as Marla Singer" says more than "Producer".
 */
fun filmographyOf(
    credits: List<PersonCreditEntry>,
    filter: CreditFilter = CreditFilter.All,
): List<PersonCreditEntry> = credits
    .filter { entry ->
        when (filter) {
            CreditFilter.All -> true
            CreditFilter.Movies -> entry.type == MediaType.Movie
            CreditFilter.Series -> entry.type == MediaType.Series
        }
    }
    .groupBy { it.type to it.tmdbId }
    .map { (_, duplicates) -> duplicates.merged() }
    // Undated credits are announced-but-unmade films. They belong at the end, not at the
    // top where a descending sort on a null year would otherwise put them.
    .sortedWith(
        compareByDescending<PersonCreditEntry> { it.year != null }
            .thenByDescending { it.year.orEmpty() }
            .thenByDescending { it.popularity }
            .thenBy { it.title },
    )

/**
 * The poster rail at the top of the sheet. Popularity rather than recency: what someone
 * is known for is rarely whatever they did last. Entries with no poster are held back
 * because the rail is nothing but posters — but they are still allowed in if holding
 * them back would leave the rail empty.
 */
fun knownForOf(
    credits: List<PersonCreditEntry>,
    limit: Int = 10,
): List<PersonCreditEntry> {
    val merged = filmographyOf(credits)
        .sortedWith(
            compareByDescending<PersonCreditEntry> { it.popularity }
                .thenByDescending { it.rating ?: 0.0 }
                .thenBy { it.title },
        )
    val withPosters = merged.filter { it.posterUrl != null }
    return (withPosters.ifEmpty { merged }).take(limit)
}

/** How many of each type they have, for the filter chips' counts. */
fun creditCountsOf(credits: List<PersonCreditEntry>): Map<CreditFilter, Int> =
    CreditFilter.entries.associateWith { filmographyOf(credits, it).size }

/**
 * Their age on [today], or on the day they died — an age that keeps counting after a
 * death is the kind of detail that makes a page feel machine-written.
 *
 * Null for a birthday that is not a whole ISO date (TMDB has partial ones) or that
 * post-dates the reference day.
 */
fun ageOf(birthday: String?, deathday: String? = null, today: LocalDate): Int? {
    val born = birthday?.toLocalDateOrNull() ?: return null
    val reference = deathday?.toLocalDateOrNull() ?: today
    if (reference < born) return null
    return born.periodUntil(reference).years
}

/** The year of an ISO date, or null — used for the birth-year badge. */
fun yearOf(date: String?): String? = date?.take(4)?.takeIf { year ->
    year.length == 4 && year.all(Char::isDigit)
}

private fun String.toLocalDateOrNull(): LocalDate? =
    runCatching { LocalDate.parse(trim()) }.getOrNull()

/**
 * Fold every credit for one title into a single row: the acting billing wins over a
 * crew job, every distinct job is kept, and the largest episode count and popularity
 * win because TMDB reports those per credit rather than per title.
 */
private fun List<PersonCreditEntry>.merged(): PersonCreditEntry {
    val base = firstOrNull { it.character != null } ?: first()
    val jobs = mapNotNull { it.job }.distinct()
    return base.copy(
        posterUrl = base.posterUrl ?: firstNotNullOfOrNull { it.posterUrl },
        year = base.year ?: firstNotNullOfOrNull { it.year },
        rating = base.rating ?: firstNotNullOfOrNull { it.rating },
        job = jobs.takeIf { it.isNotEmpty() }?.joinToString(", "),
        episodeCount = mapNotNull { it.episodeCount }.maxOrNull(),
        popularity = maxOf { it.popularity },
    )
}
