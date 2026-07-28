<script lang="ts">
  import { statusLabel, STATUS_COLORS, type LibraryStatus } from "$lib/api";
  import type { LibraryEntry } from "$lib/types/library";
  import type { Media } from "$lib/types/tmdb";
  import { ScrollArea } from "$lib/components/ui/scroll-area/index.js";
  import { Spinner } from "$lib/components/ui/spinner/index.js";
  import MediaCard from "./MediaCard.svelte";
  import { BookMarked, Star, ArrowDownUp, Filter, Film } from "lucide-svelte";
  import { onMount } from "svelte";
  import { libraryChanged } from "$lib/stores/library";
  import { flip } from "svelte/animate";
  import { cubicOut } from "svelte/easing";
  import CalendarAgenda from "./CalendarAgenda.svelte";
  import { Button } from "$lib/components/ui/button/index.js";
  import * as ButtonGroup from "$lib/components/ui/button-group/index.js";
  import * as Select from "$lib/components/ui/select/index.js";
  import { SvelteSet } from "svelte/reactivity";
  import * as m from "$lib/paraglide/messages.js";
  import {
    hasNewEpisodes,
    compareEntries,
    sortOptions,
    toMediaKey,
    type SortKey,
    genresFor,
  } from "$lib/myList";
  import { MyListDataController } from "$lib/myList.svelte";

  let {
    onSelectMedia,
    onWatch,
  }: {
    onSelectMedia: (m: Media) => void;
    onWatch?: (m: Media, season?: number, episode?: number) => void;
  } = $props();

  // ── State ────────────────────────────────────────────────────────────────────

  const data = new MyListDataController();
  const entries = $derived(data.entries);
  const loading = $derived(data.loading);
  const mediaByKey = $derived(data.mediaByKey);
  const genreNames = $derived(data.genreNames);
  let activeType = $state<"all" | "movie" | "tv">("all");
  let activeStatus = $state<LibraryStatus | "all">("all");

  // ── Sort & genre filter ───────────────────────────────────────────────────────

  const SORT_OPTIONS = sortOptions();
  let sortKey = $state<SortKey>("default");
  let activeGenre = $state<string>("all"); // genre name, or "all"

  // Trigger labels for the selects (shadcn Select renders the label ourselves).
  const typeLabel = $derived(
    activeType === "movie"
      ? m.my_list_movies()
      : activeType === "tv"
        ? m.my_list_shows()
        : m.my_list_all(),
  );
  const sortLabel = $derived(
    SORT_OPTIONS.find((o) => o.value === sortKey)?.label ?? m.my_list_sort(),
  );
  const genreLabel = $derived(
    activeGenre === "all" ? m.my_list_all_genres() : activeGenre,
  );

  // ── Data ─────────────────────────────────────────────────────────────────────

  onMount(() => {
    void data.loadEntries(true);
    void data.loadGenreNames();
  });

  let initialized = $state(false);
  $effect(() => {
    $libraryChanged;
    if (!initialized) {
      initialized = true;
      return;
    }
    void data.loadEntries(false); // silent — keep the current grid visible
  });

  // ── Derived ──────────────────────────────────────────────────────────────────

  const TAB_ORDER: (LibraryStatus | "all")[] = [
    "all",
    "watching",
    "watch_later",
    "finished",
    "dropped",
  ];

  const TAB_LABELS: Record<string, string> = {
    all: m.my_list_all(),
    watching: statusLabel("watching"),
    watch_later: statusLabel("watch_later"),
    finished: statusLabel("finished"),
    dropped: statusLabel("dropped"),
  };

  const counts = $derived(
    Object.fromEntries(
      TAB_ORDER.map((s) => {
        const typeFiltered = entries.filter(
          (e) => activeType === "all" || e.media_type === activeType,
        );
        return [
          s,
          s === "all"
            ? typeFiltered.length
            : typeFiltered.filter((e) => e.status === s).length,
        ];
      }),
    ),
  );

  // Section order for the grouped layout: each status becomes its own headed
  // list, rendered top-to-bottom in this order.
  const SECTION_ORDER: LibraryStatus[] = [
    "watching",
    "watch_later",
    "finished",
    "dropped",
  ];

  // Genre names present across the current type view — drives the genre filter
  // dropdown. Populates as Media objects finish loading.
  const availableGenres = $derived.by(() => {
    const set = new SvelteSet<string>();
    for (const e of entries) {
      if (activeType !== "all" && e.media_type !== activeType) continue;
      for (const g of genresFor(e, mediaByKey, genreNames)) set.add(g);
    }
    return [...set].sort((a, b) => a.localeCompare(b));
  });

  // Grouped, headed lists. On the "all" tab every non-empty status gets its own
  // section; on a specific tab there's a single section for that status. Within
  // each section, entries honor the active genre filter and the chosen sort.
  // Empty groups are dropped so no bare header shows.
  const sections = $derived(
    (activeStatus === "all" ? SECTION_ORDER : [activeStatus])
      .map((status) => ({
        status,
        label: statusLabel(status),
        entries: entries
          .filter(
            (e) =>
              e.status === status &&
              (activeType === "all" || e.media_type === activeType) &&
              matchesGenre(e),
          )
          .toSorted((a, b) => compareEntries(a, b, sortKey, mediaByKey)),
      }))
      .filter((section) => section.entries.length > 0),
  );

  // If the selected genre stops existing (e.g. after switching type), reset it
  // so the view doesn't get stuck showing nothing.
  $effect(() => {
    if (activeGenre !== "all" && !availableGenres.includes(activeGenre)) {
      activeGenre = "all";
    }
  });

  // ── Helpers ──────────────────────────────────────────────────────────────────

  // ── Sort & filter accessors ───────────────────────────────────────────────────

  function matchesGenre(entry: LibraryEntry): boolean {
    if (activeGenre === "all") return true;
    return genresFor(entry, mediaByKey, genreNames).includes(activeGenre);
  }

  const EMPTY_MESSAGES: Record<string, { heading: string; sub: string }> = {
    all: {
      heading: m.my_list_empty(),
      sub: m.my_list_empty_tracking(),
    },
    watching: {
      heading: m.my_list_empty_watching(),
      sub: m.my_list_empty_watching(),
    },
    watch_later: {
      heading: m.my_list_empty_later(),
      sub: m.my_list_empty_later(),
    },
    finished: {
      heading: m.my_list_empty_finished(),
      sub: m.my_list_empty_finished(),
    },
    dropped: {
      heading: m.my_list_empty_dropped(),
      sub: m.my_list_empty_dropped(),
    },
  };
