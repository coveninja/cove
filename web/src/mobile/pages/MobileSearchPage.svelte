<script lang="ts">
  import { onMount } from "svelte";
  import { SvelteMap, SvelteSet } from "svelte/reactivity";
  import { Search } from "lucide-svelte";
  import { Spinner } from "$lib/components/ui/spinner";
  import MobileMediaCard from "../components/MobileMediaCard.svelte";
  import PersonCard from "../../components/cards/PersonCard.svelte";
  import ProviderCard from "../../components/cards/ProviderCard.svelte";
  import {
    api,
    type SearchResults,
    type Person,
    type Provider,
  } from "$lib/api";
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
  let loading = $state(false);
  let inputEl = $state<HTMLInputElement | null>(null);

  onMount(() => {
    if (inputEl && query.length === 0) {
      requestAnimationFrame(() => inputEl?.focus());
    }
  });

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
      // Keep at least one type selected.
      if (selectedTypes.length > 1) {
        selectedTypes = selectedTypes.filter((x) => x !== t);
      }
    } else {
      selectedTypes = [...selectedTypes, t];
    }
  }

  // ── Derived display lists ─────────────────────────────────────────────────────

  // Fold each matched person's known-for titles into the title sections.
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
      placeholder="Search movies & TV…"
      class="h-9 flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
      bind:value={query}
    />
  </div>

  {#if query.trim()}
    <!-- Type filter chips (swipeable) -->
    <div
      class="flex gap-2 overflow-x-auto px-4 pb-3 [scrollbar-width:none] [-webkit-overflow-scrolling:touch] [&::-webkit-scrollbar]:hidden"
    >
      {#each [["movie", "Movies"], ["tv", "TV"], ["person", "People"], ["provider", "Providers"]] as [key, label] (key)}
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
          More to Explore:
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
              Top Results
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
              People
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
              Providers
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
              Movies
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
              TV Shows
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
            No results found.
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
      <p class="text-sm">Type to search…</p>
    </div>
  {/if}
</div>
