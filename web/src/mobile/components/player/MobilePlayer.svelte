<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import type { Stream } from "$lib/types/addons";
  import { Slider } from "$lib/components/ui/slider/index.js";
  import { onDestroy, untrack } from "svelte";
  import { api } from "$lib/api";
  import { settings } from "$lib/stores/settings";
  import { Player } from "$lib/player/player.svelte";
  import { PlayerCore } from "$lib/player/playerCore.svelte";
  import { saveAspectMode } from "$lib/player/aspectRatio";
  import { nextAiredEpisode } from "$lib/nextEpisode";
  import { rankStreams, type StreamSelectionMode } from "$lib/streamSelection";
  import * as m from "$lib/paraglide/messages.js";
  import { langName, trackLabel } from "$lib/player/trackLabels";
  import {
    sortAudioTracks,
    subtitleItems as buildSubtitleItems,
    subtitleRows as buildSubtitleRows,
  } from "$lib/player/trackList";
  import TrackSheet from "./TrackSheet.svelte";
  import EpisodeSheet from "./EpisodeSheet.svelte";
  import MobilePlayerControls from "./MobilePlayerControls.svelte";
  import MobileUpNext from "./MobileUpNext.svelte";
  import MobileLoadingScreen from "./MobileLoadingScreen.svelte";
  import SeekFlash from "./SeekFlash.svelte";

  // ── Props (same contract as desktop Player + mobile-specific additions) ──────

  let {
    src = "",
    media,
    pendingMessage = undefined,
    onCancelPending = undefined,
    externalSubtitles = [],
    season = undefined,
    episode = undefined,
    fileIdx = undefined,
    onPlaybackFailed = undefined,
    onPlayNext = undefined,
    onPlayStream: _onPlayStream = undefined,
    onclose = undefined,
    /** Parent registers a close-sheets callback for Escape priority handling. */
    onRegisterCloseSheets = undefined,
  }: {
    src?: string;
    media?: Media;
    pendingMessage?: string;
    onCancelPending?: () => void;
    externalSubtitles?: { id: string; url: string; lang: string }[];
    season?: number;
    episode?: number;
    fileIdx?: number;
    onPlaybackFailed?: () => void;
    onPlayNext?: (season: number, episode: number) => void;
    onPlayStream?: (
      stream: Stream,
      season?: number,
      episode?: number,
      episodeName?: string,
      candidates?: Stream[],
    ) => void;
    onclose?: () => void;
    onRegisterCloseSheets?: (fn: () => boolean) => void;
  } = $props();

  // ── Shared playback core ──────────────────────────────────────────────────
  // Everything that behaves identically in all three shells lives in
  // $lib/player/playerCore.svelte.ts. What stays here is mobile-only: the
  // bottom sheets, touch scrubbing and the tap-to-seek gestures.
  const core = new PlayerCore({
    getSrc: () => src,
    getMedia: () => media,
    getSeason: () => season,
    getEpisode: () => episode,
    getFileIdx: () => fileIdx,
    getExternalSubtitles: () => externalSubtitles,
    getPendingMessage: () => pendingMessage,
    getSettings: () => $settings,
    getTitle: () => title,
    onPlaybackFailed: () => onPlaybackFailed?.(),
    onPlayNext: (s, e) => onPlayNext?.(s, e),
    hasPlayNext: () => !!onPlayNext,
    onSrcChange: () => {
      prefetchedNext = false;
      scrubbing = false;
    },
    // The mobile shell tears the player down on backgrounding, where "ended"
    // is not meaningful — it has always saved the final position as
    // not-completed. Kept as-is.
    getDestroyCompleted: () => false,
  });

  // One $effect per core lifecycle method, in the order the inline effects ran.
  $effect(() => core.startPlayback());
  $effect(() => core.resolveOriginalLang());
  $effect(() => core.clearSwitchingWhenReady());
  $effect(() => core.resumeRetriedPlayback());
  onDestroy(() => core.destroy());
  $effect(() => core.armWatchdog());
  $effect(() => core.markPlaybackStarted());
  $effect(() => core.failOnPlaybackInterruption());
  $effect(() => core.failOnStalledTorrent());
  $effect(() => core.loadProgress());
  $effect(() => core.resumeProgress());
  $effect(() => core.saveProgressTick());
  $effect(() => core.saveProgressOnEnded());
  $effect(() => core.trackTorrentProgress());
  $effect(() => core.loadLogo());
  $effect(() => core.loadTimestamps());
  $effect(() => core.autoSkipSegment());
  $effect(() => core.applyAudioDefault());
  $effect(() => core.applySubtitleDefault());
  $effect(() => core.applySubtitleStyle());
  $effect(() => core.resolveNextEpisode());
  $effect(() => core.runUpNextCountdown());
  $effect(() => core.advanceOnEnded());

  $effect(() => {
    onRegisterCloseSheets?.(() => {
      if (audioSheetOpen || subsSheetOpen || speedSheetOpen || episodesSheetOpen) {
        audioSheetOpen = false;
        subsSheetOpen = false;
        speedSheetOpen = false;
        episodesSheetOpen = false;
        return true;
      }
      return false;
    });
  });

  // ── Playback lifecycle ───────────────────────────────────────────────────────

  let prefetchedNext = false;

  $effect(() => {
    if (
      $settings?.prefetchNextEpisode === false ||
      media?.media_type !== "tv" ||
      season == null ||
      episode == null ||
      !core.isHash ||
      core.torrent.progress < 100 ||
      prefetchedNext
    )
      return;
    prefetchedNext = true;
    const m = media;
    const mode = ($settings?.streamSelectionMode as StreamSelectionMode) ?? "balanced";
    const bandwidth = $settings?.measuredBandwidthMbps;
    const preferredProvider = $settings?.defaultProvider;
    const sourcePreference = $settings?.sourcePreference;
    // "original" resolves to the title's TMDB original language before ranking.
    const effectiveAudioLang =
      $settings?.defaultAudioLang === "original"
        ? (m.original_language ?? "")
        : ($settings?.defaultAudioLang ?? "");
    untrack(() => {
      (async () => {
        const next = await nextAiredEpisode(m.id, season!, episode!);
        if (!next) return;
        let streams;
        try {
          streams = await api.getStreams(m.id, {
            type: "tv",
            season: next.season,
            episode: next.episode.episode_number,
          });
        } catch {
          return;
        }
        if (streams.length === 0) return;
        const ranked = rankStreams(streams, mode, {
          measuredBandwidthMbps: bandwidth,
          preferredProvider,
          sourcePreference,
          defaultAudioLang: effectiveAudioLang || undefined,
        });
        const best = ranked[0];
        if (best?.infoHash) {
          api
            .prefetchDownload(best.infoHash, {
              season: next.season,
              episode: next.episode.episode_number,
            })
            .catch(() => {});
        }
      })();
    });
  });

  let subStyleSaveTimer: ReturnType<typeof setTimeout> | undefined;

  function updateSubStyle(patch: {
    subtitleSize?: number;
    subtitlePosition?: number;
    subtitleBackground?: boolean;
  }): void {
    const size = patch.subtitleSize ?? $settings?.subtitleSize ?? 100;
    const pos = patch.subtitlePosition ?? $settings?.subtitlePosition ?? 8;
    const bg = patch.subtitleBackground ?? $settings?.subtitleBackground ?? false;
    Player.setSubtitleStyle(size, pos, bg);
    clearTimeout(subStyleSaveTimer);
    subStyleSaveTimer = setTimeout(() => settings.save(patch), 400);
  }
  onDestroy(() => clearTimeout(subStyleSaveTimer));

  // ── Helpers ──────────────────────────────────────────────────────────────────

  // Track-list construction is shared with the other non-desktop shell — see
  // $lib/player/trackList.
  const sortedAudio = $derived(sortAudioTracks(Player.audioTracks));
  const subtitleItems = $derived(
    buildSubtitleItems(Player.subtitleTracks, externalSubtitles),
  );
  const subtitleRows = $derived(
    buildSubtitleRows(Player.subtitleTracks, externalSubtitles),
  );

  const selectedSubId = $derived.by((): string | number => {
    const sel = core.subSelection;
    if (sel.kind === "off") return "off";
    return sel.id;
  });

  const title = $derived(
    media ? (media.media_type === "tv" ? (media.name ?? "") : (media.title ?? "")) : "",
  );

  const episodeLabel = $derived.by(() => {
    if (media?.media_type !== "tv" || season == null || episode == null) return "";
    return `S${season}E${episode}`;
  });

  const selectedAudio = $derived(Player.audioTracks.find((t) => t.selected));

  // ── Speed control ─────────────────────────────────────────────────────────────
  const SPEEDS = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0];

  // ── Mute / volume ─────────────────────────────────────────────────────────────

  let lastVolume = $state(100);

  function toggleMute(): void {
    if (Player.volume > 0) {
      lastVolume = Player.volume;
      Player.setVolume(0);
    } else {
      Player.setVolume(lastVolume || 100);
    }
  }

  // ── Seek flash indicators ─────────────────────────────────────────────────────

  let seekFlash = $state<"left" | "right" | null>(null);
  let seekFlashTimer: ReturnType<typeof setTimeout> | undefined;

  function showSeekFlash(dir: "left" | "right"): void {
    seekFlash = dir;
    clearTimeout(seekFlashTimer);
    seekFlashTimer = setTimeout(() => (seekFlash = null), 600);
  }

  onDestroy(() => clearTimeout(seekFlashTimer));

  function nudgeSeek(delta: number): void {
    const target = Math.max(0, Math.min(Player.duration || Infinity, Player.position + delta));
    Player.seek(target);
  }

  function cycleAspect(): void {
    const next = Player.cycleAspectMode();
    if (media) saveAspectMode(media.id, next);
  }

  // ── Seek-bar scrubbing flag (the seek bar lives in MobilePlayerControls →
  //    MobileSeekBar, which reports drag start/end via onScrub) ─────────────────
  let scrubbing = $state(false);

  // ── Sheet open state ──────────────────────────────────────────────────────────
  // Must be declared before controlsActive which references them.

  let audioSheetOpen = $state(false);
  let subsSheetOpen = $state(false);
  let speedSheetOpen = $state(false);
  let episodesSheetOpen = $state(false);

  // ── Controls auto-hide ────────────────────────────────────────────────────────

  let controlsVisible = $state(true);
  let hideTimer: ReturnType<typeof setTimeout> | undefined;

  // Controls are "active" (visible + interactive) if explicitly shown, paused, or
  // not yet playing. Sheet-open always keeps them active so scrims don't vanish
  // behind an open sheet.
  const controlsActive = $derived(
    controlsVisible || Player.paused || !core.canPlay || audioSheetOpen || subsSheetOpen || speedSheetOpen || episodesSheetOpen,
  );

  function showControls(): void {
    controlsVisible = true;
    clearTimeout(hideTimer);
    if (!Player.paused && !scrubbing && !audioSheetOpen && !subsSheetOpen && !speedSheetOpen && !episodesSheetOpen) {
      hideTimer = setTimeout(() => (controlsVisible = false), 3000);
    }
  }

  // Keep controls visible while paused, buffering, or any sheet is open.
  $effect(() => {
    if (Player.paused || !core.canPlay || audioSheetOpen || subsSheetOpen || speedSheetOpen || episodesSheetOpen) {
      clearTimeout(hideTimer);
      controlsVisible = true;
    }
  });

  onDestroy(() => clearTimeout(hideTimer));

  // ── Double-tap / single-tap handler ──────────────────────────────────────────
  //
  // Scheme: IMMEDIATE TOGGLE WITH CANCEL ON DOUBLE-TAP (least laggy)
  //   - First tap:  immediately toggle controls visibility.
  //   - Second tap within 300ms in left/right third: undo the controls toggle,
  //     seek ±10s, show seek flash, then showControls() so UI is visible.
  //   - Center double-tap: undo the toggle only (no seek).
  //   - Movement threshold: 12px to distinguish taps from scroll/drag.
  //
  // The control containers only stop propagation of CLICK events — touchend
  // still bubbles from every button to this root handler (click-stoppers were
  // enough in browser dev, where mouse input fires no touch events at all).
  // So taps that originate on an interactive element are ignored here: the
  // button's own click handler is the action, not a controls toggle.

  let containerEl = $state<HTMLDivElement | null>(null);
  let tapState: { time: number; x: number } | null = null;

  function handleTouchEnd(e: TouchEvent): void {
    const touch = e.changedTouches[0];
    if (!touch) return;

    const target = e.target instanceof Element ? e.target : null;
    if (target?.closest('button, a, input, [role="slider"]')) return;

    const x = touch.clientX;
    const now = Date.now();
    const width = containerEl?.clientWidth ?? window.innerWidth;

    if (tapState && now - tapState.time < 300 && Math.abs(x - tapState.x) < 12) {
      // Double-tap: undo the first-tap toggle and apply seek
      tapState = null;
      // Revert the controls toggle that first tap applied
      controlsVisible = !controlsVisible;

      if (x < width / 3) {
        nudgeSeek(-10);
        showSeekFlash("left");
      } else if (x > (width * 2) / 3) {
        nudgeSeek(10);
        showSeekFlash("right");
      }
      // Always show controls after a seek action
      showControls();
    } else {
      // Single tap: toggle controls immediately
      if (controlsVisible) {
        controlsVisible = false;
        clearTimeout(hideTimer);
      } else {
        showControls();
      }
      tapState = { time: now, x };
      setTimeout(() => {
        if (tapState && tapState.time === now) tapState = null;
      }, 310);
    }
  }
