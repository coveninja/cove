<script lang="ts">
  import { focusable } from "../../focus/actions";
  import * as m from "$lib/paraglide/messages.js";
  import { segmentBgClass, type ChapterBar } from "$lib/player/chapters";

  let {
    chapterBars,
    isHash,
    torrentProgress,
    displayPos,
    duration,
    onkeydown,
  }: {
    chapterBars: ChapterBar[] | null;
    isHash: boolean;
    torrentProgress: number;
    displayPos: number;
    duration: number;
    onkeydown: (e: KeyboardEvent) => void;
  } = $props();

  function pillFill(chapter: ChapterBar, frac: number): number {
    if (frac >= chapter.endFrac) return 100;
    if (frac <= chapter.startFrac) return 0;
    return ((frac - chapter.startFrac) / (chapter.endFrac - chapter.startFrac)) * 100;
  }
</script>

<div
  role="slider"
  aria-label={m.player_seek_seconds()}
  aria-valuemin={0}
  aria-valuemax={duration || 0}
  aria-valuenow={displayPos}
  tabindex={0}
  class="relative mb-3 flex h-5 w-full cursor-pointer items-center"
  {onkeydown}
  use:focusable={{ groupId: "tv-player-controls" }}
>
  {#if chapterBars}
    <!-- Segmented: each chapter is its own rounded pill with a gap -->
    <div class="absolute inset-x-0 top-1/2 flex h-1.5 -translate-y-1/2 gap-0.5">
      {#each chapterBars as chapter}
        <div
          class="relative h-full overflow-hidden rounded-full {chapter.type !== 'content' ? segmentBgClass(chapter.type) : 'bg-white/25'}"
          style="flex: {chapter.endFrac - chapter.startFrac}"
        >
          <!-- Torrent buffer fill -->
          {#if isHash && torrentProgress > 0 && torrentProgress < 100}
            <div
              class="pointer-events-none absolute inset-y-0 left-0 bg-white/35"
              style="width: {pillFill(chapter, torrentProgress / 100)}%"
            ></div>
          {/if}
          <!-- Playback progress fill -->
          <div
            class="pointer-events-none absolute inset-y-0 left-0 bg-white"
            style="width: {pillFill(chapter, duration ? displayPos / duration : 0)}%"
          ></div>
        </div>
      {/each}
    </div>
  {:else}
    <!-- Unified bar (no timestamp data) -->
    <div class="absolute inset-x-0 top-1/2 h-1.5 -translate-y-1/2 overflow-hidden rounded-full bg-white/25">
      {#if isHash && torrentProgress > 0 && torrentProgress < 100}
        <div
          class="pointer-events-none absolute inset-y-0 left-0 bg-white/35"
          style="width: {torrentProgress}%"
        ></div>
      {/if}
      <div
        class="pointer-events-none absolute inset-y-0 left-0 bg-white"
        style="width: {duration ? (displayPos / duration) * 100 : 0}%"
      ></div>
    </div>
  {/if}
  <!-- Thumb -->
  <div
    class="pointer-events-none absolute top-1/2 size-5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-white shadow-md ring-1 ring-black/20"
    style="left: {duration ? (displayPos / duration) * 100 : 0}%"
  ></div>
</div>
