package com.coveninja.cove.ui.pages.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.coveninja.cove.shared.data.ExploreState
import com.coveninja.cove.shared.data.SearchState
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.Person
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.model.toUiPerson
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.ChoicePillRow
import com.coveninja.cove.ui.pages.common.PageEmptyState
import com.coveninja.cove.ui.pages.common.PageLayoutDefaults
import com.coveninja.cove.ui.pages.common.ScrollToTopButton
import com.coveninja.cove.ui.state.LibraryIndex
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.state.SearchSession
import com.coveninja.cove.ui.state.rememberMediaActions
import kotlinx.coroutines.launch

/**
 * Search: a field, whatever it has found, and somewhere to start when it has found nothing
 * because nothing has been asked for yet.
 *
 * The page used to have no field of its own — the only one was the nav bar's, so its empty
 * state existed to send you back where you came from. Everything here follows from putting
 * the field on the page: typing searches ([SearchSession] debounces it), the results narrow
 * without a round trip, and the states either side of "here are your results" have content
 * rather than instructions.
 *
 * What is fixed and what scrolls is a deliberate split. The field and the toolbar are pinned,
 * because changing a filter is something you do *while* reading results and having to scroll
 * back to the controls to do it is the thing that makes a filter row go unused. The top
 * result and the results themselves scroll under them.
 */
