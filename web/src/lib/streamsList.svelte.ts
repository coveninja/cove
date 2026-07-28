// $lib/streamsList.svelte.ts
//
// Shared data layer for the stream picker: season/episode browsing, stream
// fetching plus empty-result polling, dedup/parse/filter/sort, auto-selection
// with a probe-backed re-rank, and watch progress.
//
// Extracted from components/StreamsList.svelte, whose script block
// tv/components/TvStreamsList.svelte had copied wholesale ("verbatim copy of
// StreamsList's script block… Only the UI layer differs"). Both shells now
// drive this class and keep only their own markup.
//
// This module owns no $effect of its own — same rule the rest of $lib follows
// (see TorrentProgress). Each lifecycle method below is meant to be called
// from exactly one component $effect and reads its reactive dependencies
// synchronously, so a bare `$effect(() => ctl.loadSeasons())` tracks precisely
// what the original inline effect tracked. loadStreams() returns its teardown
// for `$effect(() => ctl.loadStreams())` to hand straight back.
//
// Everything reaching in from the component — props and the settings store —
// arrives as a getter, never a snapshot. A $derived that reads a plain value
// captured at construction would memoise it forever; invoking the getter
// inside the derived reads the underlying signal instead.

import { SvelteMap, SvelteSet } from "svelte/reactivity";

import { api } from "$lib/api";
import * as m from "$lib/paraglide/messages.js";
import { epKey, getMaxQuality, inferQuality } from "$lib/utils";
import { parseStreamMeta, type ParsedStreamMeta } from "$lib/streamMeta";
import {
  compareStreamsBy,
  formatAutoPickReason,
  getSeeders,
  getSizeBytes,
  isCodecHardDisabled,
  rankStreams,
  rankStreamsWithProbe,
  STREAM_SORT_MODES,
  type StreamSelectionMode,
  type StreamSortMode,
} from "$lib/streamSelection";
import type { Stream, WatchOption } from "$lib/types/addons";
import type { WatchProgress } from "$lib/types/library";
import type { Settings } from "$lib/types/settings";
import type { Media, TVEpisode } from "$lib/types/tmdb";

export type TVSeason = {
  season_number: number;
  episode_count: number;
  name: string;
  poster_path: string;
};

/** One rendered stream row. Extends streamSelection's SortableStream with the
 *  identity key and the hardware-decode verdict the markup needs. */
export interface ParsedStream {
  stream: Stream;
  key: string;
  seeders: number;
  sizeBytes: number;
  quality: string | null;
  /** Codec/language details parsed from the release name — drives the
   * showStreamDetails badges. */
  meta: ParsedStreamMeta;
  /** Device probe says this codec can't be hardware-decoded — row renders
   * greyed/unselectable with a "Play anyway" override. */
  isHardDisabled: boolean;
}

export type PlayStreamHandler = (
  stream: Stream,
  season?: number,
  episode?: number,
  episodeName?: string,
  candidates?: Stream[],
) => void;

export interface StreamsListOptions {
  getMedia: () => Media;
  getStreamActive: () => boolean;
  getActiveSeason: () => number | undefined;
  getActiveEpisode: () => number | undefined;
  getAutoJumpToActive: () => boolean;
  /** The component's `() => $settings` — must stay a getter so the settings
   *  store's signal is read inside each derived, not captured once. */
  getSettings: () => Settings;
  /** Writes the caller's `maxQuality` $bindable prop. */
  setMaxQuality: (quality: string | null) => void;
  onPlayStream: PlayStreamHandler;
}

/** Labels for JustWatch availability entries. */
export function watchTypeLabel(type: string): string {
  if (type === "rent") return m.streams_watch_rent();
  if (type === "buy") return m.streams_watch_buy();
  if (type === "free") return m.streams_watch_free();
  if (type === "ads") return m.streams_watch_ads();
  return type;
}

const QUALITY_ORDER = [
  "4k dv",
  "4k hdr",
  "4k",
  "1080p",
  "720p",
  "480p",
  "ts",
  "cam",
];

// Indexers that never turn anything up shouldn't poll forever — cap it and
// fall back to the existing empty state. Halved from 20 alongside the 1s→2s
// poll interval below (B4) — same ~20s total window, half the requests.
const MAX_POLL_ATTEMPTS = 10;

