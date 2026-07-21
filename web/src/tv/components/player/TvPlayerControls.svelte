<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import type { TimestampSegment } from "$lib/types/addons";
  import {
    Play,
    Pause,
    Headphones,
    Captions,
    X,
    SkipForward,
    SkipBack,
    Gauge,
    ListVideo,
    Ratio,
  } from "lucide-svelte";
  import { Player } from "$lib/player/player.svelte";
  import { ASPECT_LABELS } from "$lib/player/aspectRatio";
  import { focusable, focusGroup } from "../../focus/actions";
  import TvSeekBar from "./TvSeekBar.svelte";
  import type { ChapterBar } from "./TvSeekBar.svelte";

  // ── Types ─────────────────────────────────────────────────────────────────────

  type SubSel = { kind: "off" } | { kind: "embedded"; id: number } | { kind: "external"; id: string };
  type SubItem = { kind: string; id: string | number; label: string };

  // ── Props ─────────────────────────────────────────────────────────────────────

  let {
    // Display
    title,
    episodeLabel,
    controlsActive,

    // Skip-segment pill
    activeSegment = null,
    skipBtnEl = $bindable<HTMLButtonElement | null>(null),
    onSkipSegment,

    // Seek bar
    chapterBars,
    isHash,
    torrentProgress,
    displayPos,
    onSeekbarKeydown,

    // Transport
    onSeekBack,
    playPauseBtn = $bindable<HTMLButtonElement | null>(null),
    onPlayPause,
    onSeekForward,

    // Panel toggles (kept as $bindable — state lives in orchestrator)
    audioPanelOpen = $bindable(false),
    subsPanelOpen = $bindable(false),
    speedPanelOpen = $bindable(false),
    episodesPanelOpen = $bindable(false),

    // Subtitle display
    subtitleItems,
    selectedSubId,
    subSelection,
    hasSubtitles,

    // Aspect
    onCycleAspect,

    // Media / close
    media = undefined,
    onPlayNext = undefined,
    onclose = undefined,

    // Bar element ref (needed by orchestrator's focus-containment checks)
    barEl = $bindable<HTMLDivElement | null>(null),
    onBarKeydown,
  }: {
    title: string;
    episodeLabel: string;
    controlsActive: boolean;
    activeSegment?: { type: string; label: string; seg: TimestampSegment } | null;
    skipBtnEl?: HTMLButtonElement | null;
    onSkipSegment: () => void;
    chapterBars: ChapterBar[] | null;
    isHash: boolean;
    torrentProgress: number;
    displayPos: number;
    onSeekbarKeydown: (e: KeyboardEvent) => void;
    onSeekBack: () => void;
    playPauseBtn?: HTMLButtonElement | null;
    onPlayPause: () => void;
    onSeekForward: () => void;
    audioPanelOpen?: boolean;
    subsPanelOpen?: boolean;
    speedPanelOpen?: boolean;
    episodesPanelOpen?: boolean;
    subtitleItems: SubItem[];
    selectedSubId: string | number;
    subSelection: SubSel;
    hasSubtitles: boolean;
    onCycleAspect: () => void;
    media?: Media;
    onPlayNext?: (season: number, episode: number) => void;
    onclose?: () => void;
    barEl?: HTMLDivElement | null;
    onBarKeydown: (e: KeyboardEvent) => void;
  } = $props();

  // ── Derived from Player (importable directly) ──────────────────────────────

  const selectedAudio = $derived(Player.audioTracks.find((t) => t.selected));

  // ── Helpers ──────────────────────────────────────────────────────────────────

  function langName(code: string): string {
    try {
      return new Intl.DisplayNames(["en"], { type: "language" }).of(code) ?? code;
    } catch {
      return code;
    }
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

<div
  class="absolute inset-0 z-10 flex flex-col transition-opacity duration-200"
  class:opacity-0={!controlsActive}
  class:pointer-events-none={!controlsActive}
>
  <!-- TOP gradient scrim: title + close -->
  <div
    class="flex shrink-0 items-start justify-between bg-gradient-to-b from-black/75 to-transparent px-8 pb-10 pt-6"
    role="toolbar"
    tabindex={-1}
    aria-label="Top controls"
  >
    <!-- Title + episode label -->
    <div class="flex min-w-0 flex-1 flex-col">
      <p class="max-w-full truncate text-lg font-semibold text-white drop-shadow">
        {title}
      </p>
      {#if episodeLabel}
        <p class="text-sm text-white/60">{episodeLabel}</p>
      {/if}
    </div>
  </div>

  <!-- SPACER (center area — no interactive controls here on TV) -->
  <div class="flex-1"></div>

  <!-- BOTTOM control bar: gradient backdrop + all controls in a row -->
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <div
    bind:this={barEl}
    class="shrink-0 bg-gradient-to-t from-black/90 via-black/50 to-transparent px-8 pb-8 pt-16"
    onkeydown={onBarKeydown}
    use:focusGroup={{ id: "tv-player-controls", policy: { type: "row" } }}
  >
    <!-- IntroDB skip button (inside bar area, focusable) -->
    {#if activeSegment}
      <div class="mb-4 flex justify-end">
        <button
          bind:this={skipBtnEl}
          type="button"
          class="rounded-full border border-white/60 bg-black/70 px-5 py-2.5 text-sm font-semibold text-white backdrop-blur-sm hover:bg-white/20 focus:bg-white/20"
          onclick={onSkipSegment}
          use:focusable={{ groupId: "tv-player-controls" }}
        >
          Skip {activeSegment.label}
        </button>
      </div>
    {/if}

    <!-- Seekbar -->
    <TvSeekBar
      {chapterBars}
      {isHash}
      {torrentProgress}
      {displayPos}
      duration={Player.duration}
      onkeydown={onSeekbarKeydown}
    />

    <!-- Main button row -->
    <div class="flex items-center gap-2">
      <!-- Seek -10s -->
      <button
        type="button"
        class="flex size-12 items-center justify-center rounded-full text-white hover:bg-white/20 focus:bg-white/20"
        onclick={onSeekBack}
        aria-label="Seek back 10 seconds"
        use:focusable={{ groupId: "tv-player-controls" }}
      >
        <SkipBack class="size-6" />
      </button>

      <!-- Play / Pause -->
      <button
        bind:this={playPauseBtn}
        type="button"
        class="flex size-14 items-center justify-center rounded-full bg-white/20 text-white backdrop-blur-sm hover:bg-white/35 focus:bg-white/35"
        onclick={onPlayPause}
        aria-label={Player.paused ? "Play" : "Pause"}
        use:focusable={{ groupId: "tv-player-controls" }}
      >
        {#if Player.paused}
          <Play class="size-7 translate-x-0.5" />
        {:else}
          <Pause class="size-7" />
        {/if}
      </button>

      <!-- Seek +10s -->
      <button
        type="button"
        class="flex size-12 items-center justify-center rounded-full text-white hover:bg-white/20 focus:bg-white/20"
        onclick={onSeekForward}
        aria-label="Seek forward 10 seconds"
        use:focusable={{ groupId: "tv-player-controls" }}
      >
        <SkipForward class="size-6" />
      </button>

      <!-- Time display (not focusable) -->
      <span class="ml-3 tabular-nums text-sm text-white/70">
        {fmt(displayPos)} / {fmt(Player.duration)}
      </span>

      <div class="flex-1"></div>

      <!-- Audio tracks -->
      {#if Player.audioTracks.length > 0}
        <button
          type="button"
          class="flex min-h-[44px] items-center gap-1.5 rounded-lg px-3 py-2 text-white hover:bg-white/15 focus:bg-white/15"
          onclick={() => { audioPanelOpen = true; }}
          aria-label="Audio tracks"
          use:focusable={{ groupId: "tv-player-controls" }}
        >
          <Headphones class="size-5 shrink-0" />
          <span class="max-w-24 truncate text-sm">
            {selectedAudio?.title || langName(selectedAudio?.lang ?? "") || "Audio"}
          </span>
        </button>
      {/if}

      <!-- Subtitles -->
      {#if hasSubtitles}
        <button
          type="button"
          class="flex min-h-[44px] items-center gap-1.5 rounded-lg px-3 py-2 text-white hover:bg-white/15 focus:bg-white/15"
          onclick={() => { subsPanelOpen = true; }}
          aria-label="Subtitles"
          use:focusable={{ groupId: "tv-player-controls" }}
        >
          <Captions class="size-5 shrink-0" />
          <span class="max-w-24 truncate text-sm">
            {subSelection.kind === "off"
              ? "Subs"
              : (subtitleItems.find((i) => i.id === selectedSubId)?.label ?? "Subs")}
          </span>
        </button>
      {/if}

      <!-- Playback speed -->
      <button
        type="button"
        class="flex min-h-[44px] items-center gap-1.5 rounded-lg px-3 py-2 text-white hover:bg-white/15 focus:bg-white/15"
        onclick={() => { speedPanelOpen = true; }}
        aria-label="Playback speed"
        use:focusable={{ groupId: "tv-player-controls" }}
      >
        <Gauge class="size-5 shrink-0" />
        <span class="text-sm">{Player.playbackSpeed === 1 ? "1×" : `${Player.playbackSpeed}×`}</span>
      </button>

      <!-- Aspect ratio cycle -->
      <button
        type="button"
        class="flex min-h-[44px] items-center gap-1.5 rounded-lg px-3 py-2 text-white hover:bg-white/15 focus:bg-white/15"
        onclick={onCycleAspect}
        aria-label="Aspect ratio"
        use:focusable={{ groupId: "tv-player-controls" }}
      >
        <Ratio class="size-5 shrink-0" />
        <span class="text-sm">{ASPECT_LABELS[Player.aspectMode]}</span>
      </button>

      <!-- Episodes (TV shows only) -->
      {#if media?.media_type === "tv" && onPlayNext}
        <button
          type="button"
          class="flex min-h-[44px] items-center gap-1.5 rounded-lg px-3 py-2 text-white hover:bg-white/15 focus:bg-white/15"
          onclick={() => { episodesPanelOpen = true; }}
          aria-label="Episodes"
          use:focusable={{ groupId: "tv-player-controls" }}
        >
          <ListVideo class="size-5 shrink-0" />
          <span class="text-sm">Episodes</span>
        </button>
      {/if}

      <!-- Close -->
      <button
        type="button"
        class="flex size-11 items-center justify-center rounded-full text-white hover:bg-white/20 focus:bg-white/20"
        onclick={() => onclose?.()}
        aria-label="Close player"
        use:focusable={{ groupId: "tv-player-controls" }}
      >
        <X class="size-6" />
      </button>
    </div>
  </div>
</div>
