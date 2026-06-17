<script lang="ts">
  import type { Details, Media, MediaImages } from "$lib/types/tmdb";
  import { animate } from "animejs";
  import {
    getImageOpt,
    formatRating,
    formatRuntime,
    qualityClass,
    getVideoOpt,
  } from "$lib/utils";
  import { api } from "$lib/api";
  import { onMount } from "svelte";
  import { Spinner } from "$lib/components/ui/spinner";
  import MediaHoverCard from "./MediaHoverCard.svelte";
  import MediaExpandedModal from "./MediaExpandedModal.svelte";

  import type { LibraryEntry } from "$lib/types/library";
  import { libraryChanged } from "$lib/stores/library";
  import { CircleCheckBig, HeartOff } from "lucide-svelte";

  let {
    media,
    onclick,
    quality = null,
    newEpisodes = false,
    initialExpanded = false,
    onclose,
    onsimilar,
  }: {
    media: Media;
    onclick: (m: Media) => void;
    quality?: string | null;
    newEpisodes?: boolean;
    initialExpanded?: boolean;
    onclose?: () => void;
    onsimilar?: (m: Media) => void;
  } = $props();

  // ── DOM refs ──────────────────────────────────────────────────────────────
  let posterEl = $state<HTMLElement | null>(null);
  let buttonEl = $state<HTMLElement | null>(null);
  let hoverCardInstance = $state<MediaHoverCard | null>(null);

  // ── UI state ──────────────────────────────────────────────────────────────
  let hovered = $state(false);
  let expanded = $state(false);
  let hoverCardStyle = $state("");
  let hoverTimeout: ReturnType<typeof setTimeout>;

  // ── Data ──────────────────────────────────────────────────────────────────
  let fetched = false;
  let similar = $state<Media[]>([]);
  let images = $state<MediaImages>();
  let logoLoaded = $state(false);
  let genres = $state<string[]>([]);
  let runtime = $state<string>("");
  let cast = $state<string[]>([]);
  let ageRating = $state<string>("");
  let keywords = $state<string[]>([]);
  let originCountry = $state<string[]>([]);
  let numberOfSeasons = $state<number | null>(null);
  let numberOfEpisodes = $state<number | null>(null);
  let lastAiredSeason = $state<number | null>(null);
  let lastAiredEpisode = $state<number | null>(null);
  let videoUrl = $state<string>();
  let libraryEntry = $state<LibraryEntry | null>(null);
  const isWatched = $derived(libraryEntry?.status === "finished");
  const isDropped = $derived(libraryEntry?.status === "dropped");
  // Same reasoning as MediaPage: captured once from Details and left alone,
  // so a later change to the live `media` prop (e.g. after a library status
  // update elsewhere) can't make the overview text silently disappear.
  let detailsOverview = $state<string | null>(null);

  // ── Derived ───────────────────────────────────────────────────────────────
  const title = $derived(media.media_type === "tv" ? media.name : media.title);

  const overviewParagraphs = $derived(
    (detailsOverview ?? media.overview)
      .split(". ")
      .map((s, i, arr) => (i < arr.length - 1 ? s + "." : s))
      .filter((s) => s.trim().length > 0),
  );

  // ── Data fetching ─────────────────────────────────────────────────────────
  function fetchData(): void {
    if (fetched) return;
    fetched = true;

    api.getVideos(media).then((d) => {
      videoUrl = getVideoOpt(d, "Clip", { randomize: true });
    });
    api.getSimilar(media).then((d) => (similar = d));
    api
      .getDetails(media)
      .then((d: Details) => {
        detailsOverview = d.overview ?? null;

        console.log("[overview-debug] MediaCard getDetails resolved", {
          tmdbId: media.id,
          mediaType: media.media_type,
          hasOverview: !!d.overview,
          overviewPreview: d.overview?.slice(0, 60),
          libraryEntryAtFetchTime: libraryEntry,
        });

        genres =
          d.genres?.map((g: { name: string }) => g.name).slice(0, 3) ?? [];
        runtime = formatRuntime(d);
        cast =
          d.credits?.cast?.slice(0, 5).map((c: { name: string }) => c.name) ??
          [];
        ageRating = formatRating(d);
        keywords =
          (media.media_type === "movie"
              ? d.keywords?.keywords
              : d.keywords?.results
          )
            ?.slice(0, 4)
            .map((k: { name: string }) => k.name) ?? [];
        originCountry = d.origin_country;
        if (media.media_type === "tv") {
          numberOfSeasons = d.number_of_seasons ?? null;
          numberOfEpisodes = d.number_of_episodes ?? null;
          lastAiredSeason = d.last_episode_to_air?.season_number ?? null;
          lastAiredEpisode = d.last_episode_to_air?.episode_number ?? null;
        }
      })
      .catch((err) => {
        // Previously this call had NO catch handler at all — a rejection
        // here meant detailsOverview/genres/cast/etc. silently stayed at
        // their initial empty values forever for this card, with no error
        // surfaced anywhere. If this fires, that's very likely the bug.
        console.error("[overview-debug] MediaCard getDetails FAILED", {
          tmdbId: media.id,
          mediaType: media.media_type,
          libraryEntryAtFetchTime: libraryEntry,
          error: err,
        });
      });
  }

  // ── Hover card positioning ────────────────────────────────────────────────
  function computeHoverStyle(): void {
    if (!posterEl) return;
    const rect = posterEl.getBoundingClientRect();
    const cardWidth = rect.width * 2.2;
    const vw = window.innerWidth;
    const centerX = rect.left + rect.width / 2;
    const centerY = rect.top + rect.height / 2;

    let left: number;
    let translateX: string;

    if (centerX - cardWidth / 2 < 8) {
      left = 8;
      translateX = "0%";
    } else if (centerX + cardWidth / 2 > vw - 8) {
      left = vw - 8;
      translateX = "-100%";
    } else {
      left = centerX;
      translateX = "-50%";
    }

    hoverCardStyle = `
      position: fixed;
      top: ${centerY}px;
      left: ${left}px;
      translate: ${translateX} -50%;
      width: ${cardWidth}px;
      pointer-events: auto;
    `;
  }

  // ── Hover handlers ────────────────────────────────────────────────────────
  let popoverOpen = $state(false);

  // True whenever the pointer is anywhere inside the card, the hover card, or
  // a popover opened from either of them. Mouse events keep this current;
  // closing itself is decided by closeIfIdle() rather than directly inside
  // the mouse handlers, since popover open/close also needs to trigger it —
  // and a popover closing doesn't fire any mouse event of its own.
  let withinZone = $state(false);

  function isWithinZone(target: Node | null): boolean {
    if (!target) return false;
    if (buttonEl?.contains(target)) return true;
    const hoverEl = hoverCardInstance?.getEl();
    if (hoverEl?.contains(target)) return true;
    // Popovers are portalled to <body>, so they're never a DOM descendant of
    // either element above — check separately.
    const popover = document.querySelector(
      "[data-radix-popper-content-wrapper]",
    );
    return !!popover?.contains(target);
  }

  function closeIfIdle(): void {
    if (expanded || popoverOpen || withinZone) return;
    if (hoverCardInstance) {
      hoverCardInstance.animateClose(() => {
        hovered = false;
      });
    } else {
      hovered = false;
    }
  }

  function onHover(): void {
    withinZone = true;
    if (expanded) return;
    hoverTimeout = setTimeout(() => {
      computeHoverStyle();
      hovered = true;
      fetchData();
    }, 400);
  }

  function onLeave(e?: MouseEvent): void {
    clearTimeout(hoverTimeout);
    withinZone = isWithinZone((e?.relatedTarget ?? null) as Node | null);
    closeIfIdle();
  }

  // Re-evaluate whenever a popover closes. This is the missing piece: picking
  // an option in a popover doesn't move the mouse, so no mouseleave fires —
  // without this, the hover card would only close on the next unrelated
  // hover/unhover cycle (or, as just observed, never at all once the popover
  // starts closing itself immediately on selection).
  $effect(() => {
    if (!popoverOpen) closeIfIdle();
  });

  // ── Expand / close ────────────────────────────────────────────────────────
  function expand(): void {
    expanded = true;
    fetchData();
  }

  function closeExpanded(): void {
    expanded = false;
    hovered = false;
    onclose?.();
  }

  // ── Initial expanded (e.g. deep-linked) ──────────────────────────────────
  $effect(() => {
    if (initialExpanded) {
      expanded = true;
      hovered = true;
      fetchData();
    }
  });

  $effect(() => {
    $libraryChanged;
    api.libraryGet(media.id, media.media_type).then((result) => {
      libraryEntry = result?.entry ?? null;
    });
  });

  // ── Load animation ────────────────────────────────────────────────────────
  onMount(() => {
    api.getImages(media).then((d) => {
      images = d;
      logoLoaded = true;
      if (buttonEl) {
        animate(buttonEl, {
          scale: [0.3, 1.05, 1],
          opacity: [0, 1],
          duration: 500,
          easing: "easeOutExpo",
          onComplete: () => {
            // Clear the inline transform so this element no longer acts as a
            // containing block for position:fixed children (hover card).
            if (buttonEl) buttonEl.style.transform = "";
          },
        });
      }
    });
    api.libraryGet(media.id, media.media_type).then((result) => {
      libraryEntry = result?.entry ?? null;
    });
  });
