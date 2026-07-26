<script lang="ts">
  import { api } from "$lib/api";
  import type { Media, TVEpisode } from "$lib/types/tmdb";
  import type { LibraryEntry, WatchProgress } from "$lib/types/library";
  import { libraryChanged } from "$lib/stores/library";
  import { mediaFromEntry } from "$lib/mediaFromEntry";
  import { ChevronLeft, ChevronRight } from "lucide-svelte";
  import { Button } from "$lib/components/ui/button/index.js";
  import { Skeleton } from "$lib/components/ui/skeleton/index.js";
  import ContinueWatchingCard, {
    type ContinueItem,
  } from "./cards/ContinueWatchingCard.svelte";
  import { SvelteMap } from "svelte/reactivity";
  import { animate } from "animejs";
  import { nextUnwatchedAiredEpisode as nextUnwatchedAiredEpisodeShared } from "$lib/nextEpisode";

  // Resume is the point of this row, so we take onWatch. onSelectMedia is the
  // fallback (open details) when no player handler is wired.
  let {
    onWatch,
    onSelectMedia,
    navEnabled = false,
  }: {
    onWatch?: (m: Media, season?: number, episode?: number) => void;
    onSelectMedia: (m: Media) => void;
    navEnabled: boolean;
  } = $props();

  let items = $state<ContinueItem[]>([]);
  let loading = $state(true);
  let trackEl = $state<HTMLElement | null>(null);

  // A movie opened for a few seconds isn't really "in progress"; a resume past
  // this fraction is treated as basically finished (TV rolls forward instead).
  const MOVIE_MIN_SECONDS = 15;
  const MAX_FRACTION = 0.95;

  // Episode lists are reused within one load (a show's resume still and its
  // roll-forward lookup both hit the same season), so memoize per load cycle.
  let seasonCache = new SvelteMap<string, Promise<TVEpisode[]>>();

  function fetchSeason(id: number, season: number): Promise<TVEpisode[]> {
    const key = `${id}:${season}`;
    let p = seasonCache.get(key);
    if (!p) {
      // A non-existent season comes back as an empty/null body (resolves to
      // null, not a rejection), so coerce here — every caller can assume an array.
      p = api
        .tvEpisodes(id, season)
        .then((eps) => eps ?? [])
        .catch(() => [] as TVEpisode[]);
      seasonCache.set(key, p);
    }
    return p;
  }

  async function episodeStill(
    id: number,
    season: number,
    episode: number,
  ): Promise<string> {
    const eps = await fetchSeason(id, season);
    return eps.find((e) => e.episode_number === episode)?.still_path ?? "";
  }

  // Wraps the shared completed-aware selector with this row's per-load season
  // cache, so resume artwork and roll-forward checks share season fetches.
  function nextUnwatchedAiredEpisode(
    id: number,
    season: number,
    episode: number,
    progress: WatchProgress[],
  ): Promise<{ season: number; episode: TVEpisode } | null> {
    return nextUnwatchedAiredEpisodeShared(
      id,
      season,
      episode,
      progress,
      fetchSeason,
    );
  }

  function latestProgress(progress: WatchProgress[]): WatchProgress | null {
    if (progress.length === 0) return null;
    return progress.toSorted(
      (a, b) =>
        new Date(b.watched_at).getTime() - new Date(a.watched_at).getTime(),
    )[0];
  }

  function toMedia(entry: LibraryEntry): Media {
    return mediaFromEntry({
      id: entry.tmdb_id,
      media_type: entry.media_type,
      title: entry.title,
      name: entry.title,
      poster_path: entry.poster_path,
      vote_average: entry.vote_average,
    });
  }

  async function buildItem(entry: LibraryEntry): Promise<ContinueItem | null> {
    let progress: WatchProgress[];
    try {
      const data = await api.libraryGet(entry.tmdb_id, entry.media_type);
      progress = data?.progress ?? [];
    } catch {
      return null; // one title failing shouldn't break the row
    }

    const latest = latestProgress(progress);
    if (!latest) return null;

    const key = `${entry.tmdb_id}-${entry.media_type}`;
    const media = toMedia(entry);

    // ── Movie: resume only, no roll-forward ──
    if (entry.media_type !== "tv") {
      if (latest.completed || latest.duration_seconds <= 0) return null;
      if (latest.position_seconds < MOVIE_MIN_SECONDS) return null;
      const frac = latest.position_seconds / latest.duration_seconds;
      if (frac > MAX_FRACTION) return null;
      return {
        key,
        media,
        title: entry.title,
        image: entry.poster_path, // posters are fine for movies
        mediaType: "movie",
        season: null,
        episode: null,
        upNext: false,
        position: latest.position_seconds,
        duration: latest.duration_seconds,
        watchedAt: latest.watched_at,
        progress: frac,
      };
    }

    // ── TV ──
    const s = latest.season ?? 1;
    const e = latest.episode ?? 1;
    const frac =
      latest.duration_seconds > 0
        ? latest.position_seconds / latest.duration_seconds
        : 0;

    // Mid-episode → resume that episode at its position.
    if (!latest.completed && frac <= MAX_FRACTION) {
      const still = await episodeStill(entry.tmdb_id, s, e);
      return {
        key,
        media,
        title: entry.title,
        image: still || entry.poster_path,
        mediaType: "tv",
        season: s,
        episode: e,
        upNext: false,
        position: latest.position_seconds,
        duration: latest.duration_seconds,
        watchedAt: latest.watched_at,
        progress: frac,
      };
    }

    // Finished that episode → roll forward to the next aired one ("Up Next").
    const next = await nextUnwatchedAiredEpisode(entry.tmdb_id, s, e, progress);
    if (!next) return null; // caught up
    return {
      key,
      media,
      title: entry.title,
      image: next.episode.still_path || entry.poster_path,
      mediaType: "tv",
      season: next.season,
      episode: next.episode.episode_number,
      upNext: true,
      position: 0,
      duration: 0,
      watchedAt: latest.watched_at,
      progress: 0,
    };
  }

  // Sequence-token guard: libraryChanged can bump rapidly (e.g. a burst of
  // progress saves), firing loadContinue again before the previous run's
  // fetches — and the seasonCache reset below — have settled. Without this,
  // an older run's results can land after and overwrite a newer run's.
  let loadSeq = 0;

  async function loadContinue(): Promise<void> {
    const seq = ++loadSeq;
    loading = true;
    seasonCache = new SvelteMap();
    try {
      // Anything with watch progress has a "watching" entry (progressSave
      // auto-creates one server-side), so this is the right starting set.
      const entries = await api.libraryList("watching");
      const results = await Promise.all(entries.map(buildItem));
      // Superseded by a newer load while this one was in flight — discard.
      if (seq !== loadSeq) return;
      items = results
        .filter((r): r is ContinueItem => r !== null)
        .toSorted(
          (a, b) =>
            new Date(b.watchedAt).getTime() - new Date(a.watchedAt).getTime(),
        );
    } finally {
      if (seq === loadSeq) loading = false;
    }
  }

  // Refetch whenever the library changes — finishing/advancing an episode
  // should reorder, roll forward, or drop a tile here.
  $effect(() => {
    $libraryChanged;
    loadContinue();
  });

  function resume(item: ContinueItem): void {
    if (onWatch) {
      onWatch(item.media, item.season ?? undefined, item.episode ?? undefined);
    } else {
      onSelectMedia(item.media);
    }
  }

  let activeAnim: ReturnType<typeof animate> | null = null;

  function scrollByCards(direction: 1 | -1): void {
    if (!trackEl) return;
    activeAnim?.pause();

    const target = trackEl.scrollLeft + direction * (trackEl.clientWidth * 0.9);

    activeAnim = animate(trackEl, {
      scrollLeft: target,
      duration: 400,
      ease: "inOutQuad",
    });
  }
