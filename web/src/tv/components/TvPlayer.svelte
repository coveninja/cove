<script lang="ts">
  import type { Media, TVEpisode } from "$lib/types/tmdb";
  import type { Stream, TimestampData, TimestampSegment } from "$lib/types/addons";
  import { Spinner } from "$lib/components/ui/spinner";
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
  } from "lucide-svelte";
  import { onDestroy, onMount, untrack, tick } from "svelte";
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
  import TvTrackPanel from "./TvTrackPanel.svelte";
  import TvEpisodePanel from "./TvEpisodePanel.svelte";
  import { focusable, focusGroup } from "../focus/actions";
  import { focusAfterKeyRelease } from "../focus/focusStore.svelte";

  // ── Props (identical contract to MobilePlayer) ──────────────────────────────

  let {
    src,
    media,
    externalSubtitles = [],
    season = undefined,
    episode = undefined,
    fileIdx = undefined,
    onPlaybackFailed = undefined,
    onPlayNext = undefined,
    onPlayStream: _onPlayStream = undefined,
    onclose = undefined,
    onRegisterCloseSheets = undefined,
  }: {
    src: string;
    media?: Media;
    externalSubtitles?: { id: string; url: string; lang: string }[];
    season?: number;
    episode?: number;
    /** Addon-supplied 0-based raw file index for season-pack torrents (Stremio
     * fileIdx). When present, the backend skips regex matching and plays this
     * exact file — more reliable than pattern matching for Torrentio packs. */
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

  // Register close-sheets with parent for Escape priority handling.
  $effect(() => {
    onRegisterCloseSheets?.(() => {
      if (audioPanelOpen || subsPanelOpen || speedPanelOpen || episodesPanelOpen) {
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
    // Reset playback speed on new stream.
    Player.setPlaybackSpeed(1);

    untrack(() => {
      if ($settings?.openOnMute) {
        Player.setVolume(0);
      } else if ($settings?.defaultVolume != null) {
        Player.setVolume(Math.round($settings.defaultVolume * 100));
      }
    });
    Player.play(api.playUrl(src, { season, episode, fileIdx }));
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
        // Pass the actual ended state so onDestroy and the "ended" effect firing
        // in the same tick don't race — #completedSaved prevents downgrade.
        progress.saveNow(Player.position, Player.duration, progressCtx, Player.ended);
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

  // rememberPosition is read untracked: a settings store refresh mid-playback
  // (periodic auth sync) must not reset/re-load progress — that would seek
  // back to the last saved position (up to 10s stale). Same as Player.svelte.
  $effect(() => {
    if (!media || !src) return;
    progress.reset();
    if (untrack(() => $settings?.rememberPosition) === false) return;
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
    return torrent.start(src, { season, episode, fileIdx });
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
              fileIdx: best.fileIdx,
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
    const requestedId = m.id; // guard against stale response after media changes
    api.getLogos(m.id, m.media_type).then((logos) => {
      if (media?.id !== requestedId) return;
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
    const requestedSrc = src; // guard against stale response after switching src
    api.getTimestamps(m.id, { season, episode }).then((data) => {
      if (src !== requestedSrc) return;
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

  // autoSkippedSegments.has() is wrapped in untrack() so mutating the set
  // (autoSkippedSegments.add() below) doesn't spuriously re-run this effect.
  $effect(() => {
    const seg = activeSegment;
    if (!seg || !$settings) return;

    const segKey = `${seg.type}-${seg.seg.start_ms ?? 0}`;
    if (untrack(() => autoSkippedSegments.has(segKey))) return;

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

  // ─── Seek bar chapter markers ─────────────────────────────────────────────────

  type ChapterBar = {
    startFrac: number;
    endFrac: number;
    type: "content" | "intro" | "recap" | "credits" | "preview";
  };

  // Splits the timeline into content + named segment chapters whenever we have
  // both timestamp data and a known duration. Returns null when unified bar is
  // needed (no data, or all segments collapsed to a single chapter).
  const chapterBars = $derived.by((): ChapterBar[] | null => {
    if (!timestamps) return null;
    if (!Player.duration) return null;
    const durMs = Player.duration * 1000;

    const named: { startMs: number; endMs: number; type: string }[] = [];
    const addAll = (arr: TimestampSegment[] | undefined, type: string) =>
      arr?.forEach((s) =>
        named.push({ startMs: s.start_ms ?? 0, endMs: s.end_ms ?? durMs, type }),
      );
    addAll(timestamps.intro, "intro");
    addAll(timestamps.recap, "recap");
    addAll(timestamps.credits, "credits");
    addAll(timestamps.preview, "preview");
    if (named.length === 0) return null;

    named.sort((a, b) => a.startMs - b.startMs);

    const bars: ChapterBar[] = [];
    let pos = 0;
    for (const seg of named) {
      if (seg.startMs > pos)
        bars.push({ startFrac: pos / durMs, endFrac: seg.startMs / durMs, type: "content" });
      bars.push({
        startFrac: seg.startMs / durMs,
        endFrac: Math.min(seg.endMs / durMs, 1),
        type: seg.type as ChapterBar["type"],
      });
      pos = seg.endMs;
    }
    if (pos < durMs) bars.push({ startFrac: pos / durMs, endFrac: 1, type: "content" });

    return bars.length > 1 ? bars : null;
  });

  function segmentBgClass(type: ChapterBar["type"]): string {
    switch (type) {
      case "intro":   return "bg-amber-400/50";
      case "recap":   return "bg-blue-400/50";
      case "credits": return "bg-purple-400/50";
      case "preview": return "bg-green-400/50";
      default:        return "";
    }
  }

  // Fraction (0–100) of a chapter pill covered up to the given global timeline fraction.
  function pillFill(chapter: ChapterBar, frac: number): number {
    if (frac >= chapter.endFrac) return 100;
    if (frac <= chapter.startFrac) return 0;
    return ((frac - chapter.startFrac) / (chapter.endFrac - chapter.startFrac)) * 100;
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
    const lang = $settings.defaultSubtitleLang;
    const embedded = Player.subtitleTracks.find((t) => langMatches(t.lang, lang));
    if (embedded) {
      appliedSubDefault = true;
      selectSubtitle({ kind: "embedded", id: embedded.id });
      return;
    }
    // External list hasn't arrived yet — don't latch; this effect re-runs
    // when the subtitle fetch resolves and externalSubtitles updates.
    if (externalSubtitles.length === 0) return;
    appliedSubDefault = true;
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

  // ── Seek bar (keyboard-friendly: focusable div with key handling) ─────────────

  let scrubbing = $state(false);
  let scrubValue = $state(0);
  const displayPos = $derived(scrubbing ? scrubValue : Player.position);

  // ── Panel open state ──────────────────────────────────────────────────────────

  let audioPanelOpen = $state(false);
  let subsPanelOpen = $state(false);
  let speedPanelOpen = $state(false);
  let episodesPanelOpen = $state(false);

  const anyPanelOpen = $derived(audioPanelOpen || subsPanelOpen || speedPanelOpen || episodesPanelOpen);

  // ── Controls auto-hide ────────────────────────────────────────────────────────

  let controlsVisible = $state(true);
  let hideTimer: ReturnType<typeof setTimeout> | undefined;

  const controlsActive = $derived(
    controlsVisible || Player.paused || !canPlay || anyPanelOpen,
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
    if (Player.paused || !canPlay || anyPanelOpen) {
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
      if (!canPlay) {
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
    if (activeSegment && skipBtnEl && !controlsActive) {
      tick().then(() => skipBtnEl?.focus({ preventScroll: true }));
    }
  });

  $effect(() => {
    if (showUpNext && upNextPlayBtnEl && !controlsActive) {
      tick().then(() => upNextPlayBtnEl?.focus({ preventScroll: true }));
    }
  });
</script>

<!--
  Root: fully transparent — mpv renders behind the WebView and shows through.
-->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="relative h-full w-full overflow-hidden">

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
        bind:this={controlBarEl}
        class="shrink-0 bg-gradient-to-t from-black/90 via-black/50 to-transparent px-8 pb-8 pt-16"
        onkeydown={handleBarKeydown}
        use:focusGroup={{ id: "tv-player-controls", policy: { type: "row" } }}
      >
        <!-- IntroDB skip button (inside bar area, focusable) -->
        {#if activeSegment}
          <div class="mb-4 flex justify-end">
            <button
              bind:this={skipBtnEl}
              type="button"
              class="rounded-full border border-white/60 bg-black/70 px-5 py-2.5 text-sm font-semibold text-white backdrop-blur-sm hover:bg-white/20 focus:bg-white/20"
              onclick={() => skipSegment(activeSegment!)}
              use:focusable={{ groupId: "tv-player-controls" }}
            >
              Skip {activeSegment.label}
            </button>
          </div>
        {/if}

        <!-- Seekbar -->
        <div
          role="slider"
          aria-label="Seek"
          aria-valuemin={0}
          aria-valuemax={Player.duration || 0}
          aria-valuenow={displayPos}
          tabindex={0}
          class="relative mb-3 flex h-5 w-full cursor-pointer items-center"
          onkeydown={handleSeekbarKeydown}
          use:focusable={{ groupId: "tv-player-controls" }}
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
          {:else}
            <!-- Unified bar (no timestamp data) -->
            <div class="absolute inset-x-0 top-1/2 h-1.5 -translate-y-1/2 overflow-hidden rounded-full bg-white/25">
              {#if isHash && torrent.progress > 0 && torrent.progress < 100}
                <div
                  class="pointer-events-none absolute inset-y-0 left-0 bg-white/35"
                  style="width: {torrent.progress}%"
                ></div>
              {/if}
              <div
                class="pointer-events-none absolute inset-y-0 left-0 bg-white"
                style="width: {Player.duration ? (displayPos / Player.duration) * 100 : 0}%"
              ></div>
            </div>
          {/if}
          <!-- Thumb -->
          <div
            class="pointer-events-none absolute top-1/2 size-5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-white shadow-md ring-1 ring-black/20"
            style="left: {Player.duration ? (displayPos / Player.duration) * 100 : 0}%"
          ></div>
        </div>

        <!-- Main button row -->
        <div class="flex items-center gap-2">
          <!-- Seek -10s -->
          <button
            type="button"
            class="flex size-12 items-center justify-center rounded-full text-white hover:bg-white/20 focus:bg-white/20"
            onclick={() => { nudgeSeek(-10); showSeekFlash("left"); showControls(); }}
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
            onclick={() => { Player.togglePause(); showControls(); }}
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
            onclick={() => { nudgeSeek(10); showSeekFlash("right"); showControls(); }}
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
          {#if Player.subtitleTracks.length > 0 || externalSubtitles.length > 0}
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

    <!-- ── Up-next card ───────────────────────────────────────────────────────── -->
    {#if showUpNext && nextEp}
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div
        class="absolute right-8 bottom-40 z-20 w-80 overflow-hidden rounded-2xl border border-white/20 bg-black/90 text-white shadow-2xl backdrop-blur-sm"
        transition:fade={{ duration: 150 }}
        onclick={(e) => e.stopPropagation()}
        onkeydown={() => {}}
      >
        <div class="p-5">
          <div class="flex items-start justify-between gap-2">
            <p class="text-xs font-medium uppercase tracking-wide text-white/60">
              Up next · S{nextEp.season}E{nextEp.episode.episode_number}
            </p>
            <button
              type="button"
              class="flex size-6 shrink-0 items-center justify-center rounded-full text-white/60 hover:bg-white/20 focus:bg-white/20"
              onclick={() => (upNextDismissed = true)}
              aria-label="Dismiss"
              use:focusable={{ groupId: "tv-player-controls" }}
            >
              <X class="size-4" />
            </button>
          </div>
          {#if !$settings?.hideSpoilers && nextEp.episode.name}
            <p class="mt-1 truncate text-sm text-white/90">{nextEp.episode.name}</p>
          {/if}
          <button
            bind:this={upNextPlayBtnEl}
            type="button"
            class="mt-4 flex w-full items-center justify-center gap-2 rounded-xl border border-white/30 bg-white/10 py-3 text-sm font-medium text-white hover:bg-white/20 focus:bg-white/20"
            onclick={() => advance()}
            use:focusable={{ groupId: "tv-player-controls" }}
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
    <!-- ── Loading / buffering screen ─────────────────────────────────────────── -->
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
            class="relative z-10 max-h-48 max-w-[60vw] object-contain drop-shadow-2xl"
          />
        {:else if media?.poster_path}
          <img
            src={media.poster_path}
            alt={title}
            class="relative z-10 h-56 w-36 rounded-xl object-cover shadow-2xl"
          />
        {:else if title}
          <span class="relative z-10 px-8 text-center text-3xl font-bold text-white">{title}</span>
        {/if}
        <Spinner class="relative z-10 mt-8 size-14 text-white" />
        <p class="relative z-10 mt-4 text-base text-white/50">{loadingMessage}</p>
        <p class="relative z-10 mt-2 text-sm text-white/40">Press Back to cancel</p>
        {#if takingAWhile}
          <p
            class="relative z-10 mt-2 text-sm text-white/40"
            transition:fade={{ duration: 150 }}
          >
            This is taking a while…
          </p>
          <button
            type="button"
            class="relative z-10 mt-5 rounded-xl border border-white/30 bg-white/10 px-6 py-3 text-base text-white hover:bg-white/20 focus:bg-white/20"
            onclick={() => triggerPlaybackFailed()}
          >
            Cancel
          </button>
        {/if}
      </div>
    {/if}
  {/if}

  <!-- ── Seek flash indicators ─────────────────────────────────────────────── -->
  {#if seekFlash}
    <div
      class="pointer-events-none absolute inset-y-0 z-30 flex items-center justify-center {seekFlash === 'left' ? 'left-0 w-1/3' : 'right-0 w-1/3'}"
      transition:fade={{ duration: 120 }}
    >
      <div class="rounded-full bg-white/20 px-5 py-3 text-xl font-semibold text-white backdrop-blur-sm">
        {seekFlash === "left" ? "−10s" : "+10s"}
      </div>
    </div>
  {/if}

</div>

<!-- ── Track panels (fixed, rendered outside the main div) ───────────────── -->

{#if audioPanelOpen}
  <TvTrackPanel
    title="Audio"
    items={sortedAudio.map((t) => ({ id: t.id, label: trackLabel(t, "Audio") }))}
    selectedId={selectedAudio?.id ?? null}
    onSelect={(id) => Player.setAudioTrack(id as number)}
    onClose={() => (audioPanelOpen = false)}
  />
{/if}

{#if subsPanelOpen}
  <TvTrackPanel
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
    onClose={() => (subsPanelOpen = false)}
  />
{/if}

{#if speedPanelOpen}
  <TvTrackPanel
    title="Playback speed"
    items={SPEEDS.map((s) => ({ id: String(s), label: s === 1 ? "Normal (1×)" : `${s}×` }))}
    selectedId={String(Player.playbackSpeed)}
    onSelect={(id) => {
      Player.setPlaybackSpeed(parseFloat(id as string));
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
    onSelect={(s, e) => { episodesPanelOpen = false; onPlayNext?.(s, e); }}
  />
{/if}