</script>

<div
  bind:this={buttonEl}
  onclick={() => !expanded && onclick(media)}
  onmouseenter={onHover}
  onmouseleave={onLeave}
  class={initialExpanded
    ? "contents"
    : `relative ${!expanded ? "cursor-pointer" : ""} ${hovered || expanded ? "z-50" : "z-0"}`}
  role="button"
  tabindex="0"
  onkeydown={(e) => e.key === "Enter" && !expanded && onclick(media)}
>
  {#if !initialExpanded}
    <div bind:this={posterEl} class="relative">
      {#if logoLoaded && images.posters.length > 0}
        <img
          src={getImageOpt(images, "posters", {
            iso: "en",
            voteAverage: 5,
            randomize: true,
          })}
          alt={title}
          class="block aspect-2/3 w-full rounded-md object-cover transition-all duration-300 {isWatched
            ? 'opacity-35'
            : 'opacity-100'} {isDropped ? 'opacity-10 grayscale' : ''}"
        />
      {:else if logoLoaded && media.poster_path}
        <img
          src={media.poster_path}
          alt={title}
          class="block aspect-2/3 w-full rounded-md object-cover transition-all duration-300 {isWatched
            ? 'opacity-35'
            : 'opacity-100'} {isDropped ? 'opacity-10 grayscale' : ''}"
        />
      {:else}
        <div
          class="flex aspect-2/3 w-full items-center justify-center rounded-md"
        >
          <Spinner class="size-10" />
        </div>
      {/if}
      {#if quality}
        <span
          class="absolute bottom-1.5 left-1.5 rounded border px-1.5 py-0.5 text-xs font-medium {qualityClass(
            quality,
          )}"
        >
          {quality.toUpperCase()}
        </span>
      {/if}
      {#if newEpisodes}
        <div
          class="absolute inset-x-0 bottom-0 rounded-b-md"
          style="background: linear-gradient(to top, rgba(0,0,0,0.85) 0%, rgba(0,0,0,0.5) 55%, transparent 100%)"
        >
          <p
            class="px-2 pt-6 pb-2 text-[11px] font-semibold tracking-wide text-white"
          >
            New Episodes
          </p>
        </div>
      {/if}
      {#if isWatched}
        <div
          class="absolute inset-0 flex items-center justify-center rounded-md"
          style="background: linear-gradient(to top, rgba(0,0,0,0.7) 0%, rgba(0,0,0,0.3) 60%, transparent 100%)"
        >
          <CircleCheckBig class="size-12 text-white/80" />
        </div>
      {/if}
      {#if isDropped}
        <div
          class="absolute inset-0 flex items-center justify-center rounded-md"
          style="background: linear-gradient(to top, rgba(0,0,0,0.7) 0%, rgba(0,0,0,0.3) 60%, transparent 100%)"
        >
          <HeartOff class="size-12 text-red-600/80" />
        </div>
      {/if}
      {#if media.media_type === "tv" && numberOfSeasons !== null}
        <span
          class="absolute top-1.5 right-1.5 rounded bg-black/70 px-1.5 py-0.5 text-[10px] font-medium text-white"
        >
          {numberOfSeasons}S
        </span>
      {/if}
    </div>
  {/if}
</div>

{#if hovered && !expanded}
  <MediaHoverCard
    bind:this={hoverCardInstance}
    {media}
    style={hoverCardStyle}
    {videoUrl}
    {genres}
    {runtime}
    {ageRating}
    {originCountry}
    {numberOfSeasons}
    {numberOfEpisodes}
    {lastAiredSeason}
    {lastAiredEpisode}
    {quality}
    onmouseleave={onLeave}
    onwatch={() => onclick(media)}
    onexpand={expand}
    onpopoverchange={(open) => (popoverOpen = open)}
  />
{/if}

{#if expanded}
  <MediaExpandedModal
    {media}
    {videoUrl}
    {overviewParagraphs}
    {genres}
    {runtime}
    {ageRating}
    {originCountry}
    {numberOfSeasons}
    {numberOfEpisodes}
    {lastAiredSeason}
    {lastAiredEpisode}
    {cast}
    {keywords}
    {similar}
    {quality}
    onwatch={() => onclick(media)}
    onclose={closeExpanded}
    onsimilar={(m) => {
      closeExpanded();
      onsimilar?.(m);
    }}
  />
{/if}
