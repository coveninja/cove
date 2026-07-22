<script lang="ts">
  import { Player } from "$lib/player/player.svelte";
  import { segmentBgClass, type ChapterBar } from "$lib/player/chapters";
  import type { TorrentProgress } from "$lib/player/torrentProgress.svelte.js";
  import { fade } from "svelte/transition";

  let {
    chapterBars,
    isHash,
    torrent,
    onScrub,
  }: {
    chapterBars: ChapterBar[] | null;
    isHash: boolean;
    torrent: TorrentProgress;
    onScrub: (pos: number | null) => void;
  } = $props();

  let seekTrackEl = $state<HTMLDivElement | null>(null);
  let scrubbing = $state(false);
  let scrubValue = $state(0);
  const displayPos = $derived(scrubbing ? scrubValue : Player.position);

  function seekFraction(e: PointerEvent): number {
    if (!seekTrackEl || !Player.duration) return 0;
    const { left, width } = seekTrackEl.getBoundingClientRect();
    return Math.max(0, Math.min(1, (e.clientX - left) / width));
  }

  function onSeekPointerDown(e: PointerEvent): void {
    if (!Player.duration) return;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    scrubbing = true;
    scrubValue = seekFraction(e) * Player.duration;
    onScrub(scrubValue);
  }

  function onSeekPointerMove(e: PointerEvent): void {
    if (!scrubbing) return;
    scrubValue = seekFraction(e) * Player.duration;
    onScrub(scrubValue);
  }

  function onSeekPointerUp(e: PointerEvent): void {
    if (!scrubbing) return;
    Player.seek(seekFraction(e) * Player.duration);
    scrubbing = false;
    onScrub(null);
  }

  function pillFill(chapter: ChapterBar, frac: number): number {
    if (frac >= chapter.endFrac) return 100;
    if (frac <= chapter.startFrac) return 0;
    return ((frac - chapter.startFrac) / (chapter.endFrac - chapter.startFrac)) * 100;
  }

  const scrubChapter = $derived.by((): ChapterBar | null => {
    if (!scrubbing || !chapterBars || !Player.duration) return null;
    const frac = displayPos / Player.duration;
    return (
      chapterBars.find(
        (c) => c.type !== "content" && frac >= c.startFrac && frac < c.endFrac,
      ) ?? null
    );
  });
</script>

<!-- Seek bar: 24px hit box, 24px thumb -->
<div
  role="slider"
  aria-label="Seek"
  aria-valuemin={0}
  aria-valuemax={Player.duration || 0}
  aria-valuenow={displayPos}
  tabindex={0}
  class="relative flex h-6 w-full cursor-pointer touch-none items-center"
  bind:this={seekTrackEl}
  onpointerdown={onSeekPointerDown}
  onpointermove={onSeekPointerMove}
  onpointerup={onSeekPointerUp}
  onpointercancel={onSeekPointerUp}
  onkeydown={() => {}}
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
          {#if isHash && torrent.progress > 0 && torrent.progress < 100}
            <div
              class="pointer-events-none absolute inset-y-0 left-0 bg-white/35"
              style="width: {pillFill(chapter, torrent.progress / 100)}%"
            ></div>
          {/if}
          <!-- Playback progress fill -->
          <div
            class="pointer-events-none absolute inset-y-0 left-0 bg-white"
            style="width: {pillFill(chapter, Player.duration ? displayPos / Player.duration : 0)}%"
          ></div>
        </div>
      {/each}
    </div>
    <!-- Chapter label tooltip while scrubbing over a non-content segment -->
    {#if scrubChapter}
      <div
        class="pointer-events-none absolute -top-7 -translate-x-1/2 rounded bg-black/80 px-2 py-0.5 text-xs font-medium capitalize text-white"
        style="left: {((scrubChapter.startFrac + scrubChapter.endFrac) / 2) * 100}%"
        transition:fade={{ duration: 100 }}
      >
        {scrubChapter.type}
      </div>
    {/if}
  {:else}
    <!-- Unified bar (no timestamp data) -->
    <div
      class="absolute inset-x-0 top-1/2 h-1.5 -translate-y-1/2 overflow-hidden rounded-full bg-white/25"
    >
      <!-- Torrent buffer fill -->
      {#if isHash && torrent.progress > 0 && torrent.progress < 100}
        <div
          class="pointer-events-none absolute inset-y-0 left-0 bg-white/35"
          style="width: {torrent.progress}%"
        ></div>
      {/if}
      <!-- Playback progress fill -->
      <div
        class="pointer-events-none absolute inset-y-0 left-0 bg-white"
        style="width: {Player.duration ? (displayPos / Player.duration) * 100 : 0}%"
      ></div>
    </div>
  {/if}
  <!-- Thumb (24px) -->
  <div
    class="pointer-events-none absolute top-1/2 size-6 -translate-x-1/2 -translate-y-1/2 rounded-full bg-white shadow-md ring-1 ring-black/20 transition-transform duration-150"
    class:scale-150={scrubbing}
    class:ring-2={scrubbing}
    class:ring-accent={scrubbing}
    style="left: {Player.duration ? (displayPos / Player.duration) * 100 : 0}%"
  ></div>
</div>
