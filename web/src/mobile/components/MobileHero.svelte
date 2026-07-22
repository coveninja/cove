<script lang="ts">
  import type { Details, Media, MediaImages } from "$lib/types/tmdb";
  import { api } from "$lib/api";
  import {
    formatRating,
    formatRuntime,
    getImageOpt,
  } from "$lib/utils";
  import { animate } from "animejs";
  import { getContext, onDestroy } from "svelte";
  import { fly } from "svelte/transition";
  import { libraryChanged } from "$lib/stores/library";
  import type { LibraryEntry } from "$lib/types/library";
  import { Play, Info, ThumbsDown } from "lucide-svelte";
  import { Player } from "$lib/player/player.svelte";

  // Props mirror the signature used by LargeRecommendationsCard so MobileHomePage
  // can slot this in with the same surrounding bindings.
  let { visible = true } = $props<{ visible?: boolean }>();

  // Suppress auto-advance while mpv is playing — same gate as the desktop hero.
  const mpvBusy = $derived(Player.available && Player.duration > 0);

  let mediaIndex = $state<number>(0);
  let slideDir = $state(1);
  let medias = $state<Media[]>([]);

  const watchMedia = getContext<
    ((m: Media, season?: number, episode?: number) => void) | undefined
  >("watchMedia");
  const openDetail = getContext<((m: Media) => void) | undefined>("openMediaDetail");

  // ── Per-item data ──────────────────────────────────────────────────────────
  let backdropUrls = $state<string[]>([]);
  let logoUrls = $state<string[]>([]);
  let genres = $state<string[][]>([]);
  let runtimes = $state<string[]>([]);
  let tmdbRatings = $state<string[]>([]);
  let libraryEntries = $state<(LibraryEntry | null)[]>([]);

  let backdropEls = $state<(HTMLImageElement | undefined)[]>([]);
  let logoEls = $state<(HTMLImageElement | undefined)[]>([]);

  $effect(() => {
    api.discover("all", { limit: 10 }).then((d) => (medias = d));
  });

  $effect(() => {
    backdropUrls = new Array(medias.length).fill("");
    logoUrls = new Array(medias.length).fill("");
    genres = new Array(medias.length).fill([]);
    runtimes = new Array(medias.length).fill("");
    tmdbRatings = new Array(medias.length).fill("");
    libraryEntries = new Array(medias.length).fill(null);

    for (let i = 0; i < medias.length; i++) {
      api.getImages(medias[i]).then((d: MediaImages) => {
        backdropUrls[i] = getImageOpt(d, "backdrops", { iso: "", randomize: true });
        logoUrls[i] = getImageOpt(d, "logos", { iso: "en" });
      });
      api.getDetails(medias[i]).then((d: Details) => {
        genres[i] = d.genres.map((g) => g.name);
        runtimes[i] = formatRuntime(d);
        tmdbRatings[i] = formatRating(d);
      });
      api
        .libraryGet(medias[i].id, medias[i].media_type)
        .then((d) => {
          libraryEntries[i] = d?.entry ?? null;
        })
        .catch(() => {
          libraryEntries[i] = null;
        });
    }
  });

  // ── Library state (re-sync on library changes) ─────────────────────────────
  $effect(() => {
    $libraryChanged;
    if (medias.length === 0) return;
    for (let i = 0; i < medias.length; i++) {
      const idx = i;
      api
        .libraryGet(medias[idx].id, medias[idx].media_type)
        .then((d) => {
          libraryEntries[idx] = d?.entry ?? null;
        })
        .catch(() => {});
    }
  });

  // ── Auto-advance timer (~8 s on mobile; never driven by video clip) ────────
  const DURATION = 8000;
  let progress = $state(0);
  let currentAnimation: ReturnType<typeof animate> | null = null;

  function next(): void {
    if (medias.length === 0) return;
    slideDir = 1;
    mediaIndex = (mediaIndex + 1) % medias.length;
  }

  function prev(): void {
    if (medias.length === 0) return;
    slideDir = -1;
    mediaIndex = (mediaIndex - 1 + medias.length) % medias.length;
  }

  function startTimer(): void {
    currentAnimation?.pause();
    const obj = { value: 0 };
    progress = 0;
    currentAnimation = animate(obj, {
      value: 100,
      duration: DURATION,
      ease: "linear",
      onUpdate: () => (progress = obj.value),
      onComplete: next,
    });
  }

  $effect(() => {
    const _idx = mediaIndex;
    const busy = mpvBusy;
    const vis = visible;
    currentAnimation?.pause();
    currentAnimation = null;
    progress = 0;
    if (medias.length > 0 && !busy && vis) startTimer();
  });

  onDestroy(() => currentAnimation?.pause());

  // ── Swipe navigation ──────────────────────────────────────────────────────
  let touchStartX = 0;
  let touchStartY = 0;

  function onTouchStart(e: TouchEvent): void {
    touchStartX = e.touches[0].clientX;
    touchStartY = e.touches[0].clientY;
    // Hold auto-advance while the finger is down.
    currentAnimation?.pause();
  }

  function onTouchEnd(e: TouchEvent): void {
    const dx = e.changedTouches[0].clientX - touchStartX;
    const dy = e.changedTouches[0].clientY - touchStartY;
    // Horizontal swipe: dominant x-axis movement past a 50px threshold.
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5) {
      if (dx < 0) next();
      else prev();
      // The mediaIndex $effect restarts the timer.
    } else {
      currentAnimation?.play();
    }
  }

  // ── Entrance animation: slide + settle on the incoming item ────────────────
  let backdropAnim: ReturnType<typeof animate> | null = null;
  let logoAnim: ReturnType<typeof animate> | null = null;

  $effect(() => {
    const dir = slideDir;
    const backdropEl = backdropEls[mediaIndex];
    const logoEl = logoEls[mediaIndex];
    // Kill any in-flight entrance so fast swipes don't fight over transform.
    backdropAnim?.pause();
    logoAnim?.pause();
    // scale ≥ 1.08 keeps the 12px slide from exposing the backdrop's edges.
    if (backdropEl) {
      backdropAnim = animate(backdropEl, {
        translateX: [12 * dir, 0],
        scale: [1.08, 1],
        duration: 700,
        ease: "outCubic",
      });
    }
    if (logoEl) {
      logoAnim = animate(logoEl, {
        translateX: [24 * dir, 0],
        duration: 500,
        ease: "outCubic",
      });
    }
  });

  // ── Derived ───────────────────────────────────────────────────────────────
  const currentMedia = $derived(medias[mediaIndex]);
  const currentTitle = $derived(
    currentMedia
      ? currentMedia.media_type === "tv"
        ? currentMedia.name
        : currentMedia.title
      : "",
  );

  const watchLabel = $derived.by(() => {
    const entry = libraryEntries[mediaIndex];
    if (
      currentMedia?.media_type === "tv" &&
      entry?.last_watched_season != null &&
      entry?.last_watched_episode != null
    ) {
      return `Continue S${entry.last_watched_season}E${entry.last_watched_episode}`;
    }
    return "Watch";
  });

  // ── Actions ───────────────────────────────────────────────────────────────
  function watchCurrent(): void {
    if (!currentMedia) return;
    const entry = libraryEntries[mediaIndex];
    currentAnimation?.pause();
    watchMedia?.(
      currentMedia,
      entry?.last_watched_season ?? undefined,
      entry?.last_watched_episode ?? undefined,
    );
  }

  function openCurrentDetail(): void {
    if (!currentMedia) return;
    currentAnimation?.pause();
    openDetail?.(currentMedia);
  }

  function dismissCurrent(): void {
    if (!currentMedia) return;
    api
      .notInterested(currentMedia)
      .then(() => libraryChanged.update((n) => n + 1))
      .catch(() => {});
    medias = medias.filter(
      (m) => !(m.id === currentMedia.id && m.media_type === currentMedia.media_type),
    );
    if (mediaIndex >= medias.length) mediaIndex = 0;
  }
