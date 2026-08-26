package com.coveninja.cove.ui.tv.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.AccountState
import com.coveninja.cove.shared.data.CalendarState
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.pages.common.MediaRailStateStore
import com.coveninja.cove.ui.pages.common.rememberMediaRailStateStore
import com.coveninja.cove.ui.pages.home.BacklogRow
import com.coveninja.cove.ui.pages.home.ContinueRow
import com.coveninja.cove.ui.pages.home.HomeController
import com.coveninja.cove.ui.pages.home.HomeHero
import com.coveninja.cove.ui.pages.home.HomeLayout
import com.coveninja.cove.ui.pages.home.HomeRail
import com.coveninja.cove.ui.pages.home.HomeSectionKind
import com.coveninja.cove.ui.pages.home.arrangeHomeSections
import com.coveninja.cove.ui.pages.home.backlogRows
import com.coveninja.cove.ui.pages.home.buildHomeRails
import com.coveninja.cove.ui.pages.home.continueWatchingRows
import com.coveninja.cove.ui.pages.home.heroPick
import com.coveninja.cove.ui.pages.home.homeLayout
import com.coveninja.cove.ui.pages.home.trendingRail
import com.coveninja.cove.ui.pages.mylist.calendar.availableNow
import com.coveninja.cove.ui.state.LibraryIndex
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.MediaCatalog
import com.coveninja.cove.ui.state.WatchProgressIndex
import com.coveninja.cove.ui.state.mediaFor
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.components.TvComingSoonPage
import com.coveninja.cove.ui.tv.components.TvHero
import com.coveninja.cove.ui.tv.components.TvMediaCard
import com.coveninja.cove.ui.tv.components.TvMediaRow
import com.coveninja.cove.ui.tv.components.TvWideCard
import com.coveninja.cove.ui.tv.focus.FocusOnAppear
import com.coveninja.cove.ui.tv.focus.TvSectionScroll

/** Scroll positions worth keeping while another destination is on screen. */
@Stable
class TvHomePageState internal constructor(
    internal val listState: LazyListState,
    internal val railStates: MediaRailStateStore,
)

@Composable
fun rememberTvHomePageState(): TvHomePageState {
    val listState = rememberLazyListState()
    val railStates = rememberMediaRailStateStore()
    return remember(listState, railStates) { TvHomePageState(listState, railStates) }
}

/**
 * Home on a television: one decisive hero, then what is genuinely left to watch.
 *
 * Every ranking decision here is the phone's, unchanged — `heroPick`, `continueWatchingRows`,
 * `backlogRows` and `buildHomeRails` are pure functions over the same library, watch progress
 * and discover feed, and a title that leads Home on a phone leads it here too. What differs is
 * entirely presentational, and it differs because it has to: the phone's rails reveal their
 * controls on hover and their cards open menus on secondary click.
 *
 * The greeting, the stats row and the upcoming strip are deliberately absent. A television is
 * read from across a room and every element competes with the artwork for the same attention;
 * what earns its place is what can be pressed.
 */
