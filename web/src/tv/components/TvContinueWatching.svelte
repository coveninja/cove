<script module lang="ts">
  import type { Media } from "$lib/types/tmdb";

  // Mirrored from ContinueWatchingCard.svelte — kept here so TvContinueWatching
  // is self-contained within web/src/tv/ and doesn't import from the desktop card.
  export interface ContinueItem {
    key: string;
    media: Media;
    title: string;
    image: string;
    mediaType: "movie" | "tv";
    season: number | null;
    episode: number | null;
    upNext: boolean;
    position: number;
    duration: number;
    watchedAt: string;
    progress: number;
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
  import { nextAiredEpisode as nextAiredEpisodeShared } from "$lib/nextEpisode";
  // ── TV focus engine ──────────────────────────────────────────────────────────
  import { focusGroup, focusable } from "../focus/actions";
  // ── Icons for fallback artwork ───────────────────────────────────────────────
  import { Play, Film, Tv } from "lucide-svelte";

  let {
    onWatch,
    onSelectMedia,
  }: {
    onWatch?: (m: Media, season?: number, episode?: number) => void;
    onSelectMedia: (m: Media) => void;
  } = $props();

  let items = $state<ContinueItem[]>([]);
  let loading = $state(true);

  const MOVIE_MIN_SECONDS = 15;
  const MAX_FRACTION = 0.95;

  let seasonCache = new SvelteMap<string, Promise<TVEpisode[]>>();

  function fetchSeason(id: number, season: number): Promise<TVEpisode[]> {
    const key = `${id}:${season}`;
    let p = seasonCache.get(key);
    if (!p) {
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

  function nextAiredEpisode(
    id: number,
    season: number,
    episode: number,
  ): Promise<{ season: number; episode: TVEpisode } | null> {
    return nextAiredEpisodeShared(id, season, episode, fetchSeason);
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
      return null;
    }

    const latest = latestProgress(progress);
    if (!latest) return null;

    const key = `${entry.tmdb_id}-${entry.media_type}`;
    const media = toMedia(entry);

    if (entry.media_type !== "tv") {
      if (latest.completed || latest.duration_seconds <= 0) return null;
      if (latest.position_seconds < MOVIE_MIN_SECONDS) return null;
      const frac = latest.position_seconds / latest.duration_seconds;
      if (frac > MAX_FRACTION) return null;
      return {
        key,
        media,
        title: entry.title,
        image: entry.poster_path,
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

    const s = latest.season ?? 1;
    const e = latest.episode ?? 1;
    const frac =
      latest.duration_seconds > 0
        ? latest.position_seconds / latest.duration_seconds
        : 0;

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

    const next = await nextAiredEpisode(entry.tmdb_id, s, e);
    if (!next) return null;
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

  let loadSeq = 0;

  async function loadContinue(): Promise<void> {
    const seq = ++loadSeq;
    loading = true;
    seasonCache = new SvelteMap();
    try {
      const entries = await api.libraryList("watching");
      const results = await Promise.all(entries.map(buildItem));
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

<!--
  Renders nothing when empty (no loading state either) so the row leaves no
  dead-zone in the D-pad focus graph.
-->
{#if loading || items.length > 0}
  <div class="w-full space-y-3">
    <!--
      Header is display-only — zero focusables here, matching TvMediaRow
      convention so D-pad Left/Right doesn't land on text.
    -->
    <div class="flex items-center">
      <h2 class="text-xl font-semibold">Continue Watching</h2>
    </div>

    <!--
      D-pad-navigable horizontal strip.
      overflow-x-hidden blocks touch/mouse scroll while still forming a scroll
      container that the focus engine's scrollIntoView() drives on D-pad moves.
      rememberFocus: true restores the last-focused card when the row is re-entered.
    -->
    <div
      use:focusGroup={{ id: "row-continue-watching", policy: { type: "row" }, rememberFocus: true }}
      class="tv-row flex gap-4 p-4 overflow-x-hidden"
    >
      {#if loading}
        {#each { length: 5 } as _, i (i)}
          <div class="w-70 shrink-0">
            <div class="aspect-video w-full animate-pulse rounded-md bg-muted"></div>
          </div>
        {/each}
      {:else}
        {#each items as item (item.key)}
          <!--
            Single focusable per card — no secondary affordances.
            The focus ring and scale come from the tv-shell global CSS + these
            utilities, matching TvMediaCard's pattern.
          -->
          <div
            use:focusable={{ groupId: "row-continue-watching" }}
            onclick={() => resume(item)}
            onkeydown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                resume(item);
              }
            }}
            role="button"
            tabindex="-1"
            class="relative w-70 shrink-0 cursor-pointer overflow-hidden rounded-md transition-[transform,filter,scale] duration-150 ease-[ease] focus:scale-[1.08] focus:brightness-[1.15]"
            aria-label={item.upNext ? `Play ${item.title}` : `Resume ${item.title}`}
          >
            <!-- Artwork: episode still (TV) or poster (movie / fallback) -->
            {#if item.image}
              <img
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

            <!-- Focus play affordance (visible only when the card has focus) -->
            <span
              class="pointer-events-none absolute inset-0 flex items-center justify-center opacity-0 transition-opacity duration-150 [[data-tv-focusable]:focus_&]:opacity-100"
            >
              <span
                class="flex size-12 items-center justify-center rounded-full bg-white/90"
              >
                <Play class="size-6 translate-x-0.5 fill-current text-black" />
              </span>
            </span>
          </div>
        {/each}
      {/if}
    </div>
  </div>
{/if}
