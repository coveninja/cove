<script module lang="ts">
  import type { Media } from "$lib/types/tmdb";

  // Mirrored from ContinueWatchingCard.svelte — kept here so MobileContinueWatching
  // is self-contained within web/src/mobile/ and doesn't import from the desktop card.
  export interface ContinueItem {
    key: string; // `${tmdb_id}-${media_type}` — stable {#each} key
    media: Media; // enough of a Media to resume + open details
    title: string;
    image: string; // episode still (tv) or poster (movie / fallback)
    mediaType: "movie" | "tv";
    season: number | null; // null for movies
    episode: number | null; // null for movies
    upNext: boolean; // true => next episode "Up Next"; false => resume in progress
    position: number; // seconds watched (0 when upNext)
    duration: number; // total seconds (0 when upNext)
    watchedAt: string; // ISO; drives most-recent ordering
    progress: number; // 0..1, clamped — drives the bar (0 when upNext)
  }
</script>

<script lang="ts">
  // ── Data layer forked verbatim from ContinueWatching.svelte ─────────────────
  import { api, formatPosition } from "$lib/api";
  import type { TVEpisode } from "$lib/types/tmdb";
  import type { LibraryEntry, WatchProgress } from "$lib/types/library";
  import { libraryChanged } from "$lib/stores/library";
  import { mediaFromEntry } from "$lib/mediaFromEntry";
  import { SvelteMap } from "svelte/reactivity";
  import { nextUnwatchedAiredEpisode as nextUnwatchedAiredEpisodeShared } from "$lib/nextEpisode";
  // ── Touch feedback + image fade ──────────────────────────────────────────────
  import { pressable } from "../lib/pressable";
  import { imageFade } from "../lib/imageFade";
  // ── Icons for fallback artwork ───────────────────────────────────────────────
  import { Film, Tv } from "lucide-svelte";
  import * as m from "$lib/paraglide/messages.js";

  // Resume is the point of this row, so we take onWatch. onSelectMedia is the
  // fallback (open details) when no player handler is wired.
  let {
    onWatch,
    onSelectMedia,
  }: {
    onWatch?: (m: Media, season?: number, episode?: number) => void;
    onSelectMedia: (m: Media) => void;
  } = $props();

  let items = $state<ContinueItem[]>([]);
  let loading = $state(true);

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

  // ── Per-card derived helpers (used inline in the template) ──────────────────
  function subtitle(item: ContinueItem): string {
    if (item.mediaType !== "tv") {
      return `${formatPosition(Math.max(0, item.duration - item.position))} left`;
    }
    const tag = `S${item.season}E${item.episode}`;
    return item.upNext ? `${tag} · Up Next` : tag;
  }

  function pct(item: ContinueItem): number {
    return Math.round(Math.min(1, Math.max(0, item.progress)) * 100);
  }
</script>

{#if loading || items.length > 0}
  <div class="w-full space-y-2 px-4">
    <div class="px-1">
      <h2 class="text-base font-semibold">{m.home_continue_watching()}</h2>
    </div>

    <!-- Touch-native horizontal scroll: native inertia, no chevrons. -->
    <div
      class="flex gap-3 overflow-x-auto pb-1 [scrollbar-width:none] [-webkit-overflow-scrolling:touch] [&::-webkit-scrollbar]:hidden"
    >
      {#if loading}
        {#each { length: 4 } as _, i (i)}
          <div class="aspect-video w-56 shrink-0 animate-shimmer rounded-md"></div>
        {/each}
      {:else}
        {#each items as item (item.key)}
          <button
            use:pressable
            onclick={() => resume(item)}
            class="relative w-56 shrink-0 overflow-hidden rounded-md text-left"
            aria-label={item.upNext ? `Play ${item.title}` : `Resume ${item.title}`}
          >
            <!-- Artwork: episode still (TV) or poster (movie / fallback) -->
            {#if item.image}
              <img
                use:imageFade
                src={item.image}
                alt={item.title}
                loading="lazy"
                decoding="async"
                class="aspect-video w-full object-cover"
              />
            {:else}
              {@const Icon = item.mediaType === "tv" ? Tv : Film}
              <div
                class="flex aspect-video w-full items-center justify-center bg-secondary"
              >
                <Icon class="size-8 text-muted-foreground/40" />
              </div>
            {/if}

            <!-- Gradient overlay: title + episode/time-left label -->
            <span
              class="absolute inset-x-0 bottom-0 block px-2 pt-24 pb-2.5"
              style="background: linear-gradient(to top, rgba(0,0,0,0.85) 0%, transparent 100%)"
            >
              <span class="block truncate text-sm leading-tight font-semibold text-white">
                {item.title}
              </span>
              <span class="block truncate text-xs text-white/70">{subtitle(item)}</span>
            </span>

            <!-- Progress bar — omitted for "Up Next" tiles (progress = 0) -->
            {#if !item.upNext && pct(item) > 0}
              <span class="absolute inset-x-0 bottom-0 block h-1 bg-white/25">
                <span class="block h-full bg-accent" style="width: {pct(item)}%"></span>
              </span>
            {/if}
          </button>
        {/each}
      {/if}
    </div>
  </div>
{/if}
