<script lang="ts">
  import type { TVEpisode } from "$lib/types/tmdb";
  import { X, SkipForward } from "lucide-svelte";
  import { fade } from "svelte/transition";
  import { focusable } from "../../focus/actions";

  let {
    nextEp,
    countdownSecs,
    hideSpoilers = false,
    onDismiss,
    onWatchNow,
    watchNowBtnEl = $bindable<HTMLButtonElement | null>(null),
  }: {
    nextEp: { season: number; episode: TVEpisode };
    countdownSecs: number | null;
    hideSpoilers?: boolean;
    onDismiss: () => void;
    onWatchNow: () => void;
    watchNowBtnEl?: HTMLButtonElement | null;
  } = $props();
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
  class="absolute right-8 bottom-40 z-20 w-80 overflow-hidden rounded-2xl border border-white/20 bg-black/90 text-white shadow-2xl backdrop-blur-sm"
  transition:fade={{ duration: 150 }}
  onclick={(e) => e.stopPropagation()}
  onkeydown={() => {}}
>
  <div class="p-5">
    <div class="flex items-start justify-between gap-2">
      <p class="text-xs font-medium uppercase tracking-wide text-white/60">
        Up next · S{nextEp.season}E{nextEp.episode.episode_number}
      </p>
      <button
        type="button"
        class="flex size-6 shrink-0 items-center justify-center rounded-full text-white/60 hover:bg-white/20 focus:bg-white/20"
        onclick={onDismiss}
        aria-label="Dismiss"
        use:focusable={{ groupId: "tv-player-controls" }}
      >
        <X class="size-4" />
      </button>
    </div>
    {#if !hideSpoilers && nextEp.episode.name}
      <p class="mt-1 truncate text-sm text-white/90">{nextEp.episode.name}</p>
    {/if}
    <button
      bind:this={watchNowBtnEl}
      type="button"
      class="mt-4 flex w-full items-center justify-center gap-2 rounded-xl border border-white/30 bg-white/10 py-3 text-sm font-medium text-white hover:bg-white/20 focus:bg-white/20"
      onclick={onWatchNow}
      use:focusable={{ groupId: "tv-player-controls" }}
    >
      <SkipForward class="size-4" />
      Watch now
    </button>
    {#if countdownSecs !== null}
      <div class="mt-3">
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
</div>
