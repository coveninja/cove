<script lang="ts">
  import { api, STATUS_LABELS, STATUS_COLORS, type LibraryStatus } from "$lib/api";
  import type { LibraryEntry } from "$lib/types/library";
  import type { Media } from "$lib/types/tmdb";
  import TvMediaCard from "../components/TvMediaCard.svelte";
  import TvCalendarAgenda from "../components/TvCalendarAgenda.svelte";
  import { BookMarked, Star } from "lucide-svelte";
  import { onMount } from "svelte";
  import { libraryChanged } from "$lib/stores/library";
  import { Spinner } from "$lib/components/ui/spinner/index.js";
  import { focusGroup, focusable } from "../focus/actions";

  // Grid columns: keep this constant in sync with the CSS grid-template-columns
  // below so the focusGroup grid policy matches the visual layout.
  const COLS = 6;

  // ── State ────────────────────────────────────────────────────────────────────

  let entries = $state<LibraryEntry[]>([]);
  let loading = $state(true);
  let activeType = $state<"all" | "movie" | "tv">("all");

  // ── Sort ─────────────────────────────────────────────────────────────────────

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
    { value: "title_asc", label: "Title A–Z" },
  ];

  let sortKey = $state<SortKey>("default");

  // ── Data ─────────────────────────────────────────────────────────────────────

  async function loadEntries(showSpinner = true): Promise<void> {
    if (showSpinner) loading = true;
    try {
      entries = await api.libraryList();
    } finally {
      if (showSpinner) loading = false;
    }
  }

  onMount(() => {
    loadEntries(true);
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

  const SECTION_ORDER: LibraryStatus[] = [
    "watching",
    "watch_later",
    "finished",
    "dropped",
  ];

  const sections = $derived(
    SECTION_ORDER
      .map((status) => ({
        status,
        label: STATUS_LABELS[status],
        entries: entries
          .filter(
            (e) =>
              e.status === status &&
              (activeType === "all" || e.media_type === activeType),
          )
          .toSorted(compareEntries),
      }))
      .filter((section) => section.entries.length > 0),
  );

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

  function tmdbRating(entry: LibraryEntry): number {
    const onEntry = (entry as LibraryEntry & { vote_average?: number }).vote_average;
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
    return ts(e.last_watched_at ?? e.watched_at ?? e.updated_at ?? entry.added_at);
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

  function toMediaKey(entry: LibraryEntry): string {
    return `${entry.tmdb_id}-${entry.media_type}`;
  }

  /** Advance sortKey to the next option in the cycle (for the TV sort button). */
  function cycleSortKey(): void {
    const idx = SORT_OPTIONS.findIndex((o) => o.value === sortKey);
    sortKey = SORT_OPTIONS[(idx + 1) % SORT_OPTIONS.length].value;
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

<div class="flex h-full flex-col overflow-hidden p-4 gap-2">
  <!-- Sticky header with D-pad-navigable filter controls -->
  <div class="shrink-0 space-y-3 p-4 bg-card rounded-2xl">
    <div class="flex items-baseline gap-3">
      <h1 class="text-2xl font-bold">My List</h1>
      {#if !loading && entries.length > 0}
        <span class="text-base text-muted-foreground">
          {entries.length} title{entries.length !== 1 ? "s" : ""}
        </span>
      {/if}
    </div>

    {#if !loading && entries.length > 0}
      <!--
        Single filter row (one D-pad vertical stop):
        type chips [All | Movies | TV]  ·  sort cycle-button (Enter = next sort).
        Status chips removed — content is already grouped into status sections.
      -->
      <div
        use:focusGroup={{ id: "mylist-filters", policy: { type: "row" } }}
        class="flex items-center gap-2"
      >
        {#each [["all", "All"], ["movie", "Movies"], ["tv", "TV"]] as [val, label] (val)}
          <button
            type="button"
            use:focusable={{ groupId: "mylist-filters" }}
            onclick={() => (activeType = val as typeof activeType)}
            class="rounded-xl px-5 py-2 text-sm font-medium transition-colors {activeType ===
            val
              ? 'bg-foreground text-background'
              : 'bg-secondary text-muted-foreground'}"
          >{label}</button>
        {/each}

        <div class="h-6 w-px shrink-0 bg-white/20"></div>

        <!-- Sort cycle: Enter advances to the next sort option. -->
        <button
          type="button"
          use:focusable={{ groupId: "mylist-filters" }}
          onclick={cycleSortKey}
          class="flex items-center gap-1.5 rounded-xl bg-secondary px-5 py-2 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
        >
          Sort: {SORT_OPTIONS.find((o) => o.value === sortKey)?.label ?? ""}
        </button>
      </div>
    {/if}
  </div>

  <!-- Body -->
  {#if loading}
    <div class="flex flex-1 items-center justify-center">
      <Spinner class="size-12" />
    </div>

  {:else if entries.length === 0}
    <div class="flex flex-1 flex-col items-center justify-center gap-4 px-8 text-center">
      <BookMarked class="size-16 text-muted-foreground/30" />
      <p class="text-xl font-medium">{EMPTY_MESSAGES.all.heading}</p>
      <p class="text-base text-muted-foreground">{EMPTY_MESSAGES.all.sub}</p>
    </div>

  {:else}
    <div
      class="min-h-0 px-4 flex-1 overflow-y-auto scrollbar-none [&::-webkit-scrollbar]:hidden rounded-2xl"
    >
      <div class="pb-12 pt-4">
        <TvCalendarAgenda />

        {#if sections.length === 0}
          <div class="flex h-[40vh] flex-col items-center justify-center gap-3 text-center">
            <p class="text-xl font-medium">No titles for current filter</p>
            <p class="text-base text-muted-foreground">Try changing the type filter above.</p>
          </div>
        {:else}
          {#each sections as section (section.status)}
            <section class="mt-8 first:mt-4">
              <div class="mb-4 flex items-baseline gap-3">
                <span class="size-3 shrink-0 self-center rounded-full {STATUS_COLORS[section.status].dot}"></span>
                <h2 class="text-xl font-semibold">{section.label}</h2>
                <span class="text-base text-muted-foreground tabular-nums">
                  {section.entries.length}
                </span>
              </div>

              <!--
                D-pad-navigable grid: policy type "grid" with COLS columns.
                COLS must match the CSS grid-template-columns count below.
              -->
              <div
                use:focusGroup={{
                  id: `mylist-section-${section.status}`,
                  policy: { type: "grid", cols: COLS },
                  rememberFocus: true,
                }}
                class="grid grid-cols-6 gap-3"
              >
                {#each section.entries as entry (entry.id)}
                  {@const media = mediaByKey[toMediaKey(entry)]}
                  <div class="relative">
                    {#if media}
                      <TvMediaCard
                        {media}
                        groupId={`mylist-section-${section.status}`}
                      />
                    {:else}
                      <!-- Poster stub while Media object loads -->
                      <img
                        src={entry.poster_path}
                        alt={entry.title}
                        loading="lazy"
                        decoding="async"
                        class="aspect-2/3 w-full rounded-lg object-cover opacity-60"
                      />
                    {/if}

                    {#if entry.rating !== null && entry.rating !== undefined}
                      <div
                        class="pointer-events-none absolute top-1.5 left-1.5 z-10 flex items-center gap-1 rounded border border-yellow-400/40 bg-black/65 px-1.5 py-0.5 text-xs font-semibold text-yellow-400"
                      >
                        <Star class="size-3 fill-current" />
                        {entry.rating}
                      </div>
                    {/if}

                    {#if hasNewEpisodes(entry)}
                      <div
                        class="pointer-events-none absolute top-1.5 right-1.5 z-10 size-2.5 rounded-full bg-accent"
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

