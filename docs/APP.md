# Compose app data guide

How the Compose app (`app/shared`, `app/ui`) gets data, what shape that data is
in, and how to do the things that are easy to get wrong. The backend contract
itself is in [API.md](API.md); this is the client side of it.

## Where fetching happens

Fetching is **not** a UI concern. Repositories own it:

```
CoveApi → Live*Repository       external or HTTP-bound data
backend services → Local*Repo  embedded desktop/Android data
                  ↓
AppGraph         bundled: .content, .library, .settings, .playback, .addons
  ↓
LocalAppGraph    CompositionLocal — every page reads the graph from here
  ↓
pages            collect the flow they need, render their own Loading/Failed/Ready
```

`LiveContentRepository` starts loading in its `init` block, so home and explore
are already in flight before the first frame. A page that collects
`graph.content.home` is subscribing to work that is already happening — it is
not triggering a fetch. Navigating away and back does not refetch.

Two kinds of call live on the repositories:

| Kind | Shape | Example |
|---|---|---|
| Ambient state | `StateFlow<XState>`, collected | `graph.content.home`, `graph.library.entries` |
| On-demand | `suspend fun`, awaited | `graph.content.details(...)`, `graph.content.episodes(...)` |

`search` is the odd one: it is a `suspend fun` that **returns Unit** and pushes
its result into the `searchResults` flow. Call it to kick a search off, then
collect `graph.content.searchResults` to read the outcome.

```kotlin
scope.launch { graph.content.search(query) }   // fire
val state by graph.content.searchResults.collectAsState()   // read
```

## Three things called "media"

This is the single biggest source of confusion. Three distinct types, all
reasonable, all easy to mix up:

| Type | Package | What it is |
|---|---|---|
| `Media` | `shared.model` | The wire/domain object. Snake-cased TMDB JSON. Referred to below as **domain media**. |
| `Media` | `ui.model` | What composables render. Flattened, display-ready, has a string `id`. |
| `LibraryEntry` | `shared.model` | A saved-list row. Title + poster + status + rating, nothing else. |

Conversions all live in `app/ui/.../ui/model/Media.kt`:

```kotlin
domainMedia.toUiMedia()      // domain  → UI   (thin)
libraryEntry.toUiMedia()     // library → UI   (thinner still)
contentDetails.toUiMedia()   // details → UI   (enriched)
uiMedia.toDomainMedia()      // UI      → domain, for passing back to a repository
recommendation.toMedia()     // moreLikeThis entry → UI media (thin)
```

The UI `id` is `"Movie:550"` / `"Series:1396"` — type and TMDB id together,
because a movie and a show can share a numeric id. `tmdbId` holds the bare
number for API calls. Use `id` as a `LazyColumn`/`LazyVerticalGrid` key and for
map lookups; use `tmdbId` when talking to a repository.

## Thin vs. enriched media

**Every `Media` in a list is thin.** Home, explore, search, and library all
produce media with these fields populated and nothing else:

> `id`, `tmdbId`, `title`, `name`, `overview`, `released`, `firstAirDate`,
> `posterUrl`, `backdropUrl`, `rating`, `type`, `popularity`, `adult`,
> `originalLanguage`

Everything else is at its default — **empty list or null, not missing data**:

> `logoUrl`, `runtimeMinutes`, `certification`, `status`, `genres`, `directors`,
> `writers`, `productionCompanies`, `originCountries`, `videos`, `cast`,
> `moreLikeThis`, `seasons`

`LibraryEntry.toUiMedia()` is thinner again: no `overview`, no `released`, no
`backdropUrl`. That is why `MediaCatalog.enrich()` exists — it swaps a library
row for the fuller domain object if any feed has already seen that title.

This is the trap: `media.cast` on a card is `[]`, and `media.genres.contains(x)`
is always false. Not because the title has no cast, but because nobody has
fetched it yet. Filtering a list of thin media by `cast` or `genres` silently
matches nothing.

### Enriching one

One call, which fans out to four endpoints in parallel (`details`, `images`,
`videos`, `similar`) and returns a `ContentDetails`:

```kotlin
val domain = catalog.domainFor(media)          // UI media → domain media
val enriched = graph.content.details(domain).toUiMedia()
```

`details()` **throws** on failure — it is the one repository call that does not
swallow errors into a state object, because the caller needs to know. Wrap it:

```kotlin
runCatching { graph.content.details(domain).toUiMedia() }
    .onSuccess { … }
    .onFailure { … }
```

