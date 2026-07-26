<script lang="ts">
  import { Player } from "$lib/player/player.svelte";
  import { ASPECT_LABELS } from "$lib/player/aspectRatio";
  import type { ChapterBar } from "$lib/player/chapters";
  import type { TorrentProgress } from "$lib/player/torrentProgress.svelte.js";
  import {
    Play,
    Pause,
    Volume2,
    VolumeX,
    Headphones,
    Captions,
    Gauge,
    ListVideo,
    Ratio,
    SkipForward,
    SkipBack,
    X,
  } from "lucide-svelte";
  import { scale } from "svelte/transition";
  import { cubicOut } from "svelte/easing";
  import MobileSeekBar from "./MobileSeekBar.svelte";
  import * as m from "$lib/paraglide/messages.js";

  let {
    title,
    episodeLabel,
    activeSegment,
    chapterBars,
    isHash,
    torrent,
    audioLabel,
    subLabel,
    showAudio,
    showSubs,
    hasNextEp,
    audioSheetOpen = $bindable(false),
    subsSheetOpen = $bindable(false),
    speedSheetOpen = $bindable(false),
    episodesSheetOpen = $bindable(false),
    controlsActive,
    onclose,
    onSkipSegment,
    onToggleMute,
    onCycleAspect,
    onNudgeBack,
    onNudgeForward,
    onScrub,
    onShowControls,
  }: {
    title: string;
    episodeLabel: string;
    activeSegment: { label: string } | null;
    chapterBars: ChapterBar[] | null;
    isHash: boolean;
    torrent: TorrentProgress;
    audioLabel: string;
    subLabel: string;
    showAudio: boolean;
    showSubs: boolean;
    hasNextEp: boolean;
    audioSheetOpen?: boolean;
    subsSheetOpen?: boolean;
    speedSheetOpen?: boolean;
    episodesSheetOpen?: boolean;
    controlsActive: boolean;
    onclose?: () => void;
    onSkipSegment: () => void;
    onToggleMute: () => void;
    onCycleAspect: () => void;
    onNudgeBack: () => void;
    onNudgeForward: () => void;
    onScrub: (pos: number | null) => void;
    onShowControls: () => void;
  } = $props();

  // Track the live scrub position so the time row stays in sync with the thumb.
  let scrubPos = $state<number | null>(null);
  const displayPos = $derived(scrubPos ?? Player.position);

  function handleScrub(pos: number | null): void {
    scrubPos = pos;
    onScrub(pos);
  }

  function fmt(t: number): string {
    if (!isFinite(t) || t < 0) t = 0;
    const h = Math.floor(t / 3600);
    const m = Math.floor((t % 3600) / 60);
    const s = Math.floor(t % 60);
    const mm = h ? String(m).padStart(2, "0") : String(m);
    return `${h ? h + ":" : ""}${mm}:${String(s).padStart(2, "0")}`;
  }
</script>

<!--
  Single wrapper: opacity fades the whole overlay; pointer-events-none when
  hidden so touches fall through to the transparent mpv layer.
  Tailwind dynamic class interpolation is NOT used — only class: directives.
-->
<div
  class="absolute inset-0 z-10 flex flex-col transition-opacity duration-200"
  class:opacity-0={!controlsActive}
  class:pointer-events-none={!controlsActive}
