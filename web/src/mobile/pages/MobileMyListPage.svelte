<script lang="ts">
  import { api, STATUS_LABELS, STATUS_COLORS, type LibraryStatus } from "$lib/api";
  import type { LibraryEntry } from "$lib/types/library";
  import type { Media } from "$lib/types/tmdb";
  import MobileMediaCard from "../components/MobileMediaCard.svelte";
  import MobileCalendarAgenda from "../components/MobileCalendarAgenda.svelte";
  import { BookMarked, Star, ArrowDownUp } from "lucide-svelte";
  import { onMount } from "svelte";
  import { libraryChanged } from "$lib/stores/library";
  import { flip } from "svelte/animate";
  import { cubicOut } from "svelte/easing";
  import * as Select from "$lib/components/ui/select/index.js";
  import { SvelteSet } from "svelte/reactivity";
  import { Spinner } from "$lib/components/ui/spinner/index.js";

  let {
    onSelectMedia,
    onWatch: _onWatch,
  }: {
    onSelectMedia: (m: Media) => void;
    onWatch?: (m: Media, season?: number, episode?: number) => void;
  } = $props();

  // ── State ────────────────────────────────────────────────────────────────────

  let entries = $state<LibraryEntry[]>([]);
  let loading = $state(true);
  let activeType = $state<"all" | "movie" | "tv">("all");
  let activeStatus = $state<LibraryStatus | "all">("all");

  // ── Sort & genre filter ───────────────────────────────────────────────────────

  type SortKey =
    | "default"
    | "watched_desc"
    | "added_desc"
    | "added_asc"
    | "release_desc"
    | "tmdb_desc"
    | "personal_desc"
    | "title_asc";

  const SORT_OPTIONS: { value: SortKey; label: string }[] = [
    { value: "default", label: "Recommended" },
    { value: "watched_desc", label: "Recently watched" },
    { value: "added_desc", label: "Recently added" },
    { value: "added_asc", label: "Oldest added" },
    { value: "release_desc", label: "Release date" },
    { value: "tmdb_desc", label: "TMDB rating" },
    { value: "personal_desc", label: "Your rating" },
    { value: "title_asc", label: "Title (A–Z)" },
  ];

  let sortKey = $state<SortKey>("default");
  let activeGenre = $state<string>("all");

  let genreNames = $state<{
    movie: Record<number, string>;
    tv: Record<number, string>;
  }>({ movie: {}, tv: {} });

  const sortLabel = $derived(
    SORT_OPTIONS.find((o) => o.value === sortKey)?.label ?? "Sort",
  );

  // ── Data ─────────────────────────────────────────────────────────────────────

  async function loadEntries(showSpinner = true): Promise<void> {
    if (showSpinner) loading = true;
    try {
      entries = await api.libraryList();
    } finally {
      if (showSpinner) loading = false;
    }
  }

  async function loadGenreNames(): Promise<void> {
    try {
      const [movie, tv] = await Promise.all([
        api.genreList("movie"),
        api.genreList("tv"),
      ]);
      genreNames = {
        movie: Object.fromEntries(movie.map((g) => [g.id, g.name])) as Record<
          number,
          string
        >,
        tv: Object.fromEntries(tv.map((g) => [g.id, g.name])) as Record<
          number,
          string
        >,
      };
    } catch {
      // non-fatal
    }
  }

  onMount(() => {
    loadEntries(true);
    loadGenreNames();
  });

  let initialized = $state(false);
  $effect(() => {
    $libraryChanged;
    if (!initialized) {
      initialized = true;
      return;
    }
    loadEntries(false);
  });

  // ── Derived ──────────────────────────────────────────────────────────────────

  const TAB_ORDER: (LibraryStatus | "all")[] = [
    "all",
    "watching",
    "watch_later",
    "finished",
    "dropped",
  ];

  const TAB_LABELS: Record<string, string> = { all: "All", ...STATUS_LABELS };

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

  const SECTION_ORDER: LibraryStatus[] = [
    "watching",
    "watch_later",
    "finished",
    "dropped",
  ];

  const availableGenres = $derived.by(() => {
    const set = new SvelteSet<string>();
    for (const e of entries) {
      if (activeType !== "all" && e.media_type !== activeType) continue;
      for (const g of genresFor(e)) set.add(g);
    }
    return [...set].sort((a, b) => a.localeCompare(b));
  });

  const sections = $derived(
    (activeStatus === "all" ? SECTION_ORDER : [activeStatus])
      .map((status) => ({
        status,
        label: STATUS_LABELS[status],
        entries: entries
          .filter(
            (e) =>
              e.status === status &&
              (activeType === "all" || e.media_type === activeType) &&
              matchesGenre(e),
          )
          .toSorted(compareEntries),
      }))
      .filter((section) => section.entries.length > 0),
  );

  $effect(() => {
    if (activeGenre !== "all" && !availableGenres.includes(activeGenre)) {
      activeGenre = "all";
    }
  });

  // ── Helpers ──────────────────────────────────────────────────────────────────

  function hasNewEpisodes(entry: LibraryEntry): boolean {
    if (entry.media_type !== "tv" || entry.status !== "watching") return false;
    const airedS = entry.last_aired_season;
    const airedE = entry.last_aired_episode;
    if (airedS == null || airedE == null) return false;
    const watchedS = entry.last_watched_season ?? 0;
    const watchedE = entry.last_watched_episode ?? 0;
    if (airedS > watchedS) return true;
    return airedS === watchedS && airedE > watchedE;
  }

  function ts(d?: string | null): number {
    if (!d) return 0;
    const t = new Date(d).getTime();
    return Number.isNaN(t) ? 0 : t;
  }

  function genresFor(entry: LibraryEntry): string[] {
    const media = mediaByKey[toMediaKey(entry)] as
      | (Media & {
          genres?: { id: number; name: string }[];
          genre_ids?: number[];
        })
      | undefined;
    if (!media) return [];
    if (Array.isArray(media.genres) && media.genres.length) {
      return media.genres.map((g) => g.name).filter(Boolean);
    }
    const ids = media.genre_ids ?? [];
    const map = genreNames[entry.media_type as "movie" | "tv"] ?? {};
    return ids.map((id) => map[id]).filter(Boolean);
  }

  function tmdbRating(entry: LibraryEntry): number {
    const onEntry = (entry as LibraryEntry & { vote_average?: number })
      .vote_average;
    if (typeof onEntry === "number" && onEntry > 0) return onEntry;
    const media = mediaByKey[toMediaKey(entry)] as
      | (Media & { vote_average?: number })
      | undefined;
    return media?.vote_average ?? 0;
  }

  function personalRating(entry: LibraryEntry): number {
    return entry.rating ?? -1;
  }

  function lastWatchedAt(entry: LibraryEntry): number {
    const e = entry as LibraryEntry & {
      last_watched_at?: string;
      watched_at?: string;
      updated_at?: string;
    };
    return ts(
      e.last_watched_at ?? e.watched_at ?? e.updated_at ?? entry.added_at,
    );
  }

  function releaseDate(entry: LibraryEntry): number {
    const media = mediaByKey[toMediaKey(entry)] as
      | (Media & {
          release_date?: string;
          first_air_date?: string;
          last_air_date?: string;
        })
      | undefined;
    return ts(
      entry.last_air_date ??
        media?.release_date ??
        media?.first_air_date ??
        media?.last_air_date,
    );
  }

  function titleOf(entry: LibraryEntry): string {
    return (entry.title ?? "").toLowerCase();
  }

  function defaultCompare(a: LibraryEntry, b: LibraryEntry): number {
    const aNew = hasNewEpisodes(a) ? 1 : 0;
    const bNew = hasNewEpisodes(b) ? 1 : 0;
    if (bNew !== aNew) return bNew - aNew;
    return ts(b.last_air_date || b.added_at) - ts(a.last_air_date || a.added_at);
  }

  function compareEntries(a: LibraryEntry, b: LibraryEntry): number {
    switch (sortKey) {
      case "added_desc":
        return ts(b.added_at) - ts(a.added_at);
      case "added_asc":
        return ts(a.added_at) - ts(b.added_at);
      case "title_asc":
        return titleOf(a).localeCompare(titleOf(b));
      case "tmdb_desc":
        return tmdbRating(b) - tmdbRating(a);
      case "personal_desc":
        return personalRating(b) - personalRating(a);
      case "watched_desc":
        return lastWatchedAt(b) - lastWatchedAt(a);
      case "release_desc":
        return releaseDate(b) - releaseDate(a);
      default:
        return defaultCompare(a, b);
    }
  }

  function matchesGenre(entry: LibraryEntry): boolean {
    if (activeGenre === "all") return true;
    return genresFor(entry).includes(activeGenre);
  }

  function toMediaKey(entry: LibraryEntry): string {
    return `${entry.tmdb_id}-${entry.media_type}`;
  }

  let mediaByKey = $state<Record<string, Media>>({});

  async function ensureMediaLoaded(entry: LibraryEntry): Promise<void> {
    const key = toMediaKey(entry);
    if (mediaByKey[key]) return;
    try {
      mediaByKey[key] = await api.getMediaByID(entry.tmdb_id, entry.media_type);
    } catch {
      // non-fatal
    }
  }

  $effect(() => {
    if (entries?.length === 0) return;
    for (const entry of entries) ensureMediaLoaded(entry);
  });

  const EMPTY_MESSAGES: Record<string, { heading: string; sub: string }> = {
    all: {
      heading: "Your list is empty",
      sub: "Open any title and use the status buttons to start tracking.",
    },
    watching: {
      heading: "Nothing in progress",
      sub: "Mark something as Watching to see it here.",
    },
    watch_later: {
      heading: "Nothing saved for later",
      sub: "Found something you want to watch? Hit Watch Later.",
    },
    finished: {
      heading: "Nothing finished yet",
      sub: "Mark a title as Finished once you're done.",
    },
    dropped: {
      heading: "Nothing dropped",
      sub: "Titles you give up on will appear here.",
    },
  };