In practice you rarely call this yourself: `MediaDetailsState` already does it
for the details overlay, with staleness guards for rapid open→open. Open the
overlay with `detailsState.open(media)` and read `detailsState.detailed`.

`tagline` and `spokenLanguages` exist on UI `Media` but are never populated by
anything today. Do not build UI on them without wiring them up first.

## Recipes

### Get the cast

There is no cast endpoint. Cast arrives inside `details()`, as
`details.credits.cast`, and `ContentDetails.toUiMedia()` maps it for you —
sorted by TMDB's `order`, so `cast.first()` is the top-billed actor.

```kotlin
val enriched = graph.content.details(catalog.domainFor(media)).toUiMedia()
enriched.cast.forEach { it.tmdbId; it.name; it.character; it.profileUrl }
```

Directors and writers come from the same payload's `credits.crew`, filtered by
job — `directors` is `job == "Director"`, `writers` is any of
`Writer`/`Screenplay`/`Teleplay`/`Story`. Both are already de-duplicated (by
TMDB person id, not by name) and both are `List<Person>`, not names, so either
can open a person sheet.

### Get a person

`content.person(tmdbId)` — the one call the person sheet makes. It is in
`commonMain`, so it works on Android too, and it returns `PersonDetails`:
biography, birthday, place of birth, department, photo, and `combinedCredits`
(their whole filmography, cast and crew together).

```kotlin
val person = graph.content.person(castMember.tmdbId).toUiPerson()
filmographyOf(person.credits)          // merged, newest first
knownForOf(person.credits)             // the poster rail, by popularity
```

`person.credits` is TMDB's raw list — the same title appears once per credit, so
someone who wrote *and* directed a film is in there twice. `filmographyOf` is
what merges those into one row per title; do not render `credits` directly.
`PersonCreditEntry.toMedia()` turns a credit into the thin `Media` the details
sheet opens with, the same way a recommendation card does.

### Search for people

`SearchState.Ready` carries `people` alongside `results`; both come from the one
`content.search(query)` call, so there is no second request to make. The people
arrive as `PersonDetails` with `knownFor` filled in instead of a filmography —
`toUiPerson()` folds either into `credits`, so the same helpers work on both.

```kotlin
val ready = graph.content.searchResults.value as? SearchState.Ready
rankedPeople(ready?.people.orEmpty().map { it.toUiPerson() }, query)
```

`rankedPeople` is what the rail draws: it re-tiers TMDB's popularity ordering so
the name that was actually typed wins, and bills each row with what they are
known for. Opening one hands a thin `Person` to the person sheet, which fetches
the rest.

### Get runtime, genres, certification

All on the enriched media: `runtimeMinutes` (falls back to a TV show's first
`episodeRunTime`), `genres`, `certification`. Certification is derived on the
domain object — US theatrical rating first, US content rating as fallback,
empty string if neither exists (mapped to `null` in the UI model).

### Get seasons and episodes

Seasons come with `details()`, but **their `episodes` list is always empty**.
Season 0 (specials) is filtered out. Episodes are a separate call per season,
so browsing a show does not fetch every episode of every season up front:

```kotlin
val episodes = graph.content.episodes(media.tmdbId, season.number)
    .map { it.toUiEpisode(media.id, season.number) }
```

Watch state is stored separately again, keyed by `(season, episode)`:

```kotlin
val watched = graph.library.episodeWatchStates(media.tmdbId, domainType)
episode.copy(watched = watched[season.number to episode.number] == true)
```

`MediaActions.episodesFor(media, season)` does all three steps and is what the
overlay uses. Prefer it.

### Play something

`PlaybackSession` owns the whole flow and is what the Watch button calls. Prefer
it — it resolves which episode to play, shows the source picker, applies the
resume point, and keeps progress saved:

```kotlin
val playback = rememberPlaybackSession()
playback.open(media)                                    // movie, or resume a series
playback.open(media, season = 1, episode = 2, episodeTitle = ep.title)
```

Underneath, two calls that must stay paired:

```kotlin
val sources = graph.playback.streams(media.tmdbId, domainType, season, episode)
val url = graph.playback.playUrl(sources.first(), season, episode)
```

**They must be paired.** `/api/play?url=` serves only URLs that a preceding
`/api/streams` call registered, so a raw upstream URL handed straight to the
player is a 403. This is also why the embedded Kotlin backend resolves streams
over its own loopback HTTP rather than calling the addon services in-process.

