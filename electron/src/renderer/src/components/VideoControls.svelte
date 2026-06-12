<script lang="ts">
  import {
    Play,
    Pause,
    Volume2,
    VolumeX,
    Maximize,
    Minimize,
    SkipBack,
    SkipForward,
  } from "lucide-svelte";
  import { Button } from "$lib/components/ui/button";
  import * as Tooltip from "$lib/components/ui/tooltip";
  import * as Popover from "$lib/components/ui/popover";

  let {
    playing,
    currentTime,
    duration,
    volume,
    muted,
    buffered,
    torrentProgress = 0,
    peers = 0,
    speed = "0 B/s",
    isHash = false,
    fullscreen = false,
    showControls,
    onTogglePlay,
    onSeek,
    onSkip,
    onToggleMute,
    onSetVolume,
    onToggleFullscreen,
    onResetControlsTimer,
  }: {
    playing: boolean;
    currentTime: number;
    duration: number;
    volume: number;
    muted: boolean;
    buffered: number;
    torrentProgress?: number;
    peers?: number;
    speed?: string;
    isHash?: boolean;
    fullscreen?: boolean;
    showControls: boolean;
    onTogglePlay: () => void;
    onSeek: (e: MouseEvent) => void;
    onSkip: (seconds: number) => void;
    onToggleMute: () => void;
    onSetVolume: (e: MouseEvent) => void;
    onToggleFullscreen: () => void;
    onResetControlsTimer: () => void;
  } = $props();

  function formatTime(s: number): string {
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = Math.floor(s % 60);
    if (h > 0)
      return `${h}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
    return `${m}:${String(sec).padStart(2, "0")}`;
  }
</script>

<div
  class="pointer-events-none absolute inset-0 flex flex-col justify-end bg-linear-to-t from-black/80 via-transparent to-transparent transition-opacity duration-300"
  style="opacity: {showControls ? 1 : 0}"
  onmousemove={onResetControlsTimer}
  role="presentation"
>
  <!-- Seek bar -->
  <div class="pointer-events-auto px-4 pb-2">
    <button
      class="relative block h-1 w-full cursor-pointer rounded-full bg-white/20 transition-all hover:h-2"
      onclick={(e) => {
        e.stopPropagation();
        onSeek(e);
      }}
      aria-label="Seek"
    >
      <span
        class="absolute inset-y-0 left-0 rounded-full bg-white/30"
        style="width: {buffered}%"
      ></span>
      <span
        class="absolute inset-y-0 left-0 rounded-full bg-white"
        style="width: {duration ? (currentTime / duration) * 100 : 0}%"
      ></span>
    </button>
  </div>

  <!-- Controls row -->
  <div class="pointer-events-auto flex items-center gap-2 px-4 pb-4">
    <Tooltip.Root>
      <Tooltip.Trigger>
        <Button
          variant="ghost"
          size="icon"
          class="text-white hover:bg-white/10 hover:text-white"
          onclick={(e) => {
            e.stopPropagation();
            onSkip(-10);
          }}
        >
          <SkipBack class="size-5" />
        </Button>
      </Tooltip.Trigger>
      <Tooltip.Content>Back 10s</Tooltip.Content>
    </Tooltip.Root>

    <Tooltip.Root>
      <Tooltip.Trigger>
        <Button
          variant="ghost"
          size="icon"
          class="text-white hover:bg-white/10 hover:text-white"
          onclick={(e) => {
            e.stopPropagation();
            onTogglePlay();
          }}
        >
          {#if playing}<Pause class="size-6" />{:else}<Play
              class="size-6"
            />{/if}
        </Button>
      </Tooltip.Trigger>
      <Tooltip.Content>{playing ? "Pause" : "Play"}</Tooltip.Content>
    </Tooltip.Root>

    <Tooltip.Root>
      <Tooltip.Trigger>
        <Button
          variant="ghost"
          size="icon"
          class="text-white hover:bg-white/10 hover:text-white"
          onclick={(e) => {
            e.stopPropagation();
            onSkip(10);
          }}
        >
          <SkipForward class="size-5" />
        </Button>
      </Tooltip.Trigger>
      <Tooltip.Content>Forward 10s</Tooltip.Content>
    </Tooltip.Root>

    <span class="text-xs text-white/80 tabular-nums">
      {formatTime(currentTime)} / {formatTime(duration)}
    </span>

    <div class="flex-1"></div>

    {#if isHash}
      <Popover.Root>
        <Popover.Trigger
          class="flex items-center gap-1.5 rounded-md px-2 py-1 text-white/70 transition-colors hover:bg-white/10 hover:text-white"
          onclick={(e) => e.stopPropagation()}
        >
          <svg viewBox="0 0 12 12" class="size-3.5">
            <circle
              cx="6"
              cy="6"
              r="5"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
              class="text-white/20"
            />
            <circle
              cx="6"
              cy="6"
              r="5"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-dasharray="{(torrentProgress / 100) * 31.4} 31.4"
              stroke-linecap="round"
              transform="rotate(-90 6 6)"
              class="text-green-400 transition-all duration-500"
            />
          </svg>
          <span class="text-xs">{torrentProgress.toFixed(0)}%</span>
        </Popover.Trigger>
        <Popover.Content class="w-52" side="top">
          <p class="mb-2 text-sm font-medium">Download Progress</p>
          <div class="mb-1 h-1.5 w-full rounded-full bg-secondary">
            <div
              class="h-full rounded-full bg-green-500 transition-all duration-500"
              style="width: {torrentProgress}%"
            ></div>
          </div>
          <div class="flex justify-between text-xs text-muted-foreground">
            <span>{torrentProgress.toFixed(1)}%</span>
            {#if peers > 0}<span>{peers} peers</span>{/if}
          </div>
          <div class="mt-1 text-xs text-muted-foreground">↓ {speed}</div>
        </Popover.Content>
      </Popover.Root>
    {/if}

    <Tooltip.Root>
      <Tooltip.Trigger>
        <Button
          variant="ghost"
          size="icon"
          class="text-white hover:bg-white/10 hover:text-white"
          onclick={(e) => {
            e.stopPropagation();
            onToggleMute();
          }}
        >
          {#if muted || volume === 0}<VolumeX class="size-5" />{:else}<Volume2
              class="size-5"
            />{/if}
        </Button>
      </Tooltip.Trigger>
      <Tooltip.Content>{muted ? "Unmute" : "Mute"}</Tooltip.Content>
    </Tooltip.Root>

    <button
      class="relative block h-1 w-20 cursor-pointer rounded-full bg-white/20 transition-all hover:h-2"
      onclick={(e) => {
        e.stopPropagation();
        onSetVolume(e);
      }}
      aria-label="Volume"
    >
      <span
        class="absolute inset-y-0 left-0 rounded-full bg-white"
        style="width: {muted ? 0 : volume * 100}%"
      ></span>
    </button>

    <Tooltip.Root>
      <Tooltip.Trigger>
        <Button
          variant="ghost"
          size="icon"
          class="text-white hover:bg-white/10 hover:text-white"
          onclick={(e) => {
            e.stopPropagation();
            onToggleFullscreen();
          }}
        >
          {#if fullscreen}<Minimize class="size-5" />{:else}<Maximize
              class="size-5"
            />{/if}
        </Button>
      </Tooltip.Trigger>
      <Tooltip.Content
        >{fullscreen ? "Exit fullscreen" : "Fullscreen"}</Tooltip.Content
      >
    </Tooltip.Root>
  </div>
</div>
