package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.coveninja.cove.shared.data.ContentRepository
import com.coveninja.cove.ui.pages.search.MIN_QUERY_CHARS
import com.coveninja.cove.ui.pages.search.SEARCH_DEBOUNCE_MILLIS
import com.coveninja.cove.ui.pages.search.recordRecent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The query, the history, and the single in-flight search behind both of the app's search
 * entry points.
 *
 * It lives up here rather than inside `SearchPage` because `CoveAppContent` draws pages with
 * a `when` over the destination: everything a page remembers is thrown away the moment you
 * navigate off it, and history that forgets itself between two taps is not history. The nav
 * bar's overlay and the page's own field also have to agree on what is being searched, and
 * two sources of truth for one query is exactly how they stop agreeing.
 *
 * Three rules, each a decision rather than an accident:
 *
 *  - **One search in flight.** [ContentRepository] owns the result flow, so two searches
 *    racing can land out of order and leave the older one's results sitting under the newer
 *    one's query. Every path here cancels the previous job first.
 *  - **The nav bar does not debounce.** [setDraft] is deliberately inert. Typing in the
 *    overlay from Home would otherwise spend upstream requests filling a page nobody has
 *    navigated to yet; the overlay searches when it is told to, exactly as it did before.
 *  - **History records intent, not keystrokes.** `"b"`, `"bl"`, `"bla"` are one search being
 *    typed, not three searches. Only an explicit [submit] writes history immediately; a
 *    query that merely settled out of [type] is written by [rememberQuery], which the page
 *    calls when the viewer opens one of its results — the point at which the query is known
 *    to have been the one they meant.
 */
@Stable
class SearchSession(
    private val content: ContentRepository,
    private val scope: CoroutineScope,
) {
    /** The text in whichever field is on screen. Not necessarily what has been searched. */
    var query by mutableStateOf("")
        private set

    /** The query the results currently on screen belong to; null when nothing is searched. */
    var submitted by mutableStateOf<String?>(null)
        private set

    var recents by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * Bumped to ask the page's field to take focus.
     *
     * A counter rather than a boolean because the request has to fire again on a second press
     * of the same button, and a flag that is already true is indistinguishable from one that
     * was never lowered.
     */
    var focusTick by mutableStateOf(0)
        private set

    /*
     * The wait and the request are separate jobs on purpose.
     *
     * A keystroke always cancels the wait, and only ever cancels the request when it is
     * about to replace it. Cancelling a request without starting another one would strand
     * the repository at `Loading` with nothing left to move it off — reachable by typing a
     * character and deleting it again while its own search is in flight, which leaves the
     * page spinning forever over results that had already been fetched.
     */
    private var debounceJob: Job? = null
    private var requestJob: Job? = null

    /** Updates the text without searching — the nav-bar overlay's every keystroke. */
    fun setDraft(text: String) {
        query = text
    }

    /**
     * Updates the text and searches once typing pauses.
     *
     * Emptying the field resets to [com.coveninja.cove.shared.data.SearchState.Idle] rather
     * than leaving the last results stranded under a blank query. One or two characters left
     * mid-edit do neither: they leave whatever is on screen alone, because a query being
     * retyped is not a request to throw away the answer to the last one.
     */
    fun type(text: String) {
        query = text
        debounceJob?.cancel()

        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            // Idle again, but the text is left exactly as typed — rewriting the field's own
            // contents mid-keystroke is the sort of thing that eats a leading space and then
            // the character after it.
            submitted = null
            start("")
            return
        }
        // Both of these leave any in-flight request running. A query being retyped is not a
        // request to abandon the answer to the last one, and the search already under way is
        // either the one that is wanted or one that is about to be superseded anyway.
        if (trimmed.length < MIN_QUERY_CHARS) return
        if (trimmed.equals(submitted, ignoreCase = true)) return

        debounceJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            start(trimmed)
        }
    }

    /** Searches now, and records the query as history. */
    fun submit(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        query = trimmed
        recents = recordRecent(recents, trimmed)
        debounceJob?.cancel()
        start(trimmed)
    }

    /** Writes a query that was never explicitly submitted into the history. */
    fun rememberQuery(text: String) {
        recents = recordRecent(recents, text)
    }

    /** Empties the field and puts the page back to its idle state. */
    fun clear() {
        debounceJob?.cancel()
        query = ""
        submitted = null
        // Replaces the in-flight request rather than merely cancelling it, so the repository
        // always lands on a settled state. Blank is its own signal for Idle; nothing goes
        // upstream.
        start("")
    }

    fun requestFocus() {
        focusTick += 1
    }

    fun removeRecent(text: String) {
        recents = recents.filterNot { it.equals(text, ignoreCase = true) }
    }

    fun clearRecents() {
        recents = emptyList()
    }

    /** Replaces whatever request is running with one for [text]. */
    private fun start(text: String) {
        requestJob?.cancel()
        requestJob = scope.launch {
            // Set before the call, not after: the header names the query the page is working
            // on while the skeleton below it is still waiting for the answer.
            if (text.isNotEmpty()) submitted = text
            content.search(text)
        }
    }
}

@Composable
fun rememberSearchSession(): SearchSession {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    return remember(graph) { SearchSession(graph.content, scope) }
}
