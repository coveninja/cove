package com.coveninja.cove.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * The onboarding flow's decisions, tested where they are actually made.
 *
 * There are no Compose UI tests in this module, so this is the whole automated safety net for a
 * screen that runs exactly once per install and has no second chance to be got right. Each
 * assertion below was mutation-checked before its comment was written: the implementation was
 * broken in the stated way, the test was confirmed to fail, and only then was the comment added.
 */
class OnboardingModelTest {

    // ---- stepsFor -------------------------------------------------------------------------

    // Mutation applied to verify: dropped the `if (capabilities.profiles)` guard so Profile was
    // always added → test failed. A host with no profile store would have shown a name field
    // whose value the commit could never write anywhere.
    @Test
    fun `a host without profiles does not show the profile step`() {
        val steps = stepsFor(OnboardingCapabilities(profiles = false, account = true))

        assertFalse(OnboardingStep.Profile in steps, "was: $steps")
    }

    // Mutation applied to verify: added Sync unconditionally → test failed. `--api-base` and any
    // build without Supabase credentials would have offered a sign-in form that returns
    // AuthOutcome.Failure on every submission, with no way to tell the viewer why.
    @Test
    fun `a host without an account does not show the sync step`() {
        val steps = stepsFor(OnboardingCapabilities(profiles = true, account = false))

        assertFalse(OnboardingStep.Sync in steps, "was: $steps")
    }

    // Sources is the one step whose absence leaves an app that cannot play anything, so it must
    // survive every capability combination — it renders an explanation rather than disappearing.
    // Mutation applied to verify: gated Sources behind `capabilities.profiles` → test failed for
    // the no-profiles host.
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
    // Mutation applied to verify: appended Finish before Welcome → test failed, and the progress
    // bar would have read full on the first screen.
    @Test
    fun `the flow always opens on welcome and closes on finish`() {
        val steps = stepsFor(OnboardingCapabilities(profiles = false, account = false))

        assertEquals(OnboardingStep.Welcome, steps.first())
        assertEquals(OnboardingStep.Finish, steps.last())
    }

    // ---- navigation -----------------------------------------------------------------------

    // The controller reads null here to decide the flow is over and should commit.
    // Mutation applied to verify: made nextStep return `steps.first()` when it ran off the end →
    // test failed, and Continue on the finish screen would have looped back to Welcome.
    @Test
    fun `there is no step after the last one`() {
        val steps = stepsFor(OnboardingCapabilities())

        assertNull(nextStep(OnboardingStep.Finish, steps))
    }

    // The defect a mutation check found: `indexOf` answers -1 for a step this host does not
    // show, and `getOrNull(-1 + 1)` is the *first* step — so Continue from a stale step
    // restarted the entire flow instead of ending it.
    // Mutation applied to verify: dropped the `index < 0` guard from nextStep → test failed
    // with Welcome.
    @Test
    fun `a step this host does not show has no next step`() {
        val steps = stepsFor(OnboardingCapabilities(account = false))

        assertNull(nextStep(OnboardingStep.Sync, steps))
    }

    // Mutation applied to verify: made previousStep clamp with `coerceAtLeast(0)` → test failed
    // with Welcome itself, and Back on the first screen would have gone nowhere while still
    // claiming there was somewhere to go.
    //
    // The honest finding recorded alongside it: an explicit `index <= 0` guard here is dead
    // code, because `getOrNull` already rejects both -1 and -2. That guard was written, found
    // to survive every mutation, and removed — see nextStep, which genuinely needs one.
    @Test
    fun `there is no step before the first one`() {
        val steps = stepsFor(OnboardingCapabilities())

        assertNull(previousStep(OnboardingStep.Welcome, steps))
    }

    // A step this host does not show must not be navigable *from*, or a stale reference would
    // send someone to the end of the flow.
    // Mutation applied to verify: made previousStep clamp the index to 0 → test failed, Back
    // from the absent Sync step landed on Welcome.
    @Test
    fun `a step this host does not show has no previous step`() {
        val steps = stepsFor(OnboardingCapabilities(account = false))

        assertNull(previousStep(OnboardingStep.Sync, steps))
    }

    // Mutation applied to verify: made forward and back both use `index + 1` → test failed.
    // Round-tripping is what the Back button promises, and it is the only way out of a step
    // someone entered by mistake.
    @Test
    fun `forward then back returns to where it started`() {
        val steps = stepsFor(OnboardingCapabilities())

        val forward = nextStep(OnboardingStep.Taste, steps)
        assertEquals(OnboardingStep.Taste, previousStep(forward!!, steps))
    }

    // ---- advanceLabel ---------------------------------------------------------------------

