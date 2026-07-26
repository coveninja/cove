<script lang="ts">
  import { Player } from "$lib/player/player.svelte";
  import { fade } from "svelte/transition";
  import { segmentBgClass, type ChapterBar } from "$lib/player/chapters";
  import * as m from "$lib/paraglide/messages.js";

  let {
    chapterBars,
    onScrub,
  }: {
    chapterBars: ChapterBar[] | null;
    /** Live drag position while scrubbing, null once released — lets the parent
     *  show the dragged time in its clock without duplicating scrub state. */
    onScrub: (preview: number | null) => void;
  } = $props();

  // Scrubbing: while dragging, show the dragged time and only issue the real
  // seek on release, so we don't spam mpv (costly on torrent sources).
  let scrubbing = $state(false);
  let scrubValue = $state(0);
  let seekTrackEl = $state<HTMLDivElement | null>(null);
  let hoveredChapter = $state<ChapterBar | null>(null);

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

  // Fraction (0–100) of a chapter pill that should be filled white by the progress bar.
  function chapterFill(chapter: ChapterBar): number {
    if (!Player.duration) return 0;
    const posFrac = displayPos / Player.duration;
    if (posFrac >= chapter.endFrac) return 100;
    if (posFrac <= chapter.startFrac) return 0;
    return ((posFrac - chapter.startFrac) / (chapter.endFrac - chapter.startFrac)) * 100;
  }
</script>

<!-- Seek bar (full width, custom — no third-party slider) -->
<div
  role="slider"
  aria-label={m.player_seek_seconds()}
  aria-valuemin={0}
  aria-valuemax={Player.duration || 0}
  aria-valuenow={displayPos}
  tabindex={0}
  class="relative flex h-2 w-full cursor-pointer items-center"
  bind:this={seekTrackEl}
  onpointerdown={onSeekPointerDown}
  onpointermove={onSeekPointerMove}
  onpointerup={onSeekPointerUp}
  onpointercancel={onSeekPointerUp}
>
  {#if chapterBars}
    <!-- Segmented: each chapter is its own rounded pill with a gap -->
    <div class="flex h-full w-full gap-0.5">
      {#each chapterBars as chapter}
        <!-- svelte-ignore a11y_no_static_element_interactions -->
        <div
          class="relative h-full overflow-hidden rounded-full {chapter.type !== 'content'
            ? segmentBgClass(chapter.type)
            : 'bg-white/20'}"
          style="flex: {chapter.endFrac - chapter.startFrac}"
          onmouseenter={() => chapter.type !== 'content' && (hoveredChapter = chapter)}
          onmouseleave={() => (hoveredChapter = null)}
        >
          <div
            class="pointer-events-none absolute inset-y-0 left-0 bg-white"
            style="width: {chapterFill(chapter)}%"
          ></div>
        </div>
      {/each}
    </div>
    <!-- Chapter label tooltip, centered over the hovered pill -->
    {#if hoveredChapter}
      <div
        class="pointer-events-none absolute -top-6 -translate-x-1/2 rounded bg-black/80 px-2 py-0.5 text-xs font-medium capitalize text-white"
        style="left: {((hoveredChapter.startFrac + hoveredChapter.endFrac) / 2) * 100}%"
        transition:fade={{ duration: 100 }}
      >
        {hoveredChapter.type}
      </div>
    {/if}
  {:else}
    <!-- Unified bar (no timestamp data) -->
    <div class="absolute inset-0 overflow-hidden rounded-full bg-white/20">
      <div
        class="pointer-events-none absolute inset-y-0 left-0 bg-white"
        style="width: {Player.duration ? (displayPos / Player.duration) * 100 : 0}%"
      ></div>
    </div>
  {/if}
  <!-- Scrubber thumb (not inside any overflow-hidden clip) -->
  <div
    class="pointer-events-none absolute top-1/2 h-4 w-4 -translate-x-1/2 -translate-y-1/2 rounded-full bg-white shadow-md ring-1 ring-black/10"
    style="left: {Player.duration ? (displayPos / Player.duration) * 100 : 0}%"
  ></div>
</div>
