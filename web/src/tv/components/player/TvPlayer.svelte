<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import type { Stream } from "$lib/types/addons";
  import { onDestroy, onMount, untrack, tick } from "svelte";
  import { api } from "$lib/api";
  import { settings } from "$lib/stores/settings";
  import { Player } from "$lib/player/player.svelte";
  import { PlayerCore } from "$lib/player/playerCore.svelte";
  import { saveAspectMode } from "$lib/player/aspectRatio";
  import { nextAiredEpisode } from "$lib/nextEpisode";
  import { rankStreams, type StreamSelectionMode } from "$lib/streamSelection";
  import * as m from "$lib/paraglide/messages.js";
  import { trackLabel } from "$lib/player/trackLabels";
  import {
    sortAudioTracks,
    subtitleItems as buildSubtitleItems,
    subtitleRows as buildSubtitleRows,
  } from "$lib/player/trackList";
  import TvTrackPanel from "./TvTrackPanel.svelte";
  import TvEpisodePanel from "./TvEpisodePanel.svelte";
  import TvPlayerControls from "./TvPlayerControls.svelte";
  import TvLoadingScreen from "./TvLoadingScreen.svelte";
  import TvUpNext from "./TvUpNext.svelte";
  import TvSeekFlash from "./TvSeekFlash.svelte";
  import { focusAfterKeyRelease } from "../../focus/focusStore.svelte";

  // ── Props (identical contract to MobilePlayer) ──────────────────────────────

  let {
    src = "",
    media,
    pendingMessage = undefined,
    onCancelPending = undefined,
    externalSubtitles = [],
    season = undefined,
    episode = undefined,
    fileIdx = undefined,
    automaticStartupRecovery = true,
    onPlaybackFailed = undefined,
    onPlayNext = undefined,
    onPlayStream: _onPlayStream = undefined,
    onclose = undefined,
    onRegisterCloseSheets = undefined,
  }: {
    src?: string;
    media?: Media;
    pendingMessage?: string;
    onCancelPending?: () => void;
    externalSubtitles?: { id: string; url: string; lang: string }[];
    season?: number;
    episode?: number;
    /** Addon-supplied 0-based raw file index for season-pack torrents (Stremio
     * fileIdx). When present, the backend skips regex matching and plays this
     * exact file — more reliable than pattern matching for Torrentio packs. */
    fileIdx?: number;
    automaticStartupRecovery?: boolean;
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
  // $lib/player/playerCore.svelte.ts. What stays here is TV-only: the D-pad
  // panels, the seek-bar scrubbing model and the focus handling.
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
    getAutomaticStartupRecovery: () => automaticStartupRecovery,
    onPlaybackFailed: () => onPlaybackFailed?.(),
    onPlayNext: (s, e) => onPlayNext?.(s, e),
    hasPlayNext: () => !!onPlayNext,
    onSrcChange: () => {
      prefetchedNext = false;
      scrubbing = false;
      scrubValue = 0;
      // Reset playback speed on new stream.
      Player.setPlaybackSpeed(1);
    },
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
      if (
        audioPanelOpen ||
        subsPanelOpen ||
        speedPanelOpen ||
        episodesPanelOpen
      ) {
        audioPanelOpen = false;
        subsPanelOpen = false;
        speedPanelOpen = false;
        episodesPanelOpen = false;
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
    const mode =
      ($settings?.streamSelectionMode as StreamSelectionMode) ?? "balanced";
    const bandwidth = $settings?.measuredBandwidthMbps;
    const preferredProvider = $settings?.defaultProvider;
    const sourcePreference = $settings?.sourcePreference;
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
        });
        const best = ranked[0];
        if (best?.infoHash) {
          api
            .prefetchDownload(best.infoHash, {
              season: next.season,
              episode: next.episode.episode_number,
              fileIdx: best.fileIdx,
            })
            .catch(() => {});
        }
      })();
    });
  });

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
    media
      ? media.media_type === "tv"
        ? (media.name ?? "")
        : (media.title ?? "")
      : "",
  );

  const episodeLabel = $derived.by(() => {
    if (media?.media_type !== "tv" || season == null || episode == null)
      return "";
    return `S${season}E${episode}`;
  });

  const selectedAudio = $derived(Player.audioTracks.find((t) => t.selected));

  // ── Speed control ─────────────────────────────────────────────────────────────
  const SPEEDS = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0];

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
    const target = Math.max(
      0,
      Math.min(Player.duration || Infinity, Player.position + delta),
    );
    Player.seek(target);
  }

  function cycleAspect(): void {
    const next = Player.cycleAspectMode();
    if (media) saveAspectMode(media.id, next);
  }

  // ── Seek bar (keyboard-friendly: focusable div with key handling) ─────────────

  let scrubbing = $state(false);
  let scrubValue = $state(0);
  const displayPos = $derived(scrubbing ? scrubValue : Player.position);

  // ── Panel open state ──────────────────────────────────────────────────────────

  let audioPanelOpen = $state(false);
  let subsPanelOpen = $state(false);
  let speedPanelOpen = $state(false);
  let episodesPanelOpen = $state(false);

  const anyPanelOpen = $derived(
    audioPanelOpen || subsPanelOpen || speedPanelOpen || episodesPanelOpen,
  );

  // ── Controls auto-hide ────────────────────────────────────────────────────────

  let controlsVisible = $state(true);
  let hideTimer: ReturnType<typeof setTimeout> | undefined;

  const controlsActive = $derived(
    controlsVisible || Player.paused || !core.canPlay || anyPanelOpen,
  );

  function showControls(): void {
    controlsVisible = true;
    clearTimeout(hideTimer);
    if (!Player.paused && !scrubbing && !anyPanelOpen) {
      hideTimer = setTimeout(() => {
        controlsVisible = false;
        // When auto-hide fires, check if focus is inside the control bar and
        // move it away so the capture handler treats controls as hidden.
        const bar = controlBarEl;
        if (bar && bar.contains(document.activeElement)) {
          (document.activeElement as HTMLElement).blur();
        }
      }, 5000);
    }
  }

  // Keep controls visible while paused, buffering, or any panel is open.
  $effect(() => {
    if (Player.paused || !core.canPlay || anyPanelOpen) {
      clearTimeout(hideTimer);
      controlsVisible = true;
    }
  });

  onDestroy(() => clearTimeout(hideTimer));

  // ── Control bar element ref (for focus containment checks) ───────────────────

  let controlBarEl = $state<HTMLDivElement | null>(null);
  let playPauseBtn = $state<HTMLButtonElement | null>(null);

  // Focus play/pause and reset auto-hide. Focus waits for the opening key's
  // release (focusAfterKeyRelease) so the Enter press that revealed the
  // controls can't also toggle pause.
  function focusPlayPause(): void {
    showControls();
    tick().then(() => {
      focusAfterKeyRelease(() => playPauseBtn);
    });
  }

  // ── TV remote keydown handler (capture phase — runs before TvApp bubble) ──────
  //
  // Attached via addEventListener in onMount so we can use capture: true.
  // This lets TvPlayer intercept keys before TvApp's onkeydown (bubble phase).

  function handleKeydownCapture(e: KeyboardEvent): void {
    // Only act when a player session is active (src is set and Player available).
    if (!src || !Player.available) return;

    // Never intercept when any track panel is open — TvTrackPanel handles
    // its own Escape and the focus engine handles arrow keys inside it.
    if (anyPanelOpen) return;

    const focusInBar =
      controlBarEl != null && controlBarEl.contains(document.activeElement);

    if (e.key === "Escape") {
      if (!core.canPlay) {
        // Loading screen is visible — let Escape propagate so TvApp's handler
        // closes the player instead of invisibly toggling the controls layer.
        return;
      }
      if (controlsActive && !focusInBar) {
        // Controls visible but focus not in bar — hide controls and consume.
        controlsVisible = false;
        clearTimeout(hideTimer);
        e.preventDefault();
        e.stopPropagation();
        return;
      }
      if (focusInBar) {
        // Focus is in the bar — hide controls, consume.
        controlsVisible = false;
        clearTimeout(hideTimer);
        (document.activeElement as HTMLElement | null)?.blur();
        e.preventDefault();
        e.stopPropagation();
        return;
      }
      // Controls hidden: let Escape propagate → TvApp closes the player (step 5).
      return;
    }

    if (!controlsActive || !focusInBar) {
      // Controls hidden, OR visible without focus in the bar (seek-flash
      // window): intercept arrow/enter keys for seek/show. Without the
      // focusInBar clause a second seek press during the 1.5s flash would
      // fall through to the global focus engine and navigate the page
      // behind the player.
      switch (e.key) {
        case "ArrowLeft":
          nudgeSeek(-10);
          showSeekFlash("left");
          // Flash controls briefly but don't focus anything (controls will auto-hide).
          controlsVisible = true;
          clearTimeout(hideTimer);
          hideTimer = setTimeout(() => (controlsVisible = false), 1500);
          e.preventDefault();
          e.stopPropagation();
          return;
        case "ArrowRight":
          nudgeSeek(10);
          showSeekFlash("right");
          controlsVisible = true;
          clearTimeout(hideTimer);
          hideTimer = setTimeout(() => (controlsVisible = false), 1500);
          e.preventDefault();
          e.stopPropagation();
          return;
        case "Enter":
        case "ArrowUp":
          focusPlayPause();
          e.preventDefault();
          e.stopPropagation();
          return;
        case "ArrowDown":
          // No-op when controls hidden.
          e.preventDefault();
          e.stopPropagation();
          return;
      }
      return;
    }

    // Controls visible: let the focus engine (via TvApp bubble handler) move
    // focus within the control bar. Exception: seekbar-focused arrow keys and
    // ArrowDown to hide controls — handled in the bar's own onkeydown below.
    // We only intercept here to reset the auto-hide timer on any key.
    if (focusInBar) {
      // Reset auto-hide on any keydown while controls visible and focused.
      showControls();
    }
  }

  onMount(() => {
    window.addEventListener("keydown", handleKeydownCapture, true);
    return () => {
      window.removeEventListener("keydown", handleKeydownCapture, true);
    };
  });

  // ── Control bar keydown (bar-level: ArrowDown to hide, seekbar arrow scrub) ──

  function handleBarKeydown(e: KeyboardEvent): void {
    if (e.key === "ArrowDown") {
      controlsVisible = false;
      clearTimeout(hideTimer);
      (document.activeElement as HTMLElement | null)?.blur();
      e.stopPropagation();
      e.preventDefault();
    }
  }

  function handleSeekbarKeydown(e: KeyboardEvent): void {
    if (e.key === "ArrowLeft") {
      nudgeSeek(-10);
      showControls();
      e.stopPropagation();
      e.preventDefault();
    } else if (e.key === "ArrowRight") {
      nudgeSeek(10);
      showControls();
      e.stopPropagation();
      e.preventDefault();
    }
  }

  // ── Focus management for skip/up-next overlays ────────────────────────────────
  // When the IntroDB skip button appears and controls are hidden, focus it.
  // When up-next appears and controls are hidden, focus the "Watch now" button.

  let skipBtnEl = $state<HTMLButtonElement | null>(null);
  let upNextPlayBtnEl = $state<HTMLButtonElement | null>(null);

  $effect(() => {
    if (core.activeSegment && skipBtnEl && !controlsActive) {
      tick().then(() => skipBtnEl?.focus({ preventScroll: true }));
    }
  });

  $effect(() => {
    if (core.showUpNext && upNextPlayBtnEl && !controlsActive) {
      tick().then(() => upNextPlayBtnEl?.focus({ preventScroll: true }));
    }
  });
