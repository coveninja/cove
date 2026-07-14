<script lang="ts">
  import type { Details, Media, MediaImages } from "$lib/types/tmdb";
  import { api } from "$lib/api";
  import { formatRating, formatRuntime, getImageOpt } from "$lib/utils";
  import { animate } from "animejs";
  import { getContext, onDestroy } from "svelte";
  import { fly } from "svelte/transition";
  import { libraryChanged } from "$lib/stores/library";
  import type { LibraryEntry } from "$lib/types/library";
  import { Play, Info } from "lucide-svelte";
  import { Player } from "$lib/player/player.svelte";
  import { focusGroup, focusable } from "../focus/actions";

  // visible prop mirrors MobileHero so TvHomePage can use the same binding.
  let { visible = true } = $props<{ visible?: boolean }>();

  // Suppress auto-advance while mpv is playing — same gate as MobileHero.
  const mpvBusy = $derived(Player.available && Player.duration > 0);

  // Also pause while the user is navigating buttons inside the hero so the
  // selection doesn't change out from under them.
  let heroFocused = $state(false);

  const suppressed = $derived(mpvBusy || heroFocused);

  let mediaIndex = $state<number>(0);
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

  // ── Auto-advance timer (~8 s; suppressed while mpv busy or hero focused) ───
  const DURATION = 8000;
  let progress = $state(0);
  let currentAnimation: ReturnType<typeof animate> | null = null;

  function next(): void {
    if (medias.length === 0) return;
    mediaIndex = (mediaIndex + 1) % medias.length;
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
    const busy = suppressed;
    const vis = visible;
    currentAnimation?.pause();
    currentAnimation = null;
    progress = 0;
    if (medias.length > 0 && !busy && vis) startTimer();
  });

  onDestroy(() => currentAnimation?.pause());

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

  // ── Focus tracking: pause timer while user navigates the hero buttons ──────
  function onHeroFocusIn(): void {
    heroFocused = true;
  }

  function onHeroFocusOut(e: FocusEvent): void {
    const ct = e.currentTarget as HTMLElement;
    if (!ct.contains(e.relatedTarget as Node | null)) {
      heroFocused = false;
    }
  }
</script>

<!--
  Full-bleed landscape hero for 10-foot viewing. Static backdrop only — no
  trailer video (same as MobileHero).  Buttons live in a focusGroup row so
  D-pad Left/Right moves between Watch, Details, and Dismiss.
-->
<!-- data-tv-scroll-anchor: focusing the Watch/Details buttons (bottom edge)
     scrolls the WHOLE hero into view, not just the button row. -->
<div
  class="relative rounded-2xl w-full overflow-hidden h-[clamp(420px,42vh,500px)]"
  data-tv-scroll-anchor
  onfocusin={onHeroFocusIn}
  onfocusout={onHeroFocusOut}
>
  <!-- Backdrop images: all pre-loaded, active one shown via opacity -->
  {#each backdropUrls as url, i (i)}
    {#if url}
      <img
        class="absolute inset-0 h-full w-full object-cover transition-opacity duration-700"
        class:opacity-0={i !== mediaIndex}
        class:opacity-100={i === mediaIndex}
        src={url}
        alt="backdrop"
      />
    {/if}
  {/each}

  <!-- Gradient overlays -->
  <div
    class="absolute inset-0 bg-linear-to-t from-background from-0% via-background/40 via-40% to-transparent to-80%"
  ></div>
  <div
    class="absolute inset-0 bg-linear-to-r from-black/70 from-0% to-transparent to-55%"
  ></div>

  <!-- Content: bottom-aligned, left-padded — larger targets for 10-foot -->
  <div class="absolute inset-x-0 bottom-0 flex flex-col gap-4 px-8 pb-6">
    <!-- Logo or title -->
    <div class="relative h-20 w-full">
      {#each logoUrls as url, i (i)}
        {#if url}
          <img
            class="absolute bottom-0 left-0 max-h-full max-w-[50%] object-contain object-left transition-opacity duration-500"
            class:opacity-0={i !== mediaIndex}
            class:opacity-100={i === mediaIndex}
            src={url}
            alt="logo"
          />
        {/if}
      {/each}
      {#if !logoUrls[mediaIndex] && currentTitle}
        <p class="text-3xl font-bold leading-tight text-white drop-shadow-md">
          {currentTitle}
        </p>
      {/if}
    </div>

    <!-- Meta chips -->
    {#key mediaIndex}
      <div
        class="flex flex-wrap gap-2 text-sm font-medium text-white/70"
        in:fly={{ x: -10, duration: 300, delay: 100 }}
      >
        {#if tmdbRatings[mediaIndex]}
          <span class="rounded border border-white/20 px-2 py-1 backdrop-blur-sm">
            {tmdbRatings[mediaIndex]}
          </span>
        {/if}
        {#each (genres[mediaIndex] ?? []).slice(0, 3) as genre (genre)}
          <span class="rounded border border-white/20 px-2 py-1 backdrop-blur-sm">
            {genre}
          </span>
        {/each}
        {#if runtimes[mediaIndex]}
          <span class="rounded border border-white/20 px-2 py-1 backdrop-blur-sm">
            {runtimes[mediaIndex]}
          </span>
        {/if}
      </div>
    {/key}

    <!-- Action row in a focusGroup so D-pad Left/Right moves between buttons. -->
    <div
      use:focusGroup={{ id: "hero-actions", policy: { type: "row" } }}
      class="flex items-center gap-3"
    >
      <button
        type="button"
        use:focusable={{ groupId: "hero-actions" }}
        class="flex h-12 min-w-36 items-center justify-center gap-2 rounded-xl bg-white px-6 text-base font-semibold text-black"
        onclick={watchCurrent}
      >
        <Play class="size-5 fill-current" />
        {watchLabel}
      </button>
      <button
        type="button"
        use:focusable={{ groupId: "hero-actions" }}
        class="flex h-12 min-w-36 items-center justify-center gap-2 rounded-xl bg-white/20 px-6 text-base font-semibold text-white backdrop-blur-sm"
        onclick={openCurrentDetail}
      >
        <Info class="size-5" />
        Details
      </button>
    </div>

    <!-- Progress bar indicators: one per featured item -->
    {#if medias.length > 1}
      <div class="flex gap-1.5">
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
