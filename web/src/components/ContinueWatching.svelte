<script lang="ts">
  import {
    ContinueWatchingController,
    type ContinueItem,
  } from "$lib/continueWatching.svelte";
  import { libraryChanged } from "$lib/stores/library";
  import type { Media } from "$lib/types/tmdb";
  import { Button } from "$lib/components/ui/button/index.js";
  import { Skeleton } from "$lib/components/ui/skeleton/index.js";
  import { animate } from "animejs";
  import { ChevronLeft, ChevronRight } from "lucide-svelte";
  import ContinueWatchingCard from "./cards/ContinueWatchingCard.svelte";
  import * as m from "$lib/paraglide/messages.js";

  let {
    onWatch,
    onSelectMedia,
    navEnabled = false,
  }: {
    onWatch?: (media: Media, season?: number, episode?: number) => void;
    onSelectMedia: (media: Media) => void;
    navEnabled: boolean;
  } = $props();

  const controller = new ContinueWatchingController();
  let trackEl = $state<HTMLElement | null>(null);
  let activeAnimation: ReturnType<typeof animate> | null = null;

  $effect(() => {
    $libraryChanged;
    controller.load();
  });

  function resume(item: ContinueItem): void {
    if (onWatch) {
      onWatch(item.media, item.season ?? undefined, item.episode ?? undefined);
    } else {
      onSelectMedia(item.media);
    }
  }

  function scrollByCards(direction: 1 | -1): void {
    if (!trackEl) return;
    activeAnimation?.pause();
    activeAnimation = animate(trackEl, {
      scrollLeft: trackEl.scrollLeft + direction * (trackEl.clientWidth * 0.9),
      duration: 400,
      ease: "inOutQuad",
    });
  }
</script>

{#if controller.loading || controller.items.length > 0}
  <div class="w-full space-y-3 px-4">
    <div class="ml-12 flex items-center justify-between px-1">
      <h2 class="text-lg font-semibold">{m.home_continue_watching()}</h2>
    </div>

    <div class="flex items-center justify-between gap-2 overflow-hidden">
      {#if navEnabled}
        <Button
          onclick={() => scrollByCards(-1)}
          variant="outline"
          size="icon"
          aria-label={m.common_scroll_left()}
        >
          <ChevronLeft class="size-4" />
        </Button>
      {/if}

      <div
        bind:this={trackEl}
        class="flex min-w-0 flex-1 gap-4 overflow-x-auto px-1 pb-1 [&::-webkit-scrollbar]:hidden"
      >
        {#if controller.loading}
          {#each { length: 5 } as _, i (i)}
            <Skeleton class="aspect-video w-70 shrink-0 rounded-md" />
          {/each}
        {:else}
          {#each controller.items as item (item.key)}
            <ContinueWatchingCard {item} onResume={resume} />
          {/each}
        {/if}
      </div>

      {#if navEnabled}
        <Button
          onclick={() => scrollByCards(1)}
          variant="outline"
          size="icon"
          aria-label={m.common_scroll_right()}
        >
          <ChevronRight class="size-4" />
        </Button>
      {/if}
    </div>
  </div>
{/if}