</script>

<!-- Own safe-area top padding — no pt-18 / top-18 needed -->
<div class="flex h-full flex-col overflow-hidden">
  <!-- Sticky compact header -->
  <div
    class="sticky top-0 z-10 shrink-0 bg-background/95 pb-2 backdrop-blur-sm"
    style="padding-top: calc(var(--safe-top) + 0.5rem);"
  >
    <!-- Title row -->
    <div class="flex items-baseline gap-2 px-4 pb-1">
      <h1 class="text-xl font-semibold">My List</h1>
      {#if !loading && entries.length > 0}
        <span class="text-sm text-muted-foreground">
          {entries.length} title{entries.length !== 1 ? "s" : ""}
        </span>
      {/if}
    </div>

    {#if !loading && entries.length > 0}
      <!-- Horizontally swipeable chip strip — all filters in one row, no wrapping,
           no clipping at 360 px. -->
      <div
        class="flex gap-2 overflow-x-auto px-4 pb-1 [scrollbar-width:none] [-webkit-overflow-scrolling:touch] [&::-webkit-scrollbar]:hidden"
      >
        <!-- Type chips -->
        {#each [["all", "All"], ["movie", "Movies"], ["tv", "TV"]] as [val, label] (val)}
          <button
            type="button"
            onclick={() => (activeType = val as typeof activeType)}
            class="shrink-0 rounded-full px-3 py-1.5 text-xs font-medium transition-colors {activeType ===
            val
              ? 'bg-foreground text-background'
              : 'bg-secondary text-muted-foreground'}"
          >{label}</button>
        {/each}

        <div class="mx-0.5 w-px shrink-0 self-stretch bg-border"></div>

        <!-- Status chips with counts -->
        {#each TAB_ORDER as tab (tab)}
          {@const count = counts[tab]}
          {#if count > 0 || tab === "all"}
            <button
              type="button"
              onclick={() => (activeStatus = tab)}
              class="flex shrink-0 items-center gap-1 rounded-full px-3 py-1.5 text-xs font-medium transition-colors {activeStatus ===
              tab
                ? 'bg-foreground text-background'
                : 'bg-secondary text-muted-foreground'}"
            >
              {#if tab !== "all"}<span class="size-1.5 shrink-0 rounded-full {STATUS_COLORS[tab as LibraryStatus].dot}"></span>{/if}{TAB_LABELS[tab]}
              <span class="tabular-nums opacity-60">{count}</span>
            </button>
          {/if}
        {/each}

        <div class="mx-0.5 w-px shrink-0 self-stretch bg-border"></div>

        <!-- Sort select (shadcn, compact) -->
        <Select.Root
          type="single"
          value={sortKey}
          onValueChange={(v) => (sortKey = v as SortKey)}
        >
          <Select.Trigger
            class="h-7 shrink-0 gap-1 rounded-full border-0 bg-secondary px-3 text-xs font-medium text-muted-foreground"
          >
            <ArrowDownUp class="size-3" />
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

        <!-- Genre filter (only when genres are present) -->
        {#if availableGenres.length > 0}
          <Select.Root
            type="single"
            value={activeGenre}
            onValueChange={(v) => (activeGenre = v)}
          >
            <Select.Trigger
              class="h-7 shrink-0 gap-1 rounded-full border-0 bg-secondary px-3 text-xs font-medium text-muted-foreground {activeGenre !==
              'all'
                ? 'ring-1 ring-foreground/30'
                : ''}"
            >
              {activeGenre === "all" ? "All genres" : activeGenre}
            </Select.Trigger>
            <Select.Content>
              <Select.Item value="all" label="All genres">All genres</Select.Item>
              {#each availableGenres as g (g)}
                <Select.Item value={g} label={g}>{g}</Select.Item>
              {/each}
            </Select.Content>
          </Select.Root>
        {/if}
      </div>
    {/if}
  </div>

  <!-- Body -->
  {#if loading}
    <div class="flex flex-1 items-center justify-center">
      <Spinner class="size-8" />
    </div>

  {:else if entries.length === 0}
    <div class="flex flex-1 flex-col items-center justify-center gap-3 px-8 text-center">
      <BookMarked class="size-12 text-muted-foreground/30" />
      <p class="text-base font-medium">{EMPTY_MESSAGES.all.heading}</p>
      <p class="text-sm text-muted-foreground">{EMPTY_MESSAGES.all.sub}</p>
    </div>

  {:else}
    <div
      class="min-h-0 flex-1 overflow-y-auto overscroll-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
    >
      <div class="px-3 pb-8">
        <MobileCalendarAgenda />

        {#if sections.length === 0}
          <div
            class="flex h-[50vh] flex-col items-center justify-center gap-3 text-center"
          >
            {#if activeGenre !== "all"}
              <p class="text-base font-medium">No titles in this genre</p>
              <p class="text-sm text-muted-foreground">
                Try a different genre or clear the filter.
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
            <section class="mt-6 first:mt-4">
              <div class="mb-3 flex items-baseline gap-2 px-1">
                <span class="size-2 shrink-0 self-center rounded-full {STATUS_COLORS[section.status].dot}"></span>
                <h2 class="text-base font-semibold">{section.label}</h2>
                <span class="text-sm text-muted-foreground tabular-nums">
                  {section.entries.length}
                </span>
              </div>

              <div class="grid grid-cols-3 gap-2">
                {#each section.entries as entry (entry.id)}
                  {@const media = mediaByKey[toMediaKey(entry)]}
                  <div
                    class="relative"
                    animate:flip={{ duration: 300, easing: cubicOut }}
                  >
                    {#if media}
                      <MobileMediaCard
                        {media}
                        onclick={() => onSelectMedia(media)}
                      />
                    {:else}
                      <!-- Poster stub while Media object loads -->
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
                        class="pointer-events-none absolute top-1 left-1 z-10 flex items-center gap-0.5 rounded border border-yellow-400/40 bg-black/65 px-1 py-0.5 text-[9px] font-semibold text-yellow-400"
                      >
                        <Star class="size-2 fill-current" />
                        {entry.rating}
                      </div>
                    {/if}

                    {#if hasNewEpisodes(entry)}
                      <!-- New-episodes dot (top-right) -->
                      <div
                        class="pointer-events-none absolute top-1 right-1 z-10 size-2 rounded-full bg-accent"
                      ></div>
                    {/if}
                  </div>
                {/each}
              </div>
            </section>
          {/each}
        {/if}
      </div>
    </div>
  {/if}
</div>