</script>

<div class="relative h-full p-6 pt-18">
  <!-- ── Sticky header with gradient fade ───────────────────────────────────── -->
  <div
    class="absolute top-18 right-6 left-6 z-10 p-4 pb-6"
    style="
      background: linear-gradient(to bottom, var(--background) 0%, var(--background) 70%, rgba(0,0,0,0) 100%);
      pointer-events: none;
    "
  >
    <div class="pointer-events-auto">
      <!-- Title row -->
      <div class="mb-4 flex items-baseline gap-3">
        <h1 class="text-2xl font-semibold">{m.my_list_title()}</h1>
        {#if !loading && entries.length > 0}
          <span class="text-sm text-muted-foreground">
            {entries.length === 1
              ? m.my_list_title_count_one()
              : m.my_list_titles_count({ count: entries.length })}
          </span>
        {/if}
      </div>

      <div class="flex flex-row justify-center gap-1">
        <!-- Status tabs -->
        {#if !loading && entries.length > 0}
          <div class="flex flex-wrap gap-1.5">
            <!-- Type -->
            <Select.Root
              type="single"
              value={activeType}
              onValueChange={(v) => (activeType = v as typeof activeType)}
            >
              <Select.Trigger
                class="h-8 w-auto gap-1.5 rounded-full border-0 bg-secondary px-3 text-xs font-medium text-foreground hover:bg-secondary/70"
              >
                <Film class="size-3.5 text-muted-foreground" />
                {typeLabel}
              </Select.Trigger>
              <Select.Content>
                <Select.Item value="all" label={m.my_list_all()}
                  >{m.my_list_all()}</Select.Item
                >
                <Select.Item value="movie" label={m.my_list_movies()}
                  >{m.my_list_movies()}</Select.Item
                >
                <Select.Item value="tv" label={m.my_list_shows()}
                  >{m.my_list_shows()}</Select.Item
                >
              </Select.Content>
            </Select.Root>

            <ButtonGroup.Root>
              {#each TAB_ORDER as tab (tab)}
                {@const count = counts[tab]}
                {#if count > 0 || tab === "all"}
                  <Button
                    onclick={() => (activeStatus = tab)}
                    size="default"
                    class="flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium transition-colors
                  {activeStatus === tab
                      ? 'bg-foreground text-background'
                      : 'bg-secondary text-muted-foreground hover:bg-secondary/70 hover:text-foreground'}"
                  >
                    {#if tab !== "all"}<span
                        class="size-1.5 shrink-0 rounded-full {STATUS_COLORS[
                          tab as LibraryStatus
                        ].dot}"
                      ></span>{/if}
                    {TAB_LABELS[tab]}
                    <span
                      class="tabular-nums {activeStatus === tab
                        ? 'text-background/70'
                        : 'text-muted-foreground/60'}"
                    >
                      {count}
                    </span>
                  </Button>
                {/if}
              {/each}
            </ButtonGroup.Root>

            <!-- Sort -->
            <Select.Root
              type="single"
              value={sortKey}
              onValueChange={(v) => (sortKey = v as SortKey)}
            >
              <Select.Trigger
                class="h-8 w-auto gap-1.5 rounded-full border-0 bg-secondary px-3 text-xs font-medium text-foreground hover:bg-secondary/70"
              >
                <ArrowDownUp class="size-3.5 text-muted-foreground" />
                {sortLabel}
              </Select.Trigger>
              <Select.Content>
                {#each SORT_OPTIONS as opt (opt.value)}
                  <Select.Item value={opt.value} label={opt.label}>
                    {opt.label}
                  </Select.Item>
                {/each}
              </Select.Content>
            </Select.Root>

            <!-- Genre -->
            {#if availableGenres.length > 0}
              <Select.Root
                type="single"
                value={activeGenre}
                onValueChange={(v) => (activeGenre = v)}
              >
                <Select.Trigger
                  class="h-8 w-auto gap-1.5 rounded-full border-0 bg-secondary px-3 text-xs font-medium text-foreground hover:bg-secondary/70
                  {activeGenre !== 'all' ? 'ring-1 ring-foreground/30' : ''}"
                >
                  <Filter class="size-3.5 text-muted-foreground" />
                  {genreLabel}
                </Select.Trigger>
                <Select.Content>
                  <Select.Item value="all" label={m.my_list_all_genres()}>
                    {m.my_list_all_genres()}
                  </Select.Item>
                  {#each availableGenres as g (g)}
                    <Select.Item value={g} label={g}>{g}</Select.Item>
                  {/each}
                </Select.Content>
              </Select.Root>
            {/if}
          </div>
        {/if}
      </div>
    </div>
  </div>

  <!-- ── Loading ────────────────────────────────────────────────────────────── -->
  {#if loading}
    <div class="flex h-full items-center justify-center">
      <Spinner class="size-8" />
    </div>

    <!-- ── Empty: no entries at all ──────────────────────────────────────────── -->
  {:else if entries.length === 0}
    <div class="flex h-full flex-col items-center justify-center gap-3">
      <BookMarked class="size-12 text-muted-foreground/30" />
      <p class="text-base font-medium">{EMPTY_MESSAGES.all.heading}</p>
      <p class="text-sm text-muted-foreground">{EMPTY_MESSAGES.all.sub}</p>
    </div>

    <!-- ── Content ────────────────────────────────────────────────────────────── -->
  {:else}
    <ScrollArea class="h-full">
      <div class="mt-28 flex flex-col">
        <div>
          <CalendarAgenda {onSelectMedia} />
        </div>
      </div>

      {#if sections.length === 0}
        <div class="flex h-[60vh] flex-col items-center justify-center gap-4">
          {#if activeGenre !== "all"}
            <p class="text-base font-medium">{m.my_list_no_genre()}</p>
            <p class="text-sm text-muted-foreground">
              {m.my_list_change_filter()}
            </p>
          {:else}
            <p class="text-base font-medium">
              {EMPTY_MESSAGES[activeStatus]?.heading}
            </p>
            <p class="text-sm text-muted-foreground">
              {EMPTY_MESSAGES[activeStatus]?.sub}
            </p>
          {/if}
        </div>
      {:else}
        {#each sections as section (section.status)}
          <section class="mt-8 first:mt-5">
            <!-- List header -->
            <div class="mb-3 flex items-baseline gap-2 pr-4">
              <span
                class="size-2.5 shrink-0 self-center rounded-full {STATUS_COLORS[
                  section.status
                ].dot}"
              ></span>
              <h2 class="text-lg font-semibold">{section.label}</h2>
              <span class="text-sm text-muted-foreground tabular-nums">
                {section.entries.length}
              </span>
            </div>

            <div
              class="grid gap-4 pr-4"
              style="grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))"
            >
              {#each section.entries as entry (entry.id)}
                {@const media = mediaByKey[toMediaKey(entry)]}
                <div
                  class="relative"
                  animate:flip={{ duration: 300, easing: cubicOut }}
                >
                  {#if media}
                    <MediaCard
                      {media}
                      onclick={() => onSelectMedia(media)}
                      newEpisodes={hasNewEpisodes(entry)}
                      onwatch={onWatch}
                    />
                  {:else}
                    <!-- Real Media object still loading — show the poster we
                         already have stored so the grid doesn't look broken,
                         swap to the interactive MediaCard once it resolves. -->
                    <img
                      src={entry.poster_path}
                      alt={entry.title}
                      loading="lazy"
                      decoding="async"
                      class="aspect-2/3 w-full rounded-md object-cover opacity-60"
                    />
                  {/if}

                  {#if entry.rating !== null && entry.rating !== undefined}
                    <div
                      class="pointer-events-none absolute top-1.5 left-1.5 z-10 flex items-center gap-0.5 rounded border border-yellow-400/40 bg-black/65 px-1.5 py-0.5 text-[10px] font-semibold text-yellow-400 backdrop-blur-sm"
                    >
                      <Star class="size-2.5 fill-current" />
                      {entry.rating}
                    </div>
                  {/if}
                </div>
              {/each}
            </div>
          </section>
        {/each}
      {/if}
    </ScrollArea>
  {/if}
</div>