@Composable
fun SearchPage(
    searchState: SearchState,
    exploreState: ExploreState,
    index: LibraryIndex,
    session: SearchSession,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onOpenMedia: (Media) -> Unit,
    onPlayMedia: (Media) -> Unit,
    onOpenPerson: (Person) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = rememberMediaActions(index)

    var filters by remember { mutableStateOf(SearchFilters()) }
    var layout by remember { mutableStateOf(SearchLayout.Grid) }
    var fieldFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Both entry points land here, and the field is the page's primary control, so it takes
    // focus on arrival as well as on every later request from the nav bar.
    LaunchedEffect(session.focusTick) { focusRequester.requestFocus() }

    val shown = rememberShownResults(searchState, session.submitted)

    // A genre id only means something against the results it was counted from, and the two
    // vocabularies disagree between films and series. Format, order and "hide saved" are
    // preferences about how you like to look at results, so those survive.
    LaunchedEffect(session.submitted) { filters = filters.copy(genreId = null) }

    val saved: (String) -> Boolean = { id -> index.categoryOf(id) != null }

    val visible = remember(shown.items, filters, index) {
        applySearchFilters(shown.items, filters, saved)
    }
    // Counted with every filter but the format applied, so each segment says how many results
    // choosing it would actually leave rather than how many exist in the abstract.
    val counts = remember(shown.items, filters.genreId, filters.hideSaved, index) {
        val pool = applySearchFilters(shown.items, filters.copy(type = null), saved)
        pool.count { it.type == MediaType.Movie } to pool.count { it.type == MediaType.Series }
    }
    // Facets follow the format switch but not the genre, so the row never narrows itself down
    // to the one pill already selected. Names come from the baked-in vocabulary: resolving
    // them costs nothing, where the backend's localized list would cost a request per visit.
    val facets = remember(shown.items, filters.type, filters.hideSaved, index) {
        genreFacets(applySearchFilters(shown.items, filters.copy(genreId = null), saved))
    }
    val top = remember(visible, shown.query) { topResult(visible, shown.query) }
    val rest = remember(visible, top) { visible.filterNot { it.id == top?.id } }
    // Narrowing to a format or a genre is a question about titles, and a person is neither.
    // The rail steps aside rather than sitting above results it is not part of.
    val people = remember(shown.people, shown.query, filters.type, filters.genreId) {
        if (filters.type != null || filters.genreId != null) {
            emptyList()
        } else {
            rankedPeople(shown.people, shown.query)
        }
    }

    val searching = searchState is SearchState.Loading
    // Stale content says so by receding rather than by vanishing: the layout holds, and what
    // is on screen is still readable while the replacement is on its way.
    val resultsAlpha by animateFloatAsState(
        targetValue = if (searching) 0.45f else 1f,
        animationSpec = tween(220),
        label = "SearchResultsAlpha",
    )

    val toggleList: (Media) -> Unit = { media ->
        if (index.categoryOf(media.id) != null) {
            actions.removeFromList(media)
        } else {
            actions.setListCategory(media, MyListCategory.WatchLater)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // `/` is the shortcut every search field on the web answers to. Guarded on the
            // field not already having focus, or typing a slash into a query would be
            // swallowed by the thing meant to make the field reachable.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.Slash &&
                    !fieldFocused
                ) {
                    focusRequester.requestFocus()
                    true
                } else {
                    false
                }
            },
    ) {
        SearchField(
            query = session.query,
            searching = searching,
            onQueryChange = session::type,
            onSubmit = { session.submit(session.query) },
            onClear = session::clear,
            focusRequester = focusRequester,
            onFocusChanged = { fieldFocused = it },
            modifier = Modifier.padding(
                horizontal = PageLayoutDefaults.HorizontalPadding,
                vertical = 4.dp,
            ),
        )

        Spacer(Modifier.height(14.dp))

        when {
            // Nothing searched, or the field emptied: the richest state on the page.
            shown.items.isEmpty() && !searching && searchState is SearchState.Idle -> SearchIdle(
                recents = session.recents,
                popular = remember(exploreState) { popularSeed(exploreState) },
                mediaCard = mediaCard,
                onSearch = session::submit,
                onRemoveRecent = session::removeRecent,
                onClearRecents = session::clearRecents,
                modifier = Modifier.weight(1f),
            )

            // A cold search: nothing to keep on screen, so draw the shape of the answer.
            shown.items.isEmpty() && searching -> SearchSkeleton(modifier = Modifier.weight(1f))

            searchState is SearchState.Failed -> PageEmptyState(
                iconName = "lucide:triangle-alert",
                title = "Search failed",
                message = (searchState as SearchState.Failed).message,
                actionLabel = "Try again",
                onAction = { session.submitted?.let(session::submit) },
                modifier = Modifier.weight(1f),
            )

            shown.items.isEmpty() -> NoMatches(
                query = session.submitted.orEmpty(),
                people = people,
                onOpenPerson = onOpenPerson,
                onSearch = session::submit,
                modifier = Modifier.weight(1f),
            )

            else -> {
                SearchToolbar(
                    filters = filters,
                    layout = layout,
                    facets = facets,
                    movieCount = counts.first,
                    seriesCount = counts.second,
                    onFiltersChange = { filters = it },
                    onLayoutChange = { layout = it },
                    modifier = Modifier.padding(
                        horizontal = PageLayoutDefaults.HorizontalPadding,
                    ),
                )

                ResultCount(
                    shown = visible.size,
                    total = shown.items.size,
                    modifier = Modifier.padding(
                        start = PageLayoutDefaults.HorizontalPadding,
                        end = PageLayoutDefaults.HorizontalPadding,
                        top = 10.dp,
                        bottom = 4.dp,
                    ),
                )

                if (visible.isEmpty()) {
                    PageEmptyState(
                        iconName = "lucide:filter-x",
                        title = "Nothing matches these filters",
                        message = "There are ${shown.items.size} results for “${shown.query}”, " +
                            "but none of them fit the filters you have on.",
                        actionLabel = "Clear filters",
                        onAction = { filters = filters.cleared() },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Results(
                        layout = layout,
                        query = shown.query,
                        top = top,
                        rest = rest,
                        people = people,
                        onOpenPerson = onOpenPerson,
                        alpha = resultsAlpha,
                        inList = { media -> index.categoryOf(media.id) != null },
                        mediaCard = mediaCard,
                        onOpenMedia = onOpenMedia,
                        onPlayMedia = onPlayMedia,
                        onToggleList = toggleList,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The results on screen, and the query they belong to, held across everything that follows.
 *
 * Three things need this, and all three are about not lying for a frame:
 *
 *  - **Refining must not blank the page.** `Loading` keeps the previous answer up rather
 *    than clearing it, which is most of what makes typing feel instant. `Failed` holds it
 *    too — the page draws the error over the top, but retrying then dims the old results
 *    rather than restarting from an empty page.
 *  - **The query has to be the one that produced these results**, not whatever is being
 *    typed over them, or the highlighting and the top-result pick end up describing results
 *    they are not drawn on.
 *  - **Derived during composition, not in an effect.** An effect runs a frame late, and for
 *    that frame the page would hold empty results beside a `Ready` state — long enough to
 *    flash "nothing matched" over a search that in fact found something.
 *
 * [submitted] is deliberately not a key: it is *sampled* when results land rather than
 * tracked, which is what makes the pairing hold without depending on the order the session
 * and the repository happen to publish their changes in. The holder is a plain object rather
 * than a `MutableState` because it is written during composition, and writing snapshot state
 * there invalidates the very composition doing the writing.
 */
@Composable
private fun rememberShownResults(state: SearchState, submitted: String?): ShownResults {
    val holder = remember { ShownHolder() }
    return remember(state) {
        val next = when (state) {
            is SearchState.Ready -> ShownResults(
                query = submitted.orEmpty(),
                items = state.results.map { it.toUiMedia() },
                people = state.people.map { it.toUiPerson() },
            )
            SearchState.Idle -> ShownResults()
            SearchState.Loading, is SearchState.Failed -> holder.value
        }
        holder.value = next
        next
    }
}

/**
 * The results, in whichever arrangement is selected, under the top-result card.
 *
 * The card is a header *inside* the scrolling container rather than a fixed block above it:
 * it is 300dp of a screen that also has a nav bar, a field and a toolbar on it, and pinning
 * it too would leave a letterbox of actual results.
 */
@Composable
private fun Results(
    layout: SearchLayout,
    query: String,
    top: Media?,
    rest: List<Media>,
    people: List<Person>,
    onOpenPerson: (Person) -> Unit,
    alpha: Float,
    inList: (Media) -> Boolean,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onOpenMedia: (Media) -> Unit,
    onPlayMedia: (Media) -> Unit,
    onToggleList: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val scrolled by remember(layout) {
        derivedStateOf {
            when (layout) {
                SearchLayout.Grid -> gridState.firstVisibleItemIndex > 2
                SearchLayout.List -> listState.firstVisibleItemIndex > 4
            }
        }
    }

    // One header for both arrangements, so the people rail and the top result scroll with
    // the results rather than eating fixed height above them.
    val header: (@Composable () -> Unit)? = if (top == null && people.isEmpty()) {
        null
    } else {
        {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                SearchPeopleRail(
                    people = people,
                    onOpenPerson = onOpenPerson,
                    modifier = Modifier.padding(bottom = if (top != null) 20.dp else 0.dp),
                )
                top?.let { media ->
                    SearchTopResult(
                        media = media,
                        query = query,
                        inList = inList(media),
                        onOpen = { onOpenMedia(media) },
                        onPlay = { onPlayMedia(media) },
                        onToggleList = { onToggleList(media) },
                    )
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        AnimatedContent(
            targetState = layout,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val enterDuration = if (reducedMotion) 0 else 200
                val movementDuration = if (reducedMotion) 0 else 240
                val exitDuration = if (reducedMotion) 0 else 140
                val enter = fadeIn(tween(enterDuration)) + slideInHorizontally(tween(movementDuration)) {
                    if (forward) it / 12 else -it / 12
                }
                val exit = fadeOut(tween(exitDuration)) + slideOutHorizontally(tween(enterDuration)) {
                    if (forward) -it / 12 else it / 12
                }
                enter togetherWith exit
            },
            label = "SearchLayout",
        ) { current ->
            when (current) {
                SearchLayout.Grid -> SearchResultGrid(
                    items = rest,
                    state = gridState,
                    mediaCard = mediaCard,
                    header = header,
                )

                SearchLayout.List -> SearchResultList(
                    items = rest,
                    state = listState,
                    query = query,
                    inList = inList,
                    onOpen = onOpenMedia,
                    onToggleList = onToggleList,
                    header = header,
                )
            }
        }

        ScrollToTopButton(
            visible = scrolled,
            onClick = {
                scope.launch {
                    when (layout) {
                        SearchLayout.Grid -> gridState.animateScrollToItem(0)
                        SearchLayout.List -> listState.animateScrollToItem(0)
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = PageLayoutDefaults.HorizontalPadding, bottom = 24.dp)
                .zIndex(10f),
        )
    }
}

/**
 * "24 matches", ticking over as filters change.
 *
 * Animated because it is the only confirmation that a filter did anything at all when the
 * change it made is off the bottom of the screen.
 */
@Composable
private fun ResultCount(shown: Int, total: Int, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = resultCountLabel(shown, total),
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 }) togetherWith
                (fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 2 })
        },
        label = "SearchResultCount",
    ) { label ->
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * A search that found nothing, with somewhere to go next.
 *
 * The old version of this offered "Search again", which is the one thing the viewer has
 * already tried. A theme is a different question rather than the same one repeated.
 */
@Composable
private fun NoMatches(
    query: String,
    people: List<Person>,
    onOpenPerson: (Person) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A name with no titles behind it is still an answer — and usually the one that was
        // being asked for, since a person's own name rarely matches a film's.
        SearchPeopleRail(
            people = people,
            onOpenPerson = onOpenPerson,
            modifier = Modifier.padding(horizontal = PageLayoutDefaults.HorizontalPadding),
        )
        PageEmptyState(
            iconName = "lucide:file-question",
            title = if (query.isBlank()) "Nothing found" else "Nothing matched “$query”",
            message = "Try a shorter title, a different spelling, or search for what it is " +
                "about rather than what it is called.",
            modifier = Modifier.weight(1f),
        )
        ChoicePillRow(
            modifier = Modifier.padding(
                horizontal = PageLayoutDefaults.HorizontalPadding,
                vertical = 8.dp,
            ),
        ) {
            SEARCH_MOODS.forEach { mood ->
                ChoicePill(
                    label = mood.label,
                    selected = false,
                    iconName = mood.icon,
                    onClick = { onSearch(mood.query) },
                )
            }
        }
    }
}

/**
 * What the idle page puts on its rail.
 *
 * The discover feed, already in memory and already paid for by whichever of Home or Explore
 * loaded it first. Deliberately not a `DiscoveryRepository.browse` call: a rail nobody asked
 * for is not worth an upstream request, and this one is only ever a starting point.
 */
private fun popularSeed(state: ExploreState, limit: Int = POPULAR_SEED_LIMIT): List<Media> =
    (state as? ExploreState.Ready)
        ?.let { it.movies + it.tv }
        ?.map { it.toUiMedia() }
        ?.filter { !it.posterUrl.isNullOrBlank() }
        ?.distinctBy(Media::id)
        ?.take(limit)
        .orEmpty()

/** Results, and the query that produced them, as one value so the two cannot drift apart. */
private data class ShownResults(
    val query: String = "",
    val items: List<Media> = emptyList(),
    val people: List<Person> = emptyList(),
)

/** Carries the last shown results across a state change. See [rememberShownResults]. */
private class ShownHolder(var value: ShownResults = ShownResults())

/** A rail's worth. More is scrolling for its own sake; Explore is where browsing lives. */
private const val POPULAR_SEED_LIMIT = 24
