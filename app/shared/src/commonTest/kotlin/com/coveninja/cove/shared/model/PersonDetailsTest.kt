package com.coveninja.cove.shared.model

import com.coveninja.cove.shared.network.CoveJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The person payload as TMDB actually sends it — nulls, snake_case, a mixed
 * `combined_credits` list, and a pile of keys the app does not model. Nothing here can
 * be caught by the compiler: a renamed key or a wrong nullability only fails at runtime,
 * against a live API this suite cannot call.
 */
private val TMDB_PERSON = """
{
  "adult": false,
  "also_known_as": ["Brad Pitt", "William Bradley Pitt"],
  "biography": "William Bradley Pitt is an American actor and film producer.",
  "birthday": "1963-12-18",
  "deathday": null,
  "gender": 2,
  "homepage": null,
  "id": 287,
  "imdb_id": "nm0000093",
  "known_for_department": "Acting",
  "name": "Brad Pitt",
  "place_of_birth": "Shawnee, Oklahoma, USA",
  "popularity": 34.9,
  "profile_path": "/cckcYc2v0yh1tc9QjRelptcOBko.jpg",
  "combined_credits": {
    "cast": [
      {
        "adult": false,
        "backdrop_path": "/hZkgoQYus5vegHoetLkCJzb17zJ.jpg",
        "genre_ids": [18],
        "id": 550,
        "original_language": "en",
        "original_title": "Fight Club",
        "overview": "A ticking-time-bomb insomniac...",
        "popularity": 61.4,
        "poster_path": "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg",
        "release_date": "1999-10-15",
        "title": "Fight Club",
        "video": false,
        "vote_average": 8.4,
        "vote_count": 26280,
        "character": "Tyler Durden",
        "credit_id": "52fe4250c3a36847f80149f3",
        "order": 1,
        "media_type": "movie"
      },
      {
        "adult": false,
        "first_air_date": "2015-04-14",
        "genre_ids": [35],
        "id": 62688,
        "name": "The Jinx",
        "origin_country": ["US"],
        "poster_path": null,
        "vote_average": 7.6,
        "character": "Self",
        "credit_id": "5566bd8ec3a3685cc4001b2f",
        "episode_count": 2,
        "media_type": "tv"
      }
    ],
    "crew": [
      {
        "adult": false,
        "genre_ids": [18, 80],
        "id": 550,
        "poster_path": "/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg",
        "release_date": "1999-10-15",
        "title": "Fight Club",
        "vote_average": 8.4,
        "credit_id": "52fe4250c3a36847f8014a01",
        "department": "Production",
        "job": "Producer",
        "media_type": "movie"
      }
    ]
  }
}
""".trimIndent()

class PersonDetailsTest {

    @Test
    fun `a real TMDB person decodes`() {
        val person = CoveJson.decodeFromString<PersonDetails>(TMDB_PERSON)

        assertEquals(287, person.id)
        assertEquals("Brad Pitt", person.name)
        assertEquals("1963-12-18", person.birthday)
        assertEquals("Shawnee, Oklahoma, USA", person.placeOfBirth)
        assertEquals("Acting", person.knownForDepartment)
        assertEquals("/cckcYc2v0yh1tc9QjRelptcOBko.jpg", person.profilePath)
        assertTrue(person.biography.startsWith("William Bradley Pitt"))
        assertEquals(listOf("Brad Pitt", "William Bradley Pitt"), person.alsoKnownAs)
    }

    // deathday is null for the living and absent from some records entirely; both have
    // to mean "no death date" rather than an exception.
    @Test
    fun `an explicit null and a missing key both mean absent`() {
        val living = CoveJson.decodeFromString<PersonDetails>(TMDB_PERSON)
        assertNull(living.deathday)

        val sparse = CoveJson.decodeFromString<PersonDetails>("""{"id": 1, "name": "Nobody"}""")
        assertNull(sparse.deathday)
        assertNull(sparse.birthday)
        assertEquals("", sparse.biography)
        assertTrue(sparse.combinedCredits.cast.isEmpty())
    }

    // combined_credits mixes films and shows in one array, and the two shapes disagree
    // about nearly every field: title vs name, release_date vs first_air_date.
    @Test
    fun `movie and tv credits both survive the same list`() {
        val credits = CoveJson.decodeFromString<PersonDetails>(TMDB_PERSON).combinedCredits

        val film = credits.cast.first()
        assertEquals(MediaType.Movie, film.mediaType)
        assertEquals("Fight Club", film.displayTitle)
        assertEquals("1999-10-15", film.displayDate)
        assertEquals("Tyler Durden", film.character)
        assertEquals(0, film.episodeCount)

        val show = credits.cast[1]
        assertEquals(MediaType.Tv, show.mediaType)
        assertEquals("The Jinx", show.displayTitle)
        assertEquals("2015-04-14", show.displayDate)
        assertEquals(2, show.episodeCount)
        assertNull(show.posterPath)

        val crew = credits.crew.single()
        assertEquals("Producer", crew.job)
        assertNull(crew.character)
    }
}
