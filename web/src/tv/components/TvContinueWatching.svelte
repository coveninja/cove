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
  import { Film, Play, Tv } from "lucide-svelte";
  import { focusGroup, focusable } from "../focus/actions";

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
  <div class="w-full space-y-3">
    <div class="flex items-center">
      <h2 class="text-xl font-semibold">{m.home_continue_watching()}</h2>
    </div>

    <div
      use:focusGroup={{
        id: "row-continue-watching",
        policy: { type: "row" },
        rememberFocus: true,
      }}
      class="tv-row flex gap-4 overflow-x-hidden p-4"
    >
      {#if controller.loading}
        {#each { length: 5 } as _, i (i)}
          <div class="w-70 shrink-0">
            <div
              class="aspect-video w-full animate-pulse rounded-md bg-muted"
            ></div>
          </div>
        {/each}
      {:else}
        {#each controller.items as item (item.key)}
          <div
            use:focusable={{ groupId: "row-continue-watching" }}
            onclick={() => resume(item)}
            onkeydown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                resume(item);
              }
            }}
            role="button"
            tabindex="-1"
            class="relative w-70 shrink-0 cursor-pointer overflow-hidden rounded-md transition-[transform,filter,scale] duration-150 ease-[ease] focus:scale-[1.08] focus:brightness-[1.15]"
            aria-label={item.upNext
              ? m.common_play_title({ title: item.title })
              : m.common_resume_title({ title: item.title })}
          >
            {#if item.image}
              <img
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

            <span
              class="pointer-events-none absolute inset-0 flex items-center justify-center opacity-0 transition-opacity duration-150 [[data-tv-focusable]:focus_&]:opacity-100"
            >
              <span
                class="flex size-12 items-center justify-center rounded-full bg-white/90"
              >
                <Play class="size-6 translate-x-0.5 fill-current text-black" />
              </span>
            </span>
          </div>
        {/each}
      {/if}
    </div>
  </div>
{/if}
