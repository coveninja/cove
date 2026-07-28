<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import * as Select from "$lib/components/ui/select/index.js";
  import * as ToggleGroup from "$lib/components/ui/toggle-group/index.js";
  import { ScrollArea } from "$lib/components/ui/scroll-area/index.js";
  import MediaCard from "./MediaCard.svelte";
  import PersonCard from "./cards/PersonCard.svelte";
  import ProviderCard from "./cards/ProviderCard.svelte";
  import { SvelteMap } from "svelte/reactivity";
  import { api } from "$lib/api";
  import { Button } from "$lib/components/ui/button";
  import { animate, splitText, stagger } from "animejs";
  import { onMount, tick } from "svelte";
  import * as m from "$lib/paraglide/messages.js";
  import { qualityTargets, withKnownFor } from "$lib/search";
  import { SearchController } from "$lib/searchController.svelte";
  import { getTopSearchResults } from "$lib/searchTopResults";

  let {
    query = $bindable(""),
    loading = $bindable(false),
    onSelectMedia,
    onSuggested,
    onWatch,
    onSelectPerson = () => {},
    onSelectProvider = () => {},
  } = $props();

  // ── Search state ────────────────────────────────────────────────────────────
  const search = new SearchController();
  const data = $derived(search.data);
  const keywords = $derived(search.keywords);
  // svelte-ignore non_reactive_update
  let qualityMap = new SvelteMap<number, string>();

  // ── Controls ──────────────────────────────────────────────────────────────────
  const sortOptions = [
    { value: "relevance", label: m.search_sort_relevance() },
    { value: "rating", label: m.search_sort_rating() },
    { value: "popularity", label: m.search_sort_popularity() },
    { value: "recommended", label: m.search_sort_recommended() },
    { value: "personal", label: m.search_sort_personal() },
  ] as const;

  // string (not a strict union) so it can bind cleanly to shadcn Select.
  let sortKey = $state<string>("relevance");
  const sortLabel = $derived(
    sortOptions.find((o) => o.value === sortKey)?.label ??
      m.search_sort_relevance(),
  );

  const typeOptions = [
    { key: "movie", label: m.search_movies() },
    { key: "tv", label: m.search_tv_shows() },
    { key: "person", label: m.search_people() },
    { key: "provider", label: m.search_providers() },
  ] as const;

  // ToggleGroup (multiple) binds to a string[] of the active type keys.
  let selectedTypes = $state<string[]>(["movie", "tv", "person", "provider"]);
  const showMovie = $derived(selectedTypes.includes("movie"));
  const showTV = $derived(selectedTypes.includes("tv"));
  const showPerson = $derived(selectedTypes.includes("person"));
  const showProvider = $derived(selectedTypes.includes("provider"));

  // ── Personalization (loaded once, used for the "recommended" / "my rating"
  // sort options). Genre scores approximate how recommended a title is from its
  // genres alone — no per-result detail fetch needed. ──────────────────────────
  let movieGenreScore = new SvelteMap<number, number>();
  let tvGenreScore = new SvelteMap<number, number>();
  let ratingByKey = new SvelteMap<string, number>(); // `${id}-${type}` -> rating

  onMount(async () => {
    const [insights, entries] = await Promise.all([
      api.discoverInsights().catch(() => null),
      api.libraryList().catch(() => []),
    ]);
    if (insights) {
      for (const g of insights.top_movie_genres)
        movieGenreScore.set(g.id, g.score);
      for (const g of insights.top_tv_genres) tvGenreScore.set(g.id, g.score);
    }
    for (const e of entries ?? []) {
      if (e.rating != null)
        ratingByKey.set(`${e.tmdb_id}-${e.media_type}`, e.rating);
    }
  });

  function recScore(m: Media): number {
    const map = m.media_type === "tv" ? tvGenreScore : movieGenreScore;
    let s = 0;
    for (const id of m.genre_ids ?? []) s += map.get(id) ?? 0;
    return s;
  }

  function ratingOf(m: Media): number {
    return ratingByKey.get(`${m.id}-${m.media_type}`) ?? -1;
  }

  // ── Derived display lists ───────────────────────────────────────────────────
  // Fold each matched person's known-for titles into the title sections, so a
  // search for "Jackie Chan" also surfaces his films under Movies/TV.
  function compareMedia(a: Media, b: Media): number {
    switch (sortKey) {
      case "rating":
        return (b.vote_average ?? 0) - (a.vote_average ?? 0);
      case "popularity":
        return (b.popularity ?? 0) - (a.popularity ?? 0);
      case "recommended":
        return recScore(b) - recScore(a);
      case "personal":
        return ratingOf(b) - ratingOf(a);
      default:
        return 0;
    }
  }

  // Sort a copy, keeping the original (relevance) index as the tiebreak so the
  // order is deterministic.
  function sortMedia(list: Media[]): Media[] {
    if (sortKey === "relevance") return [...list];
    return list
      .map((m, i) => ({ m, i }))
      .sort((a, b) => compareMedia(a.m, b.m) || a.i - b.i)
      .map(({ m }) => m);
  }

  let movies = $derived(
    sortMedia(withKnownFor(data.movies, "movie", data.people)),
  );
  let tv = $derived(sortMedia(withKnownFor(data.tv, "tv", data.people)));
  let topResults = $derived(
    getTopSearchResults(data.movies, data.tv, data.title_order ?? [], {
      includeMovies: showMovie,
      includeTV: showTV,
      compare: sortKey === "relevance" ? undefined : compareMedia,
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

  // ── "Results for" animation ──────────────────────────────────────────────────
  let resultsTextEl = $state<HTMLElement>();
  let displayQuery = $state("");
  let hasAnimated = $state(false);

  async function animateText(text: string): Promise<void> {
    if (!resultsTextEl) return;
    displayQuery = text;
    await tick();

    const { chars } = splitText(resultsTextEl, { chars: { wrap: "clip" } });
    animate(chars, {
      y: [{ to: ["100%", "0%"] }],
      duration: 750,
      ease: "out(3)",
      delay: stagger(50),
    });
    hasAnimated = true;
  }

  // ── Best-effort stream of cached download qualities for the title results ─────
  // Module-scoped controller (not per-call) so a new search always aborts
  // whatever NDJSON stream the previous one left running — otherwise a slow
  // prior response could keep writing into qualityMap after the user has
  // already moved on to a new query.
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
          // Backend echoes the canonical typed id ("movie:603") — qualityMap
          // stays keyed by the bare numeric tmdb id (how MediaCard reads it).
          const numeric = Number(id.split(":").pop());
          if (!Number.isNaN(numeric)) qualityMap.set(numeric, quality);
        },
        qualityAbort.signal,
      )
      .catch(() => {});
  }

  // ── Debounced search ──────────────────────────────────────────────────────────
  // Sequence-token guard (same idea as App.svelte's quickPlayToken): the
  // debounce timer only prevents overlapping *timers*, not overlapping
  // *fetches* — once a timer fires, its awaited request keeps running even
  // if a newer keystroke starts another one. Without this, a slower older
  // response can land after and overwrite a faster newer one.
  $effect(() => {
    qualityAbort?.abort();
    const cancel = search.schedule(query, {
      beforeLoad: async (currentQuery) => {
        qualityMap = new SvelteMap();
        await animateText(currentQuery);
      },
      afterLoad: (results) => streamQuality(qualityTargets(results)),
      onClear: () => {
        qualityMap = new SvelteMap();
      },
      onLoading: (value) => {
        loading = value;
      },
    });
    return () => {
      cancel();
      qualityAbort?.abort();
    };
  });
