package com.coveninja.cove.ui.onboarding

import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.fixture.FixtureAppGraph
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType as DomainMediaType
import com.coveninja.cove.ui.model.MediaType
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What finishing the flow actually writes.
 *
 * The controller is not a Compose component — it holds snapshot state and talks to an
 * `AppGraph` — so its commit can be driven directly against `FixtureAppGraph()`, whose settings
 * and library repositories are real in-memory implementations of the same interfaces the Kotlin
 * backend supplies. That covers the part of onboarding with lasting consequences: a first run
 * that writes the wrong thing is a first run nobody gets to repeat.
 *
 * Each assertion was mutation-checked before its comment was written.
 */
class OnboardingCommitTest {

    // The single most important guarantee in the whole flow. Without it the first run repeats
    // on every launch forever.
    // Mutation applied to verify: removed `onboardingDone` from the copy() → test failed.
    @Test
    fun `finishing records that onboarding happened`() = runTest {
        val graph = FixtureAppGraph()
        val controller = controllerFor(graph, this, preview = false)

        controller.finishAndAwait(this)

        assertTrue(graph.currentSettings().onboardingDone)
    }

    // `--onboarding` exists because `onboardingDone` is OR-merged on every write path in
    // LocalSettingsRepository and can never go back to false. A preview that set it would burn
    // the harness on its first use against a real backend.
    // Mutation applied to verify: made the copy() set `onboardingDone = true` unconditionally →
    // test failed, and the second `make onboarding` run would have shown the app instead.
    @Test
    fun `a preview run deliberately leaves the flag alone`() = runTest {
        val graph = FixtureAppGraph()
        val controller = controllerFor(graph, this, preview = true)

        controller.finishAndAwait(this)

        assertFalse(graph.currentSettings().onboardingDone)
    }

    // Everything the viewer chose is still written in preview mode — the point of the harness
    // is to exercise the real thing, not a mock of it.
    // Mutation applied to verify: skipped the whole commit when `preview` was set → test failed,
    // and the harness would have silently stopped testing anything.
    @Test
    fun `a preview run still writes every other choice`() = runTest {
        val graph = FixtureAppGraph()
        val controller = controllerFor(graph, this, preview = true)
        controller.update { copy(subtitlesEnabled = true) }

        controller.finishAndAwait(this)

        assertTrue(graph.currentSettings().subtitlesEnabled)
    }

    // Settings are a whole-object replace with no server-side merge, so the commit has to copy()
    // from the current value. Constructing a fresh AppSettings would write every field this flow
    // never asked about back to its default.
    // Mutation applied to verify: replaced the copy() with `AppSettings(onboardingDone = true)` →
    // test failed; defaultVolume came back as 1.0 having been set to 0.4, and in a real profile
    // that pattern silently resets thirty-odd settings.
    @Test
    fun `unrelated settings survive the commit`() = runTest {
        val graph = FixtureAppGraph()
        graph.settings.update(graph.currentSettings().copy(defaultVolume = 0.4, hideSpoilers = true))
        val controller = controllerFor(graph, this, preview = false)

        controller.finishAndAwait(this)

        val settings = graph.currentSettings()
        assertEquals(0.4, settings.defaultVolume)
        assertTrue(settings.hideSpoilers)
    }

    // Watch Later rather than Watching: the viewer said these look good, not that they started
    // them. It is also what DiscoveryService reads to build a taste profile, which is the only
    // reason the taste step writes anything.
    // Mutation applied to verify: wrote LibraryStatus.Watching → test failed. Home's "carry on
    // watching" rail would have opened on five titles nobody had played.
    @Test
    fun `liked titles land in the library as watch later`() = runTest {
        val graph = FixtureAppGraph()
        val controller = controllerFor(graph, this, preview = false)
        controller.togglePick(pick(987, MediaType.Movie))

        controller.finishAndAwait(this)

        val entry = graph.entryFor(987, DomainMediaType.Movie)
        assertNotNull(entry, "the pick was never stored")
        assertEquals(LibraryStatus.WatchLater, entry.status)
    }

    // A series pick must not be stored as a film. The library is keyed on (tmdbId, mediaType)
    // and the two vocabularies genuinely collide — the same number is a different title in each.
    // Mutation applied to verify: hardcoded MediaType.Movie in the commit → test failed, and a
    // saved series would have shown up as somebody else's movie.
    @Test
    fun `a series pick is stored as a series`() = runTest {
        val graph = FixtureAppGraph()
        val controller = controllerFor(graph, this, preview = false)
        controller.togglePick(pick(555, MediaType.Series))

        controller.finishAndAwait(this)

        assertNotNull(graph.entryFor(555, DomainMediaType.Tv), "stored under the wrong type")
    }