</script>

<!--
  Root is fully transparent — mpv renders behind the WebView and shows through.
  No background or opaque ancestor here.
-->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
  class="relative h-full w-full overflow-hidden"
  bind:this={containerEl}
  ontouchend={handleTouchEnd}
  onkeydown={() => {}}
>

  <!-- ── Bridge unavailable ──────────────────────────────────────────────────── -->
  {#if !Player.available && !core.streamDiscoveryPending}
    <div class="absolute inset-0 z-30 grid place-items-center bg-black">
      <p class="rounded bg-black/60 px-4 py-2 text-sm text-red-400">
        {m.player_native_unavailable()}
      </p>
    </div>
  {/if}

  <!-- ── Controls overlay ───────────────────────────────────────────────────── -->
  {#if core.canPlay}
    <MobilePlayerControls
      {title}
      {episodeLabel}
      activeSegment={core.activeSegment}
      chapterBars={core.chapterBars}
      isHash={core.isHash}
      torrent={core.torrent}
      audioLabel={selectedAudio?.title || langName(selectedAudio?.lang ?? "") || "Audio"}
      subLabel={core.subSelection.kind === "off"
        ? "Subs"
        : (subtitleItems.find((i) => i.id === selectedSubId)?.label ?? "Subs")}
      showAudio={Player.audioTracks.length > 0}
      showSubs={Player.subtitleTracks.length > 0 || externalSubtitles.length > 0}
      hasNextEp={media?.media_type === "tv" && !!onPlayNext}
      bind:audioSheetOpen
      bind:subsSheetOpen
      bind:speedSheetOpen
      bind:episodesSheetOpen
      {controlsActive}
      {onclose}
      onSkipSegment={() => core.activeSegment && core.skipSegment(core.activeSegment)}
      onToggleMute={toggleMute}
      onCycleAspect={cycleAspect}
      onNudgeBack={() => { nudgeSeek(-10); showSeekFlash("left"); showControls(); }}
      onNudgeForward={() => { nudgeSeek(10); showSeekFlash("right"); showControls(); }}
      onScrub={(pos) => { scrubbing = pos !== null; if (pos !== null) showControls(); }}
      onShowControls={showControls}
    />

    <!-- ── Up-next card ─────────────────────────────────────────────────────── -->
    {#if core.showUpNext && core.nextEp}
      <MobileUpNext
        nextEp={core.nextEp}
        countdownSecs={core.countdownSecs}
        hideSpoilers={$settings?.hideSpoilers ?? false}
        onDismiss={() => (core.upNextDismissed = true)}
        onAdvance={core.advance}
      />
    {/if}
  {:else if core.streamDiscoveryPending || Player.available}
    <!-- ── Loading / buffering screen ─────────────────────────────────────── -->
    <MobileLoadingScreen
      {media}
      {title}
      logoUrl={core.logoUrl}
      loadingMessage={core.loadingMessage}
      takingAWhile={core.takingAWhile}
      failed={Player.interrupted}
      cancelVisible={core.streamDiscoveryPending}
      onclose={core.streamDiscoveryPending ? onCancelPending : onclose}
      onCancel={core.streamDiscoveryPending
        ? (onCancelPending ?? core.triggerPlaybackFailed)
        : core.triggerPlaybackFailed}
      onRetry={core.retryPlayback}
      onTryAnother={onPlaybackFailed ? core.tryAnotherStream : undefined}
    />
  {/if}

  <!-- ── Seek flash indicators (-10s / +10s) ───────────────────────────────── -->
  {#if seekFlash}
    <SeekFlash {seekFlash} />
  {/if}

</div>

<!-- ── Track sheets (fixed, rendered outside the main div) ───────────────── -->

{#if audioSheetOpen}
  <TrackSheet
    title={m.player_audio()}
    items={sortedAudio.map((t) => ({
      id: t.id,
      label: trackLabel(t, "Audio"),
    }))}
    selectedId={selectedAudio?.id ?? null}
    onSelect={(id) => core.chooseAudioTrack(id as number)}
    onClose={() => (audioSheetOpen = false)}
  />
{/if}

{#snippet subStyleFooter()}
  <div class="border-t border-white/10 px-5 pb-3 pt-3">
    <p class="pb-2 text-xs font-semibold uppercase tracking-widest text-white/40">
      {m.player_style()}
    </p>
    <div class="space-y-4">
      <div class="space-y-2">
        <div class="flex items-center justify-between text-sm">
          <span>{m.player_size()}</span>
          <span class="tabular-nums text-white/50">
            {Math.round($settings?.subtitleSize ?? 100)}%
          </span>
        </div>
        <Slider
          type="single"
          value={$settings?.subtitleSize ?? 100}
          min={50}
          max={200}
          step={10}
          onValueChange={(v) => updateSubStyle({ subtitleSize: v })}
          aria-label={m.settings_subtitle_size()}
        />
      </div>
      <div class="space-y-2">
        <div class="flex items-center justify-between text-sm">
          <span>{m.player_position()}</span>
          <span class="tabular-nums text-white/50">
            {Math.round($settings?.subtitlePosition ?? 8)}%
          </span>
        </div>
        <Slider
          type="single"
          value={$settings?.subtitlePosition ?? 8}
          min={2}
          max={90}
          step={1}
          onValueChange={(v) => updateSubStyle({ subtitlePosition: v })}
          aria-label={m.settings_subtitle_position()}
        />
      </div>
      <button
        type="button"
        class="flex w-full items-center justify-between py-1 text-sm"
        onclick={() =>
          updateSubStyle({
            subtitleBackground: !($settings?.subtitleBackground ?? false),
          })}
      >
        <span>{m.player_background_box()}</span>
        <span
          class="relative inline-flex h-6 w-10 items-center rounded-full transition-colors {($settings?.subtitleBackground ??
          false)
            ? 'bg-white/80'
            : 'bg-white/20'}"
        >
          <span
            class="inline-block size-4 rounded-full bg-neutral-900 transition-transform {($settings?.subtitleBackground ??
            false)
              ? 'translate-x-5'
              : 'translate-x-1'}"
          ></span>
        </span>
      </button>
    </div>
  </div>
{/snippet}

{#if subsSheetOpen}
  <TrackSheet
    title={m.player_subtitles()}
    items={subtitleRows}
    selectedId={selectedSubId}
    onSelect={(id) => {
      if (id === "off") {
        core.chooseSubtitle({ kind: "off" });
      } else {
        const item = subtitleItems.find((i) => i.id === id);
        if (item?.kind === "embedded") {
          core.chooseSubtitle({ kind: "embedded", id: item.id as number });
        } else if (item?.kind === "external") {
          core.chooseSubtitle({ kind: "external", id: item.id as string });
        }
      }
    }}
    onClose={() => (subsSheetOpen = false)}
    footer={subStyleFooter}
  />
{/if}

{#if speedSheetOpen}
  <TrackSheet
    title={m.player_speed()}
    items={SPEEDS.map((s) => ({ id: String(s), label: s === 1 ? "Normal (1×)" : `${s}×` }))}
    selectedId={String(Player.playbackSpeed)}
    onSelect={(id) => {
      core.chooseSpeed(parseFloat(id as string));
    }}
    onClose={() => (speedSheetOpen = false)}
  />
{/if}

{#if episodesSheetOpen && media}
  <EpisodeSheet
    {media}
    activeSeason={season}
    activeEpisode={episode}
    onclose={() => (episodesSheetOpen = false)}
    onSelect={(s, e) => {
      episodesSheetOpen = false;
      onPlayNext?.(s, e);
    }}
  />
{/if}
