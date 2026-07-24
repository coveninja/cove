<script lang="ts">
  import type { Media, TVEpisode } from "$lib/types/tmdb";
  import type { Stream, TimestampData, TimestampSegment } from "$lib/types/addons";
  import { onDestroy, onMount, untrack, tick } from "svelte";
  import { api } from "$lib/api";
  import { settings } from "$lib/stores/settings";
  import { Player } from "$lib/player/player.svelte";
  import { loadAspectMode, saveAspectMode } from "$lib/player/aspectRatio";
  import {
    loadShowTrackPrefs,
    saveShowTrackPrefs,
    type ShowTrackPrefs,
  } from "$lib/player/trackPrefs";
  import {
    ProgressSaver,
    type ProgressContext,
  } from "$lib/player/progressSaver.svelte.js";
  import { TorrentProgress } from "$lib/player/torrentProgress.svelte.js";
  import { langMatches } from "$lib/lang";
  import { nextAiredEpisode } from "$lib/nextEpisode";
  import { rankStreams, type StreamSelectionMode } from "$lib/streamSelection";
  import { SvelteSet, SvelteMap } from "svelte/reactivity";
  import { libraryChanged } from "$lib/stores/library";
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

  const streamDiscoveryPending = $derived(!src && pendingMessage !== undefined);

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

  let showPrefs = $state<ShowTrackPrefs>({});

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
    untrack(() => {
      Player.setAspectMode(media ? loadAspectMode(media.id) : "fit");
      showPrefs = media ? loadShowTrackPrefs(media.id) : {};
      if (showPrefs.speed && showPrefs.speed !== 1) {
        Player.setPlaybackSpeed(showPrefs.speed);
      }
    });
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
        // The bump waits for the save to land so the refetch can't race the POST.
        void progress.saveNow(Player.position, Player.duration, progressCtx, Player.ended)
          .then(() => libraryChanged.update((n) => n + 1));
      }
    } catch (e) {
      console.error(e);
    }
    Player.stop();
  });

  const canPlay = $derived(!!src && !switching && Player.ready && Player.duration > 0);

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
      void progress.saveNow(Player.duration, Player.duration, progressCtx, true)
        .then(() => libraryChanged.update((n) => n + 1));
    }
  });

  // ── Torrent download progress (hash sources) ─────────────────────────────────

  const isHash = $derived(!!src && !src.startsWith("http"));
  const torrent = new TorrentProgress();

  $effect(() => {
    if (!src || !isHash) return;
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
    streamDiscoveryPending
      ? pendingMessage!
      : isHash
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

  // ── Auto-select preferred audio track ────────────────────────────────────────

  $effect(() => {
    if (appliedAudioDefault || Player.audioTracks.length <= 1) return;
    // A remembered per-show audio language wins over the global default.
    const prefLang = showPrefs.audioLang;
    let targetLang: string | null | undefined = prefLang;
    if (!prefLang) {
      const setting = $settings?.defaultAudioLang;
      if (!setting) return;
      if (setting === "original") {
        if (originalLang === null) return;
        if (originalLang === "") {
          appliedAudioDefault = true;
          return;
        }
      }
      targetLang = setting === "original" ? originalLang : setting;
    }
    appliedAudioDefault = true;
    const match = Player.audioTracks.find((t) => langMatches(t.lang, targetLang));
    if (match && !match.selected) Player.setAudioTrack(match.id);
  });

  // ── Auto-select preferred subtitle track ─────────────────────────────────────

  $effect(() => {
    if (appliedSubDefault || !canPlay) return;
    // A remembered per-show subtitle choice wins over the global default.
    const pref = showPrefs.sub;
    if (pref) {
      if (pref.kind === "off") {
        appliedSubDefault = true;
        selectSubtitle({ kind: "off" });
        return;
      }
      const embMatch = Player.subtitleTracks.find((t) => langMatches(t.lang, pref.lang));
      if (embMatch) {
        appliedSubDefault = true;
        selectSubtitle({ kind: "embedded", id: embMatch.id });
        return;
      }
      const extMatch = externalSubtitles.find((s) => langMatches(s.lang, pref.lang));
      if (extMatch) {
        appliedSubDefault = true;
        selectSubtitle({ kind: "external", id: extMatch.id });
        return;
      }
      // Preferred language not present yet — wait for the external list; only
      // once it's arrived (and still no match) do we fall through to the
      // global-default behavior below.
      if (externalSubtitles.length === 0) return;
    }
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

  // ─── Apply subtitle-style preferences (size / position / background box) ─────
  // Re-applies whenever the settings change or the bridge becomes ready. mpv
  // applies these live and keeps them across loadfile.
  $effect(() => {
    if (!Player.ready) return;
    const s = $settings;
    if (!s) return;
    Player.setSubtitleStyle(
      s.subtitleSize ?? 100,
      s.subtitlePosition ?? 8,
      s.subtitleBackground ?? false,
    );
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

  // ── Per-show track / speed save wrappers ─────────────────────────────────────

  function chooseAudioTrack(id: number): void {
    Player.setAudioTrack(id);
    const t = Player.audioTracks.find((x) => x.id === id);
    if (media && t?.lang) saveShowTrackPrefs(media.id, { audioLang: t.lang });
  }

  function chooseSubtitle(sel: SubSel): void {
    selectSubtitle(sel);
    if (!media) return;
    if (sel.kind === "off") { saveShowTrackPrefs(media.id, { sub: { kind: "off" } }); return; }
    const lang = sel.kind === "embedded"
      ? Player.subtitleTracks.find((x) => x.id === sel.id)?.lang
      : externalSubtitles.find((x) => x.id === sel.id)?.lang;
    if (lang) saveShowTrackPrefs(media.id, { sub: { kind: "lang", lang } });
  }

  function chooseSpeed(speed: number): void {
    Player.setPlaybackSpeed(speed);
    if (media) saveShowTrackPrefs(media.id, { speed });
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  function langName(code: string): string {
    try {
      return new Intl.DisplayNames(["en"], { type: "language" }).of(code) ?? code;
    } catch {
      return code;
    }
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

  // Subtitle source/language grouping helper (mirroring desktop groupByLang).
  function groupByLang<T>(entries: { lang: string; item: T }[]): { label: string; items: T[] }[] {
    const OTHER = "Other";
    const groups = new SvelteMap<string, T[]>();
    for (const { lang, item } of entries) {
      const g = lang || OTHER;
      if (!groups.has(g)) groups.set(g, []);
      groups.get(g)!.push(item);
    }
    return [...groups.entries()]
      .sort((a, b) =>
        a[0] === OTHER ? 1 : b[0] === OTHER ? -1 : a[0].localeCompare(b[0]),
      )
      .map(([label, items]) => ({ label, items }));
  }

  type SubRowItem = { id: string | number; label: string; header?: boolean; indent?: boolean };

  // Grouped subtitle list for the panel: Off + per-source headers + per-lang headers + tracks.
  const subtitleRows = $derived.by((): SubRowItem[] => {
    const rows: SubRowItem[] = [{ id: "off", label: "Off" }];

    if (Player.subtitleTracks.length > 0) {
      rows.push({ id: "hdr-embedded", label: "Embedded", header: true });
      const embGroups = groupByLang(
        Player.subtitleTracks.map((t) => ({
          lang: t.lang ? langName(t.lang) : t.title || "",
          item: { id: t.id as string | number, label: trackLabel(t, "Subtitle") },
        })),
      );
      for (const g of embGroups) {
        rows.push({ id: `hdr-embedded-${g.label}`, label: g.label, header: true, indent: true });
        for (const item of g.items) rows.push(item);
      }
    }

    if (externalSubtitles.length > 0) {
      rows.push({ id: "hdr-addons", label: "Add-ons", header: true });
      const extGroups = groupByLang(
        externalSubtitles.map((s) => ({
          lang: s.lang ? langName(s.lang) : "",
          item: { id: s.id as string | number, label: langName(s.lang) || "Subtitle" },
        })),
      );
      for (const g of extGroups) {
        rows.push({ id: `hdr-addons-${g.label}`, label: g.label, header: true, indent: true });
        for (const item of g.items) rows.push(item);
      }
    }

    return rows;
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
<div class="relative h-full w-full overflow-hidden">

  <!-- ── Bridge unavailable ──────────────────────────────────────────────────── -->
  {#if !Player.available && !streamDiscoveryPending}
    <div class="absolute inset-0 z-30 grid place-items-center bg-black">
      <p class="rounded bg-black/60 px-4 py-2 text-sm text-red-400">
        Native player unavailable.
      </p>
    </div>
  {/if}

  <!-- ── Controls overlay + up-next ────────────────────────────────────────── -->
  {#if canPlay}
    <TvPlayerControls
      {title}
      {episodeLabel}
      {controlsActive}
      {activeSegment}
      bind:skipBtnEl
      onSkipSegment={() => skipSegment(activeSegment!)}
      {chapterBars}
      {isHash}
      torrentProgress={torrent.progress}
      {displayPos}
      onSeekbarKeydown={handleSeekbarKeydown}
      onSeekBack={() => { nudgeSeek(-10); showSeekFlash("left"); showControls(); }}
      bind:playPauseBtn
      onPlayPause={() => { Player.togglePause(); showControls(); }}
      onSeekForward={() => { nudgeSeek(10); showSeekFlash("right"); showControls(); }}
      bind:audioPanelOpen
      {subtitleItems}
      {selectedSubId}
      {subSelection}
      hasSubtitles={Player.subtitleTracks.length > 0 || externalSubtitles.length > 0}
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
    {#if showUpNext && nextEp}
      <TvUpNext
        {nextEp}
        {countdownSecs}
        hideSpoilers={$settings?.hideSpoilers ?? false}
        onDismiss={() => (upNextDismissed = true)}
        onWatchNow={() => advance()}
        bind:watchNowBtnEl={upNextPlayBtnEl}
      />
    {/if}

  {:else}
    <!-- ── Loading / buffering screen ───────────────────────────────────────── -->
    {#if streamDiscoveryPending || Player.available}
      <TvLoadingScreen
        {media}
        {title}
        {logoUrl}
        {loadingMessage}
        {takingAWhile}
        cancelVisible={streamDiscoveryPending}
        onCancel={streamDiscoveryPending
          ? (onCancelPending ?? triggerPlaybackFailed)
          : triggerPlaybackFailed}
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
    title="Audio"
    items={sortedAudio.map((t) => ({ id: t.id, label: trackLabel(t, "Audio") }))}
    selectedId={selectedAudio?.id ?? null}
    onSelect={(id) => chooseAudioTrack(id as number)}
    onClose={() => (audioPanelOpen = false)}
  />
{/if}

{#if subsPanelOpen}
  <TvTrackPanel
    title="Subtitles"
    items={subtitleRows}
    selectedId={selectedSubId}
    onSelect={(id) => {
      if (id === "off") {
        chooseSubtitle({ kind: "off" });
      } else {
        const item = subtitleItems.find((i) => i.id === id);
        if (item?.kind === "embedded") {
          chooseSubtitle({ kind: "embedded", id: item.id as number });
        } else if (item?.kind === "external") {
          chooseSubtitle({ kind: "external", id: item.id as string });
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
      chooseSpeed(parseFloat(id as string));
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
