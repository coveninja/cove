package com.coveninja.cove.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.coveninja.cove.shared.data.AccountState
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.ui.model.toDomainType
import com.coveninja.cove.ui.state.LocalAppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The flow's live state, and the one place it touches the graph.
 *
 * Holds a [OnboardingDraft] and the current step; every decision about *what* those mean is
 * delegated to the pure functions in `OnboardingModel.kt`. Both presentations — pointer and
 * remote — drive the same instance, which is what keeps the desktop and television flows from
 * drifting into two different products.
 *
 * **When writes happen.** Two of the steps have to write as the viewer works, because their
 * whole value is the answer coming back: an addon URL is only known to be good once the
 * repository has fetched it, and a sign-in is only known to have worked once the server says
 * so. Everything else — the profile name, the liked titles, the preferences — is held in the
 * draft and committed in one pass at the end, so backing out of a step genuinely backs out of
 * it rather than leaving a trail of half-applied changes behind.
 */
@Stable
class OnboardingController(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    val steps: List<OnboardingStep>,
    /**
     * A preview run, opened by `--onboarding` rather than by a fresh install.
     *
     * Everything the viewer chooses is still written — the point of the harness is to exercise
     * the real thing — except `onboardingDone`, which stays as it was so the next launch shows
     * the flow again. That flag is OR-merged on every write path in `LocalSettingsRepository`,
     * so once it is true nothing can put it back; the override is the only way to see this
     * screen twice against a real backend.
     */
    private val preview: Boolean,
    private val onFinished: () -> Unit,
) {
    var step: OnboardingStep by mutableStateOf(steps.first())
        private set

    var draft: OnboardingDraft by mutableStateOf(OnboardingDraft())
        private set

    /** True while the final commit is in flight, so the finish button can show it working. */
    var committing: Boolean by mutableStateOf(false)
        private set

    /**
     * Which way the last move went, so the step transition can slide with it.
     *
     * Held rather than derived: by the time the transition composes, the previous step is gone
     * and there is nothing left to compare against.
     */
    var advancing: Boolean by mutableStateOf(true)
        private set

    val canGoBack: Boolean get() = previousStep(step, steps) != null

    fun update(transform: OnboardingDraft.() -> OnboardingDraft) {
        draft = draft.transform()
    }

    fun advance() {
        val next = nextStep(step, steps)
        if (next == null) {
            finish()
            return
        }
        advancing = true
        step = next
    }

    fun back() {
        val previous = previousStep(step, steps) ?: return
        advancing = false
        step = previous
    }

    /** Jumps out of the flow entirely, committing whatever has been chosen so far. */
    fun skipAll() = finish()

    /**
     * Whether this title is already picked, matched on the UI id.
     *
     * The id carries the media type as well as the TMDB number
     * (see [com.coveninja.cove.ui.model.uiId]), which matters because a film and a series can
     * share a TMDB id and are different titles.
     */
    fun isPicked(id: String): Boolean = draft.likedTitles.any { it.id == id }

    fun togglePick(pick: OnboardingPick) {
        update {
            if (likedTitles.any { it.id == pick.id }) {
                copy(likedTitles = likedTitles.filterNot { it.id == pick.id })
            } else {
                copy(likedTitles = likedTitles + pick)
            }
        }
    }

    fun toggleGenre(id: Int) {
        update {
            copy(
                likedGenreIds = if (id in likedGenreIds) {
                    likedGenreIds - id
                } else {
                    likedGenreIds + id
                },
            )
        }
    }

    /** Records an addon the repository accepted. Called by the Sources step, not by commit. */
    fun rememberAddon(url: String) {
        update { if (url in addedAddons) this else copy(addedAddons = addedAddons + url) }
    }

    private fun finish() {
        if (committing) return
        committing = true
        scope.launch {
            commit()
            committing = false
            onFinished()
        }
    }

    /**
     * Writes the draft out, one independent piece at a time.
     *
     * Each write is wrapped on its own. A profile store that rejects a rename must not cost the
     * viewer the five titles they picked, and none of it may cost them the `onboardingDone`
     * flag — a first run that fails to record that it happened repeats itself on every launch,
     * which is the single worst outcome available here. So the flag is written last, and
     * unconditionally.
     */
    private suspend fun commit() {
        normalizedProfileName(draft.profileName)?.let { name ->
            runCatching {
                val profiles = graph.profiles.profiles.value
                val active = (profiles as? ProfilesState.Ready)?.let { ready ->
                    ready.profiles.firstOrNull { it.id == ready.activeProfileId }
                }
                if (active != null) {
                    graph.profiles.rename(active.id, name)
                } else {
                    graph.profiles.create(name)
                }
            }
        }

        // Watch Later rather than Watching: the viewer has said these look good, not that they
        // have started them. It is also what DiscoveryService reads to build a taste profile,
        // which is the whole reason the taste step writes anything at all.
        for (pick in draft.likedTitles) {
            runCatching {
                // A pick with no resolvable type cannot be stored — the library is keyed on
                // (tmdbId, mediaType) — so it is dropped rather than guessed into the wrong half.
                val type = pick.type.toDomainType() ?: return@runCatching
                graph.library.add(
                    tmdbId = pick.tmdbId,
                    mediaType = type,
                    title = pick.title,
                    posterPath = pick.posterUrl,
                    voteAverage = pick.voteAverage,
                )
                graph.library.setStatus(pick.tmdbId, type, LibraryStatus.WatchLater)
            }
        }

        runCatching {
            // Whole-object replace with no server-side merge: copy() from whatever is current,
            // never construct a fresh AppSettings. A field dropped here is written back as its
            // default, which would silently undo settings this flow never asked about.
            val current = (graph.settings.settings.value as? SettingsState.Ready)?.settings
                ?: return@runCatching
            graph.settings.update(
                current.copy(
                    subtitlesEnabled = draft.subtitlesEnabled,
                    autoPlay = draft.autoPlayNext,
                    autoSkipIntro = draft.autoSkipIntro,
                    onboardingDone = if (preview) current.onboardingDone else true,
                ),
            )
        }
    }
}

/**
 * Reads the host's capabilities once, at the point the flow opens.
 *
 * Once, deliberately: a step list that changed halfway through would move the ground under
 * someone mid-flow. `Loading` counts as available — the `Unavailable*` stand-ins report their
 * failure state synchronously on construction, so anything still loading is a real store that
 * has not answered yet.
 */
fun capabilitiesOf(graph: AppGraph): OnboardingCapabilities = OnboardingCapabilities(
    profiles = graph.profiles.profiles.value !is ProfilesState.Failed,
    account = graph.account.account.value !is AccountState.Unavailable,
)

@Composable
fun rememberOnboardingController(
    preview: Boolean,
    onFinished: () -> Unit,
): OnboardingController {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    // The controller outlives any single composition, so capturing the callback directly would
    // pin the very first lambda — the one that closes over the host's initial state. Reading it
    // through the state means the commit tells whoever is listening *now* that it finished.
    val currentOnFinished = rememberUpdatedState(onFinished)
    return remember(graph) {
        OnboardingController(
            graph = graph,
            scope = scope,
            steps = stepsFor(capabilitiesOf(graph)),
            preview = preview,
            onFinished = { currentOnFinished.value() },
        )
    }
}
