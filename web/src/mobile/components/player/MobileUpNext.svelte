<script lang="ts">
  import type { TVEpisode } from "$lib/types/tmdb";
  import { SkipForward, X } from "lucide-svelte";
  import { fade } from "svelte/transition";
  import * as m from "$lib/paraglide/messages.js";

  let {
    nextEp,
    countdownSecs,
    hideSpoilers,
    onDismiss,
    onAdvance,
  }: {
    nextEp: { season: number; episode: TVEpisode };
    countdownSecs: number | null;
    hideSpoilers: boolean;
    onDismiss: () => void;
    onAdvance: () => void;
  } = $props();
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
  class="absolute right-4 z-20 w-64 overflow-hidden rounded-xl border border-white/20 bg-black/85 text-white shadow-2xl backdrop-blur-sm"
  style="bottom: calc(max(1rem, var(--safe-bottom)) + 8rem);"
  transition:fade={{ duration: 150 }}
  onclick={(e) => e.stopPropagation()}
  onkeydown={() => {}}
>
  <div class="p-4">
    <div class="flex items-start justify-between gap-2">
      <p class="text-xs font-medium uppercase tracking-wide text-white/60">
        Up next · S{nextEp.season}E{nextEp.episode.episode_number}
      </p>
      <button
        type="button"
        class="flex size-6 shrink-0 items-center justify-center rounded-full text-white/60 active:bg-white/20"
        onclick={() => onDismiss()}
        aria-label={m.player_dismiss()}
      >
        <X class="size-4" />
      </button>
    </div>
    {#if !hideSpoilers && nextEp.episode.name}
      <p class="mt-1 truncate text-sm text-white/90">{nextEp.episode.name}</p>
    {/if}
    <button
      type="button"
      class="mt-3 flex w-full items-center justify-center gap-2 rounded-lg border border-white/30 bg-white/10 py-2.5 text-sm font-medium text-white active:bg-white/20"
      onclick={() => onAdvance()}
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