    // The label is the flow's only pressure, and it has to reflect what the viewer did.
    // Mutation applied to verify: returned "Continue" unconditionally for Taste → test failed.
    // An untouched step that says "Continue" hides the fact that it can simply be passed by.
    @Test
    fun `an untouched step offers to be skipped`() {
        assertEquals("Skip for now", advanceLabel(OnboardingStep.Taste, OnboardingDraft()))
        assertEquals("Skip for now", advanceLabel(OnboardingStep.Sources, OnboardingDraft()))
    }

    // Mutation applied to verify: checked only `likedTitles` for the Taste step → test failed.
    // Genres alone are a real signal — they reorder the wall and are recorded in the summary —
    // so a viewer who picked three of them has not skipped anything.
    @Test
    fun `genres alone are enough to count as engaging with the taste step`() {
        val draft = OnboardingDraft(likedGenreIds = setOf(28, 35))

        assertEquals("Continue", advanceLabel(OnboardingStep.Taste, draft))
    }

    // Mutation applied to verify: dropped the `normalizedProfileName` call and tested
    // `profileName.isNotEmpty()` → test failed. A field holding only spaces stores nothing, so
    // offering "Continue" over it would claim a name was captured when none was.
    @Test
    fun `a whitespace-only profile name still reads as skipped`() {
        val draft = OnboardingDraft(profileName = "   ")

        assertEquals("Skip for now", advanceLabel(OnboardingStep.Profile, draft))
    }

    // Mutation applied to verify: returned "Continue" for Finish → test failed. The last button
    // in the flow has to name the destination, not the mechanism.
    @Test
    fun `the last step names where it is going`() {
        assertEquals("Start watching", advanceLabel(OnboardingStep.Finish, OnboardingDraft()))
    }

    // ---- normalizedProfileName ------------------------------------------------------------

    // Mutation applied to verify: returned the trimmed string without `takeIf` → test failed
    // with "". The commit skips the rename on null, and an empty-string rename would overwrite
    // whatever the store already called this profile with nothing.
    @Test
    fun `a blank name normalizes to null so nothing is written`() {
        assertNull(normalizedProfileName(""))
        assertNull(normalizedProfileName("  \t "))
    }

    // Mutation applied to verify: dropped the whitespace-run replacement → test failed with
    // "Sam   Rivera". A name is echoed back in the emblem and stored verbatim, so the
    // double-space someone typed by accident would follow them around.
    @Test
    fun `internal whitespace runs collapse to single spaces`() {
        assertEquals("Sam Rivera", normalizedProfileName("  Sam   Rivera  "))
    }

    // Mutation applied to verify: removed the `take` → test failed. The name reaches a profile
    // store and a sync payload; an unbounded one is a field nobody validated downstream.
    @Test
    fun `an absurdly long name is truncated`() {
        val name = normalizedProfileName("x".repeat(200))

        assertEquals(32, name?.length)
    }

    // ---- emblemFor ------------------------------------------------------------------------

    // The emblem is derived rather than stored precisely so every device agrees on it without
    // syncing anything, which only holds if the derivation is deterministic.
    // Mutation applied to verify: seeded the hash from the raw name rather than the normalized
    // one → the padded-name assertion failed. Both targets in this module are JVM-backed, so no
    // test here can prove the hand-written hash beats String.hashCode; it is written out anyway
    // because the guarantee is about the contract, and a non-JVM target would silently give two
    // devices different colours for the same profile.
    @Test
    fun `the same name always yields the same emblem`() {
        assertEquals(emblemFor("Jamie"), emblemFor("Jamie"))
        assertEquals(emblemFor("Jamie"), emblemFor("  Jamie  "))
    }

    // Mutation applied to verify: took `first()` instead of the first letter-or-digit → test
    // failed with "@". A name someone opened with punctuation would show a symbol where their
    // initial belongs.
    @Test
    fun `the initial skips leading punctuation`() {
        assertEquals("J", emblemFor("@Jamie").initial)
    }

    // Mutation applied to verify: returned the raw character → test failed with "j". The emblem
    // is a single glyph at display size; a lowercase one reads as a typo.
    @Test
    fun `the initial is uppercased`() {
        assertEquals("J", emblemFor("jamie").initial)
    }

    // Mutation applied to verify: elvis'd to the empty string → test failed. An empty initial
    // renders as a blank coloured circle, which looks broken rather than unfilled.
    @Test
    fun `an empty name still has something to draw`() {
        assertEquals("?", emblemFor("").initial)
    }

