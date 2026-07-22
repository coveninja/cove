<script lang="ts">
  import type { Media, MediaImages } from "$lib/types/tmdb";
  import type { Stream } from "$lib/types/addons";
  import { onMount, tick } from "svelte";
  import { X, Play, ThumbsDown } from "lucide-svelte";
  import { api, formatPosition } from "$lib/api";
  import { getImageOpt, formatRuntime, formatRating } from "$lib/utils";
  import type { LibraryEntry, WatchProgress } from "$lib/types/library";
  import { libraryChanged } from "$lib/stores/library";
  import { resolveTvWatchAction, type TvWatchAction } from "$lib/watchAction";
  import TvLibraryStatusPanel from "./TvLibraryStatusPanel.svelte";
  import StarRating from "../../components/StarRating.svelte";
  import TvStreamsList from "./TvStreamsList.svelte";
  import { Button } from "$lib/components/ui/button";
  import { focusGroup } from "../focus/actions";
  import { focusAfterKeyRelease } from "../focus/focusStore.svelte";
  import TvMediaCard from "./TvMediaCard.svelte";
  import { scale } from "svelte/transition";
  import { cubicOut } from "svelte/easing";

  let {
    media,
    streamActive = false,
    activeSeason = undefined,
    activeEpisode = undefined,
    onwatch,
    onplaystream,
    onclose,
    onsimilar,
  }: {
    media: Media;
    streamActive?: boolean;
    activeSeason?: number;
    activeEpisode?: number;
    onwatch: (season?: number, episode?: number) => void;
    onplaystream?: (
      stream: Stream,
      season?: number,
      episode?: number,
      episodeName?: string,
      candidates?: Stream[],
    ) => void;
    onclose: () => void;
    onsimilar?: (m: Media) => void;
  } = $props();

  // ── Derived title / year ───────────────────────────────────────────────────
  const title = $derived(media.media_type === "tv" ? media.name : media.title);
  const year = $derived(
    (media.media_type === "tv" ? media.first_air_date : media.release_date)?.slice(0, 4),
  );

  // ── Detail data ────────────────────────────────────────────────────────────
  let detailsLoading = $state(true);
  let images = $state<MediaImages | undefined>();
  let genres: string[] = $state([]);
  let runtime = $state("");
  let castNames: string[] = $state([]);
  let ageRating = $state("");
  let similar: Media[] = $state([]);
  let numberOfEpisodes = $state<number | null>(null);
  let lastAiredSeason = $state<number | null>(null);
  let lastAiredEpisode = $state<number | null>(null);
  let detailsOverview = $state<string | null>(null);

  $effect(() => {
    detailsLoading = true;
    const type = media.media_type;
    let stale = false;
    Promise.all([
      api.getSimilar(media),
      api.getDetails(media),
      api.getImages(media),
    ])
      .then(([similarList, details, img]) => {
        if (stale) return;
        images = img;
        similar = similarList;
        detailsOverview = details.overview ?? null;
        genres = details.genres?.map((g: { name: string }) => g.name).slice(0, 3) ?? [];
        runtime = formatRuntime(details);
        castNames = details.credits?.cast?.slice(0, 8).map((c: { name: string }) => c.name) ?? [];
        ageRating = formatRating(details);
        if (type === "tv") {
          numberOfEpisodes = details.number_of_episodes ?? null;
          lastAiredSeason = details.last_episode_to_air?.season_number ?? null;
          lastAiredEpisode = details.last_episode_to_air?.episode_number ?? null;
        }
        detailsLoading = false;
      })
      .catch((err) => {
        if (!stale) {
          console.error("TvDetailOverlay fetch failed", err);
          detailsLoading = false;
        }
      });
    return () => {
      stale = true;
    };
  });

  // ── Library + progress ─────────────────────────────────────────────────────
  let libraryEntry = $state<LibraryEntry | null>(null);
  let movieProgress = $state<WatchProgress | null>(null);
  let dismissed = $state(false);
  let tvWatchAction = $state<TvWatchAction | null>(null);

  $effect(() => {
    let stale = false;
    api
      .libraryGet(media.id, media.media_type)
      .then((result) => {
        if (stale) return;
        if (!result) {
          libraryEntry = null;
          movieProgress = null;
          dismissed = false;
          tvWatchAction = null;
          return;
        }
        libraryEntry = result.entry;
        dismissed = result.dismissed;
        if (media.media_type === "movie") {
          movieProgress = result.progress[0] ?? null;
        } else {
          resolveTvWatchAction(media.id, result.progress)
            .then((action) => { if (!stale) tvWatchAction = action; })
            .catch((err) => { if (!stale) console.error(err); });
        }
      })
      .catch((err) => {
        if (!stale) console.error(err);
      });
    return () => {
      stale = true;
    };
  });

  async function toggleDismissed(): Promise<void> {
    const next = !dismissed;
    dismissed = next;
    try {
      if (next) await api.notInterested(media);
      else await api.undoNotInterested(media);
      libraryChanged.update((n) => n + 1);
    } catch {
      dismissed = !next;
    }
  }

  // ── Derived image URLs ─────────────────────────────────────────────────────
  const logoUrl = $derived(
    images && images.logos?.length > 0
      ? getImageOpt(images, "logos", { iso: "en" })
      : null,
  );
  const backdropUrl = $derived(
    images && images.backdrops?.length > 0
      ? getImageOpt(images, "backdrops", { iso: "en" })
      : null,
  );

  // ── Progress calculations ──────────────────────────────────────────────────
  const movieProgressPct = $derived(
    movieProgress && movieProgress.duration_seconds > 0
      ? Math.min(
          100,
          (movieProgress.position_seconds / movieProgress.duration_seconds) * 100,
        )
      : 0,
  );
  const hasIncompleteMovieProgress = $derived(
    media.media_type === "movie" &&
      movieProgress !== null &&
      !movieProgress.completed &&
      movieProgressPct > 1,
  );
  const watchButtonLabel = $derived.by(() => {
    if (media.media_type === "tv") {
      if (tvWatchAction) return tvWatchAction.label;
      // Generic while the resolver's season fetch is in flight.
      if (libraryEntry?.last_watched_season != null) return "Continue";
    }
    return hasIncompleteMovieProgress ? "Continue" : "Watch";
  });

  // ── Overview ───────────────────────────────────────────────────────────────
  const overviewText = $derived(detailsOverview ?? media.overview ?? "");

  // ── Stream max quality ─────────────────────────────────────────────────────
  let streamMaxQuality = $state<string | null>(null);

  // ── Actions ────────────────────────────────────────────────────────────────
  function watchNow(): void {
    onwatch(tvWatchAction?.season, tvWatchAction?.episode);
    onclose();
  }

  function playStream(
    stream: Stream,
    season?: number,
    episode?: number,
    episodeName?: string,
    candidates?: Stream[],
  ): void {
    onplaystream?.(stream, season, episode, episodeName, candidates);
    onclose();
  }

  // ── Focus: Play button ─────────────────────────────────────────────────────
  // Wrap the Button in a div so we can find its inner <button> without needing
  // the shadcn component to expose a bind:ref prop. Focus waits for the key
  // release that opened the overlay (see focusAfterKeyRelease) — otherwise the
  // same Enter press activates Play and playback starts instantly.
  let playWrapEl = $state<HTMLElement | null>(null);

  onMount(() => {
    let cancel: (() => void) | undefined;
    tick().then(() => {
      cancel = focusAfterKeyRelease(
        () => playWrapEl?.querySelector("button") as HTMLElement | null,
      );
    });
    return () => cancel?.();
  });
