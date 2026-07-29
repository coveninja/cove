// $lib/player/playerCore.svelte.ts
//
// The playback logic shared by all three player shells: the src-change
// lifecycle, the start watchdog, watch-progress wiring, torrent progress,
// logo/IntroDB fetches, segment auto-skip, automatic audio/subtitle selection,
// and the up-next overlay with its autoplay countdown.
//
// Player.svelte, MobilePlayer.svelte and TvPlayer.svelte each carried a
// byte-identical copy of everything here — ~350 lines, three times over. What
// remains in each shell is what genuinely differs: desktop's Trakt scrobbling
// and keyboard handling, mobile's touch scrubbing, TV's D-pad panels, and each
// shell's own controls/volume/aspect/OSD.
//
// Like the rest of $lib this module declares no $effect of its own. Every
// method below is meant to be called from exactly one component $effect and
// reads its reactive dependencies synchronously, so a bare
// `$effect(() => core.x())` tracks precisely what the original inline effect
// tracked. Methods returning a teardown are documented as such.
//
// Props and the settings store cross the module boundary as getters, never
// snapshots — a $derived that captured a value at construction would memoise
// it forever.

import { untrack } from "svelte";
import { SvelteSet } from "svelte/reactivity";

import { api } from "$lib/api";
import { langMatches } from "$lib/lang";
import { nextAiredEpisode } from "$lib/nextEpisode";
import { computeChapterBars } from "$lib/player/chapters";
import { Player } from "$lib/player/player.svelte";
import {
  ProgressSaver,
  type ProgressContext,
} from "$lib/player/progressSaver.svelte.js";
import type { SubSel } from "$lib/player/subtitles";
import { TorrentProgress } from "$lib/player/torrentProgress.svelte.js";
import {
  loadShowTrackPrefs,
  saveShowTrackPrefs,
  type ShowTrackPrefs,
} from "$lib/player/trackPrefs";
import { loadAspectMode } from "$lib/player/aspectRatio";
import { libraryChanged } from "$lib/stores/library";
import type { TimestampData, TimestampSegment } from "$lib/types/addons";
import type { Settings } from "$lib/types/settings";
import type { Media, TVEpisode } from "$lib/types/tmdb";

/** An external (addon-supplied) subtitle, as passed to the player. */
export interface ExternalSub {
  id: string;
  url: string;
  lang: string;
}

export interface PlayerCoreOptions {
  getSrc: () => string;
  getMedia: () => Media | undefined;
  getSeason: () => number | undefined;
  getEpisode: () => number | undefined;
  getFileIdx: () => number | undefined;
  getExternalSubtitles: () => ExternalSub[];
  getPendingMessage: () => string | undefined;
  /** The component's `() => $settings`. */
  getSettings: () => Settings | undefined;
  /** The shell's display title — each computes it slightly differently. */
  getTitle: () => string;
  /**
   * Whether failures before playback starts should automatically hand control
   * back to the playback store for candidate fallback. Manual stream picks
   * return false and wait until the user explicitly cancels.
   */
  getAutomaticStartupRecovery?: () => boolean;

  onPlaybackFailed?: () => void;
  onPlayNext?: (season: number, episode: number) => void;
  /**
   * Whether the shell can actually advance to a next episode. Both the up-next
   * overlay and advance() gate on it. Supply it when `onPlayNext` is a
   * forwarding closure (so it is always truthy) but the underlying prop may be
   * absent; defaults to "onPlayNext was provided".
   */
  hasPlayNext?: () => boolean;

  /**
   * Fires during the src-change reset, after the shared per-src state has been
   * cleared and before playback starts. Each shell clears its own transient UI
   * here: desktop closes the episodes sidebar and stops its Trakt scrobble,
   * mobile resets touch scrubbing, TV resets scrubbing and playback speed.
   */
  onSrcChange?: () => void;

  /** Fires inside the ended-effect, after progress is saved. Desktop uses it
   *  to send its Trakt "stop". */
  onEnded?: () => void;

  /** Fires at the top of destroy(), before the final save. Desktop uses it for
   *  a last Trakt "stop". */
  onBeforeDestroy?: () => void;

