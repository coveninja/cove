<script lang="ts">
  import { SvelteMap, SvelteSet } from "svelte/reactivity";
  import { Search } from "lucide-svelte";
  import { Spinner } from "$lib/components/ui/spinner";
  import TvMediaCard from "../components/TvMediaCard.svelte";
  import PersonCard from "../../components/cards/PersonCard.svelte";
  import ProviderCard from "../../components/cards/ProviderCard.svelte";
  import { api, type SearchResults } from "$lib/api";
  import type { Media } from "$lib/types/tmdb";
  import { focusGroup, focusable } from "../focus/actions";
  import { getTopSearchResults } from "$lib/searchTopResults";

  // PersonCard / ProviderCard are reused as-is; their native focusable elements
  // are D-pad-reachable via the geometric fallback without annotation.

  // Grid columns: kept in sync with CSS below.
  const COLS = 6;

  let query = $state("");
  let loading = $state(false);
  // inputEl is used to track the input element; NOT auto-focused on mount
  // (auto-focus would pop the TV IME before the user navigates to the field).
  let inputEl = $state<HTMLInputElement | null>(null);

  // ── Search state ─────────────────────────────────────────────────────────────

  const empty = (): SearchResults => ({
    movies: [],
    tv: [],
    people: [],
    providers: [],
    title_order: [],
  });

  let data = $state<SearchResults>(empty());
  let keywords = $state<{ id: number; name: string }[]>([]);
  let qualityMap = new SvelteMap<number, string>();

  // ── Type filter chips ─────────────────────────────────────────────────────────

  let selectedTypes = $state<string[]>(["movie", "tv", "person", "provider"]);
  const showMovie = $derived(selectedTypes.includes("movie"));
  const showTV = $derived(selectedTypes.includes("tv"));
  const showPerson = $derived(selectedTypes.includes("person"));
  const showProvider = $derived(selectedTypes.includes("provider"));

  function toggleType(t: string): void {
    if (selectedTypes.includes(t)) {
      if (selectedTypes.length > 1) {
        selectedTypes = selectedTypes.filter((x) => x !== t);
      }
    } else {
      selectedTypes = [...selectedTypes, t];
    }
  }

  // ── Derived display lists ─────────────────────────────────────────────────────

  function withKnownFor(list: Media[], type: "movie" | "tv"): Media[] {
    const seen = new SvelteSet(list.map((m) => m.id));
    const out = [...list];
    for (const p of data.people) {
      for (const m of p.known_for ?? []) {
        if (m.media_type === type && !seen.has(m.id)) {
          seen.add(m.id);
          out.push(m);
        }
      }
    }
    return out;
  }

  let movies = $derived(withKnownFor(data.movies, "movie"));
  let tv = $derived(withKnownFor(data.tv, "tv"));
  let topResults = $derived(
    getTopSearchResults(data.movies, data.tv, data.title_order ?? [], {
      includeMovies: showMovie,
      includeTV: showTV,
    }),
  );
  let people = $derived(
    [...data.people].sort((a, b) => b.popularity - a.popularity),
  );
  let providers = $derived(data.providers);

  let anyVisible = $derived(
    (showMovie && movies.length > 0) ||
      (showTV && tv.length > 0) ||
      (showPerson && people.length > 0) ||
      (showProvider && providers.length > 0),
  );

  // ── Quality streaming ─────────────────────────────────────────────────────────

  let qualityAbort: AbortController | null = null;

  function streamQuality(ids: { id: number; type: "movie" | "tv" }[]): void {
    qualityAbort?.abort();
    if (ids.length === 0) return;
    qualityAbort = new AbortController();
    const typedIds = ids.map((m) => `${m.type}:${m.id}`);
    api
      .streamQualityBatch(
        typedIds,
        (id, quality) => {
          const numeric = Number(id.split(":").pop());
          if (!Number.isNaN(numeric)) qualityMap.set(numeric, quality);
        },
        qualityAbort.signal,
      )
      .catch(() => {});
  }

  // ── Debounced search ──────────────────────────────────────────────────────────

  let searchSeq = 0;

  $effect(() => {
    const q = query.trim();
    const timeout = setTimeout(async () => {
      const seq = ++searchSeq;
      qualityAbort?.abort();
      if (!q) {
        data = empty();
        keywords = [];
        qualityMap = new SvelteMap();
        return;
      }
      qualityMap = new SvelteMap();
      loading = true;

      const [res, kw] = await Promise.all([
        api.searchMulti(q).catch(() => empty()),
        api.getKeywords(q).catch(() => []),
      ]);

      if (seq !== searchSeq) return;

      data = {
        movies: res.movies ?? [],
        tv: res.tv ?? [],
        people: res.people ?? [],
        providers: res.providers ?? [],
        title_order: res.title_order ?? [],
      };
      keywords = kw ?? [];
      loading = false;

      streamQuality([
        ...data.movies.map((m) => ({ id: m.id, type: "movie" as const })),
        ...data.tv.map((m) => ({ id: m.id, type: "tv" as const })),
      ]);
    }, 400);

    return () => {
      clearTimeout(timeout);
      qualityAbort?.abort();
    };
  });

  // Suppress unused variable warning — qualityMap is populated but not
  // currently used in the TV template (same as mobile, which also omits quality
  // badges from the card itself).
  void qualityMap;
