<script lang="ts">
  import type { Media, MediaImages } from "$lib/types/tmdb";
  import { getImageOpt } from "$lib/utils";
  import { api } from "$lib/api";
  import { getContext, onMount } from "svelte";
  import { Spinner } from "$lib/components/ui/spinner";
  import { libraryChanged } from "$lib/stores/library";
  import type { LibraryEntry } from "$lib/types/library";
  import { focusable } from "../focus/actions";
  import { activeLocale } from "$lib/i18n";

  let {
    media,
    groupId,
    onclick,
  }: {
    media: Media;
    groupId: string;
    onclick?: (m: Media) => void;
  } = $props();

  // Opens the shared, app-level detail overlay — same fallback-to-onclick pattern
  // as MobileMediaCard.
  const openDetail = getContext<((m: Media) => void) | undefined>("openMediaDetail");

  function openOverlay(): void {
    if (openDetail) openDetail(media);
    else onclick?.(media);
  }

  // ── DOM ref ───────────────────────────────────────────────────────────────
  let cardEl = $state<HTMLElement | null>(null);

  // ── Lazy loading ──────────────────────────────────────────────────────────
  // Only fetch art and library state once the card scrolls near the viewport.
  let visible = $state(false);

  // ── Data ──────────────────────────────────────────────────────────────────
  let images = $state<MediaImages | undefined>();
  let logoLoaded = $state(false);
  let libraryEntry = $state<LibraryEntry | null>(null);

  const isWatched = $derived(libraryEntry?.status === "finished");
  const isDropped = $derived(libraryEntry?.status === "dropped");
  const title = $derived(media.media_type === "tv" ? media.name : media.title);
  const posterLocale = activeLocale();
  const posterLocaleFallbacks = posterLocale === "en" ? [null] : ["en", null];

  // ── Library state ─────────────────────────────────────────────────────────
  $effect(() => {
    $libraryChanged;
    if (!visible) return;
    api
      .libraryGet(media.id, media.media_type)
      .then((result) => {
        libraryEntry = result?.entry ?? null;
      })
      .catch((err) => {
        console.error("TvMediaCard: failed to load library entry", err);
      });
  });

  // ── Lazy art loading ──────────────────────────────────────────────────────
  let imagesRequested = false;
  $effect(() => {
    if (!visible || imagesRequested) return;
    imagesRequested = true;
    api
      .getImages(media)
      .then((d) => {
        images = d;
      })
      .catch(() => {
        images = { backdrops: [], logos: [], posters: [] };
      })
      .finally(() => {
        logoLoaded = true;
      });
  });

  // Flip `visible` when the card scrolls within 300px of the viewport.
  onMount(() => {
    if (!cardEl || typeof IntersectionObserver === "undefined") {
      visible = true;
      return;
    }
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          visible = true;
          io.disconnect();
        }
      },
      { rootMargin: "300px" },
    );
    io.observe(cardEl);
    return () => io.disconnect();
  });
</script>

<!--
  10-foot poster card. use:focusable registers with the D-pad engine and sets
  tabIndex=-1 + data-tv-focusable (needed for the tv-shell focus ring).
  Scale + brightness on :focus are scoped here; the outline ring comes from the
  global tv-shell CSS so we do NOT override outline.
-->
<div
  bind:this={cardEl}
  use:focusable={{ groupId }}
  onclick={openOverlay}
  onkeydown={(e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      openOverlay();
    }
  }}
  class="relative rounded-lg cursor-pointer transition-[transform,filter,scale] duration-150 ease-[ease] focus:scale-[1.08] focus:brightness-[1.15]"
  role="button"
  tabindex="-1"
>
  <div class="relative">
    {#if logoLoaded && images && images.posters.length > 0}
      <img
        src={getImageOpt(images, "posters", {
          iso: posterLocale,
          isoFallbacks: posterLocaleFallbacks,
          randomize: true,
        })}
        alt={title}
        loading="lazy"
        decoding="async"
        class="block aspect-2/3 w-full rounded-lg object-cover transition-all duration-300 {isWatched
          ? 'opacity-35'
          : 'opacity-100'} {isDropped ? 'opacity-10 grayscale' : ''}"
      />
    {:else if logoLoaded && media.poster_path}
      <img
        src={media.poster_path}
        alt={title}
        loading="lazy"
        decoding="async"
        class="block aspect-2/3 w-full rounded-lg object-cover transition-all duration-300 {isWatched
          ? 'opacity-35'
          : 'opacity-100'} {isDropped ? 'opacity-10 grayscale' : ''}"
      />
    {:else}
      <div
        class="flex aspect-2/3 w-full items-center justify-center rounded-lg bg-muted"
      >
        <Spinner class="size-10" />
      </div>
    {/if}
  </div>
</div>
