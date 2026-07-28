<script lang="ts">
  import { onMount } from "svelte";
  import * as m from "$lib/paraglide/messages.js";
  import { toggleSearchType, withKnownFor } from "$lib/search";
  import { SearchController } from "$lib/searchController.svelte";
  import { Search } from "lucide-svelte";
  import { Spinner } from "$lib/components/ui/spinner";
  import MobileMediaCard from "../components/MobileMediaCard.svelte";
  import PersonCard from "../../components/cards/PersonCard.svelte";
  import ProviderCard from "../../components/cards/ProviderCard.svelte";
  import { type Person, type Provider } from "$lib/api";
  import type { Media } from "$lib/types/tmdb";
  import { getContext } from "svelte";
  import { Button } from "$lib/components/ui/button/index.js";
  import { animate, stagger } from "animejs";
  import { getTopSearchResults } from "$lib/searchTopResults";

  let {
    onSelectPerson,
    onSelectProvider,
  }: {
    onSelectPerson: (p: Person) => void;
    onSelectProvider: (p: Provider) => void;
  } = $props();

  // Provided by MobileApp via setContext("openMediaDetail", ...)
  const openMediaDetail = getContext<(m: Media) => void>("openMediaDetail");

  let query = $state("");
  let inputEl = $state<HTMLInputElement | null>(null);

  onMount(() => {
    if (inputEl && query.length === 0) {
      requestAnimationFrame(() => inputEl?.focus());
    }
  });

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

  // Fold each matched person's known-for titles into the title sections.
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

  // ── Results entrance stagger ──────────────────────────────────────────────────

  let container = $state<HTMLElement | null>(null);
  let lastSig = "";

  $effect(() => {
    const sig = `${movies.length}-${tv.length}-${people.length}`;
    if (
      sig === lastSig ||
      (movies.length === 0 && tv.length === 0 && people.length === 0)
    )
      return;
    lastSig = sig;
    const c = container;
    if (!c) return;
    requestAnimationFrame(() => {
      const cards = c.querySelectorAll("[data-search-card]");
      animate(Array.from(cards).slice(0, 15), {
        opacity: [0, 1],
        translateY: [8, 0],
        duration: 180,
        delay: stagger(25),
        ease: "outCubic",
      });
    });
  });
</script>

<!--
  Single scrollable container: the sticky input stays pinned at the top when
  scrolling through results. Safe-area padding is owned here.
-->
<div
  class="h-full overflow-y-auto overscroll-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