</script>

<div class="flex h-full flex-col p-6 pt-18">
  {#if query.length > 0}
    <div class="mb-4 shrink-0 space-y-3">
      <div class="flex text-2xl font-semibold" class:invisible={!hasAnimated}>
        {m.search_results_for()}
        <span class="size-1.5"></span>
        {#key displayQuery}
          <span class="text-accent" bind:this={resultsTextEl}
            >{displayQuery}</span
          >
        {/key}
      </div>

      <!-- Type filters + sort -->
      <div class="flex flex-wrap items-center gap-2">
        <ToggleGroup.Root
          type="multiple"
          variant="outline"
          size="sm"
          bind:value={selectedTypes}
        >
          {#each typeOptions as opt (opt.key)}
            <ToggleGroup.Item value={opt.key} aria-label={opt.label}>
              {opt.label}
            </ToggleGroup.Item>
          {/each}
        </ToggleGroup.Root>

        <div class="ml-auto flex items-center gap-2">
          <span class="text-xs text-muted-foreground">{m.search_sort_by()}</span
          >
          <Select.Root type="single" bind:value={sortKey}>
            <Select.Trigger size="sm" class="w-45 text-xs">
              {sortLabel}
            </Select.Trigger>
            <Select.Content>
              {#each sortOptions as opt (opt.value)}
                <Select.Item value={opt.value} label={opt.label}>
                  {opt.label}
                </Select.Item>
              {/each}
            </Select.Content>
          </Select.Root>
        </div>
      </div>

      {#if !loading && keywords.length > 1}
        <div class="flex flex-col gap-2">
          <span class="text-xs font-medium text-muted-foreground">
            {m.search_more_to_explore()}:
          </span>
          <ScrollArea orientation="horizontal" class="overflow-clip rounded-sm">
            <div class="flex gap-2 pb-2">
              {#each keywords as kw (kw.id)}
                <Button
                  variant="ghost"
                  size="xs"
                  class="text-muted-foreground"
                  onclick={() => onSuggested(kw.name)}
                >
                  {kw.name}
                </Button>
              {/each}
            </div>
          </ScrollArea>
        </div>
      {/if}
    </div>
  {/if}

  {#if !loading}
    <ScrollArea class="flex min-h-0 flex-1 gap-4 p-4">
      {#if topResults.length > 0}
        <section class="mb-8 space-y-3" data-search-section="top-results">
          <h2 class="text-lg font-semibold">{m.search_top_results()}</h2>
          <div
            class="grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-6"
            data-search-grid="top-results"
          >
            {#each topResults as media (`${media.media_type}:${media.id}`)}
              <MediaCard
                {media}
                onclick={() => onSelectMedia(media)}
                quality={qualityMap.get(media.id) ?? null}
                onwatch={onWatch}
              />
            {/each}
          </div>
        </section>
      {/if}

      {#if showPerson && people.length > 0}
        <section class="mb-8 space-y-3" data-search-section="people">
          <h2 class="text-lg font-semibold">{m.search_people()}</h2>
          <div
            class="grid gap-4"
            style="grid-template-columns: repeat(auto-fill, minmax(120px, 1fr))"
          >
            {#each people as person (person.id)}
              <PersonCard {person} onclick={(p) => onSelectPerson(p)} />
            {/each}
          </div>
        </section>
      {/if}

      {#if showProvider && providers.length > 0}
        <section class="space-y-3 p-4" data-search-section="providers">
          <h2 class="text-lg font-semibold">{m.search_providers()}</h2>
          <div
            class="grid gap-4"
            style="grid-template-columns: repeat(auto-fill, minmax(110px, 1fr))"
          >
            {#each providers as provider (provider.provider_id)}
              <ProviderCard {provider} onclick={(p) => onSelectProvider(p)} />
            {/each}
          </div>
        </section>
      {/if}

      <div class="space-y-8 pr-4 pb-8">
        {#if showMovie && movies.length > 0}
          <section class="space-y-3" data-search-section="movies">
            <h2 class="text-lg font-semibold">{m.search_movies()}</h2>
            <div
              class="grid gap-4"
              style="grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))"
            >
              {#each movies as media (media.id)}
                <MediaCard
                  {media}
                  onclick={() => onSelectMedia(media)}
                  quality={qualityMap.get(media.id) ?? null}
                  onwatch={onWatch}
                />
              {/each}
            </div>
          </section>
        {/if}

        {#if showTV && tv.length > 0}
          <section class="space-y-3" data-search-section="tv">
            <h2 class="text-lg font-semibold">{m.search_tv_shows()}</h2>
            <div
              class="grid gap-4"
              style="grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))"
            >
              {#each tv as media (media.id)}
                <MediaCard
                  {media}
                  onclick={() => onSelectMedia(media)}
                  quality={qualityMap.get(media.id) ?? null}
                  onwatch={onWatch}
                />
              {/each}
            </div>
          </section>
        {/if}

        {#if query.trim() && !anyVisible}
          <p class="pt-8 text-center text-sm text-muted-foreground">
            {m.search_no_results()}
          </p>
        {/if}
      </div>
    </ScrollArea>
  {/if}
</div>