export class StreamsListController {
  loadingStreams = $state(false);
  sortMode = $state<StreamSortMode>("seeders");
  qualityFilter = $state("all");

  // TV browsing state
  seasons = $state<TVSeason[]>([]);
  episodes = $state<TVEpisode[]>([]);
  selectedSeason = $state<number | null>(null);
  selectedEpisode = $state<TVEpisode | null>(null);
  loadingSeasons = $state(false);
  loadingEpisodes = $state(false);

  // Stream state
  streams = $state<Stream[]>([]);
  watchOptions = $state<WatchOption[]>([]);

  autoPicking = $state(false);
  autoPickCancelled = $state(false);
  /** Whether to show the picker at all when something's already playing for
   *  this exact selection — keeps the panel from defaulting to "here's a full
   *  list to pick from" when there's nothing to actually decide yet. */
  showAlternatives = $state(false);

  /** TV progress, keyed by "season:episode". */
  progressMap = new SvelteMap<string, WatchProgress>();
  /** Movie progress — a single record. */
  movieProgress = $state<WatchProgress | null>(null);

  #opts: StreamsListOptions;

  #pollInterval: ReturnType<typeof setInterval> | null = null;
  #pollAttempts = 0;

  // ── Fetch sequencing (B3) ────────────────────────────────────────────────
  // fetchSeq/abortCtrl guard against rapid episode switching racing a stale
  // response: loadStreams() bumps fetchSeq and creates a fresh AbortController
  // on every run, #fetchStreams bails before touching streams/maxQuality/
  // auto-pick if its seq has been superseded, and #autoPickTimer is explicitly
  // cleared on teardown so a pending 500ms auto-pick from the *previous*
  // episode/season can never fire after the user has already moved on (the old
  // wrong-episode-autoplay bug).
  #fetchSeq = 0;
  #abortCtrl: AbortController | null = null;
  #autoPickTimer: ReturnType<typeof setTimeout> | null = null;

  // Identity of the media the browsing state currently belongs to. Only used
  // by resetOnMediaChange().
  #prevMediaId: number | null = null;

  constructor(opts: StreamsListOptions) {
    this.#opts = opts;
  }

  // ── Derived view state ───────────────────────────────────────────────────

  get isTV(): boolean {
    return this.#opts.getMedia().media_type === "tv";
  }

