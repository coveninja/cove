package com.coveninja.cove.ui.model

import com.coveninja.cove.shared.model.PersonCredit
import com.coveninja.cove.shared.model.PersonCredits
import com.coveninja.cove.shared.model.PersonDetails
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.coveninja.cove.shared.model.MediaType as DomainMediaType

private fun credit(
    tmdbId: Int,
    title: String,
    year: String? = "2010",
    type: MediaType = MediaType.Movie,
    character: String? = null,
    job: String? = null,
    episodeCount: Int? = null,
    popularity: Double = 0.0,
    posterUrl: String? = "https://image.tmdb.org/t/p/w500/$tmdbId.jpg",
    rating: Double? = 7.0,
) = PersonCreditEntry(
    id = "${type.name}:$tmdbId",
    tmdbId = tmdbId,
    title = title,
    posterUrl = posterUrl,
    type = type,
    rating = rating,
    year = year,
    character = character,
    job = job,
    episodeCount = episodeCount,
    popularity = popularity,
)

private val today = LocalDate.parse("2026-08-12")

class PersonFilmographyTest {

    // TMDB lists a title once per credit, so someone who wrote and directed the same
    // film arrives twice. The row is about the title, not about the paperwork.
    // Mutation applied to verify: dropped the groupBy and returned the entries as they
    // came → test failed with 2 rows instead of 1.
    @Test
    fun `one title credited twice becomes one row`() {
        val merged = filmographyOf(
            listOf(
                credit(1, "Duel", job = "Director"),
                credit(1, "Duel", job = "Writer", character = "Man in car"),
            ),
        )

        assertEquals(1, merged.size)
        assertEquals("Man in car", merged.single().character)
        assertEquals("Director, Writer", merged.single().job)
    }

    // The billing is the interesting half: "as Marla Singer" says more about why this
    // person is on the row than "Producer" does.
    // Mutation applied to verify: took the first entry rather than the acting one
    // → test failed, the row billed the Producer credit and the character came back null.
    @Test
    fun `an acting billing outranks a crew job on the same title`() {
        val merged = filmographyOf(
            listOf(
                credit(2, "Fight Club", job = "Producer"),
                credit(2, "Fight Club", character = "Marla Singer"),
            ),
        )

        assertEquals("as Marla Singer", "as ${merged.single().character}")
        assertEquals("Marla Singer", merged.single().role)
    }

    // TMDB reports episode counts per credit, so a recurring role split across two
    // credits must not report the smaller of the two.
    // Mutation applied to verify: used minOrNull for the episode count and the first
    // entry's popularity → test failed with 3 episodes.
    @Test
    fun `merging keeps the largest episode count and popularity`() {
        val merged = filmographyOf(
            listOf(
                credit(3, "Fringe", type = MediaType.Series, episodeCount = 3, popularity = 4.0),
                credit(3, "Fringe", type = MediaType.Series, episodeCount = 17, popularity = 9.0),
            ),
        )

        assertEquals(17, merged.single().episodeCount)
        assertEquals(9.0, merged.single().popularity)
    }

    // Newest first, because that is the order anyone reads a filmography in.
    // Mutation applied to verify: sorted ascending → test failed with 1999 first.
    @Test
    fun `credits are ordered newest first`() {
        val ordered = filmographyOf(
            listOf(
                credit(1, "Older", year = "1999"),
                credit(2, "Newer", year = "2021"),
                credit(3, "Middle", year = "2010"),
            ),
        )

        assertEquals(listOf("Newer", "Middle", "Older"), ordered.map { it.title })
    }

    // An undated credit is an announced-but-unmade film. Descending on a null year
    // would otherwise float it above everything actually released.
    // Mutation applied to verify: flipped the year != null comparator to put nulls
    // first → test failed, "Untitled Sequel" led the list ahead of 2021.
    @Test
    fun `undated credits sink to the bottom`() {
        val ordered = filmographyOf(
            listOf(
                credit(1, "Untitled Sequel", year = null),
                credit(2, "Released", year = "2021"),
                credit(3, "Also released", year = "1999"),
            ),
        )

        assertEquals(listOf("Released", "Also released", "Untitled Sequel"), ordered.map { it.title })
    }

    // Mutation applied to verify: made Movies match MediaType.Series → test failed,
    // the movie filter returned the show.
    @Test
    fun `the type filter splits movies from series`() {
        val credits = listOf(
            credit(1, "A Movie", type = MediaType.Movie),
            credit(2, "A Show", type = MediaType.Series),
        )

        assertEquals(listOf("A Movie"), filmographyOf(credits, CreditFilter.Movies).map { it.title })
        assertEquals(listOf("A Show"), filmographyOf(credits, CreditFilter.Series).map { it.title })
        assertEquals(2, filmographyOf(credits, CreditFilter.All).size)
    }

    // The chip counts have to be the merged counts, or the chip promises more rows
    // than the list can show.
    // Mutation applied to verify: counted the raw credits instead of the merged ones
    // → test failed, All counted 3 rather than 2.
    @Test
    fun `chip counts match what the list will show`() {
        val counts = creditCountsOf(
            listOf(
                credit(1, "Duel", job = "Director"),
                credit(1, "Duel", job = "Writer"),
                credit(2, "A Show", type = MediaType.Series),
            ),
        )

        assertEquals(2, counts[CreditFilter.All])
        assertEquals(1, counts[CreditFilter.Movies])
        assertEquals(1, counts[CreditFilter.Series])
    }

