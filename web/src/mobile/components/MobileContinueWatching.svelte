<script lang="ts">
  import {
    ContinueWatchingController,
    continuePercent,
    continueSubtitle,
    type ContinueItem,
  } from "$lib/continueWatching.svelte";
  import * as m from "$lib/paraglide/messages.js";
  import { libraryChanged } from "$lib/stores/library";
  import type { Media } from "$lib/types/tmdb";
  import { Film, Tv } from "lucide-svelte";
  import { imageFade } from "../lib/imageFade";
  import { pressable } from "../lib/pressable";

  let {
    onWatch,
    onSelectMedia,
  }: {
    onWatch?: (media: Media, season?: number, episode?: number) => void;
    onSelectMedia: (media: Media) => void;
  } = $props();

  const controller = new ContinueWatchingController();

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
</script>

{#if controller.loading || controller.items.length > 0}
  <div class="w-full space-y-2 px-4">
    <div class="px-1">
      <h2 class="text-base font-semibold">{m.home_continue_watching()}</h2>
    </div>

    <div
      class="flex gap-3 overflow-x-auto pb-1 [scrollbar-width:none] [-webkit-overflow-scrolling:touch] [&::-webkit-scrollbar]:hidden"
    >
      {#if controller.loading}
        {#each { length: 4 } as _, i (i)}
          <div
            class="aspect-video w-56 shrink-0 animate-shimmer rounded-md"
          ></div>
        {/each}
      {:else}
        {#each controller.items as item (item.key)}
          <button
            use:pressable
            onclick={() => resume(item)}
            class="relative w-56 shrink-0 overflow-hidden rounded-md text-left"
            aria-label={item.upNext
              ? m.common_play_title({ title: item.title })
              : m.common_resume_title({ title: item.title })}
          >
            {#if item.image}
              <img
                use:imageFade
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

            <span
              class="absolute inset-x-0 bottom-0 block px-2 pt-24 pb-2.5"
              style="background: linear-gradient(to top, rgba(0,0,0,0.85) 0%, transparent 100%)"
            >
              <span
                class="block truncate text-sm leading-tight font-semibold text-white"
              >
                {item.title}
              </span>
              <span class="block truncate text-xs text-white/70">
                {continueSubtitle(item)}
              </span>
            </span>

            {#if !item.upNext && continuePercent(item) > 0}
              <span class="absolute inset-x-0 bottom-0 block h-1 bg-white/25">
                <span
                  class="block h-full bg-accent"
                  style="width: {continuePercent(item)}%"
                ></span>
              </span>
            {/if}
          </button>
        {/each}
      {/if}
    </div>
  </div>
{/if}
