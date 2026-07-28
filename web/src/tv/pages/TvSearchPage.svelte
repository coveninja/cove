<script lang="ts">
  import * as m from "$lib/paraglide/messages.js";
  import { toggleSearchType, withKnownFor } from "$lib/search";
  import { SearchController } from "$lib/searchController.svelte";
  import { Search } from "lucide-svelte";
  import { Spinner } from "$lib/components/ui/spinner";
  import TvMediaCard from "../components/TvMediaCard.svelte";
  import PersonCard from "../../components/cards/PersonCard.svelte";
  import ProviderCard from "../../components/cards/ProviderCard.svelte";
  import { focusGroup, focusable } from "../focus/actions";
  import { getTopSearchResults } from "$lib/searchTopResults";

  // PersonCard / ProviderCard are reused as-is; their native focusable elements
  // are D-pad-reachable via the geometric fallback without annotation.

  // Grid columns: kept in sync with CSS below.
  const COLS = 6;

  let query = $state("");
  // inputEl is used to track the input element; NOT auto-focused on mount
  // (auto-focus would pop the TV IME before the user navigates to the field).
  let inputEl = $state<HTMLInputElement | null>(null);

  // ── Search state ─────────────────────────────────────────────────────────────

  const search = new SearchController();
  const data = $derived(search.data);
  const keywords = $derived(search.keywords);
  const loading = $derived(search.loading);

  // ── Type filter chips ─────────────────────────────────────────────────────────

  let selectedTypes = $state<string[]>(["movie", "tv", "person", "provider"]);
  const showMovie = $derived(selectedTypes.includes("movie"));
  const showTV = $derived(selectedTypes.includes("tv"));
  const showPerson = $derived(selectedTypes.includes("person"));
  const showProvider = $derived(selectedTypes.includes("provider"));

  function toggleType(t: string): void {
    selectedTypes = toggleSearchType(selectedTypes, t);
  }

  // ── Derived display lists ─────────────────────────────────────────────────────

  let movies = $derived(withKnownFor(data.movies, "movie", data.people));
  let tv = $derived(withKnownFor(data.tv, "tv", data.people));
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

  // ── Debounced search ──────────────────────────────────────────────────────────

  $effect(() => search.schedule(query));
</script>

<div class="flex h-full flex-col overflow-hidden px-4">
  <!--
    The input and filters must be separate vertical D-pad stops. Single-line
    text inputs retain Left/Right for caret movement, so placing filter chips
    in the input's row group makes them unreachable from the keyboard.

    The input is NOT auto-focused on mount; navigating to it opens the TV IME
    only when the user explicitly enters the search field.
  -->
  <div class="shrink-0 space-y-3 py-4">
    <div
      use:focusGroup={{
        id: "search-input",
        policy: { type: "row" },
        rememberFocus: false,
      }}
      class="flex flex-1 items-center gap-3 rounded-xl bg-secondary px-4 py-3"
      data-tv-focus-group="search-input"
    >
      {#if loading}
        <Spinner class="size-5 shrink-0" />
      {:else}
        <Search class="size-5 shrink-0 text-muted-foreground" />
      {/if}
      <input
        bind:this={inputEl}
        type="search"
        placeholder={m.search_placeholder()}
        class="flex-1 bg-transparent text-base outline-none placeholder:text-muted-foreground"
        bind:value={query}
      />
    </div>

    {#if query.trim()}
      <div
        use:focusGroup={{
          id: "search-filters",
          policy: { type: "row" },
          rememberFocus: true,
        }}
        class="flex items-center gap-3"
        data-tv-focus-group="search-filters"
      >
        {#each [["movie", m.search_movies()], ["tv", m.search_tv_shows()], ["person", m.search_people()], ["provider", m.search_providers()]] as [key, label] (key)}
          <button
            type="button"
            use:focusable={{ groupId: "search-filters" }}
            onclick={() => toggleType(key)}
            class="shrink-0 rounded-xl px-5 py-2 text-sm font-medium transition-colors {selectedTypes.includes(
              key,
            )
              ? 'bg-foreground text-background'
              : 'bg-secondary text-muted-foreground'}">{label}</button
          >
        {/each}
      </div>
    {/if}
  </div>

  {#if query.trim()}
    <!-- Keyword suggestions: one horizontal D-pad row. -->
    {#if !loading && keywords.length > 1}
      <div class="shrink-0">
        <p class="ml-4 mb-2 text-sm font-medium text-muted-foreground">
          {m.search_more_to_explore()}:
        </p>
        <div
          use:focusGroup={{
            id: "search-suggestions",
            policy: { type: "row" },
            rememberFocus: true,
          }}
          class="flex gap-2 overflow-x-hidden p-4"
          data-tv-focus-group="search-suggestions"
        >
          {#each keywords as kw (kw.id)}
            <button
              type="button"
              use:focusable={{ groupId: "search-suggestions" }}
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
                {m.search_top_results()}
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
                {m.search_people()}
              </h2>
              <div
                use:focusGroup={{
                  id: "search-people-row",
                  policy: { type: "row" },
                  rememberFocus: true,
                }}
                class="flex gap-5 overflow-x-hidden pb-1"
                data-tv-focus-group="search-people-row"
              >
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
                {m.search_providers()}
              </h2>
              <div
                use:focusGroup={{
                  id: "search-providers-row",
                  policy: { type: "row" },
                  rememberFocus: true,
                }}
                class="flex gap-4 overflow-x-hidden pb-1"
                data-tv-focus-group="search-providers-row"
              >
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
                {m.search_movies()}
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
                {m.search_tv_shows()}
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
              {m.search_no_results()}
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
      <p class="text-base">{m.search_start_typing()}</p>
    </div>
  {/if}
</div>