    // Un-picking has to actually un-pick. The draft is the only record of the selection, so a
    // toggle that appended twice would save something the viewer removed.
    // Mutation applied to verify: made togglePick always append → test failed with the entry
    // present after being deselected.
    @Test
    fun `a title that was picked and unpicked is not written`() = runTest {
        val graph = FixtureAppGraph()
        val controller = controllerFor(graph, this, preview = false)
        controller.togglePick(pick(4242, MediaType.Movie))
        controller.togglePick(pick(4242, MediaType.Movie))

        controller.finishAndAwait(this)

        assertEquals(null, graph.entryFor(4242, DomainMediaType.Movie))
    }

    // Mutation applied to verify: dropped the `normalizedProfileName` guard and always renamed →
    // test failed; the active profile came back named "" and the store had lost the name it had.
    @Test
    fun `a blank profile name leaves the existing profile untouched`() = runTest {
        val graph = FixtureAppGraph()
        val before = graph.activeProfileName()
        val controller = controllerFor(graph, this, preview = false)
        controller.update { copy(profileName = "   ") }

        controller.finishAndAwait(this)

        assertEquals(before, graph.activeProfileName())
    }

    // Mutation applied to verify: called `create` instead of `rename` when a profile was already
    // active → test failed with two profiles. A fresh install already has a primary profile, so
    // creating another would leave every device with a spare empty one.
    @Test
    fun `naming the profile renames the active one rather than adding another`() = runTest {
        val graph = FixtureAppGraph()
        val countBefore = graph.profileCount()
        val controller = controllerFor(graph, this, preview = false)
        controller.update { copy(profileName = "  Jamie  ") }

        controller.finishAndAwait(this)

        assertEquals(countBefore, graph.profileCount())
        assertEquals("Jamie", graph.activeProfileName())
    }

    // The commit wraps each write on its own so one failure cannot cost the others. The library
    // write is the one most likely to fail in the wild — it is per-title and hits the network on
    // a real backend — and it must not be able to take the flag down with it.
    // Mutation applied to verify: hoisted the whole commit body into a single runCatching → test
    // failed, because a pick with no resolvable type aborted before the settings write and
    // onboardingDone was never recorded.
    @Test
    fun `a pick that cannot be stored does not cost the rest of the commit`() = runTest {
        val graph = FixtureAppGraph()
        val controller = controllerFor(graph, this, preview = false)
        // A null type has no domain equivalent, so the library cannot key it.
        controller.togglePick(pick(1, type = null))
        controller.update { copy(subtitlesEnabled = true) }

        controller.finishAndAwait(this)

        assertTrue(graph.currentSettings().onboardingDone, "the flag was lost")
        assertTrue(graph.currentSettings().subtitlesEnabled, "preferences were lost")
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun controllerFor(
        graph: AppGraph,
        scope: TestScope,
        preview: Boolean,
    ) = OnboardingController(
        graph = graph,
        scope = scope,
        steps = stepsFor(capabilitiesOf(graph)),
        preview = preview,
        onFinished = { },
    )

    /**
     * Runs the commit and waits for it.
     *
     * `finish()` launches into the controller's scope rather than suspending, because on a real
     * host the commit happens while the finish screen is already on display. Under `runTest`
     * that coroutine only runs when the scheduler is advanced, so every assertion below would
     * otherwise read the state as it was before the commit and pass for the wrong reason.
     */
    private fun OnboardingController.finishAndAwait(scope: TestScope) {
        skipAll()
        scope.testScheduler.advanceUntilIdle()
    }

    private fun pick(tmdbId: Int, type: MediaType?) = OnboardingPick(
        id = "${type?.name ?: "Media"}:$tmdbId",
        tmdbId = tmdbId,
        type = type,
        title = "Title $tmdbId",
        posterUrl = "",
        voteAverage = 7.5,
    )
}

private fun AppGraph.currentSettings() =
    (settings.settings.value as SettingsState.Ready).settings

private fun AppGraph.entryFor(tmdbId: Int, type: DomainMediaType) =
    (library.entries.value as? LibraryState.Ready)
        ?.entries
        ?.firstOrNull { it.tmdbId == tmdbId && it.mediaType == type }

private fun AppGraph.activeProfileName(): String? =
    (profiles.profiles.value as? ProfilesState.Ready)?.let { ready ->
        ready.profiles.firstOrNull { it.id == ready.activeProfileId }?.name
    }

private fun AppGraph.profileCount(): Int =
    (profiles.profiles.value as? ProfilesState.Ready)?.profiles?.size ?: 0