</script>

<!--
  Root: fully transparent — mpv renders behind the WebView and shows through.
-->
<div class="relative h-full w-full overflow-hidden">
  <!-- ── Bridge unavailable ──────────────────────────────────────────────────── -->
  {#if !Player.available && !core.streamDiscoveryPending}
    <div class="absolute inset-0 z-30 grid place-items-center bg-black">
      <p class="rounded bg-black/60 px-4 py-2 text-sm text-red-400">
        {m.player_native_unavailable()}
      </p>
    </div>
  {/if}

  <!-- ── Controls overlay + up-next ────────────────────────────────────────── -->
  {#if core.canPlay}
    <TvPlayerControls
      {title}
      {episodeLabel}
      {controlsActive}
      activeSegment={core.activeSegment}
      bind:skipBtnEl
      onSkipSegment={() => core.skipSegment(core.activeSegment!)}
      chapterBars={core.chapterBars}
      isHash={core.isHash}
      torrentProgress={core.torrent.progress}
      {displayPos}
      onSeekbarKeydown={handleSeekbarKeydown}
      onSeekBack={() => {
        nudgeSeek(-10);
        showSeekFlash("left");
        showControls();
      }}
      bind:playPauseBtn
      onPlayPause={() => {
        Player.togglePause();
        showControls();
      }}
      onSeekForward={() => {
        nudgeSeek(10);
        showSeekFlash("right");
        showControls();
      }}
      bind:audioPanelOpen
      {subtitleItems}
      {selectedSubId}
      subSelection={core.subSelection}
      hasSubtitles={Player.subtitleTracks.length > 0 ||
        externalSubtitles.length > 0}
      bind:subsPanelOpen
      bind:speedPanelOpen
      onCycleAspect={cycleAspect}
      {media}
      {onPlayNext}
      {onclose}
      bind:episodesPanelOpen
      bind:barEl={controlBarEl}
      onBarKeydown={handleBarKeydown}
    />

    <!-- ── Up-next card ─────────────────────────────────────────────────────── -->
    {#if core.showUpNext && core.nextEp}
      <TvUpNext
        nextEp={core.nextEp}
        countdownSecs={core.countdownSecs}
        hideSpoilers={$settings?.hideSpoilers ?? false}
        onDismiss={() => (core.upNextDismissed = true)}
        onWatchNow={() => core.advance()}
        bind:watchNowBtnEl={upNextPlayBtnEl}
      />
    {/if}
  {:else}
    <!-- ── Loading / buffering screen ───────────────────────────────────────── -->
    {#if core.streamDiscoveryPending || Player.available}
      <TvLoadingScreen
        {media}
        {title}
        logoUrl={core.logoUrl}
        loadingMessage={core.loadingMessage}
        takingAWhile={core.takingAWhile}
        failed={Player.interrupted}
        cancelVisible={core.streamDiscoveryPending}
        onCancel={core.streamDiscoveryPending
          ? (onCancelPending ?? core.triggerPlaybackFailed)
          : core.triggerPlaybackFailed}
        onRetry={core.retryPlayback}
        onTryAnother={onPlaybackFailed ? core.tryAnotherStream : undefined}
      />
    {/if}
  {/if}

  <!-- ── Seek flash indicators ─────────────────────────────────────────────── -->
  {#if seekFlash}
    <TvSeekFlash {seekFlash} />
  {/if}
</div>

<!-- ── Track panels (fixed, rendered outside the main div) ───────────────── -->

{#if audioPanelOpen}
  <TvTrackPanel
    title={m.player_audio()}
    items={sortedAudio.map((t) => ({
      id: t.id,
      label: trackLabel(t, "Audio"),
    }))}
    selectedId={selectedAudio?.id ?? null}
    onSelect={(id) => core.chooseAudioTrack(id as number)}
    onClose={() => (audioPanelOpen = false)}
  />
{/if}

{#if subsPanelOpen}
  <TvTrackPanel
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
    onClose={() => (subsPanelOpen = false)}
  />
{/if}

{#if speedPanelOpen}
  <TvTrackPanel
    title={m.player_speed()}
    items={SPEEDS.map((s) => ({
      id: String(s),
      label: s === 1 ? "Normal (1×)" : `${s}×`,
    }))}
    selectedId={String(Player.playbackSpeed)}
    onSelect={(id) => {
      core.chooseSpeed(parseFloat(id as string));
    }}
    onClose={() => (speedPanelOpen = false)}
  />
{/if}

{#if episodesPanelOpen && media}
  <TvEpisodePanel
    {media}
    activeSeason={season}
    activeEpisode={episode}
    onClose={() => (episodesPanelOpen = false)}
    onSelect={(s, e) => {
      episodesPanelOpen = false;
      onPlayNext?.(s, e);
    }}
  />
{/if}
