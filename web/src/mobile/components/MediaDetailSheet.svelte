<script lang="ts">
  import type { Media, MediaImages, MediaVideos } from "$lib/types/tmdb";
  import type { Stream } from "$lib/types/addons";
  import { onMount } from "svelte";
  import { X, Play, ListVideo, ChevronDown, ThumbsDown, Volume2, VolumeOff } from "lucide-svelte";
  import { api, formatPosition } from "$lib/api";
  import { getImageOpt, getVideoOpt, formatRuntime, formatRating } from "$lib/utils";
  import PlayerSimple from "../../components/PlayerSimple.svelte";
  import type { LibraryEntry, WatchProgress } from "$lib/types/library";
  import { libraryChanged } from "$lib/stores/library";
  import { resolveTvWatchAction, type TvWatchAction } from "$lib/watchAction";
  import LibraryStatusPanel from "../../components/LibraryStatusPanel.svelte";
  import StarRating from "../../components/StarRating.svelte";
  import StreamsList from "../../components/StreamsList.svelte";
  import { Button } from "$lib/components/ui/button";
  import { animate } from "animejs";
  import { pressable } from "../lib/pressable";

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
  let numberOfSeasons = $state<number | null>(null);
  let numberOfEpisodes = $state<number | null>(null);
  let lastAiredSeason = $state<number | null>(null);
  let lastAiredEpisode = $state<number | null>(null);
  let detailsOverview = $state<string | null>(null);

  $effect(() => {
    detailsLoading = true;
    const type = media.media_type;
    let stale = false;
    trailerUrl = null;
    Promise.all([
      api.getSimilar(media),
      api.getDetails(media),
      api.getImages(media),
      api.getVideos(media).catch(() => null as unknown as MediaVideos),
    ])
      .then(([similarList, details, img, vids]) => {
        if (stale) return;
        images = img;
        similar = similarList;
        detailsOverview = details.overview ?? null;
        genres = details.genres?.map((g: { name: string }) => g.name).slice(0, 3) ?? [];
        runtime = formatRuntime(details);
        castNames = details.credits?.cast?.slice(0, 8).map((c: { name: string }) => c.name) ?? [];
        ageRating = formatRating(details);
        if (type === "tv") {
          numberOfSeasons = details.number_of_seasons ?? null;
          numberOfEpisodes = details.number_of_episodes ?? null;
          lastAiredSeason = details.last_episode_to_air?.season_number ?? null;
          lastAiredEpisode = details.last_episode_to_air?.episode_number ?? null;
        }
        trailerUrl = vids
          ? (getVideoOpt(vids, "Trailer", { iso: "en", randomize: true }) ?? null)
          : null;
        detailsLoading = false;
      })
      .catch((err) => {
        if (!stale) {
          console.error("MediaDetailSheet fetch failed", err);
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

  // ── Library panel bounce ───────────────────────────────────────────────────
  let libraryPanelEl = $state<HTMLElement | null>(null);
  // Plain (non-reactive) variable to track the previous value across effect runs.
  let _prevLibraryEntry: LibraryEntry | null = null;

  $effect(() => {
    const entry = libraryEntry;
    if (entry !== null && _prevLibraryEntry === null && libraryPanelEl) {
      animate(libraryPanelEl, { scale: [1, 1.12, 1], duration: 250, ease: "outBack" });
    }
    _prevLibraryEntry = entry;
  });

  // ── Trailer (muted, looping hero video) ───────────────────────────────────
  let trailerUrl = $state<string | null>(null);
  let trailerMuted = $state(true);

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

  // ── Overview collapsible ───────────────────────────────────────────────────
  let overviewExpanded = $state(false);
  let overviewEl = $state<HTMLElement | null>(null);
  let overviewAnimating = $state(false);
  // Non-reactive: stores measured collapsed height for the collapse animation.
  let collapsedHeight = 0;
  const overviewText = $derived(detailsOverview ?? media.overview ?? "");

  function toggleOverview(): void {
    if (!overviewEl) {
      overviewExpanded = !overviewExpanded;
      return;
    }
    if (overviewAnimating) return;
    overviewAnimating = true;
    const el = overviewEl;

    if (!overviewExpanded) {
      // Expanding: capture collapsed pixel height, pin it, remove clamp, animate to full.
      const from = el.clientHeight;
      collapsedHeight = from;
      el.style.overflow = "hidden";
      el.style.height = `${from}px`;
      overviewExpanded = true;
      // Wait for Svelte to flush the clamp-class removal (microtask), then measure.
      requestAnimationFrame(() => {
        const to = el.scrollHeight;
        animate(el, {
          height: `${to}px`,
          duration: 280,
          ease: "outCubic",
          onComplete: () => {
            el.style.height = "";
            el.style.overflow = "";
            overviewAnimating = false;
          },
        });
      });
    } else {
      // Collapsing: pin current height, flip state, animate back to stored collapsed height.
      const from = el.clientHeight;
      el.style.overflow = "hidden";
      el.style.height = `${from}px`;
      overviewExpanded = false;
      requestAnimationFrame(() => {
        // collapsedHeight was saved on the most recent expand; fall back to 4 lines ~96px.
        const to = collapsedHeight || 96;
        animate(el, {
          height: `${to}px`,
          duration: 280,
          ease: "outCubic",
          onComplete: () => {
            el.style.height = "";
            el.style.overflow = "";
            overviewAnimating = false;
          },
        });
      });
    }
  }

  // ── Stream toggle ──────────────────────────────────────────────────────────
  let showStreams = $state(false);
  let streamMaxQuality = $state<string | null>(null);
  const streamsToggleLabel = $derived(
    media.media_type === "tv" ? "Episodes" : "Choose a source",
  );

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

  // ── Sheet animation + drag-to-dismiss ─────────────────────────────────────
  let sheetEl = $state<HTMLElement | null>(null);
  let scrollEl = $state<HTMLElement | null>(null);
  let visible = $state(false);
  let closing = $state(false);
  let dragging = $state(false);
  let dragOffset = $state(0);

  onMount(() => {
    // Defer visibility to next frame so the initial translateY(100%) is painted
    // before we flip to 0, giving the entry animation something to animate from.
    const rafId = requestAnimationFrame(() => {
      visible = true;
    });

    const el = sheetEl;
    if (!el) return () => cancelAnimationFrame(rafId);

    let touchStartY = 0;
    let lastY = 0;
    let lastT = 0;
    let velocity = 0;

    const onTouchStart = (e: TouchEvent) => {
      // Only activate drag when the scroll container is at the very top.
      if (!scrollEl || scrollEl.scrollTop > 2) return;
      touchStartY = e.touches[0].clientY;
      lastY = touchStartY;
      lastT = Date.now();
      velocity = 0;
      dragOffset = 0;
    };

    const onTouchMove = (e: TouchEvent) => {
      if (!touchStartY) return;
      const y = e.touches[0].clientY;
      const dy = y - touchStartY;
      if (dy < 0) {
        // User swiped upward — cancel drag tracking, let native scroll take over.
        touchStartY = 0;
        dragging = false;
        return;
      }
      const now = Date.now();
      velocity = (y - lastY) / (now - lastT || 1);
      lastY = y;
      lastT = now;
      if (!dragging && dy > 8) dragging = true;
      if (dragging) {
        dragOffset = dy;
        // Prevent the scroll container from also scrolling while we're dragging.
        e.preventDefault();
      }
    };

    const onTouchEnd = () => {
      if (!dragging) {
        touchStartY = 0;
        return;
      }
      const offset = dragOffset;
      const vel = velocity;
      dragging = false;
      touchStartY = 0;
      if (offset > 80 || vel > 0.5) {
        dragOffset = 0;
        closeSheet();
      } else {
        dragOffset = 0;
      }
    };

    el.addEventListener("touchstart", onTouchStart, { passive: true });
    el.addEventListener("touchmove", onTouchMove, { passive: false });
    el.addEventListener("touchend", onTouchEnd, { passive: true });

    return () => {
      cancelAnimationFrame(rafId);
      el.removeEventListener("touchstart", onTouchStart);
      el.removeEventListener("touchmove", onTouchMove);
      el.removeEventListener("touchend", onTouchEnd);
    };
  });

  function closeSheet(): void {
    closing = true;
    setTimeout(onclose, 300);
  }

  // Inline transform drives both the entry/exit slide and the drag follow.
  const sheetTransform = $derived.by(() => {
    if (dragging) return `translateY(${dragOffset}px)`;
    if (!visible || closing) return "translateY(100%)";
    return "translateY(0)";
  });
  // Open uses a springy overshoot curve; close/drag-back stays non-overshooting.
  const sheetTransition = $derived.by(() => {
    if (dragging) return "none";
    if (closing || !visible) return "transform 300ms cubic-bezier(0.22, 1, 0.36, 1)";
    return "transform 320ms cubic-bezier(0.34, 1.56, 0.64, 1)";
  });
</script>

<!-- Dark scrim behind the sheet -->
<div
  class="fixed inset-0 z-20 bg-black/60"
  style="opacity: {visible && !closing ? 1 : 0}; transition: opacity 300ms ease;"
></div>

<!-- Sheet itself -->
<div
  bind:this={sheetEl}
  class="fixed inset-0 z-20 flex flex-col overflow-hidden bg-background"
  style="transform: {sheetTransform}; transition: {sheetTransition}; padding-top: var(--safe-top);"
  role="dialog"
  aria-modal="true"
  aria-label={title}
>
  <!-- Drag handle (visual indicator) -->
  <div class="flex flex-none justify-center pt-2.5 pb-1">
    <div class="h-1 w-10 rounded-full bg-muted-foreground/30"></div>
  </div>

  <!-- X close button — pinned top-right, 44 px touch target -->
  <button
    class="absolute right-3 z-10 flex size-11 items-center justify-center rounded-full bg-black/50 text-white"
    style="top: calc(0.5rem + var(--safe-top));"
    onclick={closeSheet}
    aria-label="Close"
    type="button"
  >
    <X class="size-5" />
  </button>

  <!-- Scrollable content -->
  <div
    bind:this={scrollEl}
    class="flex-1 overflow-y-auto overscroll-contain"
    style="padding-bottom: calc(3.5rem + max(1.5rem, var(--safe-bottom)));"
  >
    <!-- ── Hero ───────────────────────────────────────────────────────────── -->
    <!-- isolate contains PlayerSimple's internal z-indexes so they can't bleed
         into the sheet-level stacking context. -->
    <div
      class="relative isolate w-full overflow-hidden bg-black"
      style="height: clamp(180px,52vw,320px);"
    >
      {#if trailerUrl}
        <!-- Muted looping trailer; destroyed automatically when sheet closes
             because MobileApp gates it on {#if selectedMedia}{#key ...}. -->
        <div class="absolute inset-0">
          <PlayerSimple
            src={trailerUrl}
            muted={trailerMuted}
            loop={true}
            bg={backdropUrl ?? media.poster_path ?? ""}
          />
        </div>
      {:else if backdropUrl}
        <img
          src={backdropUrl}
          alt={title}
          class="absolute inset-0 h-full w-full object-cover"
        />
      {:else}
        <img
          src={media.poster_path}
          alt={title}
          class="absolute inset-0 h-full w-full object-cover object-top"
        />
      {/if}

      <!-- Gradient fading into background -->
      <div
        class="pointer-events-none absolute inset-x-0 bottom-0 z-10 h-3/4"
        style="background: linear-gradient(to top, var(--background) 5%, rgba(0,0,0,0.4) 60%, transparent 100%);"
      ></div>

      <!-- Logo or title at bottom-left of hero -->
      <div class="pointer-events-none absolute bottom-0 left-0 z-10 max-w-[72%] p-4">
        {#if logoUrl}
          <img
            src={logoUrl}
            alt={title}
            class="max-h-16 w-auto max-w-full object-contain drop-shadow-lg"
          />
        {:else}
          <h2 class="text-xl leading-tight font-bold text-white drop-shadow-lg">
            {title}
          </h2>
        {/if}
      </div>

      <!-- Mute toggle — only shown while trailer is playing -->
      {#if trailerUrl}
        <button
          class="absolute bottom-3 right-3 z-20 flex size-9 items-center justify-center rounded-full bg-black/60 text-white"
          onclick={() => (trailerMuted = !trailerMuted)}
          type="button"
          aria-label={trailerMuted ? "Unmute trailer" : "Mute trailer"}
        >
          {#if trailerMuted}
            <VolumeOff class="size-4" />
          {:else}
            <Volume2 class="size-4" />
          {/if}
        </button>
      {/if}
    </div>

    <!-- ── Body ──────────────────────────────────────────────────────────── -->
    <div class="flex flex-col gap-4 px-4 pt-3">
      <!-- Meta row: year · runtime/seasons · age rating -->
      <div class="flex flex-wrap items-center gap-1.5 text-sm text-muted-foreground">
        {#if detailsLoading}
          <div class="h-4 w-8 animate-shimmer rounded"></div>
          <div class="h-4 w-12 animate-shimmer rounded"></div>
          <div class="h-4 w-16 animate-shimmer rounded"></div>
        {:else}
          {#if year}
            <span>{year}</span>
          {/if}
          {#if ageRating}
            <span class="rounded border border-border px-1.5 py-0.5 text-xs text-foreground"
              >{ageRating}</span
            >
          {/if}
          {#if runtime}
            <span>{runtime}</span>
          {/if}
          {#if media.media_type === "tv" && numberOfEpisodes !== null}
            <span
              >{numberOfEpisodes} ep{numberOfEpisodes !== 1 ? "s" : ""}</span
            >
          {/if}
          {#if genres.length}
            <span class="text-muted-foreground/60">·</span>
            {#each genres as genre, i (genre)}
              <span>{genre}{i < genres.length - 1 ? "," : ""}</span>
            {/each}
          {/if}
        {/if}
      </div>

      <!-- Movie progress bar -->
      {#if hasIncompleteMovieProgress && movieProgress}
        <div class="flex items-center gap-2 rounded-md bg-secondary/40 px-3 py-2">
          <div class="h-1.5 flex-1 overflow-hidden rounded-full bg-secondary">
            <div
              class="h-full rounded-full bg-accent transition-all"
              style="width: {movieProgressPct}%"
            ></div>
          </div>
          <span class="shrink-0 text-xs text-muted-foreground tabular-nums">
            {formatPosition(movieProgress.position_seconds)} / {formatPosition(
              movieProgress.duration_seconds,
            )}
          </span>
        </div>
      {/if}

      <!-- Action row -->
      <div class="flex items-center gap-2.5">
        <!-- Primary Watch button -->
        <Button
          class="h-12 flex-1 gap-2 rounded-lg text-base font-semibold"
          variant="default"
          onclick={watchNow}
        >
          <Play class="size-4 fill-current" />
          {watchButtonLabel}
        </Button>

        <!-- Library status wrapped for bounce animation -->
        <div bind:this={libraryPanelEl} class="shrink-0">
          <LibraryStatusPanel
            {libraryEntry}
            {media}
            {lastAiredSeason}
            {lastAiredEpisode}
            size="icon"
            class="size-12 rounded-lg"
          />
        </div>

        <!-- Not interested -->
        <Button
                variant={dismissed ? "destructive" : "outline"}
                size="icon"
                class="size-12 shrink-0 rounded-lg"
                onclick={toggleDismissed}
                title={dismissed ? "Undo not interested" : "Not interested"}
        >
          <ThumbsDown class="size-4" />
        </Button>

        <!-- Star rating -->
        <StarRating
          {libraryEntry}
          {media}
          {lastAiredSeason}
          {lastAiredEpisode}
        />
      </div>

      <!-- Episodes / choose a source toggle -->
      <Button
        class="h-12 w-full rounded-lg"
        variant="outline"
        onclick={() => (showStreams = !showStreams)}
      >
        <ListVideo class="size-4" />
        {streamsToggleLabel}
        <ChevronDown
          class="ml-auto size-4 transition-transform duration-200 {showStreams
            ? 'rotate-180'
            : ''}"
        />
      </Button>

      <!-- Expandable StreamsList -->
      {#if showStreams}
        <div class="min-h-[24rem]">
          <StreamsList
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
      {/if}

      <!-- Overview -->
      {#if overviewText}
        <div class="flex flex-col gap-1.5">
          <div
            bind:this={overviewEl}
            class="text-sm leading-relaxed text-muted-foreground {!overviewExpanded && !overviewAnimating ? 'line-clamp-4 overflow-hidden' : ''}"
          >
            {overviewText}
          </div>
          {#if overviewText.length > 200}
            <button
              class="self-start text-xs font-medium text-accent underline-offset-2 hover:underline"
              onclick={toggleOverview}
              type="button"
            >
              {overviewExpanded ? "Show less" : "Show more"}
            </button>
          {/if}
        </div>
      {:else if detailsLoading}
        <div class="flex flex-col gap-2">
          <div class="h-3 w-full animate-shimmer rounded"></div>
          <div class="h-3 w-5/6 animate-shimmer rounded"></div>
          <div class="h-3 w-3/4 animate-shimmer rounded"></div>
        </div>
      {/if}

      <!-- Cast row (names only — cast credits do not include profile photos) -->
      {#if castNames.length}
        <div class="flex flex-col gap-2">
          <h3 class="text-sm font-semibold text-foreground">Cast</h3>
          <div
            class="flex gap-2 overflow-x-auto pb-1 [scrollbar-width:none]"
            style="-webkit-overflow-scrolling: touch;"
          >
            {#each castNames as name (name)}
              <span
                class="shrink-0 rounded-full border border-border bg-secondary/60 px-3 py-1.5 text-xs text-foreground"
              >
                {name}
              </span>
            {/each}
          </div>
        </div>
      {/if}

      <!-- More like this (horizontal poster scroll) -->
      {#if similar.length}
        <div class="flex flex-col gap-2">
          <h3 class="text-sm font-semibold text-foreground">More like this</h3>
          <div
            class="flex gap-3 overflow-x-auto pb-2 [scrollbar-width:none]"
            style="-webkit-overflow-scrolling: touch;"
          >
            {#each similar as item (item.id)}
              <button
                use:pressable
                class="shrink-0 overflow-hidden rounded-md"
                style="width: 96px;"
                type="button"
                onclick={(e) => {
                  e.stopPropagation();
                  onsimilar?.(item);
                }}
                aria-label={item.media_type === "tv" ? item.name : item.title}
              >
                <img
                  src={item.poster_path}
                  alt={item.media_type === "tv" ? item.name : item.title}
                  loading="lazy"
                  decoding="async"
                  class="aspect-2/3 w-full rounded-md object-cover"
                />
              </button>
            {/each}
          </div>
        </div>
      {/if}
    </div>
  </div>
</div>
