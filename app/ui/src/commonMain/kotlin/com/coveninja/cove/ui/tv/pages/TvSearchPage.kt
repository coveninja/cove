package com.coveninja.cove.ui.tv.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.SearchState
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.pages.search.topResult
import com.coveninja.cove.ui.state.SearchSession
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.components.TvButton
import com.coveninja.cove.ui.model.Person
import com.coveninja.cove.ui.model.toUiPerson
import com.coveninja.cove.ui.tv.components.TvMediaCard
import com.coveninja.cove.ui.tv.components.TvPosterCard
import com.coveninja.cove.ui.tv.components.TvMediaRow
import com.coveninja.cove.ui.tv.components.TvTextField
import com.coveninja.cove.ui.tv.components.TvWideCard
import com.coveninja.cove.ui.tv.focus.TvSectionScroll
import com.coveninja.cove.ui.tv.focus.tvFocusGroup

/** Scroll position worth keeping while another destination is on screen. */
@Stable
class TvSearchPageState internal constructor(
    internal val listState: LazyListState,
)

@Composable
fun rememberTvSearchPageState(): TvSearchPageState {
    val listState = rememberLazyListState()
    return remember(listState) { TvSearchPageState(listState) }
}

/**
 * Search, with the understanding that typing is the expensive part.
 *
 * Everything here is arranged around not making the viewer type. The field is never focused for
 * them, because focusing it is what raises the television's keyboard over the whole panel.
 * Recent queries sit directly underneath as buttons, so the second search for something is one
 * press rather than another round of hunting for letters. And the top result gets its own wide
 * card above the grid: on a remote, picking the obvious answer out of a grid costs several
 * presses, and it is usually the first thing anyone wanted.
 *
 * The ranking is the phone's `topResult`, and the results are the same `SearchSession` — the
 * nav-bar overlay, the phone page and this all search once and share the answer.
 */
@Composable
internal fun TvSearchPage(
    searchState: SearchState,
    session: SearchSession,
    pageState: TvSearchPageState,
    onOpenMedia: (Media) -> Unit,
    onOpenPerson: (Person) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = TvTheme.dimens
    var focusedSection by remember { mutableStateOf<Int?>(null) }

    val results = remember(searchState) {
        (searchState as? SearchState.Ready)?.results.orEmpty().map { it.toUiMedia() }
    }
    // Already populated by the same search that fills `results`, and previously discarded here
    // — so a television could reach a person from a title's cast but never search for one.
    val people = remember(searchState) {
        (searchState as? SearchState.Ready)?.people.orEmpty().map { it.toUiPerson() }
    }
    val submitted = session.submitted.orEmpty()
    val top = remember(results, submitted) { topResult(results, submitted) }
    // The top result is drawn on its own above the grid, so leaving it in the grid as well
    // would be the same poster twice on one screen.
    val rest = remember(results, top) { results.filter { it.id != top?.id } }
    val chunks = remember(rest, dimens.posterColumns) { rest.chunked(dimens.posterColumns) }

    // Section indices are computed, not written down. The recents strip and the top result each
    // appear only sometimes, and a hard-coded offset would quietly scroll the page to the row
    // above whenever one of them was missing.
    val headerCount = 1 + if (session.recents.isNotEmpty()) 1 else 0
    val topIndex = headerCount
    val peopleIndex = headerCount + if (top != null) 1 else 0
    val gridOffset = peopleIndex + if (people.isNotEmpty()) 1 else 0

    TvSectionScroll(
        state = pageState.listState,
        focusedIndex = focusedSection,
        margin = dimens.focusScrollMargin,
    )

    LazyColumn(
        state = pageState.listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = dimens.overscanVertical + 20.dp,
            bottom = dimens.overscanVertical + 32.dp,
        ),
    ) {
        item(key = "field") {
            Box(modifier = Modifier.padding(horizontal = dimens.overscanHorizontal)) {
                TvTextField(
                    value = session.query,
                    // `type` rather than `submit`: it debounces and keeps one search in flight,
                    // so the results are already behind the keyboard by the time it is put away.
                    onValueChange = session::type,
                    label = "Search",
                    placeholder = "Film or series",
                    onSubmit = { session.submit(session.query) },
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }
        }

        if (session.recents.isNotEmpty()) {
            item(key = "recents") {
                Row(
                    modifier = Modifier
                        .padding(top = 18.dp, start = dimens.overscanHorizontal)
                        .tvFocusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    session.recents.take(RECENT_QUERY_LIMIT).forEach { recent ->
                        TvButton(
                            label = recent,
                            onClick = { session.submit(recent) },
                            icon = "iconamoon:history",
                        )
                    }
                }
            }
        }

        if (results.isEmpty()) {
            item(key = "state") {
                Text(
                    text = when {
                        searchState is SearchState.Loading -> "Searching…"
                        submitted.isNotBlank() -> "Nothing found for “$submitted”."
                        else -> "Type a title to search."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = CoveColors.Neutral.MutedDim,
                    modifier = Modifier.padding(
                        top = 28.dp,
                        start = dimens.overscanHorizontal,
                    ),
                )
            }
            return@LazyColumn
        }

        top?.let { media ->
            item(key = "top") {
                TvMediaRow(
                    title = "Top result",
                    icon = "lucide:sparkles",
                    items = listOf(media),
                    key = Media::id,
                    onFocusChanged = { if (it) focusedSection = topIndex },
                    modifier = Modifier.padding(top = dimens.sectionSpacing),
                ) { item ->
                    TvWideCard(
                        imageUrl = item.backdropUrl ?: item.posterUrl,
                        title = item.title ?: item.name.orEmpty(),
                        caption = listOfNotNull(
                            item.type?.label,
                            (item.released ?: item.firstAirDate)?.take(4),
                        ).joinToString("  ·  "),
                        wideArt = !item.backdropUrl.isNullOrBlank(),
                        onClick = { onOpenMedia(item) },
                    )
                }
            }
        }

        if (people.isNotEmpty()) {
            item(key = "people") {
                TvMediaRow(
                    title = "People",
                    icon = "lucide:users",
                    items = people,
                    key = Person::id,
                    onFocusChanged = { if (it) focusedSection = peopleIndex },
                    modifier = Modifier.padding(top = dimens.sectionSpacing),
                ) { person ->
                    TvPosterCard(
                        posterUrl = person.profileUrl,
                        label = person.name,
                        onClick = { onOpenPerson(person) },
                    )
                }
            }
        }

        itemsIndexedChunks(chunks) { position, chunk ->
            TvMediaRow(
                title = null,
                items = chunk,
                key = Media::id,
                onFocusChanged = { if (it) focusedSection = position + gridOffset },
                modifier = Modifier.padding(top = 14.dp),
            ) { media ->
                TvMediaCard(media = media, onClick = { onOpenMedia(media) })
            }
        }
    }
}

/**
 * Chunked rows keyed by their first result.
 *
 * By index would be wrong: a new search reuses the same indices for entirely different titles,
 * and every row would be told it is the row it replaced.
 */
private fun LazyListScope.itemsIndexedChunks(
    chunks: List<List<Media>>,
    content: @Composable (Int, List<Media>) -> Unit,
) {
    chunks.forEachIndexed { index, chunk ->
        item(key = "grid-${chunk.firstOrNull()?.id ?: index}") { content(index, chunk) }
    }
}

/** More than this and the strip becomes its own hunt, which is what it exists to avoid. */
private const val RECENT_QUERY_LIMIT = 6