>
  <!-- Sticky search input -->
  <div
    class="sticky top-0 z-10 flex items-center gap-2 bg-background/95 px-4 pb-3 backdrop-blur-sm"
    style="padding-top: calc(0.75rem + var(--safe-top));"
  >
    {#if loading}
      <Spinner class="size-4 shrink-0" />
    {:else}
      <Search class="size-4 shrink-0 text-muted-foreground" />
    {/if}
    <input
      bind:this={inputEl}
      type="search"
      placeholder={m.search_placeholder()}
      class="h-9 flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
      bind:value={query}
    />
  </div>

  {#if query.trim()}
    <!-- Type filter chips (swipeable) -->
    <div
      class="flex gap-2 overflow-x-auto px-4 pb-3 [scrollbar-width:none] [-webkit-overflow-scrolling:touch] [&::-webkit-scrollbar]:hidden"
    >
      {#each [["movie", m.search_movies()], ["tv", m.search_tv_shows()], ["person", m.search_people()], ["provider", m.search_providers()]] as [key, label] (key)}
        <button
          type="button"
          onclick={() => toggleType(key)}
          class="shrink-0 rounded-full px-3 py-1.5 text-xs font-medium transition-colors {selectedTypes.includes(
            key,
          )
            ? 'bg-foreground text-background'
            : 'bg-secondary text-muted-foreground'}">{label}</button
        >
      {/each}
    </div>

    <!-- Keyword suggestions -->
    {#if !loading && keywords.length > 1}
      <div class="px-4 pb-3">
        <p class="mb-1.5 text-xs font-medium text-muted-foreground">
          {m.search_more_to_explore()}:
        </p>
        <div
          class="flex gap-2 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
        >
          {#each keywords as kw (kw.id)}
            <Button
              variant="ghost"
              size="xs"
              class="shrink-0 text-muted-foreground"
              onclick={() => (query = kw.name)}
            >
              {kw.name}
            </Button>
          {/each}
        </div>
      </div>
    {/if}

    <!-- Loading skeleton: shown when fetching and no results are visible yet -->
    {#if loading && !anyVisible}
      <div class="grid grid-cols-3 gap-2 px-4">
        {#each Array(12).fill(0) as _, idx (idx)}
          <div class="aspect-2/3 w-full animate-shimmer rounded-md"></div>
        {/each}
      </div>
    {/if}

    {#if !loading}
      <div bind:this={container} class="space-y-6 px-4 pb-8">
        <!-- Unified title ranking: always six cards at most (3 columns × 2 rows). -->
        {#if topResults.length > 0}
          <section class="space-y-2" data-search-section="top-results">
            <h2
              class="text-xs font-semibold uppercase tracking-wider text-muted-foreground"
            >
              {m.search_top_results()}
            </h2>
            <div class="grid grid-cols-3 gap-2" data-search-grid="top-results">
              {#each topResults as media (`${media.media_type}:${media.id}`)}
                <div data-search-card>
                  <MobileMediaCard
                    {media}
                    onclick={() => openMediaDetail?.(media)}
                  />
                </div>
              {/each}
            </div>
          </section>
        {/if}

        <!-- People: horizontal scroll row -->
        {#if showPerson && people.length > 0}
          <section class="space-y-2" data-search-section="people">
            <h2
              class="text-xs font-semibold uppercase tracking-wider text-muted-foreground"
            >
              {m.search_people()}
            </h2>
            <div
              class="flex gap-4 overflow-x-auto pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
            >
              {#each people as person (person.id)}
                <div class="w-20 shrink-0" data-search-card>
                  <PersonCard {person} onclick={(p) => onSelectPerson(p)} />
                </div>
              {/each}
            </div>
          </section>
        {/if}

        <!-- Providers: horizontal scroll row -->
        {#if showProvider && providers.length > 0}
          <section class="space-y-2" data-search-section="providers">
            <h2
              class="text-xs font-semibold uppercase tracking-wider text-muted-foreground"
            >
              {m.search_providers()}
            </h2>
            <div
              class="flex gap-3 overflow-x-auto pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
            >
              {#each providers as provider (provider.provider_id)}
                <div class="w-20 shrink-0" data-search-card>
                  <ProviderCard
                    {provider}
                    onclick={(p) => onSelectProvider(p)}
                  />
                </div>
              {/each}
            </div>
          </section>
        {/if}

        <!-- Movies: 3-col grid -->
        {#if showMovie && movies.length > 0}
          <section class="space-y-2" data-search-section="movies">
            <h2
              class="text-xs font-semibold uppercase tracking-wider text-muted-foreground"
            >
              {m.search_movies()}
            </h2>
            <div class="grid grid-cols-3 gap-2">
              {#each movies as media (media.id)}
                <div data-search-card>
                  <MobileMediaCard
                    {media}
                    onclick={() => openMediaDetail?.(media)}
                  />
                </div>
              {/each}
            </div>
          </section>
        {/if}

        <!-- TV: 3-col grid -->
        {#if showTV && tv.length > 0}
          <section class="space-y-2" data-search-section="tv">
            <h2
              class="text-xs font-semibold uppercase tracking-wider text-muted-foreground"
            >
              {m.search_tv_shows()}
            </h2>
            <div class="grid grid-cols-3 gap-2">
              {#each tv as media (media.id)}
                <div data-search-card>
                  <MobileMediaCard
                    {media}
                    onclick={() => openMediaDetail?.(media)}
                  />
                </div>
              {/each}
            </div>
          </section>
        {/if}

        {#if !anyVisible}
          <p class="pt-8 text-center text-sm text-muted-foreground">
            {m.search_no_results()}
          </p>
        {/if}
      </div>
    {/if}
  {:else}
    <!-- Empty / idle state -->
    <div
      class="flex h-48 flex-col items-center justify-center gap-2 text-muted-foreground"
    >
      <Search class="size-8 opacity-30" />
      <p class="text-sm">{m.search_start_typing()}</p>
    </div>
  {/if}
</div>
