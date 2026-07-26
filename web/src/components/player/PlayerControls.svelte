<script lang="ts">
  import { Player, type MpvTrack } from "$lib/player/player.svelte";
  import { TorrentProgress } from "$lib/player/torrentProgress.svelte.js";
  import { ASPECT_LABELS } from "$lib/player/aspectRatio";
  import type { SubSel } from "$lib/player/subtitles";
  import type { ChapterBar } from "$lib/player/chapters";
  import * as Popover from "$lib/components/ui/popover";
  import * as Tooltip from "$lib/components/ui/tooltip";
  import { Button } from "$lib/components/ui/button";
  import { Slider } from "$lib/components/ui/slider/index.js";
  import {
    Play,
    Pause,
    Volume2,
    Volume1,
    VolumeX,
    ListVideo,
    Ratio,
    Gauge,
    Keyboard,
  } from "lucide-svelte";
  import SeekBar from "./SeekBar.svelte";
  import AudioMenu from "./AudioMenu.svelte";
  import SubtitleMenu from "./SubtitleMenu.svelte";
  import MenuItem from "./MenuItem.svelte";
  import * as m from "$lib/paraglide/messages.js";

  let {
    externalSubtitles,
    subSelection,
    chapterBars,
    isHash,
    torrent,
    showEpisodes,
    episodesOpen = $bindable(false),
    toggleMute,
    chooseAudioTrack,
    chooseSubtitle,
    updateSubStyle,
    chooseSpeed,
    cycleAspect,
    onMenuOpenChange,
  }: {
    externalSubtitles: { id: string; url: string; lang: string }[];
    subSelection: SubSel;
    chapterBars: ChapterBar[] | null;
    isHash: boolean;
    torrent: TorrentProgress;
    showEpisodes: boolean;
    episodesOpen?: boolean;
    toggleMute: () => void;
    chooseAudioTrack: (track: MpvTrack) => void;
    chooseSubtitle: (sel: SubSel) => void;
    updateSubStyle: (patch: {
      subtitleSize?: number;
      subtitlePosition?: number;
      subtitleBackground?: boolean;
    }) => void;
    chooseSpeed: (speed: number) => void;
    cycleAspect: () => void;
    onMenuOpenChange: (open: boolean) => void;
  } = $props();

  // Playback-speed options — same set as the mobile/TV players.
  const SPEEDS = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0];

  // Track-menu open state. While any picker is open, keyboard shortcuts stand
  // down so the menu's own arrow-key navigation isn't hijacked — reported up so
  // the orchestrator can suppress its shortcuts and the wheel-volume handler.
  let audioOpen = $state(false);
  let subsOpen = $state(false);
  let speedOpen = $state(false);
  let helpOpen = $state(false);
  const menuOpen = $derived(
    audioOpen || subsOpen || speedOpen || helpOpen || episodesOpen,
  );
  $effect(() => onMenuOpenChange(menuOpen));

  // Scrub preview: the SeekBar owns the drag; it reports the live position so
  // the clock reads the dragged time, then null on release.
  let scrubPreview = $state<number | null>(null);
  const displayPos = $derived(scrubPreview ?? Player.position);

  function fmt(t: number): string {
    if (!isFinite(t) || t < 0) t = 0;
    const h = Math.floor(t / 3600);
    const m = Math.floor((t % 3600) / 60);
    const s = Math.floor(t % 60);
    const mm = h ? String(m).padStart(2, "0") : String(m);
    return `${h ? h + ":" : ""}${mm}:${String(s).padStart(2, "0")}`;
  }

  function formatBytes(bytes: number): string {
    if (bytes >= 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
    if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${bytes} B`;
  }

  function formatEta(remainingBytes: number, speedBps: number): string {
    if (speedBps <= 0) return "";
    const secs = remainingBytes / speedBps;
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return m > 0 ? `~${m}m ${s}s` : `~${s}s`;
  }
</script>

{#snippet shortcut(label: string, keys: string)}
  <div class="flex items-center justify-between gap-4">
    <dt class="text-muted-foreground">{label}</dt>
    <dd>
      <kbd
        class="rounded border border-border bg-muted px-1.5 py-0.5 font-mono text-[11px] text-muted-foreground"
      >{keys}</kbd>
    </dd>
  </div>
{/snippet}

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
  class="flex w-full flex-col gap-2 px-4 pb-4 text-white"
  onclick={(e) => e.stopPropagation()}
  onkeydown={(e) => e.stopPropagation()}
>
  <SeekBar {chapterBars} onScrub={(p) => (scrubPreview = p)} />

  <!-- Transport + tracks -->
  <div class="flex items-center gap-1">
    <!-- Play / pause -->
    <Tooltip.Root>
      <Tooltip.Trigger>
        {#snippet child({ props })}
          <Button
            {...props}
            variant="ghost"
            size="icon"
            class="text-white hover:bg-white/15 hover:text-white"
            onclick={() => Player.togglePause()}
          >
            {#if Player.paused}
              <Play class="size-5" />
            {:else}
              <Pause class="size-5" />
            {/if}
          </Button>
        {/snippet}
      </Tooltip.Trigger>
      <Tooltip.Content>
        {Player.paused ? m.player_play() : m.player_pause()} · Space
      </Tooltip.Content>
    </Tooltip.Root>

    <!-- Volume: button + slider that expands on hover/focus -->
    <div class="group/vol flex items-center">
      <Tooltip.Root>
        <Tooltip.Trigger>
          {#snippet child({ props })}
            <Button
              {...props}
              variant="ghost"
              size="icon"
              class="text-white hover:bg-white/15 hover:text-white"
              onclick={toggleMute}
            >
              {#if Player.volume === 0}
                <VolumeX class="size-5" />
              {:else if Player.volume < 50}
                <Volume1 class="size-5" />
              {:else}
                <Volume2 class="size-5" />
              {/if}
            </Button>
          {/snippet}
        </Tooltip.Trigger>
        <Tooltip.Content>
          {Player.volume === 0 ? m.player_unmute() : m.player_mute()} · M
        </Tooltip.Content>
      </Tooltip.Root>
      <div
        class="ml-1 w-0 overflow-hidden opacity-0 transition-all duration-200 group-hover/vol:w-24 group-hover/vol:opacity-100 group-focus-within/vol:w-24 group-focus-within/vol:opacity-100"
      >
        <Slider
          type="single"
          value={Player.volume}
          max={100}
          step={1}
          onValueChange={(v) => Player.setVolume(v)}
          aria-label={m.player_volume()}
          class="w-24"
        />
      </div>
    </div>

    <span class="ml-2 text-xs tabular-nums text-white/80">
      {fmt(displayPos)}<span class="mx-1 text-white/40">/</span>{fmt(Player.duration)}
    </span>

    <div class="flex-1"></div>

    <!-- Torrent download progress (hash sources, mid-download) -->
    {#if isHash && torrent.progress > 0 && torrent.progress < 100}
      <Tooltip.Root>
        <Tooltip.Trigger>
          {#snippet child({ props })}
            <span {...props} class="mr-1 cursor-default text-xs tabular-nums text-white/60">
              ↓ {torrent.progress.toFixed(0)}%
            </span>
          {/snippet}
        </Tooltip.Trigger>
        <Tooltip.Content side="top">
          <div class="space-y-1.5 text-xs">
            <div class="flex items-center gap-2">
              <div class="h-1 w-24 overflow-hidden rounded-full bg-white/20">
                <div class="h-full rounded-full bg-white/70" style="width: {torrent.progress.toFixed(1)}%"></div>
              </div>
              <span class="tabular-nums">{torrent.progress.toFixed(1)}%</span>
            </div>
            <div>Speed: {torrent.speed}</div>
            <div>Peers: {torrent.peers} active / {torrent.totalPeers} known · {torrent.seeders} seeders</div>
            {#if torrent.totalBytes > 0}
              <div>{m.player_size()}: {formatBytes(torrent.downloadedBytes)} / {formatBytes(torrent.totalBytes)}</div>
            {/if}
            {#if torrent.speedBps > 0 && torrent.totalBytes > 0}
              <div>{m.player_eta()}: {formatEta(torrent.totalBytes - torrent.downloadedBytes, torrent.speedBps)}</div>
            {/if}
          </div>
        </Tooltip.Content>
      </Tooltip.Root>
    {/if}

    <!-- Audio tracks -->
    {#if Player.audioTracks.length > 0}
      <AudioMenu bind:open={audioOpen} onSelect={chooseAudioTrack} />
    {/if}

    <!-- Subtitles -->
    {#if Player.subtitleTracks.length > 0 || externalSubtitles.length > 0}
      <SubtitleMenu
        bind:open={subsOpen}
        {externalSubtitles}
        {subSelection}
        onSelect={chooseSubtitle}
        onUpdateStyle={updateSubStyle}
      />
    {/if}

    <!-- Episodes sidebar toggle (TV shows, when caller provides onPlayStream) -->
    {#if showEpisodes}
      <Tooltip.Root>
        <Tooltip.Trigger>
          {#snippet child({ props })}
            <Button
              {...props}
              variant="ghost"
              size="sm"
              class="gap-1.5 text-white hover:bg-white/15 hover:text-white {episodesOpen
                ? 'bg-white/15'
                : ''}"
              onclick={() => (episodesOpen = !episodesOpen)}
            >
              <ListVideo class="size-4" />
              <span class="text-xs">{m.player_episodes()}</span>
            </Button>
          {/snippet}
        </Tooltip.Trigger>
        <Tooltip.Content>{m.player_episodes()}</Tooltip.Content>
      </Tooltip.Root>
    {/if}

    <!-- Playback speed -->
    <Popover.Root bind:open={speedOpen}>
      <Popover.Trigger>
        {#snippet child({ props })}
          <Button
            {...props}
            variant="ghost"
            size="sm"
            class="gap-1.5 text-white hover:bg-white/15 hover:text-white"
          >
            <Gauge class="size-4" />
            <span class="text-xs">
              {Player.playbackSpeed === 1 ? "1×" : `${Player.playbackSpeed}×`}
            </span>
          </Button>
        {/snippet}
      </Popover.Trigger>
      <Popover.Content side="top" align="end" class="w-40 p-1 flex gap-1">
        <p class="px-2 py-1.5 text-xs font-medium text-muted-foreground">
          {m.player_speed()}
        </p>
        {#each SPEEDS as speed (speed)}
          <MenuItem
            label={speed === 1 ? m.player_normal_speed() : `${speed}×`}
            active={Player.playbackSpeed === speed}
            onSelect={() => chooseSpeed(speed)}
          />
        {/each}
      </Popover.Content>
    </Popover.Root>

    <!-- Aspect ratio cycle -->
    <Tooltip.Root>
      <Tooltip.Trigger>
        {#snippet child({ props })}
          <Button
            {...props}
            variant="ghost"
            size="sm"
            class="gap-1.5 text-white hover:bg-white/15 hover:text-white"
            onclick={cycleAspect}
          >
            <Ratio class="size-4" />
            <span class="max-w-28 truncate text-xs">{ASPECT_LABELS[Player.aspectMode]}</span>
          </Button>
        {/snippet}
      </Tooltip.Trigger>
      <Tooltip.Content>{m.player_aspect_ratio()} · V</Tooltip.Content>
    </Tooltip.Root>

    <!-- Keyboard shortcuts -->
    <Popover.Root bind:open={helpOpen}>
      <Popover.Trigger>
        {#snippet child({ props })}
          <Button
            {...props}
            variant="ghost"
            size="icon"
            class="text-white hover:bg-white/15 hover:text-white"
            aria-label={m.player_keyboard_shortcuts()}
          >
            <Keyboard class="size-4" />
          </Button>
        {/snippet}
      </Popover.Trigger>
      <Popover.Content side="top" align="end" class="w-64 p-3">
        <p class="mb-2 text-xs font-medium text-muted-foreground">
          {m.player_keyboard_shortcuts()}
        </p>
        <dl class="space-y-1.5 text-sm">
          {@render shortcut(m.player_play_pause(), "Space")}
          {@render shortcut(`${m.player_seek_seconds()} ±5s`, "← →")}
          {@render shortcut(`${m.player_seek_seconds()} ±10s`, "J L")}
          {@render shortcut(m.player_volume(), "↑ ↓")}
          {@render shortcut(m.player_mute(), "M")}
          {@render shortcut(m.player_subtitles(), "C")}
          {@render shortcut(m.player_aspect_ratio(), "V")}
          {@render shortcut(m.player_jump_percent(), "0–9")}
        </dl>
      </Popover.Content>
    </Popover.Root>
  </div>
</div>
