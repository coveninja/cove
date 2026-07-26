<script lang="ts">
  import type { Media, TVEpisode } from "$lib/types/tmdb";
  import type { Stream, TimestampData, TimestampSegment } from "$lib/types/addons";
  import { Slider } from "$lib/components/ui/slider/index.js";
  import { onDestroy, untrack } from "svelte";
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
  import * as m from "$lib/paraglide/messages.js";
  import { languageDisplayName } from "$lib/i18n";
  import TrackSheet from "./TrackSheet.svelte";
  import EpisodeSheet from "./EpisodeSheet.svelte";
  import MobilePlayerControls from "./MobilePlayerControls.svelte";
  import MobileUpNext from "./MobileUpNext.svelte";
  import MobileLoadingScreen from "./MobileLoadingScreen.svelte";
  import SeekFlash from "./SeekFlash.svelte";
  import { computeChapterBars } from "$lib/player/chapters";

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

  const streamDiscoveryPending = $derived(!src && pendingMessage !== undefined);

  // Register close-sheets with parent for Escape priority handling.
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
        void progress.saveNow(Player.position, Player.duration, progressCtx, false)
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

  // ─── Seek bar chapter markers (passed down to MobileSeekBar) ──────────────────
  const chapterBars = $derived(computeChapterBars(timestamps, Player.duration));

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

  // ── In-player subtitle style controls ────────────────────────────────────────
  // Applied to mpv immediately for live preview; the persisted settings write is
  // debounced so dragging a slider doesn't spam the settings PUT (the "apply
  // subtitle-style" effect re-applies it once the store updates — idempotent).
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

  function langName(code: string): string {
    return languageDisplayName(code);
  }

  function trackLabel(
    t: { id: number; title: string; lang: string },
    kind: string,
  ): string {
    if (t.title) return t.title;
    if (t.lang) return langName(t.lang);
    return `${kind} ${t.id}`;
  }

  const sortedAudio = $derived(
    [...Player.audioTracks].sort((a, b) =>
      trackLabel(a, m.player_audio()).localeCompare(
        trackLabel(b, m.player_audio()),
      ),
    ),
  );

  // Flat subtitle item list: Off + embedded + external.
  type SubItem =
    | { kind: "off"; id: "off"; label: string }
    | { kind: "embedded"; id: number; label: string }
    | { kind: "external"; id: string; label: string };

  const subtitleItems = $derived.by((): SubItem[] => {
    const items: SubItem[] = [
      { kind: "off", id: "off", label: m.player_subtitles_off() },
    ];
    for (const t of Player.subtitleTracks) {
      items.push({
        kind: "embedded",
        id: t.id,
        label: trackLabel(t, m.player_subtitle()),
      });
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
    const OTHER = m.player_other();
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

  // Grouped subtitle list for the sheet: Off + per-source headers + per-lang headers + tracks.
  const subtitleRows = $derived.by((): SubRowItem[] => {
    const rows: SubRowItem[] = [
      { id: "off", label: m.player_subtitles_off() },
    ];

    if (Player.subtitleTracks.length > 0) {
      rows.push({
        id: "hdr-embedded",
        label: m.player_embedded(),
        header: true,
      });
      const embGroups = groupByLang(
        Player.subtitleTracks.map((t) => ({
          lang: t.lang ? langName(t.lang) : t.title || "",
          item: {
            id: t.id as string | number,
            label: trackLabel(t, m.player_subtitle()),
          },
        })),
      );
      for (const g of embGroups) {
        rows.push({ id: `hdr-embedded-${g.label}`, label: g.label, header: true, indent: true });
        for (const item of g.items) rows.push(item);
      }
    }

    if (externalSubtitles.length > 0) {
      rows.push({
        id: "hdr-addons",
        label: m.player_addons(),
        header: true,
      });
      const extGroups = groupByLang(
        externalSubtitles.map((s) => ({
          lang: s.lang ? langName(s.lang) : "",
          item: {
            id: s.id as string | number,
            label: langName(s.lang) || m.player_subtitle(),
          },
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
    controlsVisible || Player.paused || !canPlay || audioSheetOpen || subsSheetOpen || speedSheetOpen || episodesSheetOpen,
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
    if (Player.paused || !canPlay || audioSheetOpen || subsSheetOpen || speedSheetOpen || episodesSheetOpen) {
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
  {#if !Player.available && !streamDiscoveryPending}
    <div class="absolute inset-0 z-30 grid place-items-center bg-black">
      <p class="rounded bg-black/60 px-4 py-2 text-sm text-red-400">
        {m.player_native_unavailable()}
      </p>
    </div>
  {/if}

  <!-- ── Controls overlay ───────────────────────────────────────────────────── -->
  {#if canPlay}
    <MobilePlayerControls
      {title}
      {episodeLabel}
      {activeSegment}
      {chapterBars}
      {isHash}
      {torrent}
      audioLabel={selectedAudio?.title || langName(selectedAudio?.lang ?? "") || "Audio"}
      subLabel={subSelection.kind === "off"
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
      onSkipSegment={() => activeSegment && skipSegment(activeSegment)}
      onToggleMute={toggleMute}
      onCycleAspect={cycleAspect}
      onNudgeBack={() => { nudgeSeek(-10); showSeekFlash("left"); showControls(); }}
      onNudgeForward={() => { nudgeSeek(10); showSeekFlash("right"); showControls(); }}
      onScrub={(pos) => { scrubbing = pos !== null; if (pos !== null) showControls(); }}
      onShowControls={showControls}
    />

    <!-- ── Up-next card ─────────────────────────────────────────────────────── -->
    {#if showUpNext && nextEp}
      <MobileUpNext
        {nextEp}
        {countdownSecs}
        hideSpoilers={$settings?.hideSpoilers ?? false}
        onDismiss={() => (upNextDismissed = true)}
        onAdvance={advance}
      />
    {/if}
  {:else if streamDiscoveryPending || Player.available}
    <!-- ── Loading / buffering screen ─────────────────────────────────────── -->
    <MobileLoadingScreen
      {media}
      {title}
      {logoUrl}
      {loadingMessage}
      {takingAWhile}
      cancelVisible={streamDiscoveryPending}
      onclose={streamDiscoveryPending ? onCancelPending : onclose}
      onCancel={streamDiscoveryPending
        ? (onCancelPending ?? triggerPlaybackFailed)
        : triggerPlaybackFailed}
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
      label: trackLabel(t, m.player_audio()),
    }))}
    selectedId={selectedAudio?.id ?? null}
    onSelect={(id) => chooseAudioTrack(id as number)}
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
      chooseSpeed(parseFloat(id as string));
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