Video itself is a `VideoPlayerHost`, read from `LocalVideoPlayerHost`. `:ui` is
multiplatform and cannot see libmpv in `:desktop`, so the desktop entry point
provides the implementation and the local is **null everywhere else** — Watch
reports that playback is unavailable rather than crashing.

### Add an addon

Built-in metadata integrations are seeded, but no third-party stream provider is,
so this is the step that makes provider stream calls work.

```kotlin
graph.addons.addAddon("https://…/manifest.json")   // reloads the list itself
graph.addons.setAddonEnabled(addon.id, false)
```

Embedded desktop and Android hosts use `LocalAddonRepository`, which calls the
shared `AddonManager` directly; addon management does not depend on the loopback
HTTP host. Mutations do not throw on rejection — a bad manifest URL is ordinary
user input, so it lands in `graph.addons.lastError` for display next to the field.

Nuvio scraper repositories use the same repository only on hosts where
`supportsNuvio` is true. Each scraper is enabled individually: enabling only the
repository runs nothing. Android currently hides this section because the GraalJS
sandbox is a desktop process boundary, not Android-compatible code.

### Search

```kotlin
scope.launch { graph.content.search(query) }
```

Results land in `graph.content.searchResults` as `SearchState`. Do not
re-filter them client-side — the backend already matched the query, including
against people and keywords the thin results do not carry.

### Save, rate, mark watched

Go through `MediaActions`, never `graph.library` directly. The library API
requires an entry to exist before its status or rating can be set, and
`MediaActions` handles that ordering (plus the "not interested" case, which
removes the entry *and* records a dismissal):

```kotlin
val index = rememberLibraryIndex()
val actions = rememberMediaActions(index)

actions.setListCategory(media, MyListCategory.WatchLater)
actions.setRating(media, 8)
actions.toggleWatched(media)
actions.removeFromList(media)
```

Read current state from the index, not from a fetch:

```kotlin
index.categoryOf(media.id)   // MyListCategory?
index.entryOf(media.id)      // LibraryEntry?  — has rating, last-watched episode, …
```

### Change a setting

`PUT /api/settings` is a **whole-object replace with no merge**. Any field
missing from the body is written as its zero value, so a partial update
silently wipes unrelated settings. `SettingsEditor` makes that impossible by
construction — it always sends the full object:

```kotlin
val editor = rememberSettingsEditor(state.settings)
editor.edit { copy(autoPlay = enabled) }
```

Never call `graph.settings.update()` with a hand-built `AppSettings`.

### Image URLs

Backend endpoints are inconsistent: most return absolute proxied URLs
(`http://127.0.0.1:6969/api/img/w500/abc.jpg`), a few return raw TMDB paths
(`/abc.jpg`). Two helpers cover it — do not concatenate URLs by hand:

```kotlin
displayImageUrl(path, "w500")      // raw path → absolute; absolute → unchanged
tmdbImageSize(existingUrl, "w1280") // rewrite the size segment of an existing URL
```

`displayImageUrl` is for a path off a model. `tmdbImageSize` is for a URL you
already have and want at a different resolution — it handles both the TMDB
`/t/p/<size>/` and the proxy `/api/img/<size>/` layouts. Double-wrapping an
already-absolute URL yields a 400 from the backend.

### Icons

Icons are real [Iconify](https://icon-sets.iconify.design/) artwork, baked in at build
time. Any collection works — the tree currently uses `lucide`, `iconamoon`, and
`mingcute` together. Use the full Iconify name, collection prefix included:

```kotlin
IconifyIcon(icon = "mingcute:star-half-fill", modifier = Modifier.size(18.dp))
```

Adding an icon is two steps:

1. Write the call with the name from icon-sets.iconify.design.
2. Run `./gradlew generateIcons` — it scans the tree for `"prefix:name"` literals,
   fetches each collection from `api.iconify.design`, converts the SVG to path data,
   and rewrites `app/ui/.../ui/icons/CoveIcons.kt`.

`CoveIcons.kt` is **generated and checked in — never hand-edit it.** `generateIcons` is
the only thing that touches the network; the app never does, so icons work offline.

If you skip step 2 the build fails, by design:

```
> Icons used in source are missing from CoveIcons.kt — run: ./gradlew generateIcons
    solar:heart-bold
```

`verifyIcons` runs ahead of every Kotlin compile to enforce that. It exists because the
previous shim mapped unknown names onto a stand-in Material glyph, so a typo or an
unhandled name rendered as a plausible-looking wrong icon instead of failing.

Two things that will stop generation rather than silently degrade:

- **An SVG element the converter doesn't handle** (`line`, `polyline`, `polygon`,
  `ellipse`, …). Only `path`, `g`, `circle`, and `rect` are implemented; `circle` and
  `rect` are converted to path data, and `g` attributes are inherited by children.
