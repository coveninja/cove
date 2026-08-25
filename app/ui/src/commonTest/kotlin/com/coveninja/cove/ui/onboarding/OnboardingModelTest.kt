package com.coveninja.cove.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class OnboardingModelTest {

    // ---- stepsFor -------------------------------------------------------------------------

    @Test
    fun `a host without profiles does not show the profile step`() {
        val steps = stepsFor(OnboardingCapabilities(profiles = false, account = true))

        assertFalse(OnboardingStep.Profile in steps, "was: $steps")
    }

    @Test
    fun `a host without an account does not show the sync step`() {
        val steps = stepsFor(OnboardingCapabilities(profiles = true, account = false))

        assertFalse(OnboardingStep.Sync in steps, "was: $steps")
    }

    // Sources is the one step whose absence leaves an app that cannot play anything, so it must
    // survive every capability combination — it renders an explanation rather than disappearing.
    @Test
    fun `sources and taste survive every capability combination`() {
        val combinations = listOf(
            OnboardingCapabilities(profiles = true, account = true),
            OnboardingCapabilities(profiles = true, account = false),
            OnboardingCapabilities(profiles = false, account = true),
            OnboardingCapabilities(profiles = false, account = false),
        )

        combinations.forEach { capabilities ->
            val steps = stepsFor(capabilities)
            assertTrue(OnboardingStep.Sources in steps, "sources missing for $capabilities")
            assertTrue(OnboardingStep.Taste in steps, "taste missing for $capabilities")
        }
    }

    // The bookends are what the scaffold keys its centred layouts and its progress ends off.
    @Test
    fun `the flow always opens on welcome and closes on finish`() {
        val steps = stepsFor(OnboardingCapabilities(profiles = false, account = false))

        assertEquals(OnboardingStep.Welcome, steps.first())
        assertEquals(OnboardingStep.Finish, steps.last())
    }

    // ---- navigation -----------------------------------------------------------------------

    // The controller reads null here to decide the flow is over and should commit.
    @Test
    fun `there is no step after the last one`() {
        val steps = stepsFor(OnboardingCapabilities())

        assertNull(nextStep(OnboardingStep.Finish, steps))
    }

    // `indexOf` answers -1 for an absent step, and `getOrNull(-1 + 1)` is the first step.
    @Test
    fun `a step this host does not show has no next step`() {
        val steps = stepsFor(OnboardingCapabilities(account = false))

        assertNull(nextStep(OnboardingStep.Sync, steps))
    }

    @Test
    fun `there is no step before the first one`() {
        val steps = stepsFor(OnboardingCapabilities())

        assertNull(previousStep(OnboardingStep.Welcome, steps))
    }

    // A step this host does not show must not be navigable *from*, or a stale reference would
    // send someone to the end of the flow.
    @Test
    fun `a step this host does not show has no previous step`() {
        val steps = stepsFor(OnboardingCapabilities(account = false))

        assertNull(previousStep(OnboardingStep.Sync, steps))
    }

    @Test
    fun `forward then back returns to where it started`() {
        val steps = stepsFor(OnboardingCapabilities())

        val forward = nextStep(OnboardingStep.Taste, steps)
        assertEquals(OnboardingStep.Taste, previousStep(forward!!, steps))
    }

    // ---- advanceLabel ---------------------------------------------------------------------

    // The label is the flow's only pressure, and it has to reflect what the viewer did.
    @Test
    fun `an untouched step offers to be skipped`() {
        assertEquals("Skip for now", advanceLabel(OnboardingStep.Taste, OnboardingDraft()))
        assertEquals("Skip for now", advanceLabel(OnboardingStep.Sources, OnboardingDraft()))
    }

    @Test
    fun `genres alone are enough to count as engaging with the taste step`() {
        val draft = OnboardingDraft(likedGenreIds = setOf(28, 35))

        assertEquals("Continue", advanceLabel(OnboardingStep.Taste, draft))
    }

    @Test
    fun `a whitespace-only profile name still reads as skipped`() {
        val draft = OnboardingDraft(profileName = "   ")

        assertEquals("Skip for now", advanceLabel(OnboardingStep.Profile, draft))
    }

    @Test
    fun `the last step names where it is going`() {
        assertEquals("Start watching", advanceLabel(OnboardingStep.Finish, OnboardingDraft()))
    }

    // ---- normalizedProfileName ------------------------------------------------------------

    @Test
    fun `a blank name normalizes to null so nothing is written`() {
        assertNull(normalizedProfileName(""))
        assertNull(normalizedProfileName("  \t "))
    }

    @Test
    fun `internal whitespace runs collapse to single spaces`() {
        assertEquals("Sam Rivera", normalizedProfileName("  Sam   Rivera  "))
    }

    @Test
    fun `an absurdly long name is truncated`() {
        val name = normalizedProfileName("x".repeat(200))

        assertEquals(32, name?.length)
    }

    // ---- emblemFor ------------------------------------------------------------------------

    // The emblem is derived rather than stored precisely so every device agrees on it without
    // syncing anything, which only holds if the derivation is deterministic.
    @Test
    fun `the same name always yields the same emblem`() {
        assertEquals(emblemFor("Jamie"), emblemFor("Jamie"))
        assertEquals(emblemFor("Jamie"), emblemFor("  Jamie  "))
    }

    @Test
    fun `the initial skips leading punctuation`() {
        assertEquals("J", emblemFor("@Jamie").initial)
    }

    @Test
    fun `the initial is uppercased`() {
        assertEquals("J", emblemFor("jamie").initial)
    }

    @Test
    fun `an empty name still has something to draw`() {
        assertEquals("?", emblemFor("").initial)
    }

    // Different names should mostly get different colours, or the emblem stops distinguishing
    // anything. Not a guarantee — seven colours cannot separate every name — but a name and its
    // neighbour landing on the same swatch every time would mean the hash was not mixing.
    @Test
    fun `different names spread across the palette`() {
        val colors = listOf("Alex", "Jamie", "Sam", "Jordan", "Taylor", "Robin", "Casey")
            .map { emblemFor(it).color }
            .distinct()

        assertTrue(colors.size >= 3, "only ${colors.size} distinct colours: $colors")
    }

    // ---- manifestUrlProblem ---------------------------------------------------------------

    // The two mistakes people actually make. Both fail upstream with a message about the fetch
    // rather than about the paste, which is why they are caught here.
    @Test
    fun `a stremio install link is explained rather than attempted`() {
        val problem = manifestUrlProblem("stremio://example.com/manifest.json")

        assertTrue(problem?.contains("stremio://") == true, "was: $problem")
        assertFalse(manifestUrlSubmittable("stremio://example.com/manifest.json"))
    }

    @Test
    fun `something that is not a URL is refused`() {
        assertTrue(manifestUrlProblem("example.com/manifest.json") != null)
        assertFalse(manifestUrlSubmittable("example.com/manifest.json"))
    }

    // A doubtful URL is a hint, not a gate — the repository is the authority on whether a
    // manifest resolves, and plenty of addons serve one from a path that does not end in
    // manifest.json.
    @Test
    fun `an unusual but well-formed URL is doubted, not blocked`() {
        val url = "https://example.com/addon/configure"

        assertTrue(manifestUrlProblem(url) != null, "should warn")
        assertTrue(manifestUrlSubmittable(url), "should still be submittable")
    }

    @Test
    fun `a manifest URL with a query string raises no doubt`() {
        assertNull(manifestUrlProblem("https://example.com/manifest.json?config=abc"))
    }

    // ---- summaryFor -----------------------------------------------------------------------

    @Test
    fun `a fully skipped flow summarizes nothing`() {
        assertEquals(emptyList(), summaryFor(OnboardingDraft()))
    }

    @Test
    fun `a single item is described in the singular`() {
        val summary = summaryFor(OnboardingDraft(addedAddons = listOf("https://a/manifest.json")))

        assertEquals(1, summary.size)
        assertEquals("source", summary.first().label)
    }

    @Test
    fun `each kind of choice is counted separately`() {
        val summary = summaryFor(
            OnboardingDraft(
                addedAddons = listOf("https://a/manifest.json", "https://b/manifest.json"),
                likedTitles = listOf(pick(1), pick(2), pick(3)),
                likedGenreIds = setOf(28, 35),
            ),
        )

        assertEquals(listOf(2, 3, 2), summary.map { it.count })
        assertEquals(listOf("sources", "titles saved", "genres"), summary.map { it.label })
    }

    // ---- rankByGenre ----------------------------------------------------------------------

    // Reordering rather than filtering is the whole design of the taste wall: a narrow pick can
    // match almost nothing in a discover feed, and a wall that empties out as you choose reads
    // as broken.
    @Test
    fun `choosing a genre reorders the wall without shrinking it`() {
        val items = listOf(
            "action" to listOf(28),
            "comedy" to listOf(35),
            "docs" to listOf(99),
        )

        val ranked = rankByGenre(items, setOf(35)) { it.second }

        assertEquals(3, ranked.size, "nothing may be dropped")
        assertEquals("comedy", ranked.first().first)
    }

    @Test
    fun `with nothing chosen the catalog keeps its own order`() {
        val items = listOf("a" to listOf(1), "b" to listOf(2))

        assertEquals(items, rankByGenre(items, emptySet()) { it.second })
    }

    // A title is matched by sharing *a* chosen genre, not by being entirely composed of them.
    // Requiring all would match almost nothing, since most titles carry three or four ids.
    //
    // Place the multi-genre title second so an incorrect `all` implementation changes the order.
    @Test
    fun `a title matches on any one of its genres`() {
        val items = listOf("other" to listOf(99), "mixed" to listOf(28, 18, 53))

        val ranked = rankByGenre(items, setOf(18)) { it.second }

        assertEquals("mixed", ranked.first().first)
    }

    // ---- genre vocabulary -----------------------------------------------------------------

    // The bubbles write ids straight into the draft and the wall matches Media.genreIds against
    // them, so a duplicate would make one bubble toggle another's state.
    @Test
    fun `every genre bubble has a distinct id`() {
        assertEquals(
            OnboardingGenres.size,
            OnboardingGenres.map { it.id }.distinct().size,
            "duplicate genre ids: ${OnboardingGenres.map { it.id }}",
        )
    }

    // Two bubbles reading the same word is indistinguishable to the viewer even with different
    // ids behind them.
    @Test
    fun `every genre bubble has a distinct label`() {
        assertEquals(
            OnboardingGenres.size,
            OnboardingGenres.map { it.label }.distinct().size,
        )
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun pick(id: Int) = OnboardingPick(
        id = "Movie:$id",
        tmdbId = id,
        type = com.coveninja.cove.ui.model.MediaType.Movie,
        title = "Title $id",
        posterUrl = "",
        voteAverage = 0.0,
    )

}
