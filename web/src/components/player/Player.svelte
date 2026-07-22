<script lang="ts">
  import type { Media, TVEpisode } from "$lib/types/tmdb";
  import type { Stream, TimestampData, TimestampSegment } from "$lib/types/addons";
  import { onDestroy, untrack } from "svelte";
  import { fade } from "svelte/transition";
  import SkipSegmentButton from "./SkipSegmentButton.svelte";
  import UpNextOverlay from "./UpNextOverlay.svelte";
  import LoadingScreen from "./LoadingScreen.svelte";
  import EpisodesSidebar from "./EpisodesSidebar.svelte";
  import PlayerControls from "./PlayerControls.svelte";
  import type { SubSel } from "$lib/player/subtitles";
  import { computeChapterBars } from "$lib/player/chapters";
  import { api } from "$lib/api";
  import { settings } from "$lib/stores/settings";
  import { Player } from "$lib/player/player.svelte";
  import { loadAspectMode, saveAspectMode, ASPECT_LABELS } from "$lib/player/aspectRatio";
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
  import { SvelteSet } from "svelte/reactivity";
  import { libraryChanged } from "$lib/stores/library";

  // ─── Props (unchanged from the old Player) ──────────────────────────────────

  let {
    src,
    media,
    externalSubtitles = [],
    season = undefined,
    episode = undefined,
    fileIdx = undefined,
    onPlaybackFailed = undefined,
    onPlayNext = undefined,
    onPlayStream = undefined,
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
    /** Fired once (per src) when playback never starts — a startup timeout
     * or a stalled torrent that never got peers. The caller (App.svelte)
     * decides what to do: try the next candidate stream, or give up. */
    onPlaybackFailed?: () => void;
    /** Fired when the up-next overlay's "Watch now" is clicked, its countdown
     * finishes, or the episode ends with autoplay on. Absence disables the
     * whole up-next feature (no overlay, no autoplay-advance) — the caller
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

  // ─── Playback lifecycle ─────────────────────────────────────────────────────

  // mpv plays the backend stream URL directly — no probe, no HLS, no transcode.
  // The Go backend still serves it (so torrent streaming keeps working); mpv
  // just consumes it over http with range requests for seeking.
  let appliedAudioDefault = false;
  let appliedSubDefault = false;
  const addedExternal = new SvelteSet<string>(); // external sub ids already sub-add'd

  // Per-show remembered audio/subtitle/speed picks (device-local, keyed by
  // media.id). Loaded on every src change and consulted by the auto-select
  // effects below (they prefer these over the global defaults); updated when
  // the user changes a track or the speed. Reset to {} for an unknown title.
  let showPrefs = $state<ShowTrackPrefs>({});

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
    episodesOpen = false;
    // Trakt: stop any active scrobble for the outgoing stream before the new
    // one starts. Use traktSavedCtx (captured at 'start' time) because by the
    // time this effect re-runs the season/episode props may already reflect
    // the new episode, making progressCtx() return the wrong context.
    untrack(() => {
      if (traktState === "started" || traktState === "paused") {
        const stopCtx = traktSavedCtx;
        if (stopCtx) sendTraktScrobble("stop", stopCtx);
      }
      traktState = "idle";
      traktSavedCtx = null;
    });
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
    Player.play(api.playUrl(src, { season, episode, fileIdx }));
    untrack(() => {
      Player.setAspectMode(media ? loadAspectMode(media.id) : "fit");
      // Load this title's remembered picks. Audio/subtitle are applied by the
      // auto-select effects (which read showPrefs); speed has no such effect,
      // so apply it here — play() just reset it to 1×.
      showPrefs = media ? loadShowTrackPrefs(media.id) : {};
      if (showPrefs.speed && showPrefs.speed !== 1) {
        Player.setPlaybackSpeed(showPrefs.speed);
      }
    });
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
      // Trakt: fire a stop scrobble if still active so the server records the
      // resume point. At destroy time props haven't changed yet, so
      // progressCtx() still reflects the correct episode.
      if (traktState === "started" || traktState === "paused") {
        sendTraktScrobble("stop");
      }
      if (media && Player.duration > 0) {
        // Pass the actual ended state so the "ended" effect and onDestroy firing
        // in the same tick don't race: if ended=true the #completedSaved guard
        // in ProgressSaver prevents any downgrade; if false the record is left
        // as-is (not marked complete when it wasn't).
        // One bump per session teardown — never on the ~10s maybeSave ticks,
        // which would make every libraryChanged subscriber refetch during
        // playback. The bump waits for the save to land so the refetch it
        // triggers can't race the POST and read pre-save state.
        void progress.saveNow(
                Player.position,
                Player.duration,
                progressCtx,
                Player.ended,
        ).then(() => libraryChanged.update((n) => n + 1));
      }
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

  // Load any saved position when the source changes. rememberPosition is read
  // untracked: a settings store refresh mid-playback (the periodic auth sync
  // pulls merged settings) must not re-run this effect — resetting and
  // re-loading here would make the resume effect below seek back to the last
  // saved position, which trails live playback by up to 10s.
  $effect(() => {
    if (!media || !src) return;
    progress.reset();
    if (untrack(() => $settings?.rememberPosition) === false) return;
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
    if (Player.ended && media) {
      void progress.saveNow(
              Player.duration,
              Player.duration,
              progressCtx,
              true,
      ).then(() => libraryChanged.update((n) => n + 1));
      // Trakt: stop at 100% — registers as watched on Trakt's side (≥80%).
      if (traktState !== "stopped") {
        traktState = "stopped";
        untrack(() => sendTraktScrobble("stop"));
      }
    }
  });

  // ─── Trakt scrobbling ────────────────────────────────────────────────────────
  // Plain lets — not $state, so writes from within effects don't trigger
  // re-runs of the effects that read Player.paused / Player.ended.

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
    const c = ctx ?? progressCtx();
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

  // Send start / pause events on play-state transitions. Only canPlay and
  // Player.paused are tracked deps — progressCtx() / settings / position are
  // read inside untrack() so position ticks and settings syncs don't loop.
  $effect(() => {
    if (!canPlay) return;
    if (!Player.paused && traktState !== "started") {
      traktState = "started";
      untrack(() => {
        traktSavedCtx = progressCtx();
        sendTraktScrobble("start", traktSavedCtx);
      });
    } else if (Player.paused && traktState === "started") {
      traktState = "paused";
      untrack(() => sendTraktScrobble("pause"));
    }
  });

  // ─── Torrent download progress (SSE, hash sources only) ──────────────────────

  const isHash = $derived(!src.startsWith("http"));
  const torrent = new TorrentProgress();

  $effect(() => {
    if (!isHash) return;
    return torrent.start(src, { season, episode, fileIdx });
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

  // ─── IntroDB timestamps ──────────────────────────────────────────────────────

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

  // ─── Seek bar chapter markers ────────────────────────────────────────────────
  // Computed here (it needs the fetched timestamps + duration) and passed down
  // to PlayerControls → SeekBar, which colours and fills the pills.
  const chapterBars = $derived(computeChapterBars(timestamps, Player.duration));

  // ─── Auto-select preferred audio track ──────────────────────────────────────
  // mpv/ffmpeg tag embedded audio tracks with ISO 639-2 (three-letter, e.g.
  // "jpn"), while the setting and TMDB's original_language are ISO 639-1
  // ("ja") — langMatches normalizes both sides before comparing so this
  // doesn't silently no-op for every non-English track.

  $effect(() => {
    if (appliedAudioDefault || Player.audioTracks.length <= 1) return;
    // A remembered per-show audio language wins over the global default.
    const prefLang = showPrefs.audioLang;
    let targetLang: string | null | undefined = prefLang;
    if (!prefLang) {
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
      targetLang = setting === "original" ? originalLang : setting;
    }
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
  // applies these live and keeps them across loadfile, so no per-file re-arming
  // is needed beyond this.
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
    if (subSelection.kind !== "off") {
      chooseSubtitle({ kind: "off" });
      flash("Subtitles off");
      return;
    }
    const emb = Player.subtitleTracks[0];
    if (emb) {
      chooseSubtitle({ kind: "embedded", id: emb.id });
      flash("Subtitles on");
      return;
    }
    const ext = externalSubtitles[0];
    if (ext) {
      chooseSubtitle({ kind: "external", id: ext.id });
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
    if (e.key === "Escape" && !canPlay && !menuOpen && _onclose) {
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

  // ─── In-player subtitle style controls ───────────────────────────────────────
  // Live-tweak size/position/background from the subtitle menu. The change is
  // applied to mpv immediately for instant preview, and the persisted settings
  // write is debounced so dragging a slider doesn't spam the settings PUT (the
  // "Apply subtitle-style preferences" $effect also re-applies it once the store
  // updates — idempotent).
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

  // ─── Persist per-show track / speed picks on explicit user action ────────────
  // Wrappers around the raw Player calls used by the control menus and the
  // captions/speed keys, so an actual user choice (not the auto-select effects)
  // is remembered for this title. Audio/subtitle are stored by language so they
  // re-match on the next episode; speed is stored verbatim.
  function chooseAudioTrack(track: { id: number; lang: string }): void {
    Player.setAudioTrack(track.id);
    if (media) saveShowTrackPrefs(media.id, { audioLang: track.lang || undefined });
  }

  function chooseSubtitle(sel: SubSel): void {
    selectSubtitle(sel);
    if (!media) return;
    if (sel.kind === "off") {
      saveShowTrackPrefs(media.id, { sub: { kind: "off" } });
      return;
    }
    const lang =
      sel.kind === "embedded"
        ? Player.subtitleTracks.find((x) => x.id === sel.id)?.lang
        : externalSubtitles.find((x) => x.id === sel.id)?.lang;
    // No language tag → can't re-match next episode, so don't store a pref.
    if (lang) saveShowTrackPrefs(media.id, { sub: { kind: "lang", lang } });
  }

  function chooseSpeed(speed: number): void {
    Player.setPlaybackSpeed(speed);
    if (media) saveShowTrackPrefs(media.id, { speed });
  }

  // ─── Helpers ────────────────────────────────────────────────────────────────

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
        onwheel={(e) => { if (!menuOpen) nudgeVolume(e.deltaY < 0 ? 5 : -5); }}
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
      <PlayerControls
        {externalSubtitles}
        {subSelection}
        {chapterBars}
        {isHash}
        {torrent}
        showEpisodes={media?.media_type === "tv" && !!onPlayStream}
        bind:episodesOpen
        {toggleMute}
        {chooseAudioTrack}
        {chooseSubtitle}
        {updateSubStyle}
        {chooseSpeed}
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
  {#if activeSegment}
    <SkipSegmentButton
      label={activeSegment.label}
      onSkip={() => skipSegment(activeSegment!)}
    />
  {/if}

  <!-- ── Up-next overlay (F6) ─────────────────────────────────────────────── -->
  {#if showUpNext && nextEp}
    <UpNextOverlay
      {nextEp}
      {countdownSecs}
      hideSpoilers={$settings?.hideSpoilers ?? false}
      onDismiss={dismissUpNext}
      onWatchNow={advance}
    />
  {/if}

  <!-- ── Loading screen ─────────────────────────────────────────────────────── -->
  {#if Player.available && !canPlay}
    <LoadingScreen
      {media}
      {title}
      {logoUrl}
      {loadingMessage}
      {takingAWhile}
      onCancel={triggerPlaybackFailed}
      onClose={_onclose}
    />
  {/if}
</div>