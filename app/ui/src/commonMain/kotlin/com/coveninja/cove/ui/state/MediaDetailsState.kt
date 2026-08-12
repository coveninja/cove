package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia

@Stable
class MediaDetailsState {
    var selected by mutableStateOf<Media?>(null)
        private set
    var detailed by mutableStateOf<Media?>(null)
        private set
    var retained by mutableStateOf<Media?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    // While the overlay is open, show the enriched detailed object; after dismiss,
    // keep the retained version alive so the shared-element exit transition has
    // something to draw during the close animation.
    val overlayMedia: Media? get() = if (selected != null) detailed else retained

    fun open(media: Media) { selected = media }
    fun dismiss() { selected = null }

    // Called from rememberMediaDetailsState's LaunchedEffect only.
    internal fun clearDetailed() { detailed = null }
    internal fun startLoading(media: Media) {
        detailed = media
        retained = media
        error = null
    }
    internal fun onLoaded(media: Media) {
        detailed = media
        retained = media
    }
    internal fun onError(message: String) { error = message }
}

@Composable
fun rememberMediaDetailsState(catalog: MediaCatalog): MediaDetailsState {
    val graph = LocalAppGraph.current
    val state = remember { MediaDetailsState() }

    // Mirrors the LaunchedEffect that was inline in CoveApp: fires whenever the
    // selected id changes, fetches the full details object, and guards every
    // assignment with a staleness check so a rapid open→open sequence doesn't
    // overwrite a newer request's result.
    LaunchedEffect(state.selected?.id) {
        val selected = state.selected ?: run {
            state.clearDetailed()
            return@LaunchedEffect
        }
        state.startLoading(selected)

        val domain = catalog.domainFor(selected)
        runCatching { graph.content.details(domain).toUiMedia() }
            .onSuccess { loaded ->
                if (state.selected?.id == selected.id) state.onLoaded(loaded)
            }
            .onFailure { err ->
                if (state.selected?.id == selected.id) {
                    state.onError(err.message ?: "Unable to load media details")
                }
            }
    }

    return state
}