    // What someone is known for is rarely whatever they did last, so this rail is
    // ordered by popularity rather than by year.
    // Mutation applied to verify: sorted by year like the filmography does → test
    // failed, "Forgettable" (2024) led the rail.
    @Test
    fun `known for leads with the most popular, not the most recent`() {
        val rail = knownForOf(
            listOf(
                credit(1, "Forgettable", year = "2024", popularity = 2.0),
                credit(2, "The Famous One", year = "1994", popularity = 90.0),
                credit(3, "Mid", year = "2015", popularity = 40.0),
            ),
        )

        assertEquals(listOf("The Famous One", "Mid", "Forgettable"), rail.map { it.title })
    }

    // The rail is nothing but posters, so a credit without one is held back — unless
    // holding them all back would leave an empty rail where a list of titles was.
    // Mutation applied to verify: dropped the ifEmpty fallback → test failed, a person
    // whose credits all lack posters got an empty rail.
    @Test
    fun `posterless credits are held back but never leave the rail empty`() {
        val mixed = knownForOf(
            listOf(
                credit(1, "No poster", popularity = 90.0, posterUrl = null),
                credit(2, "Has poster", popularity = 10.0),
            ),
        )
        assertEquals(listOf("Has poster"), mixed.map { it.title })

        val none = knownForOf(listOf(credit(1, "No poster", posterUrl = null)))
        assertEquals(listOf("No poster"), none.map { it.title })
    }

    // Mutation applied to verify: returned the full list before take(limit) → test
    // failed with 12 entries.
    @Test
    fun `known for is capped at the limit`() {
        val credits = (1..12).map { credit(it, "Title $it", popularity = it.toDouble()) }

        assertEquals(3, knownForOf(credits, limit = 3).size)
    }

    // Mutation applied to verify: used reference.year - born.year without the
    // periodUntil → test failed, a birthday later in the year read 63 instead of 62.
    @Test
    fun `age counts whole years only`() {
        assertEquals(62, ageOf(birthday = "1963-12-18", today = today))
        assertEquals(63, ageOf(birthday = "1963-08-12", today = today))
        assertEquals(62, ageOf(birthday = "1963-08-13", today = today))
    }

    // An age that keeps counting after a death is the kind of detail that makes a page
    // feel machine-written.
    // Mutation applied to verify: ignored deathday and measured against today → test
    // failed with 95.
    @Test
    fun `age stops at the day they died`() {
        assertEquals(83, ageOf(birthday = "1930-08-25", deathday = "2014-08-11", today = today))
    }

    // TMDB has partial and missing birthdays, and the sheet must not show "-1" for one.
    // Mutation applied to verify: dropped the reference < born guard → test failed, a
    // birthday in the future came back as 0 rather than as no age at all.
    @Test
    fun `an unparseable or future birthday has no age`() {
        assertNull(ageOf(birthday = null, today = today))
        assertNull(ageOf(birthday = "1963", today = today))
        assertNull(ageOf(birthday = "", today = today))
        assertNull(ageOf(birthday = "2027-01-01", today = today))
    }

    // Mutation applied to verify: dropped the isDigit check → test failed, "unkn"
    // came back as a year.
    @Test
    fun `only a real year is treated as one`() {
        assertEquals("1963", yearOf("1963-12-18"))
        assertNull(yearOf("unknown"))
        assertNull(yearOf(null))
        assertNull(yearOf("19"))
    }

    // A credit with no media type cannot be opened — there is no details endpoint to
    // call for it — and one with no title has nothing to draw.
    // Mutation applied to verify: defaulted a missing media_type to Movie → test
    // failed, the typeless credit survived alongside "Fine".
    @Test
    fun `credits nothing can be done with are dropped in the mapper`() {
        val person = PersonDetails(
            id = 7,
            name = "Nobody",
            combinedCredits = PersonCredits(
                cast = listOf(
                    PersonCredit(id = 1, title = "Fine", mediaType = DomainMediaType.Movie),
                    PersonCredit(id = 2, title = "No type", mediaType = null),
                    PersonCredit(id = 3, title = "", mediaType = DomainMediaType.Tv),
                ),
            ),
        ).toUiPerson()

        assertEquals(listOf("Fine"), person.credits.map { it.title })
    }

    // The sheet opens on the thin person from the card, so the mapper has to fill in
    // the parts that only the person endpoint knows.
    // Mutation applied to verify: kept blank strings rather than nulling them → test
    // failed, biography came back as "" instead of null.
    @Test
    fun `blank person fields become null rather than empty text`() {
        val person = PersonDetails(id = 9, name = "Someone", biography = "  ").toUiPerson()

        assertNull(person.biography)
        assertNull(person.placeOfBirth)
        assertNull(person.knownForDepartment)
        assertEquals("Person:9", person.id)
        assertTrue(person.credits.isEmpty())
    }
}
