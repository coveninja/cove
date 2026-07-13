<script lang="ts">
  import type { Media, MediaImages } from "$lib/types/tmdb";
  import { getImageOpt } from "$lib/utils";
  import { api } from "$lib/api";
  import { getContext, onMount } from "svelte";
  import { Spinner } from "$lib/components/ui/spinner";
  import { libraryChanged } from "$lib/stores/library";
  import type { LibraryEntry } from "$lib/types/library";

  let {
    media,
    onclick,
  }: {
    media: Media;
    onclick?: (m: Media) => void;
  } = $props();

  // Opens the shared, app-level detail overlay.
  const openDetail = getContext<((m: Media) => void) | undefined>("openMediaDetail");
  function openOverlay(): void {
    if (openDetail) openDetail(media);
    else onclick?.(media);
  }

  // ── DOM ref ───────────────────────────────────────────────────────────────
  let buttonEl = $state<HTMLElement | null>(null);

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
        console.error("MobileMediaCard: failed to load library entry", err);
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
    if (!buttonEl || typeof IntersectionObserver === "undefined") {
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
    io.observe(buttonEl);
    return () => io.disconnect();
  });
</script>

<div
  bind:this={buttonEl}
  onclick={openOverlay}
  class="relative cursor-pointer"
  role="button"
  tabindex="0"
  onkeydown={(e) => e.key === "Enter" && openOverlay()}
>
  <div class="relative">
    {#if logoLoaded && images && images.posters.length > 0}
      <img
        src={getImageOpt(images, "posters", {
          iso: "en",
          voteAverage: 5,
          randomize: true,
        })}
        alt={title}
        loading="lazy"
        decoding="async"
        class="block aspect-2/3 w-full rounded-md object-cover transition-all duration-300 {isWatched
          ? 'opacity-35'
          : 'opacity-100'} {isDropped ? 'opacity-10 grayscale' : ''}"
      />
    {:else if logoLoaded && media.poster_path}
      <img
        src={media.poster_path}
        alt={title}
        loading="lazy"
        decoding="async"
        class="block aspect-2/3 w-full rounded-md object-cover transition-all duration-300 {isWatched
          ? 'opacity-35'
          : 'opacity-100'} {isDropped ? 'opacity-10 grayscale' : ''}"
      />
    {:else}
      <div
        class="flex aspect-2/3 w-full items-center justify-center rounded-md bg-muted"
      >
        <Spinner class="size-8" />
      </div>
    {/if}
  </div>
</div>