  /**
   * What to pass as `completed` on the final save at destroy time. Desktop and
   * TV report `Player.ended`; the mobile shell has always passed `false` (it
   * tears the player down on backgrounding, where "ended" is not meaningful).
   * Defaults to the desktop/TV behaviour.
   */
  getDestroyCompleted?: () => boolean;
}

export class PlayerCore {
  // ── Per-src lifecycle state ──────────────────────────────────────────────
  switching = $state(false);
  takingAWhile = $state(false);
  originalLang = $state<string | null>(null);
  showPrefs = $state<ShowTrackPrefs>({});
  subSelection = $state<SubSel>({ kind: "off" });

  // ── Up-next overlay state ────────────────────────────────────────────────
  nextEp = $state<{ season: number; episode: TVEpisode } | null>(null);
  upNextDismissed = $state(false);
  countdownSecs = $state<number | null>(null);

  // ── Fetched metadata ─────────────────────────────────────────────────────
  logoUrl = $state<string | null>(null);
  timestamps = $state<TimestampData | null>(null);

  readonly progress = new ProgressSaver();
  readonly torrent = new TorrentProgress();

  #opts: PlayerCoreOptions;

  #appliedAudioDefault = false;
  #appliedSubDefault = false;
  #addedExternal = new SvelteSet<string>(); // external sub ids already sub-add'd
  #autoSkippedSegments = new SvelteSet<string>();
  #failedFired = false;
  #everCanPlay = false;
  #advanced = false; // guards advance() from firing twice for one src
  #resolvingNextEp = false; // per-src guard so nextAiredEpisode fires once
  #retryPosition: number | null = null;

  constructor(opts: PlayerCoreOptions) {
    this.#opts = opts;
  }

  // ── Derived view state ───────────────────────────────────────────────────

  get streamDiscoveryPending(): boolean {
    return !this.#opts.getSrc() && this.#opts.getPendingMessage() !== undefined;
  }

  get isHash(): boolean {
    const src = this.#opts.getSrc();
    return !!src && !src.startsWith("http");
  }

  get canPlay(): boolean {
    return (
      !!this.#opts.getSrc() &&
      !this.switching &&
      !Player.interrupted &&
      Player.ready &&
      Player.duration > 0
    );
  }

  /** True once playback genuinely started for the current src. Read by the
   *  shells' own ended/advance guards. */
  get everCanPlay(): boolean {
    return this.#everCanPlay;
  }

  /** True once advance() has fired for the current src. */
  get advanced(): boolean {
    return this.#advanced;
  }

  get loadingMessage(): string {
    if (this.streamDiscoveryPending) return this.#opts.getPendingMessage()!;
    if (!this.isHash) return "Buffering…";
    return this.torrent.peers > 0
      ? `Connecting · ${this.torrent.peers} peers · ${this.torrent.speed}`
      : "Connecting to peers…";
  }