@Composable
internal fun TvHomePage(
    homeState: HomeState,
    controller: HomeController,
    pageState: TvHomePageState,
    index: LibraryIndex,
    watchProgress: WatchProgressIndex,
    catalog: MediaCatalog,
    onOpenMedia: (Media) -> Unit,
    onPlayMedia: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalAppGraph.current
    val dimens = TvTheme.dimens
    val calendarState by graph.calendar.calendar.collectAsState()
    val settingsState by graph.settings.settings.collectAsState()

    // The television has no editor for this — it is set on a phone or a desktop and synced —
    // but it honours the result, or a household that customises Home would find the change
    // stopped at the living room door.
    val layout = remember(settingsState, controller.catalogRails) {
        (settingsState as? SettingsState.Ready)?.settings
            ?.homeLayout(controller.catalogRails.map(HomeRail::section))
            ?: HomeLayout.Default
    }

    val initialContentReady = homeState !is HomeState.Loading

    // Same staging as the phone: let the first useful frame win the main thread, since none of
    // these requests change what is already on screen, they only upgrade it.
    LaunchedEffect(initialContentReady) {
        if (initialContentReady) {
            withFrameNanos { }
            graph.calendar.refresh(force = false)
        }
    }
    LaunchedEffect(initialContentReady, layout) {
        if (initialContentReady) {
            withFrameNanos { }
            withFrameNanos { }
            withFrameNanos { }
            controller.loadPersonal(layout)
            controller.loadCatalogs(layout)
        }
    }

    val trending = remember(homeState) {
        (homeState as? HomeState.Ready)?.items.orEmpty().map { it.toUiMedia() }
    }
    val calendarItems = remember(calendarState) {
        (calendarState as? CalendarState.Ready)?.items.orEmpty()
    }
    val continuing = remember(index, watchProgress, catalog, layout.continueRows) {
        continueWatchingRows(
            entries = index.entries,
            progressFor = watchProgress::progressFor,
            enrich = catalog::enrich,
            limit = layout.continueRows,
        )
    }
    val backlog = remember(calendarItems, index, catalog) {
        backlogRows(availableNow(calendarItems)) { item -> index.mediaFor(item, catalog) }
    }
    val hero = remember(continuing, backlog, trending) { heroPick(continuing, backlog, trending) }

    LaunchedEffect(initialContentReady, hero?.media?.id, layout) {
        if (initialContentReady && !layout.isHidden(HomeSectionKind.Hero)) {
            hero?.let { controller.enrichHero(it.media) }
        }
    }
    LaunchedEffect(initialContentReady, continuing) {
        if (initialContentReady) controller.loadEpisodeStills(continuing)
    }

    val rails = remember(controller.personalRails, controller.catalogRails, trending, layout) {
        // Hidden rails leave before assembly, not after: `buildHomeRails` drops a membership
        // rail whose titles have mostly appeared already, and one nobody can see would
        // otherwise spend that budget and take a visible rail down with it.
        buildHomeRails(
            (controller.personalRails + controller.catalogRails + trendingRail(trending))
                .filterNot { layout.isHidden(it.section) },
        )
    }

    val sections = remember(hero, continuing, backlog, rails, layout) {
        // The same arrangement the pointer shell runs, over this shell's own section type.
        // Greeting and Upcoming are never built here, so their keys simply go unmatched —
        // a television that renders fewer sections needs no special case.
        arrangeHomeSections(
            items = buildList {
                hero?.let { add(TvHomeSection.Hero(it)) }
                if (continuing.isNotEmpty()) add(TvHomeSection.Continue(continuing))
                if (backlog.isNotEmpty()) add(TvHomeSection.Backlog(backlog))
                rails.forEach { rail -> add(TvHomeSection.Rail(rail)) }
            },
            key = TvHomeSection::key,
            order = layout.order,
            hidden = layout.hidden,
        )
    }

    var focusedSection by remember { mutableStateOf<Int?>(null) }
    val heroFocusRequester = remember { FocusRequester() }
    TvSectionScroll(
        state = pageState.listState,
        focusedIndex = focusedSection,
        margin = dimens.focusScrollMargin,
    )
    // The hero's primary button is where a viewer's first press should land. Deferred by a
    // frame inside the helper, which is also what stops the press that opened this page from
    // activating whatever it focuses.
    //
    // Only when the hero actually leads the page, which it no longer always does. Hidden, its
    // requester is never attached and the request is swallowed unnoticed; merely *moved*, it
    // is worse than that — grabbing focus for a section halfway down would scroll straight
    // past everything the viewer put above it.
    FocusOnAppear(
        heroFocusRequester,
        enabled = sections.firstOrNull() is TvHomeSection.Hero,
    )

    if (sections.isEmpty()) {
        val account by graph.account.account.collectAsState()
        TvHomeEmpty(
            loading = homeState is HomeState.Loading,
            signedOut = account is AccountState.SignedOut,
            // An empty page has two very different causes now, and offering the wrong one is
            // worse than saying nothing: telling somebody to sign in when the real answer is
            // that they hid every section sends them to fix an account that is already fine.
            allHidden = hero != null || continuing.isNotEmpty() ||
                backlog.isNotEmpty() || rails.isNotEmpty(),
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        state = pageState.listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = dimens.overscanVertical + 32.dp),
    ) {
        itemsIndexed(
            items = sections,
            key = { _, section -> section.key },
            contentType = { _, section -> section::class },
        ) { position, section ->
            val report: (Boolean) -> Unit = { focused -> if (focused) focusedSection = position }
            when (section) {
                is TvHomeSection.Hero -> TvHero(
                    hero = section.hero,
                    art = controller.heroArt(section.hero.media),
                    onPlay = { onPlayMedia(section.hero.media) },
                    onOpenDetails = { onOpenMedia(section.hero.media) },
                    playFocusRequester = heroFocusRequester,
                    onFocusChanged = report,
                )

                is TvHomeSection.Continue -> TvMediaRow(
                    title = "Carry on watching",
                    subtitle = "Where you left off",
                    icon = "iconamoon:history",
                    items = section.rows,
                    key = ContinueRow::id,
                    state = pageState.railStates.stateFor("continue"),
                    onFocusChanged = report,
                    modifier = Modifier.padding(top = dimens.sectionSpacing),
                ) { row ->
                    TvWideCard(
                        // The still upgrades in place once its season fetch lands; until then
                        // the row's own backdrop is already on screen.
                        imageUrl = controller.stillFor(row) ?: row.artUrl,
                        title = row.displayTitle,
                        caption = row.caption,
                        watchFraction = row.watchFraction,
                        wideArt = controller.stillFor(row) != null || row.hasWideArt,
                        onClick = { onPlayMedia(row.media) },
                    )
                }

                is TvHomeSection.Backlog -> TvMediaRow(
                    title = "Ready to watch",
                    subtitle = "Aired while you were away",
                    icon = "lucide:tv",
                    items = section.rows,
                    key = BacklogRow::id,
                    state = pageState.railStates.stateFor("backlog"),
                    onFocusChanged = report,
                    modifier = Modifier.padding(top = dimens.sectionSpacing),
                ) { row ->
                    TvWideCard(
                        imageUrl = row.media.backdropUrl ?: row.media.posterUrl,
                        title = row.displayTitle,
                        caption = row.caption,
                        badge = row.badge,
                        wideArt = !row.media.backdropUrl.isNullOrBlank(),
                        // Opens rather than plays: "3 waiting" is a choice of episode, and
                        // guessing which one would be wrong as often as it was right.
                        onClick = { onOpenMedia(row.media) },
                    )
                }

                is TvHomeSection.Rail -> TvMediaRow(
                    title = section.rail.title,
                    subtitle = section.rail.subtitle,
                    icon = section.rail.icon,
                    items = section.rail.media,
                    key = Media::id,
                    state = pageState.railStates.stateFor(section.rail.id),
                    onFocusChanged = report,
                    modifier = Modifier.padding(top = dimens.sectionSpacing),
                ) { media ->
                    TvMediaCard(
                        media = media,
                        watchFraction = watchProgress.fractionFor(media.id),
                        onClick = { onOpenMedia(media) },
                    )
                }
            }
        }
    }
}

