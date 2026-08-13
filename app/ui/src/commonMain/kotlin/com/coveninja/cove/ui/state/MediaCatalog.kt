package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.coveninja.cove.shared.data.ContentRepository
import com.coveninja.cove.shared.data.ExploreState
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.data.SearchState
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toDomainMedia
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.shared.model.Media as DomainMedia
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield

// Cross-page content index. MyListPage upgrades thin LibraryEntry objects using
// the fuller domain items seen in home/explore/search; the details fetch also
// needs the domain object behind a UI id.
@Stable
class MediaCatalog(private val domainItems: List<DomainMedia>) {
    // Built once in init — safe to access from many recompositions without re-hashing.
    val domainByUiId: Map<String, DomainMedia> = domainItems.associateBy { it.toUiMedia().id }

    fun domainFor(media: Media): DomainMedia = domainByUiId[media.id] ?: media.toDomainMedia()

    fun enrich(entry: LibraryEntry): Media {
        // Prefer the richer domain object if one exists for this entry's TMDB id + type;
        // fall back to constructing a thin UI object from the library row itself.
        val domain = domainItems.firstOrNull {
            it.id == entry.tmdbId && it.mediaType == entry.mediaType
        }
        return domain?.toUiMedia() ?: entry.toUiMedia()
    }
}

// Wrapping construction in remember(homeState, exploreState, searchState) prevents
// the catalog — and all its maps — from being rebuilt on every recomposition of
// the host composable (which includes every pointer move during a drag).
@Composable
fun rememberMediaCatalog(
    homeState: HomeState,
    exploreState: ExploreState,
    searchState: SearchState,
    localizedLibraryItems: List<DomainMedia> = emptyList(),
): MediaCatalog = remember(homeState, exploreState, searchState, localizedLibraryItems) {
        val homeDomain = (homeState as? HomeState.Ready)?.items.orEmpty()
        val exploreDomain = (exploreState as? ExploreState.Ready)
            ?.let { it.movies + it.tv }
            .orEmpty()
        val searchDomain = (searchState as? SearchState.Ready)?.results.orEmpty()
        // Library hydration leads so a saved title's current-locale presentation wins even
        // during the brief hand-off while a locale-triggered Home refresh is starting.
        val allItems = (localizedLibraryItems + homeDomain + exploreDomain + searchDomain)
            .distinctBy { it.mediaType to it.id }
        MediaCatalog(allItems)
    }

/**
 * Resolves thin, persisted library rows back through TMDB for presentation.
 *
 * A library title is an offline fallback, not localized truth: it may have been added in a
 * different language or synced from another device. The stable TMDB id/type is the source of
 * truth. The identity key deliberately excludes status/rating so ordinary library mutations do
 * not refetch every title; locale and membership changes do.
 */
@Composable
fun rememberLocalizedLibraryMedia(
    entries: List<LibraryEntry>,
    content: ContentRepository,
    localeKey: String,
    initialContentReady: Boolean,
    knownItems: List<DomainMedia> = emptyList(),
): List<DomainMedia> {
    val identity = remember(entries, localeKey) {
        LibraryPresentationKey(
            localeKey,
            entries.map { "${it.mediaType.wireName}:${it.tmdbId}" }.sorted(),
        )
    }
    var localizedByIdentity by remember(content) {
        mutableStateOf<Map<LibraryPresentationIdentity, DomainMedia>>(emptyMap())
    }
    val wanted = remember(entries, localeKey) {
        entries.associateBy { entry -> entry.presentationIdentity(localeKey) }
    }
    val knownByIdentity = remember(knownItems, localeKey) {
        knownItems.associateBy { item ->
            LibraryPresentationIdentity(localeKey, item.id, item.mediaType)
        }
    }

    LaunchedEffect(content, identity, initialContentReady, knownByIdentity.keys) {
        if (!initialContentReady) return@LaunchedEffect

        // Discovery and first-viewport images get two complete frames before background
        // presentation hydration joins the network queue.
        withFrameNanos { }
        withFrameNanos { }
        yield()

        val missing = wanted.filterKeys { key ->
            key !in knownByIdentity && key !in localizedByIdentity
        }.values.toList()

        missing.chunked(LIBRARY_PRESENTATION_CONCURRENCY).forEach { batch ->
            val resolved = coroutineScope {
                batch.map { entry ->
                    async {
                        runCatching { content.media(entry.tmdbId, entry.mediaType) }
                            .getOrNull()
                            ?.let { entry.presentationIdentity(localeKey) to it }
                    }
                }.awaitAll().filterNotNull()
            }
            if (resolved.isNotEmpty()) localizedByIdentity += resolved
            // Let the small in-place update settle before hydrating another pair.
            withFrameNanos { }
        }
    }

    return remember(wanted.keys, knownByIdentity, localizedByIdentity) {
        wanted.keys.mapNotNull { key -> knownByIdentity[key] ?: localizedByIdentity[key] }
    }
}

private data class LibraryPresentationKey(val locale: String, val identities: List<String>)
private data class LibraryPresentationIdentity(
    val locale: String,
    val tmdbId: Int,
    val mediaType: com.coveninja.cove.shared.model.MediaType?,
)

private fun LibraryEntry.presentationIdentity(locale: String) = LibraryPresentationIdentity(
    locale = locale,
    tmdbId = tmdbId,
    mediaType = mediaType,
)

private const val LIBRARY_PRESENTATION_CONCURRENCY = 2
