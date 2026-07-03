<script lang="ts">
  import type { Media, TVEpisode } from "$lib/types/tmdb";
  import type { TimestampData, TimestampSegment } from "$lib/types/addons";
  import { Spinner } from "$lib/components/ui/spinner";
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
    Headphones,
    Captions,
    Check,
    Keyboard,
    X,
    SkipForward,
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
  import {SvelteMap, SvelteSet} from "svelte/reactivity";

  // ─── Props (unchanged from the old Player) ──────────────────────────────────

  let {
    src,
    media,
    externalSubtitles = [],
    season = undefined,
    episode = undefined,
    onPlaybackFailed = undefined,
    onPlayNext = undefined,
  }: {
    src: string;
    media?: Media;
    externalSubtitles?: { id: string; url: string; lang: string }[];
    season?: number;
    episode?: number;
    /** Fired once (per src) when playback never starts — a startup timeout
     * or a stalled torrent that never got peers. The caller (App.svelte)
     * decides what to do: try the next candidate stream, or give up. */
    onPlaybackFailed?: () => void;
    /** Fired when the up-next overlay's "Watch now" is clicked, its countdown
     * finishes, or the episode ends with autoplay on. Absence disables the
     * whole up-next feature (no overlay, no autoplay-advance) — the caller
     * (App.svelte) is what actually knows how to start the next episode. */
    onPlayNext?: (season: number, episode: number) => void;
  } = $props();

  // ─── Playback lifecycle ─────────────────────────────────────────────────────

  // mpv plays the backend stream URL directly — no probe, no HLS, no transcode.
  // The Go backend still serves it (so torrent streaming keeps working); mpv
  // just consumes it over http with range requests for seeking.
  let appliedAudioDefault = false;
  let appliedSubDefault = false;
  const addedExternal = new SvelteSet<string>(); // external sub ids already sub-add'd

  // "Original language" audio (F1): the ISO 639-1 code of the title's
  // original audio, resolved once per src. null while unresolved (or if the
  // title genuinely has no original_language, in which case it stays null
  // forever and the "original" preference is simply unresolvable for this
  // title — the auto-select effect below then no-ops rather than guessing).
  let originalLang = $state<string | null>(null);

  // ─── Up-next overlay state (F6) ──────────────────────────────────────────────
  // See the "Up-next overlay" section further down for the resolution/countdown
  // effects that drive these.
  let nextEp = $state<{ season: number; episode: TVEpisode } | null>(null);
  let upNextDismissed = $state(false);
  let advanced = false; // guards advance() from firing twice for one src
  let countdownSecs = $state<number | null>(null);
  let resolvingNextEp = false; // per-src guard so nextAiredEpisode fires once

  // ─── Background next-episode prefetch state (F7) ────────────────────────────
  let prefetchedNext = false; // per-src guard so the prefetch trigger fires once

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
    // Apply volume settings at stream start. Read inside untrack so that a
    // settings change while watching doesn't re-run this effect and restart
    // the stream.
    untrack(() => {
      if ($settings?.openOnMute) {
        Player.setVolume(0);
      } else if ($settings?.defaultVolume != null) {
        Player.setVolume(Math.round($settings.defaultVolume * 100));
      }
    });
    Player.play(api.playUrl(src, { season, episode }));
  });

  // Resolve original_language for "original" audio preference. media is
  // often only a partial object (library-launched playback carries just
  // id/media_type/etc.), so original_language may not be populated even
  // though the title has one — fetch the full record in that case rather
  // than treating "field absent" as "title has no original language".
  $effect(() => {
    if (!src || $settings?.defaultAudioLang !== "original") return;
    if (originalLang !== null) return;
    const m = media;
    if (!m) return;
    if (m.original_language) {
      originalLang = m.original_language;
      return;
    }
    const requestedSrc = src; // guards against a stale response after switching src
    untrack(() => {
      api
        .getMediaByID(m.id, m.media_type)
        .then((full) => {
          if (src !== requestedSrc) return;
          // Empty string is a valid "resolved to nothing" — still distinct
          // from null (unresolved), so the auto-select effect stops retrying.
          originalLang = full.original_language ?? "";
        })
        .catch(() => {
          if (src !== requestedSrc) return;
          originalLang = ""; // give up — treat as unresolvable
        });
    });
  });

  $effect(() => {
    if (switching && Player.ready && Player.duration > 0) {
      switching = false;
    }
  });

  // Stop playback when the player closes so video/audio don't keep running
  // behind the rest of the UI — and persist where we got to. Saving must never
  // prevent the stop, so it's guarded.
  onDestroy(() => {
    if (!Player.available) return;
    try {
      if (media && Player.duration > 0)
        progress.saveNow(
                Player.position,
                Player.duration,
                progressCtx,
                false,
        );
    } catch (e) {
      console.error(e);
    }
    Player.stop();
  });
  let switching = $state(false);

  const canPlay = $derived(!switching && Player.ready && Player.duration > 0)

  // ─── Playback-start watchdog (B2) ───────────────────────────────────────────
  // If a stream never actually starts — a dead torrent with no peers, a dead
  // direct link — mpv just sits there with no error to catch, and without
  // this the loading screen spins forever. Two independent triggers call
  // triggerPlaybackFailed(): a startup timer (armed fresh for every src) and
  // torrent.stalled (torrentProgress's own give-up-after-repeated-SSE-errors
  // signal, previously computed but never read by anything). failedFired
  // guards against both firing, and everCanPlay guards against firing after
  // playback already succeeded once for this src (e.g. a later stall once
  // the swarm empties out mid-watch shouldn't retrigger a "failed to start").

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

    // Hash (torrent) sources get longer: the backend's own metadata-fetch
    // timeout is 45s (player.go:228 getLargestTorrentFile) — let that fail
    // first so the error surfaces from the right layer instead of racing it.
    const isHashSrc = !src.startsWith("http");
    const failTimeoutMs = isHashSrc ? 50_000 : 25_000;
    const failTimer = setTimeout(triggerPlaybackFailed, failTimeoutMs);
    const slowTimer = setTimeout(() => {
      takingAWhile = true;
    }, 15_000);

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

  // The stalled signal (torrentProgress gave up reconnecting the progress
  // SSE) means the torrent is effectively dead — treat it the same as a
  // startup timeout rather than leaving the loading screen spinning.
  $effect(() => {
    if (torrent.stalled && !canPlay) {
      triggerPlaybackFailed();
    }
  });

  // ─── Watch progress (mpv-driven) ─────────────────────────────────────────────

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
      probedDuration: null, // mpv reports the real duration
    };
  }

  // Load any saved position when the source changes.
  $effect(() => {
    if (!media || !src) return;
    progress.reset();
    if ($settings?.rememberPosition === false) return;
    progress.load(media.id, media.media_type, season ?? null, episode ?? null);
  });

  // Seek to it once, the first time playback is ready.
  $effect(() => {
    if (!canPlay) return;
    progress.resume((t) => Player.seek(t));
  });

  // Throttled save while playing (re-runs as position ticks).
  $effect(() => {
    const pos = Player.position;
    if (!canPlay || !media || Player.paused) return;
    progress.maybeSave(pos, Player.duration, progressCtx);
  });

  // Mark complete at end of file.
  $effect(() => {
    if (Player.ended && media)
      progress.saveNow(
              Player.duration,
              Player.duration,
              progressCtx,
              true,
      );
  });

  // ─── Torrent download progress (SSE, hash sources only) ──────────────────────

  const isHash = $derived(!src.startsWith("http"));
  const torrent = new TorrentProgress();

  $effect(() => {
    if (!isHash) return;
    return torrent.start(src, { season, episode });
  });

  // ─── Background next-episode prefetch trigger (F7) ──────────────────────────
  // Fires once the CURRENT episode's file has finished downloading — the
  // point at which the swarm's spare capacity is genuinely free rather than
  // competing with active playback. Deliberately independent of the up-next
  // overlay's own nextEp resolution (F6) above: this can legitimately fire
  // long before the user is anywhere near the end of the episode.
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
        const next = await nextAiredEpisode(m.id, season, episode);
        if (!next) return; // caught up — nothing to warm

        // Single call, no retry — this is a background nicety, not something
        // worth the retry/backoff machinery fetchStreamsWithRetry (App.svelte)
        // uses for the user-facing path. Also warms the backend's own
        // per-title caches and registers direct URLs (rememberStream) — all a
        // top-ranked HTTP candidate needs; only a torrent winner needs the
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

  let logoUrl = $state<string | null>(null);

  $effect(() => {
    const m = media;
    if (!m) { logoUrl = null; return; }
    logoUrl = null;
    api.getLogos(m.id, m.media_type).then((logos) => {
      logoUrl = logos[0] ?? null;
    }).catch(() => {});
  });

  // ─── IntroDB timestamps ──────────────────────────────────────────────────────

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

  // The segment the player is currently inside (checked by position in ms).
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

  // Auto-skip segments when the matching setting is enabled.
  // Uses autoSkippedSegments to avoid re-skipping if the user seeks back.
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

  // ─── Seek bar chapter markers ────────────────────────────────────────────────

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

  let hoveredChapter = $state<ChapterBar | null>(null);

  // Fraction (0–100) of a chapter pill that should be filled white by the progress bar.
  function chapterFill(chapter: ChapterBar): number {
    if (!Player.duration) return 0;
    const posFrac = displayPos / Player.duration;
    if (posFrac >= chapter.endFrac) return 100;
    if (posFrac <= chapter.startFrac) return 0;
    return ((posFrac - chapter.startFrac) / (chapter.endFrac - chapter.startFrac)) * 100;
  }

  // ─── Auto-select preferred audio track ──────────────────────────────────────
  // mpv/ffmpeg tag embedded audio tracks with ISO 639-2 (three-letter, e.g.
  // "jpn"), while the setting and TMDB's original_language are ISO 639-1
  // ("ja") — langMatches normalizes both sides before comparing so this
  // doesn't silently no-op for every non-English track.

  $effect(() => {
    if (appliedAudioDefault || Player.audioTracks.length <= 1) return;
    const setting = $settings?.defaultAudioLang;
    if (!setting) return;
    if (setting === "original") {
      // originalLang is still resolving (or media hasn't arrived yet) — don't
      // mark this applied, so the effect re-runs once it settles instead of
      // permanently giving up on the "original language" preference.
      if (originalLang === null) return;
      if (originalLang === "") {
        // Resolved to "unresolvable" (title has no original_language) —
        // nothing sensible to match against; leave mpv's own default alone.
        appliedAudioDefault = true;
        return;
      }
    }
    const targetLang = setting === "original" ? originalLang : setting;
    appliedAudioDefault = true;
    const match = Player.audioTracks.find((t) => langMatches(t.lang, targetLang));
    if (match && !match.selected) Player.setAudioTrack(match.id);
  });

  // ─── Auto-select preferred subtitle track ───────────────────────────────────
  // Gated on the file being loaded (duration > 0) so embedded tracks have had a
  // chance to populate before we choose between them and the external list.
  // Same 639-1/639-2 mismatch as audio tracks applies here (embedded tracks
  // come from mpv/container metadata), hence langMatches again.

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

  // ─── Up-next overlay + autoplay countdown (F6) ──────────────────────────────
  // autoPlay has existed as a setting since settings.go:23 but nothing ever
  // read it — this is what actually wires it up.

  // Resolve the next aired episode once per src (not on every position tick —
  // nextAiredEpisode hits the season-episodes endpoint).
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
      nextAiredEpisode(id, season, episode)
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

  // Shown once there's a resolved next episode and the player is near the
  // episode's end — either IntroDB flagged a credits segment, or we're within
  // the last 40s of the file (no credits data for this title).
  const showUpNext = $derived(
    !!nextEp &&
      !upNextDismissed &&
      !!onPlayNext &&
      (activeSegment?.type === "credits" ||
        (Player.duration > 0 && Player.duration - Player.position < 40 && canPlay) ||
        Player.ended),
  );

  // Countdown: arm a 10s "Playing in Ns" timer once the overlay shows and
  // autoplay is on. Cleared (and re-armable) whenever showUpNext flips back
  // to false — most notably a seek back away from the episode's end.
  //
  // countdownSecs must only ever be touched inside untrack() here (the
  // interval callback is fine — async callbacks aren't tracked): the effect
  // reads it to display-drive the template indirectly, and a tracked read
  // followed by the writes below would make the effect re-run on its own
  // write, tearing down the interval it just created and then bailing on the
  // "already counting down" guard — freezing the countdown at 10s forever.
  // With untrack, the effect's only dependencies are showUpNext and autoPlay.
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
        // advanced isn't reactive state, so this effect won't automatically
        // re-run (and tear down the interval) just because advance() sets
        // it — clear explicitly here instead of relying on that.
        clearInterval(interval);
        advance();
        return;
      }
      countdownSecs -= 1;
    }, 1000);
    return () => clearInterval(interval);
  });

  // Immediate advance when the file ends with autoplay on — covers the case
  // where the file has no trailing ~40s credits window (or IntroDB has no
  // data for it) for the countdown to have armed against. everCanPlay (the
  // watchdog's per-src "playback genuinely started" flag) guards against a
  // stale/spurious ended reading during the src-switch window ever chaining
  // an advance for an episode that never actually played.
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
    // Mirrors the ended-effect above: mark the current episode complete so the
    // library records it (and the backend's next-episode prefetch worker, F7,
    // sees a completed episode to trigger off of).
    if (media && Player.duration > 0) {
      progress.saveNow(Player.duration, Player.duration, progressCtx, true);
    }
    onPlayNext(nextEp.season, nextEp.episode.episode_number);
  }

  function dismissUpNext(): void {
    upNextDismissed = true;
  }

  // ─── Controls state ─────────────────────────────────────────────────────────

  let lastVolume = $state(100);

  // Track-menu open state. While any picker is open, keyboard shortcuts stand
  // down so the menu's own arrow-key navigation isn't hijacked.
  let audioOpen = $state(false);
  let subsOpen = $state(false);
  let helpOpen = $state(false);
  const menuOpen = $derived(audioOpen || subsOpen || helpOpen);

  // Scrubbing: while dragging the seek bar, show the dragged time and only issue
  // the real seek on release, so we don't spam mpv (costly on torrent sources).
  let scrubbing = $state(false);
  let scrubValue = $state(0);
  const displayPos = $derived(scrubbing ? scrubValue : Player.position);

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

  // ─── Custom seek bar (pointer-based, no third-party slider) ────────────────
  let seekTrackEl = $state<HTMLDivElement | null>(null);

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

  function onVolumeChange(v: number): void {
    Player.setVolume(v);
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
    if (subSelection.kind !== "off") {
      selectSubtitle({ kind: "off" });
      flash("Subtitles off");
      return;
    }
    const emb = Player.subtitleTracks[0];
    if (emb) {
      selectSubtitle({ kind: "embedded", id: emb.id });
      flash("Subtitles on");
      return;
    }
    const ext = externalSubtitles[0];
    if (ext) {
      selectSubtitle({ kind: "external", id: ext.id });
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
      case "c":
        toggleCaptions();
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
    // External: add once (mpv selects it on add), then it lives as a track.
    const ext = externalSubtitles.find((s) => s.id === sel.id);
    if (!ext) return;
    if (addedExternal.has(ext.id)) {
      // already loaded — find the matching mpv track by language and select it
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

  // ─── Helpers ────────────────────────────────────────────────────────────────

  function langName(code: string): string {
    try {
      return (
              new Intl.DisplayNames(["en"], { type: "language" }).of(code) ?? code
      );
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

  // Best available human label for a track. mpv exposes whatever the container
  // tagged: prefer an explicit title, else the language name, else a numbered
  // fallback (some files ship untagged tracks — nothing to name them by).
  function trackLabel(
          t: { id: number; title: string; lang: string },
          kind: "Audio" | "Subtitle",
  ): string {
    if (t.title) return t.title;
    if (t.lang) return langName(t.lang);
    return `${kind} ${t.id}`;
  }

  // Sorted for stable, language-grouped menus (untagged → bottom by number).
  const sortedAudio = $derived(
          [...Player.audioTracks].sort((a, b) =>
                  trackLabel(a, "Audio").localeCompare(trackLabel(b, "Audio")),
          ),
  );

  // Subtitle menu grouped by language: embedded mpv tracks + external
  // (OpenSubtitles) entries fall under their language; tracks with no language
  // tag land in "Other". Groups are sorted alphabetically with "Other" last.
  type SubMenuItem =
          | { kind: "embedded"; key: string; id: number; label: string }
          | { kind: "external"; key: string; id: string; label: string };

  const OTHER = "Other";

  const subtitleGroups = $derived.by(() => {
    const groups = new SvelteMap<string, SubMenuItem[]>();
    const push = (g: string, item: SubMenuItem) => {
      if (!groups.has(g)) groups.set(g, []);
      groups.get(g)!.push(item);
    };

    for (const t of Player.subtitleTracks) {
      const g = t.lang ? langName(t.lang) : t.title || OTHER;
      push(g, {
        kind: "embedded",
        key: `e${t.id}`,
        id: t.id,
        label: trackLabel(t, "Subtitle"),
      });
    }
    for (const s of externalSubtitles) {
      const g = s.lang ? langName(s.lang) : OTHER;
      push(g, {
        kind: "external",
        key: `x${s.id}`,
        id: s.id,
        label: `${langName(s.lang)} · OpenSubtitles`,
      });
    }

    return [...groups.entries()]
            .sort((a, b) =>
                    a[0] === OTHER ? 1 : b[0] === OTHER ? -1 : a[0].localeCompare(b[0]),
            )
            .map(([label, items]) => ({ label, items }));
  });

  const title = $derived(
          media ? (media.media_type === "tv" ? media.name : media.title) : "",
  );

  const selectedAudio = $derived(
          Player.audioTracks.find((t) => t.selected),
  );

  const subtitleLabel = $derived.by(() => {
    // Capture into a const so the discriminated-union narrowing survives into
    // the .find() callbacks below (TS drops narrowing of a reassignable `let`
    // inside nested closures, but keeps it for a const).
    const sel = subSelection;
    if (sel.kind === "off") return "Subtitles";
    if (sel.kind === "embedded") {
      const t = Player.subtitleTracks.find((x) => x.id === sel.id);
      return t ? trackLabel(t, "Subtitle") : "Subtitles";
    }
    const e = externalSubtitles.find((x) => x.id === sel.id);
    return e ? langName(e.lang) : "Subtitles";
  });

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

{#snippet menuItem(label: string, active: boolean, onSelect: () => void)}
  <button
          type="button"
          onclick={onSelect}
          class="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm outline-none transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:bg-accent focus-visible:text-accent-foreground {active
      ? 'font-medium'
      : ''}"
  >
    <span class="flex-1 truncate">{label}</span>
    {#if active}<Check class="size-4 shrink-0" />{/if}
  </button>
{/snippet}

{#snippet shortcut(label: string, keys: string)}
  <div class="flex items-center justify-between gap-4">
    <dt class="text-muted-foreground">{label}</dt>
    <dd>
      <kbd
              class="rounded border border-border bg-muted px-1.5 py-0.5 font-mono text-[11px] text-muted-foreground"
      >{keys}</kbd
      >
    </dd>
  </div>
{/snippet}

<!-- Root is transparent so mpv (rendered behind the WebEngineView) shows through.
     For this to reveal video, the page background and every ancestor down to the
     video region must also be transparent — see integration notes. -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
        class="relative h-full w-full overflow-hidden"
        onmousemove={showControls}
        onclick={() => Player.togglePause()}
        onkeydown={() => {}}
>
  <!-- ── Bridge unavailable (running outside the Cove shell) ─────────────────── -->
  {#if !Player.available}
    <div class="absolute inset-0 z-30 grid place-items-center bg-black">
      <p class="rounded bg-black/60 px-4 py-2 text-sm text-red-400">
        Native player unavailable — run inside the Cove desktop app.
      </p>
    </div>
  {/if}

  <!-- ── Keyboard/action feedback flash ──────────────────────────────────────── -->
  {#if feedback}
    <div class="pointer-events-none absolute inset-0 z-20 grid place-items-center">
      <div
              class="rounded-full bg-black/70 px-4 py-2 text-sm font-medium text-white backdrop-blur-sm"
              transition:fade={{ duration: 150 }}
      >
        {feedback}
      </div>
    </div>
  {/if}

  <!-- ── Controls ───────────────────────────────────────────────────────────── -->
  {#if canPlay}
    <div
            class="absolute inset-0 z-10 flex flex-col justify-end bg-linear-to-t from-black/85 via-black/15 to-transparent transition-opacity duration-200 {controlsVisible ||
      Player.paused
        ? 'opacity-100'
        : 'pointer-events-none opacity-0'}"
    >
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div
              class="flex w-full flex-col gap-2 px-4 pb-4 text-white"
              onclick={(e) => e.stopPropagation()}
              onkeydown={(e) => e.stopPropagation()}
      >
        <!-- Seek bar (full width, custom — no third-party slider) -->
        <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
        <div
                role="slider"
                aria-label="Seek"
                aria-valuemin={0}
                aria-valuemax={Player.duration || 0}
                aria-valuenow={displayPos}
                tabindex={0}
                class="relative flex h-2 w-full cursor-pointer items-center"
                bind:this={seekTrackEl}
                onpointerdown={onSeekPointerDown}
                onpointermove={onSeekPointerMove}
                onpointerup={onSeekPointerUp}
                onpointercancel={onSeekPointerUp}
        >
          {#if chapterBars}
            <!-- Segmented: each chapter is its own rounded pill with a gap -->
            <div class="flex h-full w-full gap-0.5">
              {#each chapterBars as chapter}
                <div
                  class="relative h-full overflow-hidden rounded-full {chapter.type !== 'content'
                    ? segmentBgClass(chapter.type)
                    : 'bg-white/20'}"
                  style="flex: {chapter.endFrac - chapter.startFrac}"
                  onmouseenter={() => chapter.type !== 'content' && (hoveredChapter = chapter)}
                  onmouseleave={() => hoveredChapter = null}
                >
                  <div
                    class="pointer-events-none absolute inset-y-0 left-0 bg-white"
                    style="width: {chapterFill(chapter)}%"
                  ></div>
                </div>
              {/each}
            </div>
            <!-- Chapter label tooltip, centered over the hovered pill -->
            {#if hoveredChapter}
              <div
                class="pointer-events-none absolute -top-6 -translate-x-1/2 rounded bg-black/80 px-2 py-0.5 text-xs font-medium capitalize text-white"
                style="left: {(hoveredChapter.startFrac + hoveredChapter.endFrac) / 2 * 100}%"
                transition:fade={{ duration: 100 }}
              >
                {hoveredChapter.type}
              </div>
            {/if}
          {:else}
            <!-- Unified bar (no timestamp data) -->
            <div class="absolute inset-0 overflow-hidden rounded-full bg-white/20">
              <div
                class="pointer-events-none absolute inset-y-0 left-0 bg-white"
                style="width: {Player.duration ? (displayPos / Player.duration) * 100 : 0}%"
              ></div>
            </div>
          {/if}
          <!-- Scrubber thumb (not inside any overflow-hidden clip) -->
          <div
                  class="pointer-events-none absolute top-1/2 h-4 w-4 -translate-x-1/2 -translate-y-1/2 rounded-full bg-white shadow-md ring-1 ring-black/10"
                  style="left: {Player.duration ? (displayPos / Player.duration) * 100 : 0}%"
          ></div>
        </div>

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
              {Player.paused ? "Play" : "Pause"} · Space
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
                {Player.volume === 0 ? "Unmute" : "Mute"} · M
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
                      onValueChange={onVolumeChange}
                      aria-label="Volume"
                      class="w-24"
              />
            </div>
          </div>

          <span class="ml-2 text-xs tabular-nums text-white/80">
            {fmt(displayPos)}<span class="mx-1 text-white/40">/</span>{fmt(
                  Player.duration,
          )}
          </span>

          <div class="flex-1"></div>

          <!-- Torrent download progress (hash sources, mid-download) -->
          {#if isHash && torrent.progress > 0 && torrent.progress < 100}
            <span class="mr-1 text-xs tabular-nums text-white/60">
              ↓ {torrent.progress.toFixed(0)}%
            </span>
          {/if}

          <!-- Audio tracks -->
          {#if Player.audioTracks.length > 0}
            <Popover.Root bind:open={audioOpen}>
              <Popover.Trigger>
                {#snippet child({ props })}
                  <Button
                          {...props}
                          variant="ghost"
                          size="sm"
                          class="gap-1.5 text-white hover:bg-white/15 hover:text-white"
                  >
                    <Headphones class="size-4" />
                    <span class="max-w-28 truncate text-xs">
                      {selectedAudio?.title ||
                      langName(selectedAudio?.lang ?? "") ||
                      "Audio"}
                    </span>
                  </Button>
                {/snippet}
              </Popover.Trigger>
              <Popover.Content side="top" align="end" class="w-56 p-1">
                <p class="px-2 py-1.5 text-xs font-medium text-muted-foreground">
                  Audio
                </p>
                <div class="max-h-72 overflow-y-auto">
                  {#each sortedAudio as track (track.id)}
                    {@render menuItem(
                            trackLabel(track, "Audio"),
                            !!track.selected,
                            () => Player.setAudioTrack(track.id),
                    )}
                  {/each}
                </div>
              </Popover.Content>
            </Popover.Root>
          {/if}

          <!-- Subtitles -->
          {#if Player.subtitleTracks.length > 0 || externalSubtitles.length > 0}
            <Popover.Root bind:open={subsOpen}>
              <Popover.Trigger>
                {#snippet child({ props })}
                  <Button
                          {...props}
                          variant="ghost"
                          size="sm"
                          class="gap-1.5 text-white hover:bg-white/15 hover:text-white"
                  >
                    <Captions class="size-4" />
                    <span class="max-w-28 truncate text-xs">{subtitleLabel}</span>
                  </Button>
                {/snippet}
              </Popover.Trigger>
              <Popover.Content side="top" align="end" class="w-60 p-1">
                <p class="px-2 py-1.5 text-xs font-medium text-muted-foreground">
                  Subtitles
                </p>
                <div class="max-h-72 overflow-y-auto">
                  {@render menuItem("Off", subSelection.kind === "off", () =>
                          selectSubtitle({ kind: "off" }),
                  )}
                  {#each subtitleGroups as group (group.label)}
                    <p
                            class="px-2 pt-2 pb-1 text-[11px] font-medium tracking-wide text-muted-foreground/70 uppercase"
                    >
                      {group.label}
                    </p>
                    {#each group.items as item (item.key)}
                      {@render menuItem(
                              item.label,
                              (subSelection.kind === "embedded" &&
                                      item.kind === "embedded" &&
                                      subSelection.id === item.id) ||
                              (subSelection.kind === "external" &&
                                      item.kind === "external" &&
                                      subSelection.id === item.id),
                              () =>
                                      item.kind === "embedded"
                                              ? selectSubtitle({ kind: "embedded", id: item.id })
                                              : selectSubtitle({ kind: "external", id: item.id }),
                      )}
                    {/each}
                  {/each}
                </div>
              </Popover.Content>
            </Popover.Root>
          {/if}

          <!-- Keyboard shortcuts -->
          <Popover.Root bind:open={helpOpen}>
            <Popover.Trigger>
              {#snippet child({ props })}
                <Button
                        {...props}
                        variant="ghost"
                        size="icon"
                        class="text-white hover:bg-white/15 hover:text-white"
                        aria-label="Keyboard shortcuts"
                >
                  <Keyboard class="size-4" />
                </Button>
              {/snippet}
            </Popover.Trigger>
            <Popover.Content side="top" align="end" class="w-64 p-3">
              <p class="mb-2 text-xs font-medium text-muted-foreground">
                Keyboard shortcuts
              </p>
              <dl class="space-y-1.5 text-sm">
                {@render shortcut("Play / pause", "Space")}
                {@render shortcut("Seek ±5s", "← →")}
                {@render shortcut("Seek ±10s", "J L")}
                {@render shortcut("Volume", "↑ ↓")}
                {@render shortcut("Mute", "M")}
                {@render shortcut("Subtitles", "C")}
                {@render shortcut("Jump to 0–90%", "0–9")}
              </dl>
            </Popover.Content>
          </Popover.Root>
        </div>
      </div>
    </div>
  {/if}

  <!-- ── Skip segment button (IntroDB) ────────────────────────────────────── -->
  {#if activeSegment}
    <!-- svelte-ignore a11y_consider_explicit_label -->
    <button
      class="absolute bottom-20 right-6 z-20 rounded border border-white/50 bg-black/70 px-4 py-2 text-sm font-medium text-white backdrop-blur-sm transition-colors hover:bg-white/20"
      onclick={(e) => { e.stopPropagation(); skipSegment(activeSegment!); }}
      transition:fade={{ duration: 150 }}
    >
      Skip {activeSegment.label}
    </button>
  {/if}

  <!-- ── Up-next overlay (F6) ─────────────────────────────────────────────── -->
  {#if showUpNext && nextEp}
    <div
      class="absolute bottom-20 right-6 z-20 w-72 overflow-hidden rounded-lg border border-white/20 bg-black/80 text-white shadow-2xl backdrop-blur-sm"
      transition:fade={{ duration: 150 }}
    >
      <div class="flex items-start justify-between gap-2 px-4 pt-3">
        <p class="text-xs font-medium uppercase tracking-wide text-white/60">
          Up next · S{nextEp.season}E{nextEp.episode.episode_number}
        </p>
        <button
          class="shrink-0 rounded p-0.5 text-white/60 transition-colors hover:bg-white/10 hover:text-white"
          onclick={(e) => { e.stopPropagation(); dismissUpNext(); }}
          aria-label="Dismiss"
        >
          <X class="size-4" />
        </button>
      </div>
      {#if !$settings?.hideSpoilers && nextEp.episode.name}
        <p class="truncate px-4 pb-3 text-sm text-white/90">{nextEp.episode.name}</p>
      {:else}
        <div class="pb-3"></div>
      {/if}
      <button
        class="flex w-full items-center justify-center gap-2 bg-white/10 px-4 py-2.5 text-sm font-medium transition-colors hover:bg-white/20"
        onclick={(e) => { e.stopPropagation(); advance(); }}
      >
        <SkipForward class="size-4" />
        Watch now
      </button>
      {#if countdownSecs !== null}
        <div class="px-4 py-2">
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
  {/if}

  <!-- ── Loading screen ─────────────────────────────────────────────────────── -->
  {#if Player.available && !canPlay}
    <div class="absolute inset-0 z-20 flex flex-col items-center justify-center bg-black">
      {#if media?.poster_path}
        <div
                class="absolute inset-0 scale-110 bg-cover bg-center"
                style="background-image: url('{media.poster_path}'); filter: blur(5px); opacity: 0.35;"
        ></div>
      {/if}
      <div class="absolute inset-0 bg-black/65"></div>
      {#if logoUrl}
        <img
                src={logoUrl}
                alt={title}
                class="relative z-10 max-h-40 max-w-xs object-contain drop-shadow-2xl"
        />
      {:else if media?.poster_path}
        <img
                src={media.poster_path}
                alt={title}
                class="relative z-10 h-48 w-32 rounded-lg object-cover shadow-2xl"
        />
      {:else if title}
        <span class="relative z-10 px-8 text-center text-3xl font-bold text-white">{title}</span>
      {/if}
      <Spinner class="relative z-10 mt-6 size-10" />
      <p class="relative z-10 mt-4 text-sm text-white/50">{loadingMessage}</p>
      {#if takingAWhile}
        <p class="relative z-10 mt-2 text-xs text-white/40" transition:fade={{ duration: 150 }}>
          This is taking a while…
        </p>
        <Button
          variant="outline"
          size="sm"
          class="relative z-10 mt-4 text-white"
          onclick={() => triggerPlaybackFailed()}
        >
          Cancel
        </Button>
      {/if}
    </div>
  {/if}
</div>