- **A paint value other than `none`, `currentColor`, or `#hex`** — including a path with
  no `fill` at all, which SVG defines as black. Multi-colour collections will trip this.

Both throw from `generateIcons` naming the icon and the cause. Extend the converter in
`app/ui/build.gradle.kts` when you hit one.

Colour follows `LocalContentColor` unless you pass `tint`, so icons adapt to the theme
without per-call-site colouring. Names built dynamically escape the source scan — add
those to the `extraIcons` list in `app/ui/build.gradle.kts`.

## Adding a page

A page owns its own data and its own loading and error rendering. The shape:

```kotlin
@Composable
fun ThingPage(mediaCard: @Composable (Media, Modifier) -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val state by graph.content.thing.collectAsState()

    when (val s = state) {
        ThingState.Loading -> PageLoading("Loading things…")
        is ThingState.Failed -> PageError("Things could not load", s.message)
        is ThingState.Ready -> ThingReady(
            media = remember(s) { s.items.map { it.toUiMedia() } },
            mediaCard = mediaCard,
            modifier = modifier,
        )
    }
}
```

Wrap derived collections in `remember(state)`. `CoveApp` recomposes on every
pointer move during a drag, and unremembered `map`/`associateBy` over a feed
rebuilds on each of those frames.

Do not add a `graph` parameter — read `LocalAppGraph.current`. Do not hoist the
page's loading/error state into `CoveApp`.

## What stays in CoveApp

`CoveApp` is deliberately small and holds only what no single page can own:

- `SharedTransitionLayout` — posters morph into the details overlay across pages
- nav destination + both `NavBar` instances
- `DragSession` — dragging starts on a card in a page and ends on a drop target in the nav bar
- the details overlay and its selection
- the `pageMediaCard` slot, which binds a card to shared-transition, drag, and library actions at once

Shared state holders live in `app/ui/.../ui/state/`:

| Holder | Job |
|---|---|
| `MediaCatalog` | index of every domain media seen in home/explore/search; enriches library rows, resolves the domain object behind a UI id |
| `LibraryIndex` | library entries by UI id, plus the id→category map cards need |
| `MediaActions` | every library mutation, in the correct order |
| `MediaDetailsState` | details overlay selection + fetch |
| `DragSession` | drag payload, position, drop-target bounds |
| `SettingsEditor` | whole-object-safe settings writes |

## Traps

- **A thin media's empty `cast`/`genres`/`seasons` means "not fetched", not "none".** Only `details()` fills them.
- **`search()` returns Unit.** Read the flow.
- **`details()` throws.** Every other repository call folds errors into a `Failed` state.
- **Settings PUT is a whole-object replace.** Use `SettingsEditor.edit`.
- **`add` before `setStatus`/`setRating`.** Use `MediaActions`.
- **An empty `/api/discover` response is valid, not a failure** — OSS builds without the discover tag return `[]`, which is a `Ready` state with no items.
- **Season 0 is filtered out** of `seasons`, so `seasons.size` is not `numberOfSeasons`.
- **`/api/streams` must run before `/api/play`.** The play route only serves URLs a prior streams call registered, and the registry entry expires after 30 minutes.
- **`/api/streams` requires season and episode for TV** and rejects the request without them, so Watch on a series must resolve an episode first.
- **`AppSettings.defaultVolume` is 0..1; mpv's volume is 0..100.** Passing it through unscaled is near-silent audio, with nothing in the logs to say so.
- **No third-party stream provider ships by default.** A fresh profile resolves zero playable sources, so playback cannot be exercised end-to-end until one is added — Profile → Settings → Provider addons is where they go in.
- **Compose cannot draw over a `SwingPanel` except on Direct3D and Metal.** Interop blending is unsupported on an OpenGL render API, so anything composed over an embedded video panel is invisible on Linux. The in-app player reads frames back and draws them itself for this reason.
- **Fixtures are a real backend substitute.** `FixtureAppGraph` implements every repository, so `--backend-mode fixtures` exercises these paths with no server. If a recipe here does not work against fixtures, the fixture is missing a case.
