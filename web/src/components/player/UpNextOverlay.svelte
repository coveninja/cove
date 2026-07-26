<script lang="ts">
  import type { TVEpisode } from "$lib/types/tmdb";
  import { Button } from "$lib/components/ui/button";
  import { X, SkipForward } from "lucide-svelte";
  import { fade } from "svelte/transition";
  import * as m from "$lib/paraglide/messages.js";

  let {
    nextEp,
    countdownSecs,
    hideSpoilers,
    onDismiss,
    onWatchNow,
  }: {
    nextEp: { season: number; episode: TVEpisode };
    countdownSecs: number | null;
    hideSpoilers: boolean;
    onDismiss: () => void;
    onWatchNow: () => void;
  } = $props();
</script>

<div
  class="absolute bottom-20 right-6 z-20 w-72 overflow-hidden rounded-lg border border-white/20 bg-black/80 text-white shadow-2xl backdrop-blur-sm p-4"
  transition:fade={{ duration: 150 }}
>
  <div class="flex items-start justify-between gap-2">
    <p class="text-xs font-medium uppercase tracking-wide text-white/60">
      Up next · S{nextEp.season}E{nextEp.episode.episode_number}
    </p>
    <Button
      variant="outline"
      size="icon-sm"
      class="shrink-0 p-0.5"
      onclick={(e) => {
        e.stopPropagation();
        onDismiss();
      }}
      aria-label={m.player_dismiss()}
    >
      <X class="size-4" />
    </Button>
  </div>
  {#if !hideSpoilers && nextEp.episode.name}
    <p class="truncate px-4 pb-3 text-sm text-white/90">{nextEp.episode.name}</p>
  {:else}
    <div class="pb-3"></div>
  {/if}
  <Button
    variant="outline"
    class="flex w-full items-center justify-center hover:text-accent"
    onclick={(e) => {
      e.stopPropagation();
      onWatchNow();
    }}
  >
    <SkipForward class="size-4" />
    Watch now
  </Button>
  {#if countdownSecs !== null}
    <div class="px-4 py-2">
      <p class="mb-1.5 text-xs text-white/60">Playing in {countdownSecs}s</p>
      <div class="h-1 w-full overflow-hidden rounded-full bg-white/20">
        <div
          class="h-full bg-white transition-[width] duration-1000 ease-linear"
          style="width: {((10 - countdownSecs) / 10) * 100}%"
        ></div>
      </div>
    </div>
  {/if}
</div>
