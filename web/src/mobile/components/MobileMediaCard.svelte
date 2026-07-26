<script lang="ts">
  import type { Media, MediaImages } from "$lib/types/tmdb";
  import { getImageOpt } from "$lib/utils";
  import { api } from "$lib/api";
  import { getContext, onMount } from "svelte";
  import { Spinner } from "$lib/components/ui/spinner";
  import { CircleCheckBig, HeartOff } from "lucide-svelte";
  import { libraryChanged } from "$lib/stores/library";
  import type { LibraryEntry } from "$lib/types/library";
  import { pressable } from "../lib/pressable";
  import { imageFade } from "../lib/imageFade";
  import { longpress } from "../lib/longpress";
  import MobileMediaActionsSheet from "./MobileMediaActionsSheet.svelte";
  import { activeLocale } from "$lib/i18n";

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
  let dismissed = $state(false);
  let hasProgress = $state(false);
  let actionsOpen = $state(false);

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
        dismissed = result?.dismissed ?? false;
        hasProgress = (result?.progress.length ?? 0) > 0;
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
  use:pressable
  use:longpress={{
    onLongPress: () => {
      actionsOpen = true;
    },
  }}
  onclick={openOverlay}
  oncontextmenu={(event) => event.preventDefault()}
  class="relative cursor-pointer"
  role="button"
  tabindex="0"
  onkeydown={(e) => e.key === "Enter" && openOverlay()}
>
  <div class="relative">
    {#if logoLoaded && images && images.posters.length > 0}
      <img
        use:imageFade
        src={getImageOpt(images, "posters", {
          iso: posterLocale,
          isoFallbacks: posterLocaleFallbacks,
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
        use:imageFade
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
    {#if isWatched}
      <div
        class="absolute inset-0 flex items-center justify-center rounded-md"
        style="background: linear-gradient(to top, rgba(0,0,0,0.7) 0%, rgba(0,0,0,0.3) 60%, transparent 100%)"
      >
        <CircleCheckBig class="size-10 text-white/80" />
      </div>
    {/if}
    {#if isDropped}
      <div
        class="absolute inset-0 flex items-center justify-center rounded-md"
        style="background: linear-gradient(to top, rgba(0,0,0,0.7) 0%, rgba(0,0,0,0.3) 60%, transparent 100%)"
      >
        <HeartOff class="size-10 text-red-600/80" />
      </div>
    {/if}
  </div>
</div>

<MobileMediaActionsSheet
  {media}
  {libraryEntry}
  {dismissed}
  {hasProgress}
  bind:open={actionsOpen}
  showTrigger={false}
/>