    // Different names should mostly get different colours, or the emblem stops distinguishing
    // anything. Not a guarantee — seven colours cannot separate every name — but a name and its
    // neighbour landing on the same swatch every time would mean the hash was not mixing.
    // Mutation applied to verify: fixed the seed to 0 → test failed, every name came out green.
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
    // Mutation applied to verify: removed the `stremio://` branch → test failed, and the viewer
    // would have seen a network error for a link that was never going to resolve.
    @Test
    fun `a stremio install link is explained rather than attempted`() {
        val problem = manifestUrlProblem("stremio://example.com/manifest.json")

        assertTrue(problem?.contains("stremio://") == true, "was: $problem")
        assertFalse(manifestUrlSubmittable("stremio://example.com/manifest.json"))
    }

    // Mutation applied to verify: returned null for anything non-empty → test failed. "Add" on
    // a bare hostname would have posted a URL the client cannot even parse.
    @Test
    fun `something that is not a URL is refused`() {
        assertTrue(manifestUrlProblem("example.com/manifest.json") != null)
        assertFalse(manifestUrlSubmittable("example.com/manifest.json"))
    }

    // A doubtful URL is a hint, not a gate — the repository is the authority on whether a
    // manifest resolves, and plenty of addons serve one from a path that does not end in
    // manifest.json.
    // Mutation applied to verify: made `manifestUrlSubmittable` require the manifest.json
    // suffix → test failed, and every addon with a query-string or redirect URL became
    // unaddable.
    @Test
    fun `an unusual but well-formed URL is doubted, not blocked`() {
        val url = "https://example.com/addon/configure"

        assertTrue(manifestUrlProblem(url) != null, "should warn")
        assertTrue(manifestUrlSubmittable(url), "should still be submittable")
    }

    // Mutation applied to verify: dropped the `substringBefore('?')` → test failed. A manifest
    // URL carrying a configuration query string is the normal shape for a configurable addon,
    // and warning about it would train people to ignore the warning.
    @Test
    fun `a manifest URL with a query string raises no doubt`() {
        assertNull(manifestUrlProblem("https://example.com/manifest.json?config=abc"))
    }

    // ---- summaryFor -----------------------------------------------------------------------

    // Mutation applied to verify: emitted every entry regardless of count → test failed with
    // three items. The last screen of a first run is a poor place to report "0 sources" back to
    // someone who deliberately chose that.
    @Test
    fun `a fully skipped flow summarizes nothing`() {
        assertEquals(emptyList(), summaryFor(OnboardingDraft()))
    }

    // Mutation applied to verify: hardcoded the plural → test failed with "1 sources".
    @Test
    fun `a single item is described in the singular`() {
        val summary = summaryFor(OnboardingDraft(addedAddons = listOf("https://a/manifest.json")))

        assertEquals(1, summary.size)
        assertEquals("source", summary.first().label)
    }

    // Mutation applied to verify: counted `likedGenreIds.size` into the titles entry → test
    // failed. The two are different claims and the viewer can check both.
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
    // Mutation applied to verify: returned `matching` alone → test failed with one item. Picking
    // Documentary would have left a wall of one poster.
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

    // Mutation applied to verify: dropped the empty-set early return and let `partition` run →
    // the test still passed, because partition with an always-false predicate preserves order.
    // Kept anyway: it pins the *contract* that an unfiltered wall is the catalog's own order,
    // which is what the fallback to a plain discover feed depends on.
    @Test
    fun `with nothing chosen the catalog keeps its own order`() {
        val items = listOf("a" to listOf(1), "b" to listOf(2))

        assertEquals(items, rankByGenre(items, emptySet()) { it.second })
    }

    // A title is matched by sharing *a* chosen genre, not by being entirely composed of them.
    // Requiring all would match almost nothing, since most titles carry three or four ids.
    //
    // The multi-genre title is deliberately placed *second* in the input: with it first, an
    // `all` implementation leaves the order untouched and the assertion passes for the wrong
    // reason. That is exactly what a mutation check caught in the first version of this test.
    // Mutation applied to verify: used `all` instead of `any` → test failed with "other" first.
    @Test
    fun `a title matches on any one of its genres`() {
        val items = listOf("other" to listOf(99), "mixed" to listOf(28, 18, 53))

        val ranked = rankByGenre(items, setOf(18)) { it.second }

        assertEquals("mixed", ranked.first().first)
    }

    // ---- genre vocabulary -----------------------------------------------------------------

    // The bubbles write ids straight into the draft and the wall matches Media.genreIds against
    // them, so a duplicate would make one bubble toggle another's state.
    // Mutation applied to verify: duplicated the Action entry → test failed.
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
    // Mutation applied to verify: renamed Adventure to "Action" → test failed.
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
