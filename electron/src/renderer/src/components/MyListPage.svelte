<script lang="ts">
  import { api, STATUS_LABELS, type LibraryStatus } from "$lib/api";
  import type { LibraryEntry } from "$lib/types/library";
  import type { Media } from "$lib/types/tmdb";
  import { ScrollArea } from "$lib/components/ui/scroll-area/index.js";
  import { Spinner } from "$lib/components/ui/spinner/index.js";
  import MediaCard from "./MediaCard.svelte";
  import { BookMarked, Star } from "lucide-svelte";
  import { onMount } from "svelte";
  import { libraryChanged } from "$lib/stores/library";
  import { flip } from "svelte/animate";
  import { cubicOut } from "svelte/easing";
  import Upcoming from "./Upcoming.svelte";
  import { Button } from "$lib/components/ui/button/index.js";
  import * as ButtonGroup from "$lib/components/ui/button-group/index.js";

  let {
    onSelectMedia,
    onWatch,
  }: {
    onSelectMedia: (m: Media) => void;
    onWatch?: (m: Media, season?: number, episode?: number) => void;
  } = $props();

  // ── State ────────────────────────────────────────────────────────────────────

  let entries = $state<LibraryEntry[]>([]);
  let loading = $state(true);
  let activeType = $state<"all" | "movie" | "tv">("all");
  let activeStatus = $state<LibraryStatus | "all">("all");

  // ── Data ─────────────────────────────────────────────────────────────────────

  async function loadEntries(showSpinner = true): Promise<void> {
    if (showSpinner) loading = true;
    try {
      entries = await api.libraryList();
    } finally {
      if (showSpinner) loading = false;
    }
  }

  onMount(() => loadEntries(true));

  let initialized = $state(false);
  $effect(() => {
    $libraryChanged;
    if (!initialized) {
      initialized = true;
      return;
    }
    loadEntries(false); // silent — no spinner, no re-render
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

  const STATUS_ORDER: Record<string, number> = {
    watching: 0,
    watch_later: 1,
    finished: 2,
    dropped: 3,
  };

  const filtered = $derived(
    (activeStatus === "all"
        ? entries
        : entries.filter((e) => e.status === activeStatus)
    )
      .filter((e) => activeType === "all" || e.media_type === activeType)
      .toSorted((a, b) => {
        // On the "all" tab, group by status order first
        if (activeStatus === "all") {
          const statusDiff =
            (STATUS_ORDER[a.status] ?? 99) - (STATUS_ORDER[b.status] ?? 99);
          if (statusDiff !== 0) return statusDiff;
        }

        // Within "watching": new episodes float to top
        const aNew = hasNewEpisodes(a) ? 1 : 0;
        const bNew = hasNewEpisodes(b) ? 1 : 0;
        if (bNew !== aNew) return bNew - aNew;

        // Then by most recent date
        const aDate = a.last_air_date || a.updated_at;
        const bDate = b.last_air_date || b.updated_at;
        return new Date(bDate).getTime() - new Date(aDate).getTime();
      }),
  );

  // ── Helpers ──────────────────────────────────────────────────────────────────

  // A show has unwatched new episodes when the latest aired episode is
  // numerically ahead of the user's last-watched episode. Comparing season
  // and episode numbers (not dates) avoids the bug where a recently-watched
  // older episode looks "newer" than an unwatched episode that aired weeks
  // ago — timestamps don't reflect watch order, episode numbers do.
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

  function toMediaKey(entry: LibraryEntry): string {
    return `${entry.tmdb_id}-${entry.media_type}`;
  }

  // Real, fully-populated Media objects fetched per entry — replaces the old
  // toMedia() stub that hand-built a partial Media client-side (hardcoding
  // overview: "" among other things). LibraryEntry intentionally doesn't
  // persist a full copy of TMDB's metadata, so this is the genuine source
  // instead of a lossy stand-in. Keyed so re-fetching on every libraryChanged
  // tick doesn't re-request titles already loaded.
  let mediaByKey = $state<Record<string, Media>>({});

  async function ensureMediaLoaded(entry: LibraryEntry): Promise<void> {
    const key = toMediaKey(entry);
    if (mediaByKey[key]) return;
    try {
      mediaByKey[key] = await api.getMediaByID(entry.tmdb_id, entry.media_type);
    } catch (e) {
      console.error("Failed to load media for", key, e);
    }
  }

  $effect(() => {
    for (const entry of entries) {
      ensureMediaLoaded(entry); // no-op if cached; fires in parallel otherwise
    }
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
        <h1 class="text-2xl font-semibold">My List</h1>
        {#if !loading && entries.length > 0}
          <span class="text-sm text-muted-foreground">
            {entries.length} title{entries.length !== 1 ? "s" : ""}
          </span>
        {/if}
      </div>

      <div class="flex flex-col gap-1">
        {#if !loading && entries.length > 0}
          <div class="mt-2 flex gap-1.5">
            <ButtonGroup.Root>
              {#each [["all", "All"], ["movie", "Movies"], ["tv", "TV Shows"]] as [type, typeLabel] (type)}
                <Button
                  onclick={() => (activeType = type as typeof activeType)}
                  size="xs"
                  class="rounded-full px-3 py-1 text-xs font-medium transition-colors
          {activeType === type
                    ? 'bg-foreground text-background'
                    : 'bg-secondary text-muted-foreground hover:bg-secondary/70 hover:text-foreground'}"
                >
                  {typeLabel}
                </Button>
              {/each}
            </ButtonGroup.Root>
          </div>
        {/if}

        <!-- Status tabs -->
        {#if !loading && entries.length > 0}
          <div class="flex flex-wrap gap-1.5">
            <ButtonGroup.Root>
              {#each TAB_ORDER as tab (tab)}
                {@const count = counts[tab]}
                {#if count > 0 || tab === "all"}
                  <Button
                    onclick={() => (activeStatus = tab)}
                    size="xs"
                    class="flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium transition-colors
                  {activeStatus === tab
                      ? 'bg-foreground text-background'
                      : 'bg-secondary text-muted-foreground hover:bg-secondary/70 hover:text-foreground'}"
                  >
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
      <div class="mt-35 rounded-2xl border-b bg-card p-4">
        <Upcoming {onSelectMedia} />
      </div>

      {#if filtered.length === 0}
        <div class="flex h-[60vh] flex-col items-center justify-center gap-4">
          <p class="text-base font-medium">
            {EMPTY_MESSAGES[activeStatus]?.heading}
          </p>
          <p class="text-sm text-muted-foreground">
            {EMPTY_MESSAGES[activeStatus]?.sub}
          </p>
        </div>
      {:else}
        <div
          class="mt-5 grid gap-4 pr-4"
          style="grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))"
        >
          {#each filtered as entry (entry.id)}
            {@const media = mediaByKey[toMediaKey(entry)]}
            <div
              class="relative"
              animate:flip={{ duration: 300, easing: cubicOut }}
            >
              {#if media}
                <MediaCard
                  {media}
                  onclick={() => onSelectMedia(media)}
                  onsimilar={(m) => onSelectMedia(m)}
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
      {/if}
    </ScrollArea>
  {/if}
</div>
