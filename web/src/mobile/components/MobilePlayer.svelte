<script lang="ts">
  import type { Media, TVEpisode } from "$lib/types/tmdb";
  import type { Stream, TimestampData, TimestampSegment } from "$lib/types/addons";
  import { Spinner } from "$lib/components/ui/spinner";
  import {
    Play,
    Pause,
    Volume2,
    VolumeX,
    Headphones,
    Captions,
    X,
    SkipForward,
    SkipBack,
    Maximize2,
    Minimize2,
    Gauge,
  } from "lucide-svelte";
  import { onDestroy, untrack } from "svelte";
  import { fade } from "svelte/transition";
  import { api } from "$lib/api";
  import { settings } from "$lib/stores/settings";
  import { Player } from "$lib/player/player.svelte";
  import {
    ProgressSaver,
    type ProgressContext,
  } from "$lib/player/progressSaver.svelte.js";
  import { TorrentProgress } from "$lib/player/torrentProgress.svelte.js";
  import { langMatches } from "$lib/lang";
  import { nextAiredEpisode } from "$lib/nextEpisode";
  import { rankStreams, type StreamSelectionMode } from "$lib/streamSelection";
  import { SvelteSet } from "svelte/reactivity";
  import { libraryChanged } from "$lib/stores/library";
  import TrackSheet from "./TrackSheet.svelte";

  // ── Props (same contract as desktop Player + mobile-specific additions) ──────

  let {
    src,
    media,
    externalSubtitles = [],
    season = undefined,
    episode = undefined,
    onPlaybackFailed = undefined,
    onPlayNext = undefined,
    onPlayStream: _onPlayStream = undefined,
    onclose = undefined,
    /** Parent registers a close-sheets callback for Escape priority handling. */
    onRegisterCloseSheets = undefined,
  }: {
    src: string;
    media?: Media;
    externalSubtitles?: { id: string; url: string; lang: string }[];
    season?: number;
    episode?: number;
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

  // Register close-sheets with parent for Escape priority handling.
  $effect(() => {
    onRegisterCloseSheets?.(() => {
      if (audioSheetOpen || subsSheetOpen || speedSheetOpen) {
        audioSheetOpen = false;
        subsSheetOpen = false;
        speedSheetOpen = false;
        return true;
      }
      return false;
    });
  });

  // ── Playback lifecycle ───────────────────────────────────────────────────────

  let appliedAudioDefault = false;
  let appliedSubDefault = false;
  const addedExternal = new SvelteSet<string>();

  let originalLang = $state<string | null>(null);

  // Up-next overlay state
  let nextEp = $state<{ season: number; episode: TVEpisode } | null>(null);
  let upNextDismissed = $state(false);
  let advanced = false;
  let countdownSecs = $state<number | null>(null);
  let resolvingNextEp = false;

  // Background prefetch guard
  let prefetchedNext = false;

  $effect(() => {
    if (!src || !Player.available) return;
    switching = true;
    scrubbing = false;
    scrubValue = 0;
    appliedAudioDefault = false;
    appliedSubDefault = false;
    originalLang = null;
    nextEp = null;
    upNextDismissed = false;
    advanced = false;
    countdownSecs = null;
    resolvingNextEp = false;
    prefetchedNext = false;
    addedExternal.clear();
    autoSkippedSegments.clear();
    subSelection = { kind: "off" };

    untrack(() => {
      if ($settings?.openOnMute) {
        Player.setVolume(0);
      } else if ($settings?.defaultVolume != null) {
        Player.setVolume(Math.round($settings.defaultVolume * 100));
      }
    });
    Player.play(api.playUrl(src, { season, episode }));
  });

  // Resolve original_language for "original" audio preference.
  $effect(() => {
    if (!src || $settings?.defaultAudioLang !== "original") return;
    if (originalLang !== null) return;
    const m = media;
    if (!m) return;
    if (m.original_language) {
      originalLang = m.original_language;
      return;
    }
    const requestedSrc = src;
    untrack(() => {
      api
        .getMediaByID(m.id, m.media_type)
        .then((full) => {
          if (src !== requestedSrc) return;
          originalLang = full.original_language ?? "";
        })
        .catch(() => {
          if (src !== requestedSrc) return;
          originalLang = "";
        });
    });
  });

  let switching = $state(false);

  $effect(() => {
    if (switching && Player.ready && Player.duration > 0) {
      switching = false;
    }
  });

  onDestroy(() => {
    if (!Player.available) return;
    try {
      if (media && Player.duration > 0) {
        progress.saveNow(Player.position, Player.duration, progressCtx, false);
        libraryChanged.update((n) => n + 1);
      }
    } catch (e) {
      console.error(e);
    }
    Player.stop();
  });

  const canPlay = $derived(!switching && Player.ready && Player.duration > 0);

  // ── Playback-start watchdog ──────────────────────────────────────────────────

  let failedFired = false;
  let everCanPlay = false;
  let takingAWhile = $state(false);

  function triggerPlaybackFailed(): void {
    if (failedFired || everCanPlay) return;
    failedFired = true;
    onPlaybackFailed?.();
  }

  $effect(() => {
    if (!src || !Player.available) return;
    failedFired = false;
    everCanPlay = false;
    takingAWhile = false;

    const isHashSrc = !src.startsWith("http");
    const failTimeoutMs = isHashSrc ? 50_000 : 25_000;
    const failTimer = setTimeout(triggerPlaybackFailed, failTimeoutMs);
    const slowTimer = setTimeout(() => { takingAWhile = true; }, 15_000);

    return () => {
      clearTimeout(failTimer);
      clearTimeout(slowTimer);
    };
  });

  $effect(() => {
    if (canPlay) {
      everCanPlay = true;
      takingAWhile = false;
    }
  });

  $effect(() => {
    if (torrent.stalled && !canPlay) {
      triggerPlaybackFailed();
    }
  });

  // ── Watch progress ───────────────────────────────────────────────────────────

  const progress = new ProgressSaver();

  function progressCtx(): ProgressContext {
    return {
      tmdbId: media!.id,
      mediaType: media!.media_type,
      title,
      posterPath: media!.poster_path ?? "",
      voteAverage: media!.vote_average ?? 0,
      lastAirDate: (media as { last_air_date?: string }).last_air_date ?? "",
      season: season ?? null,
      episode: episode ?? null,
      probedDuration: null,
    };
  }

  $effect(() => {
    if (!media || !src) return;
    progress.reset();
    if ($settings?.rememberPosition === false) return;
    progress.load(media.id, media.media_type, season ?? null, episode ?? null);
  });

  $effect(() => {
    if (!canPlay) return;
    progress.resume((t) => Player.seek(t));
  });

  $effect(() => {
    const pos = Player.position;
    if (!canPlay || !media || Player.paused) return;
    progress.maybeSave(pos, Player.duration, progressCtx);
  });

  $effect(() => {
    if (Player.ended && media) {
      progress.saveNow(Player.duration, Player.duration, progressCtx, true);
      libraryChanged.update((n) => n + 1);
    }
  });

  // ── Torrent download progress (hash sources) ─────────────────────────────────

  const isHash = $derived(!src.startsWith("http"));
  const torrent = new TorrentProgress();

  $effect(() => {
    if (!isHash) return;
    return torrent.start(src, { season, episode });
  });

  // ── Background next-episode prefetch ─────────────────────────────────────────

  $effect(() => {
    if (
      $settings?.prefetchNextEpisode === false ||
      media?.media_type !== "tv" ||
      season == null ||
      episode == null ||
      !isHash ||
      torrent.progress < 100 ||
      prefetchedNext
    )
      return;
    prefetchedNext = true;
    const m = media;
    const mode = ($settings?.streamSelectionMode as StreamSelectionMode) ?? "balanced";
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
            })
            .catch(() => {});
        }
      })();
    });
  });

  const loadingMessage = $derived(
    isHash
      ? torrent.peers > 0
        ? `Connecting · ${torrent.peers} peers · ${torrent.speed}`
        : "Connecting to peers…"
      : "Buffering…",
  );

  // ── Logo ─────────────────────────────────────────────────────────────────────

  let logoUrl = $state<string | null>(null);

  $effect(() => {
    const m = media;
    if (!m) { logoUrl = null; return; }
    logoUrl = null;
    api.getLogos(m.id, m.media_type).then((logos) => {
      logoUrl = logos[0] ?? null;
    }).catch(() => {});
  });

  // ── IntroDB timestamps ────────────────────────────────────────────────────────

  let timestamps = $state<TimestampData | null>(null);
  const autoSkippedSegments = new SvelteSet<string>();

  $effect(() => {
    const m = media;
    if (!m) { timestamps = null; return; }
    timestamps = null;
    api.getTimestamps(m.id, { season, episode }).then((data) => {
      timestamps = data;
    }).catch((e) => {
      console.warn("[introdb] fetch failed:", e);
    });
  });

  const activeSegment = $derived.by(() => {
    if (!timestamps || !canPlay) return null;
    const posMs = Player.position * 1000;

    const check = (
      segs: TimestampSegment[] | undefined,
      type: string,
      label: string,
    ) => {
      if (!segs?.length) return null;
      for (const seg of segs) {
        const start = seg.start_ms ?? 0;
        const end = seg.end_ms ?? Player.duration * 1000;
        if (posMs >= start && posMs < end) return { type, label, seg };
      }
      return null;
    };

    return (
      check(timestamps.recap, "recap", "Recap") ||
      check(timestamps.intro, "intro", "Intro") ||
      check(timestamps.credits, "credits", "Credits") ||
      check(timestamps.preview, "preview", "Preview")
    );
  });

  $effect(() => {
    const seg = activeSegment;
    if (!seg || !$settings) return;

    const segKey = `${seg.type}-${seg.seg.start_ms ?? 0}`;
    if (autoSkippedSegments.has(segKey)) return;

    const shouldSkip =
      (seg.type === "intro" && $settings.autoSkipIntro) ||
      (seg.type === "recap" && $settings.autoSkipRecap) ||
      (seg.type === "credits" && $settings.autoSkipCredits) ||
      (seg.type === "preview" && $settings.autoSkipPreview);

    if (shouldSkip) {
      autoSkippedSegments.add(segKey);
      Player.seek((seg.seg.end_ms ?? Player.duration * 1000) / 1000);
    }
  });

  function skipSegment(seg: { seg: TimestampSegment }): void {
    Player.seek((seg.seg.end_ms ?? Player.duration * 1000) / 1000);
  }

  // ── Auto-select preferred audio track ────────────────────────────────────────

  $effect(() => {
    if (appliedAudioDefault || Player.audioTracks.length <= 1) return;
    const setting = $settings?.defaultAudioLang;
    if (!setting) return;
    if (setting === "original") {
      if (originalLang === null) return;
      if (originalLang === "") {
        appliedAudioDefault = true;
        return;
      }
    }
    const targetLang = setting === "original" ? originalLang : setting;
    appliedAudioDefault = true;
    const match = Player.audioTracks.find((t) => langMatches(t.lang, targetLang));
    if (match && !match.selected) Player.setAudioTrack(match.id);
  });

  // ── Auto-select preferred subtitle track ─────────────────────────────────────

  $effect(() => {
    if (appliedSubDefault || !canPlay) return;
    if (!$settings?.subtitlesEnabled) return;
    appliedSubDefault = true;
    const lang = $settings.defaultSubtitleLang;

    const embedded = Player.subtitleTracks.find((t) => langMatches(t.lang, lang));
    if (embedded) {
      selectSubtitle({ kind: "embedded", id: embedded.id });
      return;
    }
    const ext =
      externalSubtitles.find((s) => langMatches(s.lang, lang)) ?? externalSubtitles[0];
    if (ext) selectSubtitle({ kind: "external", id: ext.id });
  });

  // ── Up-next overlay + autoplay countdown ─────────────────────────────────────

  $effect(() => {
    if (
      !src ||
      !canPlay ||
      nextEp !== null ||
      resolvingNextEp ||
      media?.media_type !== "tv" ||
      season == null ||
      episode == null
    )
      return;
    resolvingNextEp = true;
    const requestedSrc = src;
    const id = media.id;
    untrack(() => {
      nextAiredEpisode(id, season!, episode!)
        .then((next) => {
          if (src !== requestedSrc) return;
          nextEp = next;
        })
        .catch(() => {})
        .finally(() => {
          if (src === requestedSrc) resolvingNextEp = false;
        });
    });
  });

  const showUpNext = $derived(
    !!nextEp &&
      !upNextDismissed &&
      !!onPlayNext &&
      (activeSegment?.type === "credits" ||
        (Player.duration > 0 && Player.duration - Player.position < 40 && canPlay) ||
        Player.ended),
  );

  $effect(() => {
    if (!showUpNext || !$settings?.autoPlay || advanced) {
      untrack(() => (countdownSecs = null));
      return;
    }
    untrack(() => (countdownSecs = 10));
    const interval = setInterval(() => {
      if (countdownSecs === null) return;
      if (countdownSecs <= 1) {
        countdownSecs = 0;
        clearInterval(interval);
        advance();
        return;
      }
      countdownSecs -= 1;
    }, 1000);
    return () => clearInterval(interval);
  });

  $effect(() => {
    if (
      Player.ended &&
      everCanPlay &&
      $settings?.autoPlay &&
      nextEp &&
      !upNextDismissed &&
      !advanced
    ) {
      advance();
    }
  });

  function advance(): void {
    if (advanced || !nextEp || !onPlayNext) return;
    advanced = true;
    if (media && Player.duration > 0) {
      progress.saveNow(Player.duration, Player.duration, progressCtx, true);
    }
    onPlayNext(nextEp.season, nextEp.episode.episode_number);
  }

  // ── Subtitle selection ────────────────────────────────────────────────────────

  type SubSel =
    | { kind: "off" }
    | { kind: "embedded"; id: number }
    | { kind: "external"; id: string };

  let subSelection = $state<SubSel>({ kind: "off" });

  function selectSubtitle(sel: SubSel): void {
    subSelection = sel;
    if (sel.kind === "off") {
      Player.setSubtitleTrack(-1);
      return;
    }
    if (sel.kind === "embedded") {
      Player.setSubtitleTrack(sel.id);
      return;
    }
    const ext = externalSubtitles.find((s) => s.id === sel.id);
    if (!ext) return;
    if (addedExternal.has(ext.id)) {
      const t = Player.subtitleTracks.find((x) => x.lang === ext.lang);
      if (t) Player.setSubtitleTrack(t.id);
    } else {
      addedExternal.add(ext.id);
      Player.addSubtitle(
        api.subtitleProxyUrl(ext.url),
        ext.lang.toUpperCase(),
        ext.lang,
      );
    }
  }

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

  function trackLabel(
    t: { id: number; title: string; lang: string },
    kind: "Audio" | "Subtitle",
  ): string {
    if (t.title) return t.title;
    if (t.lang) return langName(t.lang);
    return `${kind} ${t.id}`;
  }

  const sortedAudio = $derived(
    [...Player.audioTracks].sort((a, b) =>
      trackLabel(a, "Audio").localeCompare(trackLabel(b, "Audio")),
    ),
  );

  // Flat subtitle item list: Off + embedded + external.
  type SubItem =
    | { kind: "off"; id: "off"; label: string }
    | { kind: "embedded"; id: number; label: string }
    | { kind: "external"; id: string; label: string };

  const subtitleItems = $derived.by((): SubItem[] => {
    const items: SubItem[] = [{ kind: "off", id: "off", label: "Off" }];
    for (const t of Player.subtitleTracks) {
      items.push({ kind: "embedded", id: t.id, label: trackLabel(t, "Subtitle") });
    }
    for (const s of externalSubtitles) {
      items.push({
        kind: "external",
        id: s.id,
        label: `${langName(s.lang)} · OpenSubtitles`,
      });
    }
    return items;
  });

  const selectedSubId = $derived.by((): string | number => {
    const sel = subSelection;
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

  // ── Seek bar (pointer-based, adapted for touch with larger hit area) ──────────

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
    showControls();
  }

  function onSeekPointerMove(e: PointerEvent): void {
    if (!scrubbing) return;
    scrubValue = seekFraction(e) * Player.duration;
  }

  function onSeekPointerUp(e: PointerEvent): void {
    if (!scrubbing) return;
    Player.seek(seekFraction(e) * Player.duration);
    scrubbing = false;
  }

  // ── Sheet open state ──────────────────────────────────────────────────────────
  // Must be declared before controlsActive which references them.

  let audioSheetOpen = $state(false);
  let subsSheetOpen = $state(false);
  let speedSheetOpen = $state(false);

  // ── Controls auto-hide ────────────────────────────────────────────────────────

  let controlsVisible = $state(true);
  let hideTimer: ReturnType<typeof setTimeout> | undefined;

  // Controls are "active" (visible + interactive) if explicitly shown, paused, or
  // not yet playing. Sheet-open always keeps them active so scrims don't vanish
  // behind an open sheet.
  const controlsActive = $derived(
    controlsVisible || Player.paused || !canPlay || audioSheetOpen || subsSheetOpen || speedSheetOpen,
  );

  function showControls(): void {
    controlsVisible = true;
    clearTimeout(hideTimer);
    if (!Player.paused && !scrubbing && !audioSheetOpen && !subsSheetOpen && !speedSheetOpen) {
      hideTimer = setTimeout(() => (controlsVisible = false), 3000);
    }
  }

  // Keep controls visible while paused, buffering, or any sheet is open.
  $effect(() => {
    if (Player.paused || !canPlay || audioSheetOpen || subsSheetOpen || speedSheetOpen) {
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
  // Touch events on control buttons are stopPropagation'd, so only taps on the
  // transparent overlay area reach this handler.

  let containerEl = $state<HTMLDivElement | null>(null);
  let tapState: { time: number; x: number } | null = null;

  function handleTouchEnd(e: TouchEvent): void {
    const touch = e.changedTouches[0];
    if (!touch) return;

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
  {#if !Player.available}
    <div class="absolute inset-0 z-30 grid place-items-center bg-black">
      <p class="rounded bg-black/60 px-4 py-2 text-sm text-red-400">
        Native player unavailable.
      </p>
    </div>
  {/if}

  <!-- ── Controls overlay ───────────────────────────────────────────────────── -->
  {#if canPlay}
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
        aria-label="Top controls"
      >
        <!-- Close button (44px touch target) -->
        <button
          type="button"
          class="flex size-11 items-center justify-center rounded-full text-white active:bg-white/20"
          onclick={() => onclose?.()}
          aria-label="Close player"
        >
          <X class="size-6" />
        </button>

        <!-- Title + episode label -->
        <div class="flex min-w-0 flex-1 flex-col items-center px-3 pt-1">
          <p class="max-w-full truncate text-center text-sm font-semibold text-white drop-shadow">
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
          onclick={() => { nudgeSeek(-10); showSeekFlash("left"); showControls(); }}
          aria-label="Seek back 10 seconds"
        >
          <SkipBack class="size-6" />
        </button>

        <!-- Play / Pause (64px) -->
        <button
          type="button"
          class="flex size-16 items-center justify-center rounded-full bg-white/20 text-white backdrop-blur-sm active:bg-white/35"
          onclick={() => { Player.togglePause(); showControls(); }}
          aria-label={Player.paused ? "Play" : "Pause"}
        >
          {#if Player.paused}
            <Play class="size-8 translate-x-0.5" />
          {:else}
            <Pause class="size-8" />
          {/if}
        </button>

        <!-- Seek +10s (48px target) -->
        <button
          type="button"
          class="flex size-12 items-center justify-center rounded-full text-white active:bg-white/20"
          onclick={() => { nudgeSeek(10); showSeekFlash("right"); showControls(); }}
          aria-label="Seek forward 10 seconds"
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
              onclick={() => skipSegment(activeSegment!)}
            >
              Skip {activeSegment.label}
            </button>
          </div>
        {/if}

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
          <!-- Track background -->
          <div class="absolute inset-x-0 top-1/2 h-1.5 -translate-y-1/2 overflow-hidden rounded-full bg-white/25">
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
          <!-- Thumb (24px) -->
          <div
            class="pointer-events-none absolute top-1/2 size-6 -translate-x-1/2 -translate-y-1/2 rounded-full bg-white shadow-md ring-1 ring-black/20"
            style="left: {Player.duration ? (displayPos / Player.duration) * 100 : 0}%"
          ></div>
        </div>

        <!-- Time row -->
        <div class="mb-2 mt-1 flex items-center justify-between text-xs tabular-nums text-white/80">
          <span>{fmt(displayPos)}</span>
          <span class="text-white/40">{fmt(Player.duration)}</span>
        </div>

        <!-- Bottom button row -->
        <div class="flex items-center gap-1">
          <!-- Audio tracks (only if multiple tracks available) -->
          {#if Player.audioTracks.length > 0}
            <button
              type="button"
              class="flex min-h-[44px] items-center gap-1.5 rounded-lg px-3 py-2 text-white active:bg-white/15"
              onclick={() => { audioSheetOpen = true; showControls(); }}
              aria-label="Audio tracks"
            >
              <Headphones class="size-4 shrink-0" />
              <span class="max-w-20 truncate text-xs">
                {selectedAudio?.title || langName(selectedAudio?.lang ?? "") || "Audio"}
              </span>
            </button>
          {/if}

          <!-- Subtitles -->
          {#if Player.subtitleTracks.length > 0 || externalSubtitles.length > 0}
            <button
              type="button"
              class="flex min-h-[44px] items-center gap-1.5 rounded-lg px-3 py-2 text-white active:bg-white/15"
              onclick={() => { subsSheetOpen = true; showControls(); }}
              aria-label="Subtitles"
            >
              <Captions class="size-4 shrink-0" />
              <span class="max-w-20 truncate text-xs">
                {subSelection.kind === "off"
                  ? "Subs"
                  : (subtitleItems.find((i) => i.id === selectedSubId)?.label ?? "Subs")}
              </span>
            </button>
          {/if}

          <!-- Playback speed -->
          <button
            type="button"
            class="flex min-h-[44px] items-center gap-1.5 rounded-lg px-3 py-2 text-white active:bg-white/15"
            onclick={() => { speedSheetOpen = true; showControls(); }}
            aria-label="Playback speed"
          >
            <Gauge class="size-4 shrink-0" />
            <span class="text-xs">{Player.playbackSpeed === 1 ? "1×" : `${Player.playbackSpeed}×`}</span>
          </button>

          <div class="flex-1"></div>

          <!-- Mute toggle (hardware volume keys handle level on Android) -->
          <button
            type="button"
            class="flex size-11 items-center justify-center rounded-full text-white active:bg-white/15"
            onclick={toggleMute}
            aria-label={Player.volume === 0 ? "Unmute" : "Mute"}
          >
            {#if Player.volume === 0}
              <VolumeX class="size-5" />
            {:else}
              <Volume2 class="size-5" />
            {/if}
          </button>

          <!-- Fullscreen toggle (rotates device on Android) -->
          <button
            type="button"
            class="flex size-11 items-center justify-center rounded-full text-white active:bg-white/15"
            onclick={() => Player.setFullscreen(!Player.isFullscreen)}
            aria-label={Player.isFullscreen ? "Exit fullscreen" : "Fullscreen"}
          >
            {#if Player.isFullscreen}
              <Minimize2 class="size-5" />
            {:else}
              <Maximize2 class="size-5" />
            {/if}
          </button>
        </div>
      </div>
    </div>

    <!-- ── Up-next card (bottom-right, above bottom controls) ─────────────────── -->
    {#if showUpNext && nextEp}
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div
        class="absolute right-4 z-20 w-64 overflow-hidden rounded-xl border border-white/20 bg-black/85 text-white shadow-2xl backdrop-blur-sm"
        style="bottom: calc(max(1rem, var(--safe-bottom)) + 8rem);"
        transition:fade={{ duration: 150 }}
        onclick={(e) => e.stopPropagation()}
        onkeydown={() => {}}
      >
        <div class="p-4">
          <div class="flex items-start justify-between gap-2">
            <p class="text-xs font-medium uppercase tracking-wide text-white/60">
              Up next · S{nextEp.season}E{nextEp.episode.episode_number}
            </p>
            <button
              type="button"
              class="flex size-6 shrink-0 items-center justify-center rounded-full text-white/60 active:bg-white/20"
              onclick={() => (upNextDismissed = true)}
              aria-label="Dismiss"
            >
              <X class="size-4" />
            </button>
          </div>
          {#if !$settings?.hideSpoilers && nextEp.episode.name}
            <p class="mt-1 truncate text-sm text-white/90">{nextEp.episode.name}</p>
          {/if}
          <button
            type="button"
            class="mt-3 flex w-full items-center justify-center gap-2 rounded-lg border border-white/30 bg-white/10 py-2.5 text-sm font-medium text-white active:bg-white/20"
            onclick={() => advance()}
          >
            <SkipForward class="size-4" />
            Watch now
          </button>
          {#if countdownSecs !== null}
            <div class="mt-3">
              <p class="mb-1.5 text-xs text-white/60">Playing in {countdownSecs}s</p>
              <div class="h-1 w-full overflow-hidden rounded-full bg-white/20">
                <div
                  class="h-full bg-white transition-[width] duration-1000 ease-linear"
                  style="width: {((10 - countdownSecs) / 10) * 100}%"
                ></div>
              </div>
            </div>
          {/if}
        </div>
      </div>
    {/if}

  {:else}
    <!-- ── Loading / buffering screen (initial load or startup) ─────────────────── -->
    {#if Player.available}
      <div class="absolute inset-0 z-20 flex flex-col items-center justify-center">
        {#if media?.poster_path}
          <div
            class="absolute inset-0 scale-110 bg-cover bg-center"
            style="background-image: url('{media.poster_path}'); filter: blur(6px); opacity: 0.3;"
          ></div>
        {/if}
        <div class="absolute inset-0 bg-black/70"></div>
        {#if logoUrl}
          <img
            src={logoUrl}
            alt={title}
            class="relative z-10 max-h-36 max-w-[80vw] object-contain drop-shadow-2xl"
          />
        {:else if media?.poster_path}
          <img
            src={media.poster_path}
            alt={title}
            class="relative z-10 h-44 w-28 rounded-lg object-cover shadow-2xl"
          />
        {:else if title}
          <span class="relative z-10 px-8 text-center text-2xl font-bold text-white">{title}</span>
        {/if}
        <Spinner class="relative z-10 mt-6 size-12 text-white" />
        <p class="relative z-10 mt-4 text-sm text-white/50">{loadingMessage}</p>
        {#if takingAWhile}
          <p
            class="relative z-10 mt-2 text-xs text-white/40"
            transition:fade={{ duration: 150 }}
          >
            This is taking a while…
          </p>
          <button
            type="button"
            class="relative z-10 mt-4 rounded-lg border border-white/30 bg-white/10 px-4 py-2 text-sm text-white"
            onclick={() => triggerPlaybackFailed()}
          >
            Cancel
          </button>
        {/if}
      </div>
    {/if}
  {/if}

  <!-- ── Seek flash indicators (-10s / +10s) ───────────────────────────────── -->
  {#if seekFlash}
    <div
      class="pointer-events-none absolute inset-y-0 z-30 flex items-center justify-center {seekFlash === 'left' ? 'left-0 w-1/3' : 'right-0 w-1/3'}"
      transition:fade={{ duration: 120 }}
    >
      <div class="rounded-full bg-white/20 px-4 py-2 text-lg font-semibold text-white backdrop-blur-sm">
        {seekFlash === "left" ? "−10s" : "+10s"}
      </div>
    </div>
  {/if}

</div>

<!-- ── Track sheets (fixed, rendered outside the main div) ───────────────── -->

{#if audioSheetOpen}
  <TrackSheet
    title="Audio"
    items={sortedAudio.map((t) => ({ id: t.id, label: trackLabel(t, "Audio") }))}
    selectedId={selectedAudio?.id ?? null}
    onSelect={(id) => Player.setAudioTrack(id as number)}
    onClose={() => (audioSheetOpen = false)}
  />
{/if}

{#if subsSheetOpen}
  <TrackSheet
    title="Subtitles"
    items={subtitleItems.map((i) => ({ id: i.id, label: i.label }))}
    selectedId={selectedSubId}
    onSelect={(id) => {
      if (id === "off") {
        selectSubtitle({ kind: "off" });
      } else {
        const item = subtitleItems.find((i) => i.id === id);
        if (item?.kind === "embedded") {
          selectSubtitle({ kind: "embedded", id: item.id as number });
        } else if (item?.kind === "external") {
          selectSubtitle({ kind: "external", id: item.id as string });
        }
      }
    }}
    onClose={() => (subsSheetOpen = false)}
  />
{/if}

{#if speedSheetOpen}
  <TrackSheet
    title="Playback speed"
    items={SPEEDS.map((s) => ({ id: String(s), label: s === 1 ? "Normal (1×)" : `${s}×` }))}
    selectedId={String(Player.playbackSpeed)}
    onSelect={(id) => {
      Player.setPlaybackSpeed(parseFloat(id as string));
    }}
    onClose={() => (speedSheetOpen = false)}
  />
{/if}