</script>

<!--
  Full-bleed portrait hero: backdrop fills width at ~56vw height, gradient fades
  into background at the bottom so content rows flow directly below.
-->
<div
  class="relative w-full overflow-hidden"
  style="height: clamp(420px, 56vw, 480px); touch-action: pan-y;"
  role="region"
  aria-label="Featured"
  ontouchstart={onTouchStart}
  ontouchend={onTouchEnd}
>
  <!-- Backdrop images: all pre-loaded, current one shown via opacity -->
  {#each backdropUrls as url, i (i)}
    {#if url}
      <img
        class="absolute inset-0 h-full w-full object-cover transition-opacity duration-700"
        class:opacity-0={i !== mediaIndex}
        class:opacity-100={i === mediaIndex}
        src={url}
        alt="backdrop"
        style="will-change: transform;"
        bind:this={backdropEls[i]}
      />
    {/if}
  {/each}

  <!-- Gradient overlays: darken bottom and left for legibility -->
  <div
    class="absolute inset-0 bg-linear-to-t from-background from-0% via-background/40 via-40% to-transparent to-80%"
  ></div>
  <div
    class="absolute inset-0 bg-linear-to-r from-black/60 from-0% to-transparent to-60%"
  ></div>

  <!-- Content: bottom-aligned, left-padded -->
  <div
    class="absolute inset-x-0 bottom-0 flex flex-col gap-3 px-4 pb-4"
    style="padding-top: var(--safe-top);"
  >
    <!-- Logo or title -->
    <div class="relative h-16 w-full">
      {#each logoUrls as url, i (i)}
        {#if url}
          <img
            class="absolute bottom-0 left-0 max-h-full max-w-[60%] object-contain object-left transition-opacity duration-500"
            class:opacity-0={i !== mediaIndex}
            class:opacity-100={i === mediaIndex}
            src={url}
            alt="logo"
            bind:this={logoEls[i]}
          />
        {/if}
      {/each}
      {#if !logoUrls[mediaIndex] && currentTitle}
        <p class="text-2xl font-bold leading-tight text-white drop-shadow-md">
          {currentTitle}
        </p>
      {/if}
    </div>

    <!-- Meta chips -->
    {#key mediaIndex}
      <div
        class="flex flex-wrap gap-1.5 text-xs font-medium text-white/70"
        in:fly={{ x: 24 * slideDir, duration: 300, delay: 100 }}
      >
        {#if tmdbRatings[mediaIndex]}
          <span class="rounded border border-white/20 px-1.5 py-0.5 backdrop-blur-sm">
            {tmdbRatings[mediaIndex]}
          </span>
        {/if}
        {#each (genres[mediaIndex] ?? []).slice(0, 3) as genre (genre)}
          <span class="rounded border border-white/20 px-1.5 py-0.5 backdrop-blur-sm">
            {genre}
          </span>
        {/each}
        {#if runtimes[mediaIndex]}
          <span class="rounded border border-white/20 px-1.5 py-0.5 backdrop-blur-sm">
            {runtimes[mediaIndex]}
          </span>
        {/if}
      </div>
    {/key}

    <!-- Action row: Watch + Details + Dismiss — all ≥44px targets -->
    <div class="flex items-center gap-2">
      <button
        type="button"
        class="flex h-11 flex-1 items-center justify-center gap-2 rounded-lg bg-white text-sm font-semibold text-black active:scale-95 active:brightness-90 transition-[transform,filter] duration-75"
        onclick={watchCurrent}
      >
        <Play class="size-4 fill-current" />
        {watchLabel}
      </button>
      <button
        type="button"
        class="flex h-11 flex-1 items-center justify-center gap-2 rounded-lg bg-white/20 text-sm font-semibold text-white backdrop-blur-sm active:scale-95 active:brightness-90 transition-[transform,filter] duration-75"
        onclick={openCurrentDetail}
      >
        <Info class="size-4" />
        Details
      </button>
      <button
        type="button"
        class="flex size-11 shrink-0 items-center justify-center rounded-lg bg-white/15 text-white backdrop-blur-sm active:scale-95 active:brightness-90 transition-[transform,filter] duration-75"
        onclick={dismissCurrent}
        aria-label="Not interested"
      >
        <ThumbsDown class="size-4" />
      </button>
    </div>

    <!-- Progress bar indicators: one per featured item -->
    {#if medias.length > 1}
      <div class="flex gap-1">
        {#each medias as _, i (i)}
          <div class="h-0.5 flex-1 overflow-hidden rounded-full bg-white/25">
            <div
              class="h-full bg-white"
              style="width: {i < mediaIndex ? 100 : i === mediaIndex ? progress : 0}%"
            ></div>
          </div>
        {/each}
      </div>
    {/if}
  </div>
</div>
