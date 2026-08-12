package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.coveninja.cove.ui.model.Person
import com.coveninja.cove.ui.model.toUiPerson

/**
 * The person sheet's selection, shaped exactly like [MediaDetailsState] — the two sheets
 * behave the same way, so they hold their state the same way.
 */
@Stable
class PersonDetailsState {
    var selected by mutableStateOf<Person?>(null)
        private set
    var detailed by mutableStateOf<Person?>(null)
        private set
    var retained by mutableStateOf<Person?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    // Same arrangement as the title sheet: the enriched person while open, the last one
    // after dismiss, so the closing shared-element transition still has something to draw.
    val overlayPerson: Person? get() = if (selected != null) detailed else retained

    fun open(person: Person) { selected = person }
    fun dismiss() { selected = null }

    // Called from rememberPersonDetailsState's LaunchedEffect only.
    internal fun clearDetailed() { detailed = null }
    internal fun startLoading(person: Person) {
        detailed = person
        retained = person
        error = null
    }
    internal fun onLoaded(person: Person) {
        detailed = person
        retained = person
    }
    internal fun onError(message: String) { error = message }
}

@Composable
fun rememberPersonDetailsState(): PersonDetailsState {
    val graph = LocalAppGraph.current
    val state = remember { PersonDetailsState() }

    LaunchedEffect(state.selected?.tmdbId) {
        val selected = state.selected ?: run {
            state.clearDetailed()
            return@LaunchedEffect
        }
        // The card that was tapped already knows their name, their billing and their
        // face, so the sheet opens with those rather than with a spinner.
        state.startLoading(selected)

        runCatching { graph.content.person(selected.tmdbId).toUiPerson() }
            .onSuccess { loaded ->
                if (state.selected?.tmdbId == selected.tmdbId) {
                    // The fetch has no billing for this title — that only exists in the
                    // credits we came from — so the role is carried across by hand, as
                    // is the photo when TMDB's person record has none and the credit did.
                    state.onLoaded(
                        loaded.copy(
                            role = selected.role,
                            profileUrl = loaded.profileUrl ?: selected.profileUrl,
                        ),
                    )
                }
            }
            .onFailure { err ->
                if (state.selected?.tmdbId == selected.tmdbId) {
                    state.onError(err.message ?: "Unable to load this person")
                }
            }
    }

    return state
}