  /** The intro/recap/credits/preview segment the position currently sits in. */
  activeSegment = $derived.by(() => {
    if (!this.timestamps || !this.canPlay) return null;
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
      check(this.timestamps.recap, "recap", "Recap") ||
      check(this.timestamps.intro, "intro", "Intro") ||
      check(this.timestamps.credits, "credits", "Credits") ||
      check(this.timestamps.preview, "preview", "Preview")
    );
  });

  chapterBars = $derived(computeChapterBars(this.timestamps, Player.duration));

  /**
   * Shown once there's a resolved next episode and the player is near the
   * episode's end — either IntroDB flagged a credits segment, or we're within
   * the last 40s of the file (no credits data for this title).
   *
   * A getter rather than a $derived field: it reads #opts, and a $derived
   * class-field initialiser is statically ordered before the constructor
   * assigns it. Everything it touches is cheap.
   */
  get showUpNext(): boolean {
    return (
      !!this.nextEp &&
      !this.upNextDismissed &&
      this.#hasPlayNext() &&
      (this.activeSegment?.type === "credits" ||
        (Player.duration > 0 &&
          Player.duration - Player.position < 40 &&
          this.canPlay) ||
        Player.ended)
    );
  }

  // ── Shared imperative helpers ────────────────────────────────────────────

  progressCtx = (): ProgressContext => {
    const media = this.#opts.getMedia()!;
    return {
      tmdbId: media.id,
      mediaType: media.media_type,
      title: this.#opts.getTitle(),
      posterPath: media.poster_path ?? "",
      voteAverage: media.vote_average ?? 0,
      lastAirDate: (media as { last_air_date?: string }).last_air_date ?? "",
      season: this.#opts.getSeason() ?? null,
      episode: this.#opts.getEpisode() ?? null,
      probedDuration: null, // mpv reports the real duration
    };
  };

  skipSegment(seg: { seg: TimestampSegment }): void {
    Player.seek((seg.seg.end_ms ?? Player.duration * 1000) / 1000);
  }

  /** Hide the up-next overlay for this source. The countdown effect clears
   *  itself once showUpNext flips false, so nothing else to do here. */
  dismissUpNext(): void {
    this.upNextDismissed = true;
  }

  #hasPlayNext(): boolean {
    return this.#opts.hasPlayNext
      ? this.#opts.hasPlayNext()
      : !!this.#opts.onPlayNext;
  }

  #automaticStartupRecovery(): boolean {
    return this.#opts.getAutomaticStartupRecovery?.() ?? true;
  }

  advance(): void {
    const onPlayNext = this.#opts.onPlayNext;
    if (this.#advanced || !this.nextEp || !onPlayNext || !this.#hasPlayNext())
      return;
    this.#advanced = true;
    // Mirrors the ended-effect: mark the current episode complete so the
    // library records it (and the backend's next-episode prefetch worker sees
    // a completed episode to trigger off of).
    if (this.#opts.getMedia() && Player.duration > 0) {
      this.progress.saveNow(
        Player.duration,
        Player.duration,
        this.progressCtx,
        true,
      );
    }
    onPlayNext(this.nextEp.season, this.nextEp.episode.episode_number);
  }

  selectSubtitle(sel: SubSel): void {
    this.subSelection = sel;
    if (sel.kind === "off") {
      Player.setSubtitleTrack(-1);
      return;
    }
    if (sel.kind === "embedded") {
      Player.setSubtitleTrack(sel.id);
      return;
    }
    // External: add once (mpv selects it on add), then it lives as a track.
    const ext = this.#opts.getExternalSubtitles().find((s) => s.id === sel.id);
    if (!ext) return;
    if (this.#addedExternal.has(ext.id)) {
      // already loaded — find the matching mpv track by language and select it
      const t = Player.subtitleTracks.find((x) => x.lang === ext.lang);
      if (t) Player.setSubtitleTrack(t.id);
    } else {
      this.#addedExternal.add(ext.id);
      Player.addSubtitle(
        api.subtitleProxyUrl(ext.url),
        ext.lang.toUpperCase(),
        ext.lang,
      );
    }
  }

  /** User-driven subtitle pick — also remembers the choice for this show. */
  chooseSubtitle(sel: SubSel): void {
    this.selectSubtitle(sel);
    const media = this.#opts.getMedia();
    if (!media) return;
    if (sel.kind === "off") {
      saveShowTrackPrefs(media.id, { sub: { kind: "off" } });
      return;
    }
    const lang =
      sel.kind === "embedded"
        ? Player.subtitleTracks.find((x) => x.id === sel.id)?.lang
        : this.#opts.getExternalSubtitles().find((x) => x.id === sel.id)?.lang;
    // No language tag → can't re-match next episode, so don't store a pref.
    if (lang) saveShowTrackPrefs(media.id, { sub: { kind: "lang", lang } });
  }

  /**
   * User-driven audio pick — also remembers the choice for this show.
   *
   * The desktop shell keeps its own variant: it takes the track object and
   * writes a pref even for an untagged track, where this one skips the write.
   * Unifying them would change desktop behaviour, so it stays separate.
   */
  chooseAudioTrack(id: number): void {
    Player.setAudioTrack(id);
    const media = this.#opts.getMedia();
    const lang = Player.audioTracks.find((t) => t.id === id)?.lang;
    if (media && lang) saveShowTrackPrefs(media.id, { audioLang: lang });
  }

  chooseSpeed(speed: number): void {
    Player.setPlaybackSpeed(speed);
    const media = this.#opts.getMedia();
    if (media) saveShowTrackPrefs(media.id, { speed });
  }

  // ── Lifecycle: one component $effect each ────────────────────────────────

  /**
   * The src-change reset: clears every per-src flag, lets the shell clear its
   * own transient UI via onSrcChange, applies the volume settings and starts
   * playback, then restores this title's aspect mode and remembered picks.
   */
  startPlayback(): void {
    const src = this.#opts.getSrc();
    if (!src || !Player.available) return;
    this.switching = true;
    this.#appliedAudioDefault = false;
    this.#appliedSubDefault = false;
    this.originalLang = null;
    this.nextEp = null;
    this.upNextDismissed = false;
    this.#advanced = false;
    this.countdownSecs = null;
    this.#resolvingNextEp = false;
    this.#retryPosition = null;
    this.#addedExternal.clear();
    this.#autoSkippedSegments.clear();
    this.subSelection = { kind: "off" };

    this.#opts.onSrcChange?.();

    const season = this.#opts.getSeason();
    const episode = this.#opts.getEpisode();
    const fileIdx = this.#opts.getFileIdx();
    const media = this.#opts.getMedia();

    // Apply volume settings at stream start. Read inside untrack so that a
    // settings change while watching doesn't re-run this effect and restart
    // the stream.
    untrack(() => {
      const s = this.#opts.getSettings();
      if (s?.openOnMute) {
        Player.setVolume(0);
      } else if (s?.defaultVolume != null) {
        Player.setVolume(Math.round(s.defaultVolume * 100));
      }
    });
    Player.play(api.playUrl(src, { season, episode, fileIdx }));
    untrack(() => {
      Player.setAspectMode(media ? loadAspectMode(media.id) : "fit");
      // Load this title's remembered picks. Audio/subtitle are applied by the
      // auto-select effects (which read showPrefs); speed has no such effect,
      // so apply it here — play() just reset it to 1×.
      this.showPrefs = media ? loadShowTrackPrefs(media.id) : {};
      if (this.showPrefs.speed && this.showPrefs.speed !== 1) {
        Player.setPlaybackSpeed(this.showPrefs.speed);
      }
    });
  }

  /**
   * Resolve original_language for the "original" audio preference. media is
   * often only a partial object (library-launched playback carries just
   * id/media_type/etc.), so original_language may not be populated even though
   * the title has one — fetch the full record in that case rather than
   * treating "field absent" as "title has no original language".
   */
  resolveOriginalLang(): void {
    const src = this.#opts.getSrc();
    if (!src || this.#opts.getSettings()?.defaultAudioLang !== "original")
      return;
    if (this.originalLang !== null) return;
    const m = this.#opts.getMedia();
    if (!m) return;
    if (m.original_language) {
      this.originalLang = m.original_language;
      return;
    }
    const requestedSrc = src; // guards against a stale response after switching src
    untrack(() => {
      api
        .getMediaByID(m.id, m.media_type)
        .then((full) => {
          if (this.#opts.getSrc() !== requestedSrc) return;
          // Empty string is a valid "resolved to nothing" — still distinct
          // from null (unresolved), so the auto-select effect stops retrying.
          this.originalLang = full.original_language ?? "";
        })
        .catch(() => {
          if (this.#opts.getSrc() !== requestedSrc) return;
          this.originalLang = ""; // give up — treat as unresolvable
        });
    });
  }

  clearSwitchingWhenReady(): void {
    if (this.switching && Player.ready && Player.duration > 0) {
      this.switching = false;
    }
  }

  // ── Playback-start watchdog ──────────────────────────────────────────────
  // If a stream never actually starts — a dead torrent with no peers, a dead
  // direct link — mpv just sits there with no error to catch, and without this
  // the loading screen spins forever. Two independent triggers call
  // triggerPlaybackFailed(): a startup timer (armed fresh for every src) and
  // torrent.stalled (torrentProgress's give-up-after-repeated-SSE-errors
  // signal). #failedFired guards against both firing, and #everCanPlay guards
  // against firing after playback already succeeded once for this src (a later
  // stall once the swarm empties out mid-watch shouldn't retrigger a "failed
  // to start").

  triggerPlaybackFailed(): void {
    if (this.#failedFired || this.#everCanPlay) return;
    this.#failedFired = true;
    this.#opts.onPlaybackFailed?.();
  }

  /** Arms the watchdog for the current src. Returns its teardown. */
  armWatchdog(): (() => void) | undefined {
    const src = this.#opts.getSrc();
    if (!src || !Player.available) return;
    this.#failedFired = false;
    this.#everCanPlay = false;
    this.takingAWhile = false;

    // Hash (torrent) sources get longer: the backend's own metadata-fetch
    // timeout is 45s — let that fail first so the error surfaces from the
    // right layer instead of racing it.
    const isHashSrc = !src.startsWith("http");
    const failTimeoutMs = isHashSrc ? 50_000 : 25_000;
    const failTimer = this.#automaticStartupRecovery()
      ? setTimeout(() => this.triggerPlaybackFailed(), failTimeoutMs)
      : undefined;
    const slowTimer = setTimeout(() => {
      this.takingAWhile = true;
    }, 15_000);

    return () => {
      if (failTimer !== undefined) clearTimeout(failTimer);
      clearTimeout(slowTimer);
    };
  }

  markPlaybackStarted(): void {
    if (this.canPlay) {
      this.#everCanPlay = true;
      this.takingAWhile = false;
    }
  }

  /** A stalled torrent is effectively dead — treat it as a startup timeout
   *  rather than leaving the loading screen spinning. */
  failOnStalledTorrent(): void {
    if (
      this.#automaticStartupRecovery() &&
      this.torrent.stalled &&
      !this.canPlay
    ) {
      this.triggerPlaybackFailed();
    }
  }

  /**
   * A terminal mpv signal before playback starts is the existing dead-stream
   * case and may automatically fall back to another candidate. Once playback
   * has started, keep the current session open instead: the loading screen
   * offers an explicit retry or stream switch, and completion/autoplay remain
   * disabled because Player.ended is false.
   */
  failOnPlaybackInterruption(): void {
    if (
      this.#automaticStartupRecovery() &&
      Player.interrupted &&
      !this.#everCanPlay
    ) {
      this.triggerPlaybackFailed();
    }
  }

  /** Reload the same source after a mid-stream failure and resume at the last
   * reported position once mpv has loaded it again. */
  retryPlayback(): void {
    if (!Player.interrupted || !this.#opts.getSrc()) return;
    const resumeAt = Player.position;
    this.startPlayback();
    this.#retryPosition = resumeAt;
  }

  /** Called from a component effect after clearSwitchingWhenReady(). */
  resumeRetriedPlayback(): void {
    if (this.#retryPosition === null || !this.canPlay) return;
    const resumeAt = this.#retryPosition;
    this.#retryPosition = null;
    Player.seek(resumeAt);
  }

  /** User-requested fallback after a stream that had already been playing
   * fails. This intentionally bypasses the startup watchdog's everCanPlay
   * guard because it is an explicit action, not an automatic transition. */
  tryAnotherStream(): void {
    this.#opts.onPlaybackFailed?.();
  }

  // ── Watch progress ───────────────────────────────────────────────────────

  /**
   * Load any saved position when the source changes. rememberPosition is read
   * untracked: a settings store refresh mid-playback (the periodic auth sync
   * pulls merged settings) must not re-run this — resetting and re-loading
   * would make resumeProgress() seek back to the last saved position, which
   * trails live playback by up to 10s.
   */
  loadProgress(): void {
    const media = this.#opts.getMedia();
    if (!media || !this.#opts.getSrc()) return;
    this.progress.reset();
    if (untrack(() => this.#opts.getSettings()?.rememberPosition) === false)
      return;
    this.progress.load(
      media.id,
      media.media_type,
      this.#opts.getSeason() ?? null,
      this.#opts.getEpisode() ?? null,
    );
  }

  /** Seek to the saved position once, the first time playback is ready. */
  resumeProgress(): void {
    if (!this.canPlay) return;
    this.progress.resume((t) => Player.seek(t));
  }

  /** Throttled save while playing (re-runs as position ticks). */
  saveProgressTick(): void {
    const pos = Player.position;
    if (!this.canPlay || !this.#opts.getMedia() || Player.paused) return;
    this.progress.maybeSave(pos, Player.duration, this.progressCtx);
  }

  /** Mark complete at end of file. */
  saveProgressOnEnded(): void {
    if (Player.ended && this.#opts.getMedia()) {
      void this.progress
        .saveNow(Player.duration, Player.duration, this.progressCtx, true)
        .then(() => libraryChanged.update((n) => n + 1));
      this.#opts.onEnded?.();
    }
  }

  /** Torrent download progress (SSE, hash sources only). Returns a teardown. */
  trackTorrentProgress(): (() => void) | undefined {
    const src = this.#opts.getSrc();
    if (!src || !this.isHash) return;
    return this.torrent.start(src, {
      season: this.#opts.getSeason(),
      episode: this.#opts.getEpisode(),
      fileIdx: this.#opts.getFileIdx(),
    });
  }

  // ── Fetched metadata ─────────────────────────────────────────────────────

  loadLogo(): void {
    const m = this.#opts.getMedia();
    if (!m) {
      this.logoUrl = null;
      return;
    }
    this.logoUrl = null;
    const requestedId = m.id; // guard against stale response after media changes
    api
      .getLogos(m.id, m.media_type)
      .then((logos) => {
        if (this.#opts.getMedia()?.id !== requestedId) return;
        this.logoUrl = logos[0] ?? null;
      })
      .catch(() => {});
  }

  loadTimestamps(): void {
    const m = this.#opts.getMedia();
    if (!m) {
      this.timestamps = null;
      return;
    }
    this.timestamps = null;
    const requestedSrc = this.#opts.getSrc(); // guard against stale response after switching src
    api
      .getTimestamps(m.id, {
        season: this.#opts.getSeason(),
        episode: this.#opts.getEpisode(),
      })
      .then((data) => {
        if (this.#opts.getSrc() !== requestedSrc) return;
        this.timestamps = data;
      })
      .catch((e) => {
        console.warn("[introdb] fetch failed:", e);
      });
  }

  /**
   * Auto-skip segments when the matching setting is enabled.
   * #autoSkippedSegments avoids re-skipping if the user seeks back; the .has()
   * check is wrapped in untrack() so mutating the set doesn't spuriously
   * re-run the calling effect.
   */
  autoSkipSegment(): void {
    const seg = this.activeSegment;
    const s = this.#opts.getSettings();
    if (!seg || !s) return;

    const segKey = `${seg.type}-${seg.seg.start_ms ?? 0}`;
    if (untrack(() => this.#autoSkippedSegments.has(segKey))) return;

    const shouldSkip =
      (seg.type === "intro" && s.autoSkipIntro) ||
      (seg.type === "recap" && s.autoSkipRecap) ||
      (seg.type === "credits" && s.autoSkipCredits) ||
      (seg.type === "preview" && s.autoSkipPreview);

    if (shouldSkip) {
      this.#autoSkippedSegments.add(segKey);
      Player.seek((seg.seg.end_ms ?? Player.duration * 1000) / 1000);
    }
  }

  // ── Automatic track selection ────────────────────────────────────────────

  /**
   * mpv/ffmpeg tag embedded audio tracks with ISO 639-2 (three-letter, e.g.
   * "jpn"), while the setting and TMDB's original_language are ISO 639-1
   * ("ja") — langMatches normalizes both sides before comparing so this
   * doesn't silently no-op for every non-English track.
   */
  applyAudioDefault(): void {
    if (this.#appliedAudioDefault || Player.audioTracks.length <= 1) return;
    // A remembered per-show audio language wins over the global default.
    const prefLang = this.showPrefs.audioLang;
    let targetLang: string | null | undefined = prefLang;
    if (!prefLang) {
      const setting = this.#opts.getSettings()?.defaultAudioLang;
      if (!setting) return;
      if (setting === "original") {
        // originalLang is still resolving (or media hasn't arrived yet) —
        // don't mark this applied, so it re-runs once it settles instead of
        // permanently giving up on the "original language" preference.
        if (this.originalLang === null) return;
        if (this.originalLang === "") {
          // Resolved to "unresolvable" (title has no original_language) —
          // nothing sensible to match against; leave mpv's default alone.
          this.#appliedAudioDefault = true;
          return;
        }
      }
      targetLang = setting === "original" ? this.originalLang : setting;
    }
    this.#appliedAudioDefault = true;
    const match = Player.audioTracks.find((t) =>
      langMatches(t.lang, targetLang),
    );
    if (match && !match.selected) Player.setAudioTrack(match.id);
  }

  /**
   * Gated on the file being loaded (duration > 0) so embedded tracks have had
   * a chance to populate before we choose between them and the external list.
   * Same 639-1/639-2 mismatch as audio tracks applies here.
   */
  applySubtitleDefault(): void {
    if (this.#appliedSubDefault || !this.canPlay) return;
    const externalSubtitles = this.#opts.getExternalSubtitles();
    // A remembered per-show subtitle choice wins over the global default.
    const pref = this.showPrefs.sub;
    if (pref) {
      if (pref.kind === "off") {
        this.#appliedSubDefault = true;
        this.selectSubtitle({ kind: "off" });
        return;
      }
      const embMatch = Player.subtitleTracks.find((t) =>
        langMatches(t.lang, pref.lang),
      );
      if (embMatch) {
        this.#appliedSubDefault = true;
        this.selectSubtitle({ kind: "embedded", id: embMatch.id });
        return;
      }
      const extMatch = externalSubtitles.find((s) =>
        langMatches(s.lang, pref.lang),
      );
      if (extMatch) {
        this.#appliedSubDefault = true;
        this.selectSubtitle({ kind: "external", id: extMatch.id });
        return;
      }
      // Preferred language not present yet — wait for the external list; only
      // once it's arrived (and still no match) do we fall through to the
      // global-default behaviour below.
      if (externalSubtitles.length === 0) return;
    }
    const s = this.#opts.getSettings();
    if (!s?.subtitlesEnabled) return;
    const lang = s.defaultSubtitleLang;
    const embedded = Player.subtitleTracks.find((t) =>
      langMatches(t.lang, lang),
    );
    if (embedded) {
      this.#appliedSubDefault = true;
      this.selectSubtitle({ kind: "embedded", id: embedded.id });
      return;
    }
    // External list hasn't arrived yet — don't latch; this re-runs when the
    // subtitle fetch resolves and externalSubtitles updates.
    if (externalSubtitles.length === 0) return;
    this.#appliedSubDefault = true;
    const ext =
      externalSubtitles.find((s2) => langMatches(s2.lang, lang)) ??
      externalSubtitles[0];
    if (ext) this.selectSubtitle({ kind: "external", id: ext.id });
  }

  /**
   * Apply subtitle-style preferences (size / position / background box).
   * Re-applies whenever the settings change or the bridge becomes ready. mpv
   * applies these live and keeps them across loadfile, so no per-file
   * re-arming is needed.
   */
  applySubtitleStyle(): void {
    if (!Player.ready) return;
    const s = this.#opts.getSettings();
    if (!s) return;
    Player.setSubtitleStyle(
      s.subtitleSize ?? 100,
      s.subtitlePosition ?? 8,
      s.subtitleBackground ?? false,
    );
  }

  // ── Up-next overlay + autoplay countdown ─────────────────────────────────

  /** Resolve the next aired episode once per src (not on every position tick —
   *  nextAiredEpisode hits the season-episodes endpoint). */
  resolveNextEpisode(): void {
    const src = this.#opts.getSrc();
    const media = this.#opts.getMedia();
    const season = this.#opts.getSeason();
    const episode = this.#opts.getEpisode();
    if (
      !src ||
      !this.canPlay ||
      this.nextEp !== null ||
      this.#resolvingNextEp ||
      media?.media_type !== "tv" ||
      season == null ||
      episode == null
    )
      return;
    this.#resolvingNextEp = true;
    const requestedSrc = src;
    const id = media.id;
    untrack(() => {
      nextAiredEpisode(id, season, episode)
        .then((next) => {
          if (this.#opts.getSrc() !== requestedSrc) return;
          this.nextEp = next;
        })
        .catch(() => {})
        .finally(() => {
          if (this.#opts.getSrc() === requestedSrc)
            this.#resolvingNextEp = false;
        });
    });
  }

  /**
   * Countdown: arm a 10s "Playing in Ns" timer once the overlay shows and
   * autoplay is on. Cleared (and re-armable) whenever showUpNext flips back to
   * false — most notably a seek back away from the episode's end.
   *
   * countdownSecs must only ever be touched inside untrack() here (the
   * interval callback is fine — async callbacks aren't tracked): a tracked
   * read followed by the writes below would make the calling effect re-run on
   * its own write, tearing down the interval it just created and then bailing
   * on the "already counting down" guard — freezing the countdown at 10s
   * forever. With untrack, the only dependencies are showUpNext and autoPlay.
   *
   * Returns the interval teardown.
   */
  runUpNextCountdown(): (() => void) | undefined {
    if (
      !this.showUpNext ||
      !this.#opts.getSettings()?.autoPlay ||
      this.#advanced
    ) {
      untrack(() => (this.countdownSecs = null));
      return;
    }
    untrack(() => (this.countdownSecs = 10));
    const interval = setInterval(() => {
      if (this.countdownSecs === null) return;
      if (this.countdownSecs <= 1) {
        this.countdownSecs = 0;
        // #advanced isn't reactive state, so the calling effect won't
        // automatically re-run (and tear down the interval) just because
        // advance() sets it — clear explicitly here instead.
        clearInterval(interval);
        this.advance();
        return;
      }
      this.countdownSecs -= 1;
    }, 1000);
    return () => clearInterval(interval);
  }

  /**
   * Immediate advance when the file ends with autoplay on — covers the case
   * where the file has no trailing ~40s credits window (or IntroDB has no data
   * for it) for the countdown to have armed against. #everCanPlay guards
   * against a stale/spurious ended reading during the src-switch window ever
   * chaining an advance for an episode that never actually played.
   */
  advanceOnEnded(): void {
    if (
      Player.ended &&
      this.#everCanPlay &&
      this.#opts.getSettings()?.autoPlay &&
      this.nextEp &&
      !this.upNextDismissed &&
      !this.#advanced
    ) {
      this.advance();
    }
  }

  // ── Teardown ─────────────────────────────────────────────────────────────

  /**
   * Stop playback when the player closes so video/audio don't keep running
   * behind the rest of the UI — and persist where we got to. Saving must never
   * prevent the stop, so it's guarded. Call from the component's onDestroy.
   */
  destroy(): void {
    if (!Player.available) return;
    try {
      this.#opts.onBeforeDestroy?.();
      if (this.#opts.getMedia() && Player.duration > 0) {
        // Pass the actual ended state so the "ended" effect and destroy firing
        // in the same tick don't race: if ended=true the #completedSaved guard
        // in ProgressSaver prevents any downgrade; if false the record is left
        // as-is (not marked complete when it wasn't).
        // One bump per session teardown — never on the ~10s maybeSave ticks,
        // which would make every libraryChanged subscriber refetch during
        // playback. The bump waits for the save to land so the refetch it
        // triggers can't race the POST and read pre-save state.
        const completed = this.#opts.getDestroyCompleted
          ? this.#opts.getDestroyCompleted()
          : Player.ended;
        void this.progress
          .saveNow(
            Player.position,
            Player.duration,
            this.progressCtx,
            completed,
          )
          .then(() => libraryChanged.update((n) => n + 1));
      }
    } catch (e) {
      console.error(e);
    }
    Player.stop();
  }
}

// The background next-episode prefetch is deliberately NOT owned here: the
// three shells' copies have drifted (mobile passes defaultAudioLang into the
// ranking and omits fileIdx; desktop and TV do the opposite), so unifying them
// would change behaviour on at least one shell. Each shell keeps its own copy,
// resetting its own per-src guard from the onSrcChange hook, until that drift
// is resolved deliberately.