</script>

{#if loading || items.length > 0}
  <div class="w-full space-y-3 px-4">
    <div class="ml-12 flex items-center justify-between px-1">
      <h2 class="text-lg font-semibold">Continue Watching</h2>
    </div>

    <div class="flex items-center justify-between gap-2 overflow-hidden">
      {#if navEnabled}
        <Button
                onclick={() => scrollByCards(-1)}
                variant="outline"
                size="icon"
                aria-label="Scroll left"
        >
          <ChevronLeft class="size-4" />
        </Button>
      {/if}


      <div
        bind:this={trackEl}
        class="flex min-w-0 flex-1 gap-4 overflow-x-auto px-1 pb-1 [&::-webkit-scrollbar]:hidden"
      >
        {#if loading}
          {#each { length: 5 } as _, i (i)}
            <Skeleton class="aspect-video w-70 shrink-0 rounded-md" />
          {/each}
        {:else}
          {#each items as item (item.key)}
            <ContinueWatchingCard {item} onResume={resume} />
          {/each}
        {/if}
      </div>
      {#if navEnabled}
      <Button
        onclick={() => scrollByCards(1)}
        variant="outline"
        size="icon"
        aria-label="Scroll right"
      >
        <ChevronRight class="size-4" />
      </Button>
      {/if}
    </div>
  </div>
{/if}
