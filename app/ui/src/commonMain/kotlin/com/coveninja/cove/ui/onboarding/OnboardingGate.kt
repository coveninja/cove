package com.coveninja.cove.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.SettingsState

/**
 * Decides whether this launch gets the app or the first-run flow.
 *
 * Shared by both roots so the decision is made once. Which flow to show is the caller's — the
 * pointer and remote presentations are different composables and neither root should know about
 * the other's — but *when* to show one is the same question on every host, and it has three
 * answers that are all easy to get subtly wrong:
 *
 * 1. **The app renders instead of the flow, never underneath it.** Composing the app behind an
 *    opaque overlay would start every page controller's requests for a screen nobody can see.
 * 2. **`Loading` renders nothing.** Settings arrive a beat after the first frame; treating "not
 *    known yet" as "not onboarded" flashes the welcome screen at the start of every launch.
 * 3. **`Failed` counts as onboarded.** A settings store that cannot be read is one the flow
 *    could never write its result to, so showing the flow would trap the viewer in it forever.
 */
@Composable
fun OnboardingGate(
    graph: AppGraph,
    forced: Boolean,
    flow: @Composable (preview: Boolean, onFinished: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val settingsState by graph.settings.settings.collectAsState()

    // Latched rather than read back out of settings. Finishing writes `onboardingDone` and that
    // write round-trips through the store, so without this the flow would linger for the frames
    // in between. In a forced preview it is the only thing that ends the flow at all, since the
    // flag is deliberately left untouched there.
    var dismissed by remember { mutableStateOf(false) }

    val onboarded: Boolean? = when (val state = settingsState) {
        is SettingsState.Ready -> state.settings.onboardingDone
        is SettingsState.Failed -> true
        SettingsState.Loading -> null
    }

    when {
        onboarded == null -> Unit
        dismissed || (onboarded && !forced) -> content()
        else -> flow(forced) { dismissed = true }
    }
}