  /** True when the season/episode currently browsed here is the exact thing
   *  already playing (full or minimized to PiP). Prevents auto-select from
   *  firing again and silently swapping out the stream you're watching — this
   *  list keeps polling/rendering in the background now that the player no
   *  longer unmounts it while a stream is active. */
  get alreadyPlayingThisSelection(): boolean {
    return (
      this.#opts.getStreamActive() &&
      (!this.isTV ||
        (this.selectedSeason === this.#opts.getActiveSeason() &&
          this.selectedEpisode?.episode_number ===
            this.#opts.getActiveEpisode()))
    );
  }

  /** Preferred audio language with "original" resolved to the title's TMDB
   *  original language — shared by the auto-select ranking and language sort. */
  get effectiveAudioLang(): string {
    const s = this.#opts.getSettings();
    return s?.defaultAudioLang === "original"
      ? (this.#opts.getMedia().original_language ?? "")
      : (s?.defaultAudioLang ?? "");
  }

  availableQualities = $derived.by(() => {
    const qs = [
      // Transient dedupe set — built, spread and dropped inside this one
      // derive, so it never needs to be reactive.
      // eslint-disable-next-line svelte/prefer-svelte-reactivity
      ...new Set(this.streams.map((s) => inferQuality(s)).filter(Boolean)),
    ];
    qs.sort((a, b) => QUALITY_ORDER.indexOf(a!) - QUALITY_ORDER.indexOf(b!));
    return ["all", ...qs];
  });

  // D5: seeders/size/quality are regex-parsed out of the stream title (see
  // streamSelection.ts) — parsing is the expensive part, so it only reruns
  // when `streams` itself changes, not on every filter/sort toggle. `key` is
  // a stable identity for the {#each} in each shell (url/infoHash/title,
  // matching rankStreams' dedup key) so toggling a filter/sort no longer tears
  // down and rebuilds every row's DOM (the previous key was object identity on
  // a freshly-mapped object every derive, which changed on every filter/sort
  // toggle even though the underlying stream hadn't).
  // Some addons return identical streams (same URL/infoHash/title), which
  // crashes Svelte's keyed {#each} block. We dedupe by `key` here.
  parsedStreams = $derived.by(() => {
    const seen = new SvelteSet<string>();
    const result: ParsedStream[] = [];
    for (const s of this.streams) {
      const key = s.url || s.infoHash || s.title;
      if (!key || seen.has(key)) continue;
      seen.add(key);
      result.push({
        stream: s,
        key,
        seeders: getSeeders(s),
        sizeBytes: getSizeBytes(s),
        quality: inferQuality(s),
        meta: parseStreamMeta(s),
        isHardDisabled: isCodecHardDisabled(s),
      });
    }
    return result;
  });

  filteredStreams = $derived.by(() => {
    const filtered = this.parsedStreams.filter(
      (s) => this.qualityFilter === "all" || s.quality === this.qualityFilter,
    );
    const preferred = this.#opts.getSettings()?.defaultProvider;
    const compare = compareStreamsBy(
      this.sortMode,
      this.effectiveAudioLang || undefined,
    );
    return filtered.toSorted((a, b) => {
      if (preferred) {
        const aPref = a.stream.addonName === preferred ? 1 : 0;
        const bPref = b.stream.addonName === preferred ? 1 : 0;
        if (aPref !== bPref) return bPref - aPref;
      }
      return compare(a, b);
    });
  });

  selectedSeasonLabel = $derived.by(
    () =>
      this.seasons.find((s) => s.season_number === this.selectedSeason)?.name ??
      (this.selectedSeason !== null
        ? m.common_season_number({ season: this.selectedSeason })
        : m.media_seasons()),
  );

  // ── UI helpers ───────────────────────────────────────────────────────────

  /** Advance the quality filter one step — the TV shell's replacement for the
   *  desktop dropdown. */
  cycleQuality(): void {
    const idx = this.availableQualities.indexOf(this.qualityFilter);
    this.qualityFilter =
      this.availableQualities[(idx + 1) % this.availableQualities.length] ??
      "all";
  }

  /** Advance the sort mode one step — TV's replacement for the dropdown. */
  cycleSort(): void {
    const idx = STREAM_SORT_MODES.findIndex((s) => s.value === this.sortMode);
    this.sortMode =
      STREAM_SORT_MODES[(idx + 1) % STREAM_SORT_MODES.length].value;
  }

  /** Back out of an episode's stream list to the episode browser. */
  clearSelectedEpisode(): void {
    this.selectedEpisode = null;
    this.streams = [];
  }

  /** Give up on auto-select and show the full list instead. */
  cancelAutoPick(): void {
    this.autoPickCancelled = true;
    this.autoPicking = false;
  }

  // ── Lifecycle: each of these belongs in one component $effect ────────────

  /**
   * Reset TV browsing state when the media prop changes identity (e.g. the
   * in-player episodes sidebar switches to a different title without
   * unmounting the list). Without this, selectedSeason from the previous title
   * leaks into the new title's season fetch, causing it to skip the
   * default-season logic and sometimes show episodes from the wrong season.
   *
   * Only the desktop shell wires this up. TvStreamsList has no equivalent
   * reuse path — TvDetailOverlay tears the list down between titles — and has
   * never run this reset, so leaving it unwired keeps TV behaviour unchanged.
   */
  resetOnMediaChange(): void {
    const id = this.#opts.getMedia().id;
    if (this.#prevMediaId !== null && id !== this.#prevMediaId) {
      this.selectedSeason = null;
      this.selectedEpisode = null;
      this.seasons = [];
      this.episodes = [];
    }
    this.#prevMediaId = id;
  }

  /**
   * #fetchStreams sets autoPicking = true right before kicking off playback,
   * but nothing ever flips it back once that stream actually starts — it used
   * to not matter because the whole list got unmounted the instant playback
   * began. It no longer does, so clear it explicitly once we can see the pick
   * succeeded.
   */
  clearAutoPickingWhenPlaying(): void {
    if (this.alreadyPlayingThisSelection && this.autoPicking) {
      this.autoPicking = false;
    }
  }

  /** Fetch all episode progress for this show whenever the media changes. */
  loadProgress(): void {
    if (!this.isTV) return;
    api
      .libraryGet(this.#opts.getMedia().id, "tv")
      .then((result) => {
        this.progressMap.clear();
        for (const p of result?.progress ?? []) {
          if (p.season != null && p.episode != null) {
            this.progressMap.set(epKey(p.season, p.episode), p);
          }
        }
      })
      .catch(console.error);
  }

  /** Fetch movie progress. */
  loadMovieProgress(): void {
    if (this.isTV) return;
    api
      .progressGet(this.#opts.getMedia().id, "movie")
      .then((p) => {
        this.movieProgress = p;
      })
      .catch(console.error);
  }

  /** Fetch streaming availability (JustWatch) — runs once per media item. */
  loadWatchOptions(): void {
    const media = this.#opts.getMedia();
    api
      .getWatchOptions(media.id, media.media_type)
      .then((opts) => (this.watchOptions = opts))
      .catch(() => (this.watchOptions = []));
  }

  loadSeasons(): void {
    if (!this.isTV) return;
    this.loadingSeasons = true;
    api
      .tvSeasons<TVSeason>(this.#opts.getMedia().id)
      .then((data) => {
        this.seasons = data ?? [];
        if (this.seasons.length > 0 && this.selectedSeason === null) {
          // Land on whatever's already playing (full or minimized to PiP)
          // instead of always defaulting to season 1.
          const activeSeason = this.#opts.getActiveSeason();
          this.selectedSeason =
            activeSeason != null &&
            this.seasons.some((s) => s.season_number === activeSeason)
              ? activeSeason
              : this.seasons[0].season_number;
        }
      })
      .finally(() => (this.loadingSeasons = false));
  }

  loadEpisodes(): void {
    if (!this.isTV || this.selectedSeason === null) return;
    this.loadingEpisodes = true;
    this.episodes = [];
    this.selectedEpisode = null;
    this.streams = [];
    api
      .tvEpisodes(this.#opts.getMedia().id, this.selectedSeason)
      .then((data) => {
        this.episodes = data ?? [];
        // Same idea, one level deeper: jump straight to the episode that's
        // already playing rather than leaving the user on the episode browser,
        // having to find and re-click it themselves.
        // When autoJumpToActive is false (the in-player sidebar), skip this so
        // the sidebar opens on the episode list rather than the stream list.
        const activeEpisode = this.#opts.getActiveEpisode();
        if (
          this.#opts.getAutoJumpToActive() &&
          this.selectedSeason === this.#opts.getActiveSeason() &&
          activeEpisode != null
        ) {
          const match = this.episodes.find(
            (e) => e.episode_number === activeEpisode,
          );
          if (match) this.selectedEpisode = match;
        }
      })
      .finally(() => (this.loadingEpisodes = false));
  }

  /** Fetch streams for the current selection. Returns the teardown to hand
   *  back from the calling $effect. */
  loadStreams(): () => void {
    if (this.isTV && (!this.selectedEpisode || this.selectedSeason === null))
      return () => {};

    this.#clearPoll();
    if (this.#autoPickTimer != null) {
      clearTimeout(this.#autoPickTimer);
      this.#autoPickTimer = null;
    }
    this.#abortCtrl?.abort();
    const seq = ++this.#fetchSeq;
    const ctrl = new AbortController();
    this.#abortCtrl = ctrl;

    this.loadingStreams = true;
    this.streams = [];
    this.#pollAttempts = 0;
    this.autoPickCancelled = false;
    this.autoPicking = false;
    this.showAlternatives = false;
    this.#fetchStreams(seq, ctrl.signal).then(() => {
      // superseded or destroyed before the response landed
      if (seq !== this.#fetchSeq || ctrl.signal.aborted) return;
      this.loadingStreams = false;
      if (this.streams.length === 0)
        // 2s, not 1s (B4) — A3's per-addon negative cache makes each poll
        // hit-or-miss the same 20s-TTL cache entry either way, so a tighter
        // interval mostly just burns more requests without surfacing results
        // any sooner.
        this.#pollInterval = setInterval(
          () => this.#pollFetchStreams(seq, ctrl.signal),
          2000,
        );
    });

    return () => {
      this.#clearPoll();
      ctrl.abort();
      if (this.#autoPickTimer != null) {
        clearTimeout(this.#autoPickTimer);
        this.#autoPickTimer = null;
      }
    };
  }

  // ── Internals ────────────────────────────────────────────────────────────

  #clearPoll(): void {
    if (this.#pollInterval) {
      clearInterval(this.#pollInterval);
      this.#pollInterval = null;
    }
  }

  // setInterval callback for the empty-results poll. Stops itself (falling
  // back to the existing "no streams" empty state) once MAX_POLL_ATTEMPTS is
  // reached instead of retrying forever. seq/signal are bound to the fetch
  // generation that started this poll — if a newer run has since superseded
  // it, bail immediately instead of firing a stale request.
  #pollFetchStreams(seq: number, signal: AbortSignal): void {
    if (seq !== this.#fetchSeq) return;
    this.#pollAttempts++;
    if (this.#pollAttempts > MAX_POLL_ATTEMPTS) {
      this.#clearPoll();
      return;
    }
    this.#fetchStreams(seq, signal);
  }

  async #fetchStreams(seq: number, signal: AbortSignal): Promise<void> {
    let res: Stream[];
    try {
      res = await api.getStreams(
        this.#opts.getMedia().id,
        this.isTV
          ? {
              type: "tv",
              season: this.selectedSeason!,
              episode: this.selectedEpisode!.episode_number,
            }
          : {},
        signal,
      );
    } catch (e) {
      if ((e as { name?: string } | null)?.name === "AbortError") return;
      throw e;
    }

    // Superseded by a newer run (episode/season switch) while this request was
    // in flight — discard rather than clobber the current pick.
    if (seq !== this.#fetchSeq) return;

    this.streams = res;
    this.#opts.setMaxQuality(getMaxQuality(this.streams));
    if (this.streams.length > 0) this.#clearPoll();

    const settings = this.#opts.getSettings();
    if (
      settings?.autoSelectStream &&
      !this.autoPickCancelled &&
      !this.autoPicking &&
      !this.alreadyPlayingThisSelection &&
      this.streams.length > 0
    ) {
      const selectionMode =
        (settings.streamSelectionMode as StreamSelectionMode) ?? "balanced";
      const rankOpts = {
        measuredBandwidthMbps: settings.measuredBandwidthMbps,
        preferredProvider: settings.defaultProvider,
        sourcePreference: settings.sourcePreference,
        defaultAudioLang: this.effectiveAudioLang || undefined,
      };
      // Synchronous initial ranking — drives the log line and the fallback
      // used when the probe doesn't land before the 500ms window closes.
      const initialRanking = rankStreams(this.streams, selectionMode, rankOpts);
      const best = initialRanking[0] ?? null;
      if (best) {
        const mode = settings.streamSelectionMode ?? "balanced";
        console.log(
          `[stream-select] auto (${mode}): "${best.name}" — ${formatAutoPickReason(best)}`,
          best,
        );
        this.autoPicking = true;
        // Background probe: re-rank with dead links demoted and probed
        // Content-Lengths filling unknown sizes. Fills probedRanking before the
        // 500ms timer fires if the backend responds in time.
        let probedRanking: Stream[] | null = null;
        rankStreamsWithProbe(
          this.streams,
          selectionMode,
          { ...rankOpts, probeEnabled: settings.probeStreams ?? true },
          signal,
        )
          .then((ranked) => {
            if (seq === this.#fetchSeq && !this.autoPickCancelled)
              probedRanking = ranked;
          })
          .catch(() => {});
        // Small delay so the "Auto-selecting…" message and its cancel button
        // actually get a moment on screen before playback starts.
        this.#autoPickTimer = setTimeout(() => {
          this.#autoPickTimer = null;
          if (seq === this.#fetchSeq && !this.autoPickCancelled) {
            const ranking = probedRanking ?? initialRanking;
            // Pass a handful of runner-up candidates so App.svelte's watchdog
            // (B2) can auto-advance to the next one if this pick turns out to
            // be dead, without a full re-fetch.
            this.#opts.onPlayStream(
              ranking[0],
              this.selectedSeason ?? undefined,
              this.selectedEpisode?.episode_number,
              this.selectedEpisode?.name,
              ranking.slice(0, 5),
            );
          }
        }, 500);
      }
    }
  }
}