/** One row of the page, so its index in the list and its focus report cannot drift apart. */
private sealed interface TvHomeSection {
    val key: String

    data class Hero(val hero: HomeHero) : TvHomeSection {
        override val key: String get() = HomeSectionKind.Hero.key
    }

    data class Continue(val rows: List<ContinueRow>) : TvHomeSection {
        override val key: String get() = HomeSectionKind.ContinueWatching.key
    }

    data class Backlog(val rows: List<BacklogRow>) : TvHomeSection {
        override val key: String get() = HomeSectionKind.Backlog.key
    }

    // The rail's stable section, not its id: this key is what the viewer's saved order is
    // written against, and an order set on a phone has to mean the same thing here.
    data class Rail(val rail: HomeRail) : TvHomeSection {
        override val key: String get() = rail.section
    }
}

/**
 * An empty Home, saying what would fix it.
 *
 * The signed-out case is the one that matters and it is the one a fresh television is always
 * in. Nothing plays until providers arrive, providers arrive with an account, and there is no
 * way to guess that from a page that merely says it is empty — so it names the destination and
 * where to find it, which on this shell means the rail down the left.
 */
@Composable
private fun TvHomeEmpty(
    loading: Boolean,
    signedOut: Boolean,
    allHidden: Boolean,
    modifier: Modifier = Modifier,
) {
    TvComingSoonPage(
        title = when {
            loading -> "Loading your evening"
            allHidden -> "Every section is hidden"
            signedOut -> "Sign in to get started"
            else -> "Nothing here yet"
        },
        detail = when {
            loading -> "Fetching your library and what is trending."
            // Names where the control is, because it is deliberately not on this shell: the
            // layout is edited on a phone or a desktop and reaches the television by sync.
            allHidden -> "There is content to show, but no section is switched on. Turn one " +
                "back on under Settings, in Interface, on your phone or computer."
            signedOut -> "Open Profile in the menu on the left. Signing in brings your list " +
                "and the providers you set up elsewhere onto this television."
            else -> "Save something to your list and it will show up here."
        },
        icon = when {
            loading -> "lucide:loader-circle"
            allHidden -> "lucide:eye-off"
            signedOut -> "iconamoon:profile-circle"
            else -> "iconamoon:home"
        },
        modifier = modifier,
    )
}