</script>

<!--
  Full-screen fixed detail overlay.

  use:focusGroup with free policy + trapFocus:true keeps all D-pad navigation
  inside the overlay while it is open. The geometric fallback (scoped to the
  group container by policyNavigate in focusStore) reaches every native
  focusable inside StreamsList, StarRating and TvMediaCard without any edits to
  those shared components. TvLibraryStatusPanel opens TvTrackPanel as a
  separate focus group (its own trapFocus), so it is not scoped inside this
  overlay group — that is intentional and correct.
-->
<!--
  tv-detail-root: hidden (not unmounted) while playback is fullscreen — this
  overlay sits at z-40 ABOVE the z-30 player, so starting a stream from
  StreamsList here would otherwise leave an opaque sheet covering the video.
  Mobile gets the same behavior for free from z-order (sheet z-20 < player
  z-30); on TV we mirror it with visibility. State survives, so closing the
  player lands back on this overlay.
-->
<div
  class="tv-detail-root fixed inset-0 z-40 overflow-y-auto bg-background"
  use:focusGroup={{ id: "tv-detail", policy: { type: "free" }, trapFocus: true }}
  role="dialog"
  aria-modal="true"
  aria-label={title}
  transition:scale={{ duration: 280, start: 0.95, opacity: 0, easing: cubicOut }}
