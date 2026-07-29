<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import type { Stream } from "$lib/types/addons";
  import { onDestroy, untrack } from "svelte";
  import { fade } from "svelte/transition";
  import SkipSegmentButton from "./SkipSegmentButton.svelte";
  import UpNextOverlay from "./UpNextOverlay.svelte";
  import LoadingScreen from "./LoadingScreen.svelte";
  import EpisodesSidebar from "./EpisodesSidebar.svelte";
  import PlayerControls from "./PlayerControls.svelte";
  import { api } from "$lib/api";
  import { settings } from "$lib/stores/settings";
  import { Player } from "$lib/player/player.svelte";
  import type { ProgressContext } from "$lib/player/progressSaver.svelte.js";
  import { PlayerCore } from "$lib/player/playerCore.svelte";
  import { saveAspectMode, ASPECT_LABELS } from "$lib/player/aspectRatio";
  import { saveShowTrackPrefs } from "$lib/player/trackPrefs";
  import { nextAiredEpisode } from "$lib/nextEpisode";
  import { rankStreams, type StreamSelectionMode } from "$lib/streamSelection";
  import * as m from "$lib/paraglide/messages.js";

  // ─── Props (unchanged from the old Player) ──────────────────────────────────

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
    onPlayStream = undefined,
  }: {
    src?: string;
    media?: Media;
    /** Pre-session quick-play status. When present without src, this player
     * owns the stream-discovery loading state before native playback starts. */
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
    /** Fired once (per src) when playback never starts — a startup timeout
     * or a stalled core.torrent that never got peers. The caller (App.svelte)
     * decides what to do: try the next candidate stream, or give up. */
    onPlaybackFailed?: () => void;
    /** Fired when the up-next overlay's "Watch now" is clicked, its countdown
     * finishes, or the episode ends with autoplay on. Absence disables the
     * whole up-next feature (no overlay, no autoplay-core.advance) — the caller
     * (App.svelte) is what actually knows how to start the next episode. */
    onPlayNext?: (season: number, episode: number) => void;
    /** Fired when the user picks an episode from the in-player sidebar. Same
     * signature as StreamsList's onPlayStream so it can be forwarded directly.
     * Sidebar is only shown for TV shows when this prop is provided. */
    onPlayStream?: (
      stream: Stream,
      season?: number,
      episode?: number,
      episodeName?: string,
      candidates?: Stream[],
    ) => void;
    /** Fired when the user closes the player (X button). The caller
     * (App.svelte) tears down the player session. */
    onclose?: () => void;
  } = $props();

  // Internal alias that avoids the Window.onclose type conflict introduced by
  // <svelte:window> — Svelte 5 merges Window event-handler types into scope
  // when a component uses <svelte:window>, causing bare `onclose?.()` to be
  // inferred as `(ev: Event) => void` instead of `() => void`.
  const _onclose = $derived(onclose as (() => void) | undefined);
  // ── Shared playback core ──────────────────────────────────────────────────
  // Everything that behaves identically in all three shells lives in
  // $lib/player/playerCore.svelte.ts. What stays in this file is desktop-only:
  // Trakt scrobbling, the keyboard map, the OSD flash, volume/aspect controls
  // and the episodes sidebar.
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
      episodesOpen = false;
      // Trakt: stop any active scrobble for the outgoing stream before the new
      // one starts. Use traktSavedCtx (captured at 'start' time) because by the
      // time this runs the season/episode props may already reflect the new
      // episode, making core.progressCtx() return the wrong context.
      untrack(() => {
        if (traktState === "started" || traktState === "paused") {
          const stopCtx = traktSavedCtx;
          if (stopCtx) sendTraktScrobble("stop", stopCtx);
        }
        traktState = "idle";
        traktSavedCtx = null;
      });
    },
    onEnded: () => {
      if (traktState !== "stopped") {
        traktState = "stopped";
        untrack(() => sendTraktScrobble("stop"));
      }
    },
    onBeforeDestroy: () => {
      // Fire a stop scrobble if still active so the server records the resume
      // point. At destroy time props haven't changed yet, so core.progressCtx()
      // still reflects the correct episode.
      if (traktState === "started" || traktState === "paused") {
        sendTraktScrobble("stop");
      }
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

  let prefetchedNext = false; // per-src guard so the prefetch trigger fires once

  let traktState: "idle" | "started" | "paused" | "stopped" = "idle";
  // Context snapshot taken at 'start' time. The src-change effect needs the
  // OLD episode's context because by the time it fires the season/episode
  // props have already updated to the new episode.
  let traktSavedCtx: ProgressContext | null = null;

  function sendTraktScrobble(
    action: "start" | "pause" | "stop",
    ctx?: ProgressContext,
  ): void {
    if (!media || !$settings?.traktScrobbleEnabled) return;
    const c = ctx ?? core.progressCtx();
    const dur = Player.duration;
    const progress = dur > 0 ? Math.min(100, (Player.position / dur) * 100) : 0;
    api
      .traktScrobble({
        action,
        tmdb_id: c.tmdbId,
        media_type: c.mediaType,
        season: c.season,
        episode: c.episode,
        progress,
      })
      .catch(() => {});
  }

  // Send start / pause events on play-state transitions. Only core.canPlay and
  // Player.paused are tracked deps — core.progressCtx() / settings / position are
  // read inside untrack() so position ticks and settings syncs don't loop.
  $effect(() => {
    if (!core.canPlay) return;
    if (!Player.paused && traktState !== "started") {
      traktState = "started";
      untrack(() => {
        traktSavedCtx = core.progressCtx();
        sendTraktScrobble("start", traktSavedCtx);
      });
    } else if (Player.paused && traktState === "started") {
      traktState = "paused";
      untrack(() => sendTraktScrobble("pause"));
    }
  });

  // ─── Torrent download progress (SSE, hash sources only) ─────────────────────

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
        const next = await nextAiredEpisode(m.id, season, episode);
        if (!next) return; // caught up — nothing to warm

        // Single call, no retry — this is a background nicety, not something
        // worth the retry/backoff machinery fetchStreamsWithRetry (App.svelte)
        // uses for the user-facing path. Also warms the backend's own
        // per-title caches and registers direct URLs (rememberStream) — all a
        // top-ranked HTTP candidate needs; only a core.torrent winner needs the
        // extra prefetch-download call below.
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

        // Same ranking opts as quickPlay (App.svelte) so the eventual real
        // play picks the identical winner this prefetch warmed.
        const ranked = rankStreams(streams, mode, {
          measuredBandwidthMbps: bandwidth,
          preferredProvider,
          sourcePreference,
        });
        const best = ranked[0];
        if (best?.infoHash) {
          // Called even if it equals the current src — season-pack case:
          // this just queues the next file within the same swarm, and the
          // backend's single-slot bookkeeping handles a same-hash prefetch
          // fine.
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

  let lastVolume = $state(100);

  // The episodes sidebar's open state (shared with PlayerControls' toggle button).
  let episodesOpen = $state(false);
  // Any track menu / the episodes sidebar being open — reported up from
  // PlayerControls. While open, keyboard shortcuts stand down so the menu's own
  // arrow-key navigation isn't hijacked.
  let menuOpen = $state(false);

  function toggleMute(): void {
    if (Player.volume > 0) {
      lastVolume = Player.volume;
      Player.setVolume(0);
      flash("Muted");
    } else {
      const v = lastVolume || 100;
      Player.setVolume(v);
      flash(`Volume ${Math.round(v)}%`);
    }
  }

  function nudgeVolume(delta: number): void {
    const v = Math.max(0, Math.min(100, Math.round(Player.volume + delta)));
    Player.setVolume(v);
    flash(`Volume ${v}%`);
  }
  function nudgeSeek(delta: number): void {
    const target = Math.max(
      0,
      Math.min(Player.duration || Infinity, Player.position + delta),
    );
    Player.seek(target);
    flash(`${delta > 0 ? "+" : "−"}${Math.abs(delta)}s`);
  }

  function seekToFraction(frac: number): void {
    if (Player.duration) Player.seek(Player.duration * frac);
  }

  function toggleCaptions(): void {
    if (core.subSelection.kind !== "off") {
      core.chooseSubtitle({ kind: "off" });
      flash("Subtitles off");
      return;
    }
    const emb = Player.subtitleTracks[0];
    if (emb) {
      core.chooseSubtitle({ kind: "embedded", id: emb.id });
      flash("Subtitles on");
      return;
    }
    const ext = externalSubtitles[0];
    if (ext) {
      core.chooseSubtitle({ kind: "external", id: ext.id });
      flash("Subtitles on");
    }
  }

  // ─── On-screen feedback flash (so keyboard actions register even when the
  //     control bar is hidden) ─────────────────────────────────────────────────
  let feedback = $state<string | null>(null);
  let feedbackTimer: ReturnType<typeof setTimeout> | undefined;
  function flash(text: string): void {
    feedback = text;
    clearTimeout(feedbackTimer);
    feedbackTimer = setTimeout(() => (feedback = null), 700);
  }
  onDestroy(() => clearTimeout(feedbackTimer));

  function cycleAspect(): void {
    const next = Player.cycleAspectMode();
    if (media) saveAspectMode(media.id, next);
    flash(ASPECT_LABELS[next]);
  }

  // ─── Keyboard shortcuts ──────────────────────────────────────────────────────

  function isTypingTarget(t: EventTarget | null): boolean {
    const el = t as HTMLElement | null;
    if (!el || !el.tagName) return false;
    return (
      el.tagName === "INPUT" ||
      el.tagName === "TEXTAREA" ||
      el.tagName === "SELECT" ||
      el.isContentEditable
    );
  }

  function onKey(e: KeyboardEvent): void {
    // Escape cancels out of the loading/buffering screen entirely — the
    // overlay covers every other close affordance, and the shortcuts below
    // are unreachable until Player.ready anyway.
    if (e.key === "Escape" && !core.canPlay && !menuOpen && _onclose) {
      e.preventDefault();
      _onclose();
      return;
    }
    if (!Player.available || !Player.ready) return;
    // Don't steal keys from a focused field or an open picker menu.
    if (menuOpen || isTypingTarget(e.target)) return;
    if (e.ctrlKey || e.metaKey || e.altKey) return;

    let handled = true;
    switch (e.key) {
      case " ":
      case "k": {
        const willPause = !Player.paused;
        Player.togglePause();
        flash(willPause ? "Paused" : "Playing");
        break;
      }
      case "ArrowRight":
        nudgeSeek(5);
        break;
      case "ArrowLeft":
        nudgeSeek(-5);
        break;
      case "l":
        nudgeSeek(10);
        break;
      case "j":
        nudgeSeek(-10);
        break;
      case "ArrowUp":
        nudgeVolume(5);
        break;
      case "ArrowDown":
        nudgeVolume(-5);
        break;
      case "m":
        toggleMute();
        break;
      case "f":
        Player.toggleFullscreen();
        flash(Player.isFullscreen ? "Fullscreen" : "Windowed");
        break;
      case "c":
        toggleCaptions();
        break;
      case "v":
        cycleAspect();
        break;
      case "Home":
        Player.seek(0);
        break;
      case "End":
        if (Player.duration) Player.seek(Player.duration - 1);
        break;
      default:
        if (e.key >= "0" && e.key <= "9") seekToFraction(Number(e.key) / 10);
        else handled = false;
    }

    if (handled) {
      e.preventDefault();
      showControls();
    }
  }

  // ─── Subtitle selection (embedded mpv tracks + lazy external) ────────────────

  let subStyleSaveTimer: ReturnType<typeof setTimeout> | undefined;

  function updateSubStyle(patch: {
    subtitleSize?: number;
    subtitlePosition?: number;
    subtitleBackground?: boolean;
  }): void {
    const size = patch.subtitleSize ?? $settings?.subtitleSize ?? 100;
    const pos = patch.subtitlePosition ?? $settings?.subtitlePosition ?? 8;
    const bg =
      patch.subtitleBackground ?? $settings?.subtitleBackground ?? false;
    Player.setSubtitleStyle(size, pos, bg);
    clearTimeout(subStyleSaveTimer);
    subStyleSaveTimer = setTimeout(() => settings.save(patch), 400);
  }
  onDestroy(() => clearTimeout(subStyleSaveTimer));

  // ─── Persist per-show track / speed picks on explicit user action ────────────
  // Wrappers around the raw Player calls used by the control menus and the
  // captions/speed keys, so an actual user choice (not the auto-select effects)
  // is remembered for this title. Audio/subtitle are stored by language so they
  // re-match on the next episode; speed is stored verbatim.
  function chooseAudioTrack(track: { id: number; lang: string }): void {
    Player.setAudioTrack(track.id);
    if (media)
      saveShowTrackPrefs(media.id, { audioLang: track.lang || undefined });
  }

  const title = $derived(
    media ? (media.media_type === "tv" ? media.name : media.title) : "",
  );

  // ─── Controls auto-hide ──────────────────────────────────────────────────────

  let controlsVisible = $state(true);
  let hideTimer: ReturnType<typeof setTimeout> | undefined;

  function showControls(): void {
    controlsVisible = true;
    clearTimeout(hideTimer);
    if (!Player.paused)
      hideTimer = setTimeout(() => (controlsVisible = false), 3000);
  }

  onDestroy(() => clearTimeout(hideTimer));
</script>

<svelte:window onkeydown={onKey} />

<!-- Root is transparent so mpv (rendered behind the WebEngineView) shows through.
     For this to reveal video, the page background and every ancestor down to the
     video region must also be transparent — see integration notes. -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
  class="relative h-full w-full overflow-hidden"
  onmousemove={showControls}
  onclick={() => Player.togglePause()}
  onkeydown={() => {}}
  onwheel={(e) => {
    if (!menuOpen) nudgeVolume(e.deltaY < 0 ? 5 : -5);
  }}
>
  <!-- ── Bridge unavailable (running outside the Cove shell) ─────────────────── -->
  {#if !Player.available && !core.streamDiscoveryPending}
    <div class="absolute inset-0 z-30 grid place-items-center bg-black">
      <p class="rounded bg-black/60 px-4 py-2 text-sm text-red-400">
        {m.player_native_desktop_only()}
      </p>
    </div>
  {/if}

  <!-- ── Keyboard/action feedback flash ──────────────────────────────────────── -->
  {#if feedback}
    <div
      class="pointer-events-none absolute inset-0 z-20 grid place-items-center"
    >
      <div
        class="rounded-full bg-black/70 px-4 py-2 text-sm font-medium text-white backdrop-blur-sm"
        transition:fade={{ duration: 150 }}
      >
        {feedback}
      </div>
    </div>
  {/if}

  <!-- ── Controls ───────────────────────────────────────────────────────────── -->
  {#if core.canPlay}
    <div
      class="absolute inset-0 z-10 flex flex-col justify-end bg-linear-to-t from-black/85 via-black/15 to-transparent transition-opacity duration-200 {controlsVisible ||
      Player.paused
        ? 'opacity-100'
        : 'pointer-events-none opacity-0'}"
    >
      <PlayerControls
        {externalSubtitles}
        subSelection={core.subSelection}
        chapterBars={core.chapterBars}
        isHash={core.isHash}
        torrent={core.torrent}
        showEpisodes={media?.media_type === "tv" && !!onPlayStream}
        bind:episodesOpen
        {toggleMute}
        {chooseAudioTrack}
        chooseSubtitle={core.chooseSubtitle}
        {updateSubStyle}
        chooseSpeed={core.chooseSpeed}
        {cycleAspect}
        onMenuOpenChange={(o) => (menuOpen = o)}
      />
    </div>

    <!-- ── Episodes sidebar ─────────────────────────────────────────────────── -->
    {#if episodesOpen && media}
      <EpisodesSidebar
        {media}
        {season}
        {episode}
        {onPlayStream}
        onClose={() => (episodesOpen = false)}
      />
    {/if}
  {/if}

  <!-- ── Skip segment button (IntroDB) ────────────────────────────────────── -->
  {#if core.activeSegment}
    <SkipSegmentButton
      label={core.activeSegment.label}
      onSkip={() => core.skipSegment(core.activeSegment!)}
    />
  {/if}

  <!-- ── Up-next overlay (F6) ─────────────────────────────────────────────── -->
  {#if core.showUpNext && core.nextEp}
    <UpNextOverlay
      nextEp={core.nextEp}
      countdownSecs={core.countdownSecs}
      hideSpoilers={$settings?.hideSpoilers ?? false}
      onDismiss={core.dismissUpNext}
      onWatchNow={core.advance}
    />
  {/if}

  <!-- ── Loading screen ─────────────────────────────────────────────────────── -->
  {#if core.streamDiscoveryPending || (Player.available && !core.canPlay)}
    <LoadingScreen
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
      onClose={core.streamDiscoveryPending ? onCancelPending : _onclose}
    />
  {/if}
</div>