</script>

<div class="flex h-full flex-col overflow-hidden px-4">
  <!--
    Combined search-bar group: input (always visible) + type chips (when query
    is active) in ONE row focusGroup so there is only one vertical D-pad stop
    for the entire search header.

    The input is a native focusable and is auto-discovered by the focus engine
    via containment; chip buttons are registered via use:focusable.

    NOT auto-focused on mount — on TV the user navigates to it via D-pad,
    which triggers the IME naturally without popping it prematurely.
  -->
  <div
    use:focusGroup={{ id: "search-bar", policy: { type: "row" } }}
    class="shrink-0 flex items-center gap-3 py-4"
  >
    <div
      class="flex flex-1 items-center gap-3 rounded-xl bg-secondary px-4 py-3"
    >
      {#if loading}
        <Spinner class="size-5 shrink-0" />
      {:else}
        <Search class="size-5 shrink-0 text-muted-foreground" />
      {/if}
      <input
        bind:this={inputEl}
        type="search"
        placeholder="Search movies & TV…"
        class="flex-1 bg-transparent text-base outline-none placeholder:text-muted-foreground"
        bind:value={query}
      />
    </div>

    {#if query.trim()}
      {#each [["movie", "Movies"], ["tv", "TV"], ["person", "People"], ["provider", "Providers"]] as [key, label] (key)}
        <button
          type="button"
          use:focusable={{ groupId: "search-bar" }}
          onclick={() => toggleType(key)}
          class="shrink-0 rounded-xl px-5 py-2 text-sm font-medium transition-colors {selectedTypes.includes(
            key,
          )
            ? 'bg-foreground text-background'
            : 'bg-secondary text-muted-foreground'}">{label}</button
        >
      {/each}
    {/if}
  </div>

  {#if query.trim()}
    <!-- Keyword suggestions — native buttons, geometric fallback navigates them -->
    {#if !loading && keywords.length > 1}
      <div class="shrink-0">
        <p class="ml-4 mb-2 text-sm font-medium text-muted-foreground">
          More to Explore:
        </p>
        <div class="flex gap-2 overflow-x-hidden p-4">
          {#each keywords as kw (kw.id)}
            <button
              type="button"
              class="shrink-0 rounded-lg bg-secondary px-4 py-2 text-sm text-muted-foreground hover:text-foreground"
              onclick={() => (query = kw.name)}>{kw.name}</button
            >
          {/each}
        </div>
      </div>
    {/if}

    <!-- Results -->
    <div
      class="min-h-0 flex-1 overflow-y-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden px-4"
    >
      {#if !loading}
        <div class="space-y-8 pb-12">
          <!-- One six-card D-pad group preserving the unified title ranking. -->
          {#if topResults.length > 0}
            <section class="space-y-3" data-search-section="top-results">
              <h2
                class="text-xs font-semibold uppercase tracking-wider text-muted-foreground"
              >
                Top Results
              </h2>
              <div
                use:focusGroup={{
                  id: "search-top-results-grid",
                  policy: { type: "grid", cols: COLS },
                  rememberFocus: true,
                }}
                class="grid grid-cols-6 gap-4"
                data-search-grid="top-results"
              >
                {#each topResults as media (`${media.media_type}:${media.id}`)}
                  <TvMediaCard {media} groupId="search-top-results-grid" />
                {/each}
              </div>
            </section>
          {/if}

          <!-- People: horizontal strip — PersonCard is reused as-is; geometric
               fallback reaches its native interactive elements. -->
          {#if showPerson && people.length > 0}
            <section class="space-y-3" data-search-section="people">
              <h2
                class="text-xs font-semibold uppercase tracking-wider text-muted-foreground"
              >
                People
              </h2>
              <div class="flex gap-5 overflow-x-hidden pb-1">
                {#each people as person (person.id)}
                  <div class="w-24 shrink-0">
                    <PersonCard {person} />
                  </div>
                {/each}
              </div>
            </section>
          {/if}

          <!-- Providers: horizontal strip — same reuse pattern as PersonCard. -->
          {#if showProvider && providers.length > 0}
            <section class="space-y-3" data-search-section="providers">
              <h2
                class="text-xs font-semibold uppercase tracking-wider text-muted-foreground"
              >
                Providers
              </h2>
              <div class="flex gap-4 overflow-x-hidden pb-1">
                {#each providers as provider (provider.provider_id)}
                  <div class="w-24 shrink-0">
                    <ProviderCard {provider} />
                  </div>
                {/each}
              </div>
            </section>
          {/if}

          <!-- Movies: D-pad-navigable grid -->
          {#if showMovie && movies.length > 0}
            <section class="space-y-3" data-search-section="movies">
              <h2
                class="text-xs font-semibold uppercase tracking-wider text-muted-foreground"
              >
                Movies
              </h2>
              <div
                use:focusGroup={{
                  id: "search-movies-grid",
                  policy: { type: "grid", cols: COLS },
                  rememberFocus: true,
                }}
                class="grid grid-cols-6 gap-4"
              >
                {#each movies as media (media.id)}
                  <TvMediaCard {media} groupId="search-movies-grid" />
                {/each}
              </div>
            </section>
          {/if}

          <!-- TV Shows: D-pad-navigable grid -->
          {#if showTV && tv.length > 0}
            <section class="space-y-3" data-search-section="tv">
              <h2
                class="text-xs font-semibold uppercase tracking-wider text-muted-foreground"
              >
                TV Shows
              </h2>
              <div
                use:focusGroup={{
                  id: "search-tv-grid",
                  policy: { type: "grid", cols: COLS },
                  rememberFocus: true,
                }}
                class="grid grid-cols-6 gap-4"
              >
                {#each tv as media (media.id)}
                  <TvMediaCard {media} groupId="search-tv-grid" />
                {/each}
              </div>
            </section>
          {/if}

          {#if !anyVisible}
            <p class="pt-8 text-center text-base text-muted-foreground">
              No results found.
            </p>
          {/if}
        </div>
      {/if}
    </div>
  {:else}
    <!-- Idle state -->
    <div
      class="flex flex-1 flex-col items-center justify-center gap-3 text-muted-foreground"
    >
      <Search class="size-12 opacity-30" />
      <p class="text-base">Type to search…</p>
    </div>
  {/if}
</div>