>
  <!-- ── Hero backdrop ────────────────────────────────────────────────────── -->
  <div
    class="relative w-full overflow-hidden bg-black h-[clamp(260px,45vh,520px)]"
  >
    {#if backdropUrl}
      <img
        src={backdropUrl}
        alt={title}
        class="absolute inset-0 h-full w-full object-cover"
      />
    {:else if media.poster_path}
      <img
        src={media.poster_path}
        alt={title}
        class="absolute inset-0 h-full w-full object-cover object-top"
      />
    {/if}

    <!-- Gradient fading into background -->
    <div
      class="pointer-events-none absolute inset-x-0 bottom-0 z-10 h-4/5"
      style="background: linear-gradient(to top, var(--background) 5%, rgba(0,0,0,0.5) 60%, transparent 100%);"
    ></div>

    <!-- Logo or title at bottom-left of hero -->
    <div class="pointer-events-none absolute bottom-0 left-0 z-10 max-w-[60%] p-8">
      {#if logoUrl}
        <img
          src={logoUrl}
          alt={title}
          class="max-h-24 w-auto max-w-full object-contain drop-shadow-lg"
        />
      {:else}
        <h2 class="text-3xl leading-tight font-bold text-white drop-shadow-lg">
          {title}
        </h2>
      {/if}
    </div>

    <!-- Close button pinned top-right -->
    <button
      class="absolute right-6 top-6 z-20 flex size-12 items-center justify-center rounded-full bg-black/50 text-white"
      onclick={onclose}
      aria-label="Close"
      type="button"
    >
      <X class="size-6" />
    </button>
  </div>

  <!-- ── Body ─────────────────────────────────────────────────────────────── -->
  <div class="flex flex-col gap-6 px-8 py-6">
    <!-- Meta row: year · runtime/seasons · age rating · genres -->
    <div class="flex flex-wrap items-center gap-2 text-base text-muted-foreground">
      {#if year}
        <span>{year}</span>
      {/if}
      {#if ageRating}
        <span class="rounded border border-border px-2 py-0.5 text-sm text-foreground"
          >{ageRating}</span
        >
      {/if}
      {#if runtime}
        <span>{runtime}</span>
      {/if}
      {#if media.media_type === "tv" && numberOfEpisodes !== null}
        <span>{numberOfEpisodes} ep{numberOfEpisodes !== 1 ? "s" : ""}</span>
      {/if}
      {#if genres.length}
        <span class="text-muted-foreground/60">·</span>
        {#each genres as genre, i (genre)}
          <span>{genre}{i < genres.length - 1 ? "," : ""}</span>
        {/each}
      {/if}
    </div>

    <!-- Movie progress bar -->
    {#if hasIncompleteMovieProgress && movieProgress}
      <div class="flex items-center gap-3 rounded-md bg-secondary/40 px-4 py-3">
        <div class="h-1.5 flex-1 overflow-hidden rounded-full bg-secondary">
          <div
            class="h-full rounded-full bg-accent transition-all"
            style="width: {movieProgressPct}%"
          ></div>
        </div>
        <span class="shrink-0 text-sm text-muted-foreground tabular-nums">
          {formatPosition(movieProgress.position_seconds)} / {formatPosition(
            movieProgress.duration_seconds,
          )}
        </span>
      </div>
    {/if}

    <!-- Action row -->
    <div class="flex items-center gap-4 justify-between">
      <!-- Primary Watch / Continue button — wrapped for programmatic focus -->
      <div bind:this={playWrapEl}>
        <Button
          class="h-14 gap-2 rounded-xl px-8 text-lg font-semibold"
          variant="default"
          onclick={watchNow}
        >
          <Play class="size-5 fill-current" />
          {watchButtonLabel}
        </Button>
      </div>

      <div class="flex gap-4">
        <!-- Library status -->
        <TvLibraryStatusPanel
                {libraryEntry}
                {media}
                {lastAiredSeason}
                {lastAiredEpisode}
                class="size-14 shrink-0 rounded-xl"
        />

        <!-- Not interested -->
        <Button
                variant={dismissed ? "destructive" : "outline"}
                size="icon"
                class="size-14 shrink-0 rounded-xl"
                onclick={toggleDismissed}
                title={dismissed ? "Undo not interested" : "Not interested"}
        >
          <ThumbsDown class="size-5" />
        </Button>

        <!-- Star rating -->
        <StarRating
                {libraryEntry}
                {media}
                {lastAiredSeason}
                {lastAiredEpisode}
                variant="inline"
        />
      </div>


    </div>

    <!-- Overview -->
    {#if overviewText}
      <p class="max-w-3xl text-base leading-relaxed text-muted-foreground">
        {overviewText}
      </p>
    {:else if detailsLoading}
      <p class="animate-pulse text-base text-muted-foreground">Loading details…</p>
    {/if}

    <!-- Cast row (names only) -->
    {#if castNames.length}
      <div class="flex flex-col gap-2">
        <h3 class="text-base font-semibold text-foreground">Cast</h3>
        <div class="flex flex-wrap gap-2">
          {#each castNames as name (name)}
            <span
              class="rounded-full border border-border bg-secondary/60 px-3 py-1.5 text-sm text-foreground"
            >
              {name}
            </span>
          {/each}
        </div>
      </div>
    {/if}

    <!-- Streams / episodes section — always expanded on TV (no toggle) -->
    <div class="flex flex-col gap-3">
      <h3 class="text-base font-semibold text-foreground">
        {media.media_type === "tv" ? "Episodes & Sources" : "Sources"}
      </h3>
      <TvStreamsList
        {media}
        onPlayStream={(
          s: Stream,
          season?: number,
          episode?: number,
          episodeName?: string,
          candidates?: Stream[],
        ) => playStream(s, season, episode, episodeName, candidates)}
        bind:maxQuality={streamMaxQuality}
        {streamActive}
        {activeSeason}
        {activeEpisode}
      />
    </div>

    <!-- More like this — TvMediaCard grid, D-pad navigable via detail-similar group -->
    {#if similar.length}
      <div class="flex flex-col gap-4 pb-8">
        <h3 class="text-base font-semibold text-foreground">More like this</h3>
        <div class="grid grid-cols-6 gap-4 xl:grid-cols-8">
          {#each similar.slice(0, 16) as item (item.id)}
            <TvMediaCard
              media={item}
              groupId="detail-similar"
              onclick={(m) => onsimilar?.(m)}
            />
          {/each}
        </div>
      </div>
    {/if}
  </div>
</div>

<style>
  /* See the tv-detail-root comment above: yield to the player during playback. */
  :global(html.cove-playing) .tv-detail-root {
    visibility: hidden;
  }
</style>