>
  <!-- TOP gradient scrim: safe-area-inset-top aware -->
  <div
    class="flex shrink-0 items-start justify-between bg-gradient-to-b from-black/75 to-transparent px-4 pb-10"
    style="padding-top: max(1rem, var(--safe-top));"
    onclick={(e) => e.stopPropagation()}
    onkeydown={() => {}}
    role="toolbar"
    tabindex={-1}
    aria-label={m.player_top_controls()}
  >
    <!-- Close button (44px touch target) -->
    <button
      type="button"
      class="flex size-11 items-center justify-center rounded-full text-white active:bg-white/20"
      onclick={() => onclose?.()}
      aria-label={m.player_close()}
    >
      <X class="size-6" />
    </button>

    <!-- Title + episode label -->
    <div class="flex min-w-0 flex-1 flex-col items-center px-3 pt-1">
      <p
        class="max-w-full truncate text-center text-sm font-semibold text-white drop-shadow"
      >
        {title}
      </p>
      {#if episodeLabel}
        <p class="text-xs text-white/60">{episodeLabel}</p>
      {/if}
    </div>

    <!-- Spacer to balance close button width -->
    <div class="size-11 shrink-0"></div>
  </div>

  <!-- CENTER controls: seek-back, play/pause, seek-forward -->
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div
    class="flex flex-1 items-center justify-center gap-8"
    onclick={(e) => e.stopPropagation()}
    onkeydown={() => {}}
  >
    <!-- Seek -10s (48px target) -->
    <button
      type="button"
      class="flex size-12 items-center justify-center rounded-full text-white active:bg-white/20"
      onclick={() => { onNudgeBack(); }}
      aria-label={m.player_seek_back()}
    >
      <SkipBack class="size-6" />
    </button>

    <!-- Play / Pause (64px) -->
    <button
      type="button"
      class="flex size-16 items-center justify-center rounded-full bg-white/20 text-white backdrop-blur-sm active:bg-white/35"
      onclick={() => { Player.togglePause(); onShowControls(); }}
      aria-label={Player.paused ? m.player_play() : m.player_pause()}
    >
      {#key Player.paused}
        <span
          class="inline-flex items-center justify-center"
          in:scale={{ duration: 120, start: 0.6, easing: cubicOut }}
        >
          {#if Player.paused}
            <Play class="size-8 translate-x-0.5" />
          {:else}
            <Pause class="size-8" />
          {/if}
        </span>
      {/key}
    </button>

    <!-- Seek +10s (48px target) -->
    <button
      type="button"
      class="flex size-12 items-center justify-center rounded-full text-white active:bg-white/20"
      onclick={() => { onNudgeForward(); }}
      aria-label={m.player_seek_forward()}
    >
      <SkipForward class="size-6" />
    </button>
  </div>

  <!-- BOTTOM gradient scrim: safe-area-inset-bottom aware -->
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div
    class="shrink-0 bg-gradient-to-t from-black/85 via-black/30 to-transparent px-4 pt-8"
    style="padding-bottom: max(1rem, var(--safe-bottom));"
    onclick={(e) => e.stopPropagation()}
    onkeydown={() => {}}
  >
    <!-- Skip intro/recap pill -->
    {#if activeSegment}
      <div class="mb-3 flex justify-end">
        <button
          type="button"
          class="rounded-full border border-white/60 bg-black/60 px-4 py-2 text-sm font-semibold text-white backdrop-blur-sm active:bg-white/20"
          onclick={() => onSkipSegment()}
        >
          {m.player_skip_segment({ segment: activeSegment.label })}
        </button>
      </div>
    {/if}

    <!-- Seek bar -->
    <MobileSeekBar
      {chapterBars}
      {isHash}
      {torrent}
      onScrub={handleScrub}
    />

    <!-- Time row -->
    <div
      class="mb-2 mt-1 flex items-center justify-between text-xs tabular-nums text-white/80"
    >
      <span>{fmt(displayPos)}</span>
      <span class="text-white/40">{fmt(Player.duration)}</span>
    </div>

    <!-- Bottom button row -->
    <div class="flex items-center gap-1">
      <!-- Audio tracks (only if multiple tracks available) -->
      {#if showAudio}
        <button
          type="button"
          class="flex min-h-[44px] items-center gap-1.5 rounded-lg px-3 py-2 text-white active:bg-white/15"
          onclick={() => { audioSheetOpen = true; onShowControls(); }}
          aria-label={m.player_audio_tracks()}
        >
          <Headphones class="size-4 shrink-0" />
          <span class="max-w-20 truncate text-xs">
            {audioLabel}
          </span>
        </button>
      {/if}

      <!-- Subtitles -->
      {#if showSubs}
        <button
          type="button"
          class="flex min-h-[44px] items-center gap-1.5 rounded-lg px-3 py-2 text-white active:bg-white/15"
          onclick={() => { subsSheetOpen = true; onShowControls(); }}
          aria-label={m.player_subtitles()}
        >
          <Captions class="size-4 shrink-0" />
          <span class="max-w-20 truncate text-xs">
            {subLabel}
          </span>
        </button>
      {/if}

      <div class="flex-1"></div>

      <!-- Aspect ratio cycle -->
      <button
        type="button"
        class="flex min-h-11 items-center gap-1.5 rounded-lg px-3 py-2 text-white active:bg-white/15"
        onclick={() => { onCycleAspect(); onShowControls(); }}
        aria-label={m.player_aspect_ratio()}
      >
        <Ratio class="size-5 shrink-0" />
        <span class="text-sm">{ASPECT_LABELS[Player.aspectMode]}</span>
      </button>

      <!-- Playback speed -->
      <button
        type="button"
        class="flex min-h-11 items-center gap-1.5 rounded-lg px-3 py-2 text-white active:bg-white/15"
        onclick={() => { speedSheetOpen = true; onShowControls(); }}
        aria-label={m.player_speed()}
      >
        <Gauge class="size-5 shrink-0" />
        <span class="text-sm"
          >{Player.playbackSpeed === 1 ? "1×" : `${Player.playbackSpeed}×`}</span
        >
      </button>

      <!-- Mute toggle (hardware volume keys handle level on Android) -->
      <button
        type="button"
        class="flex size-11 items-center justify-center rounded-full text-white active:bg-white/15"
        onclick={onToggleMute}
        aria-label={Player.volume === 0 ? m.player_unmute() : m.player_mute()}
      >
        {#if Player.volume === 0}
          <VolumeX class="size-5" />
        {:else}
          <Volume2 class="size-5" />
        {/if}
      </button>

      <!-- Episodes (TV shows only) -->
      {#if hasNextEp}
        <button
          type="button"
          class="flex min-h-11 items-center gap-1.5 rounded-lg px-3 py-2 text-white active:bg-white/15"
          onclick={() => { episodesSheetOpen = true; onShowControls(); }}
          aria-label={m.player_episodes()}
        >
          <ListVideo class="size-5 shrink-0" />
        </button>
      {/if}
    </div>
  </div>
</div>
