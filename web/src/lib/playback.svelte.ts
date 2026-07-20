// Playback session state and control flow, extracted from App.svelte so a
// second (mobile) shell can reuse it without reimplementing the full state
// machine. The desktop App.svelte is the only current consumer; zero
// behavior change is the contract.

import type { Media } from "$lib/types/tmdb";
import type { Stream } from "$lib/types/addons";
import type { PlayerSession } from "$lib/types/types";
import { get } from "svelte/store";
import { settings } from "$lib/stores/settings";
import { rankStreamsWithProbe, type StreamSelectionMode } from "$lib/streamSelection";
import { api } from "$lib/api";
import { Player } from "$lib/player/player.svelte";

class PlaybackStore {
  playerSession = $state<PlayerSession | null>(null);
  playerMode = $state<"full" | null>(null);

  // Covers the gap between a "Watch" click and playerSession being set — the
  // fetchStreamsWithRetry/rankStreams/tvEpisodes work in quickPlay, none of
  // which shows anything today.
  quickPlayPending = $state<{ media: Media; message: string } | null>(null);

  // Tiny inline toast — mirrors Player.svelte's own "feedback flash" pattern
  // rather than pulling in a library for two short-lived messages.
  playbackToast = $state<string | null>(null);

  // Not $state — bumped on every quickPlay call so a stale call (superseded
  // by a newer "Watch" click before the first one resolved) can detect it's
  // stale and skip touching quickPlayPending/playerSession.
  #quickPlayToken = 0;

  // Aborts the previous quickPlay's in-flight stream fetch the moment a new
  // one starts — quickPlayToken alone only prevents a stale response from
  // being *acted on*, it doesn't stop the superseded request from running to
  // completion on the wire.
  #quickPlayAbort: AbortController | null = null;

  #playbackToastTimer: ReturnType<typeof setTimeout> | undefined;

  // Injected by the shell (App.svelte or the Android WebView host) during
  // init so startPlayback can play the chime without owning the AudioContext
  // lifecycle.
  #playStartSound: (() => Promise<void>) | null = null;

  // Injected by the shell so handlePlaybackFailed can reopen the media detail
  // overlay when auto-advance is exhausted and the user needs to pick manually.
  #openMediaDetail: ((media: Media) => void) | null = null;

  /**
   * Wire up shell-specific callbacks. Call once from the shell's top-level
   * <script> so the callbacks are in place before the first user interaction.
   */
  init(opts: {
    playStartSound: () => Promise<void>;
    openMediaDetail: (media: Media) => void;
  }): void {
    this.#playStartSound = opts.playStartSound;
    this.#openMediaDetail = opts.openMediaDetail;
  }

  showPlaybackToast(text: string, ms = 3000): void {
    this.playbackToast = text;
    clearTimeout(this.#playbackToastTimer);
    this.#playbackToastTimer = setTimeout(() => (this.playbackToast = null), ms);
  }

  startPlayback(
    media: Media,
    stream: Stream,
    season?: number,
    episode?: number,
    episodeName?: string,
    candidates?: Stream[],
    attempt = 0,
  ): void {
    this.quickPlayPending = null;
    this.#playStartSound?.();

    this.playerSession = {
      media,
      stream,
      season,
      episode,
      episodeName,
      subtitles: [],
      candidates,
      attempt,
    };
    this.playerMode = "full";

    api
      .getSubtitles({
        id: media.id,
        type: media.media_type,
        season: media.media_type === "tv" ? (season ?? undefined) : undefined,
        episode: media.media_type === "tv" ? (episode ?? undefined) : undefined,
      })
      .then((subs) => {
        // Guard against a newer startPlayback call having superseded this one
        // while the fetch was in flight.
        if (this.playerSession && this.playerSession.stream === stream) {
          this.playerSession = {
            ...this.playerSession,
            subtitles: Array.isArray(subs) ? subs : [],
          };
        }
      })
      .catch(() => {});
  }

  // Streams often come back empty on the first hit while indexers are still
  // searching — StreamsList handles that by polling every second; quickPlay
  // does the same here rather than giving up after one empty response.
  // `signal` short-circuits both the retry loop and the fetch itself once a
  // newer quickPlay call has superseded this one (see #quickPlayAbort below).
  async #fetchStreamsWithRetry(
    fetcher: (signal: AbortSignal) => Promise<Stream[]>,
    signal: AbortSignal,
  ): Promise<Stream[]> {
    // 8 attempts at 2s (B4), not 15 at 1s — A3's per-addon negative cache
    // makes each retry hit-or-miss the same 20s-TTL cache entry either way,
    // so the tighter interval mostly just burned more requests.
    for (let attempt = 0; attempt < 8; attempt++) {
      if (signal.aborted) return [];
      try {
        const res = await fetcher(signal);
        if (Array.isArray(res) && res.length > 0) return res;
      } catch (e) {
        if ((e as { name?: string } | null)?.name === "AbortError") return [];
        console.error("quickPlay: failed to fetch streams", e);
        return [];
      }
      await new Promise((r) => setTimeout(r, 2000));
    }
    return [];
  }

  // Used by "Watch"/"Continue" buttons on media cards: skip the media page
  // entirely and go straight to picking a stream and playing it, the same
  // way StreamsList's auto-select does. season/episode come from the
  // card's own library data (last_watched_season/episode); undefined for
  // movies, or for a TV show with no progress yet (in which case we start
  // from the first episode).
  async quickPlay(
    media: Media,
    season?: number,
    episode?: number,
  ): Promise<void> {
    const myToken = ++this.#quickPlayToken;
    this.#quickPlayAbort?.abort();
    const ctrl = new AbortController();
    this.#quickPlayAbort = ctrl;
    this.quickPlayPending = { media, message: "Finding streams…" };

    const isTV = media.media_type === "tv";
    const targetSeason = isTV ? (season ?? 1) : undefined;
    const targetEpisode = isTV ? (episode ?? 1) : undefined;

    const streams = await this.#fetchStreamsWithRetry(
      (signal) =>
        api.getStreams(
          media.id,
          isTV
            ? { type: "tv", season: targetSeason, episode: targetEpisode }
            : {},
          signal,
        ),
      ctrl.signal,
    );
    if (myToken !== this.#quickPlayToken) return;
    if (streams.length === 0) {
      this.quickPlayPending = { media, message: "No stream found" };
      setTimeout(() => {
        if (myToken === this.#quickPlayToken) this.quickPlayPending = null;
      }, 2500);
      return;
    }

    const s = get(settings);
    const mode = (s?.streamSelectionMode as StreamSelectionMode) ?? "balanced";
    // "original" resolves to the title's TMDB original language before ranking.
    const effectiveAudioLang =
      s?.defaultAudioLang === "original"
        ? (media.original_language ?? "")
        : (s?.defaultAudioLang ?? "");
    const ranked = await rankStreamsWithProbe(streams, mode, {
      measuredBandwidthMbps: s?.measuredBandwidthMbps,
      preferredProvider: s?.defaultProvider,
      sourcePreference: s?.sourcePreference,
      probeEnabled: s?.probeStreams ?? true,
      defaultAudioLang: effectiveAudioLang || undefined,
    }, ctrl.signal);
    if (myToken !== this.#quickPlayToken) return;
    const best = ranked[0] ?? null;
    if (!best) {
      this.quickPlayPending = { media, message: "No stream found" };
      setTimeout(() => {
        if (myToken === this.#quickPlayToken) this.quickPlayPending = null;
      }, 2500);
      return;
    }

    let episodeName: string | undefined;
    if (isTV) {
      try {
        const eps = await api.tvEpisodes(media.id, targetSeason!);
        episodeName = eps.find((e) => e.episode_number === targetEpisode)?.name;
      } catch (e) {
        // Non-critical — just means the topbar's subtitle line won't show
        // an episode title.
        console.error("quickPlay: failed to fetch episode name", e);
      }
    }
    if (myToken !== this.#quickPlayToken) return;

    // Runner-up candidates (B2) so the playback watchdog can auto-advance if
    // `best` turns out to be dead, without a full re-fetch.
    this.startPlayback(
      media,
      best,
      targetSeason,
      targetEpisode,
      episodeName,
      ranked.slice(0, 5),
      0,
    );
  }

  // ─── Playback-start watchdog: auto-advance / give up (B2) ──────────────────
  // Fired by <Player> (via onPlaybackFailed) when the current stream never
  // actually started — a startup timeout or a stalled/dead torrent. Drops
  // the dead stream from the candidate list and tries the next one, up to 3
  // fallback attempts; once exhausted (or there were no candidates to begin
  // with — a manual stream-list pick), closes the player and reopens the
  // media detail so the user sees the manual stream list instead of a
  // frozen loading screen.
  handlePlaybackFailed(): void {
    const session = this.playerSession;
    if (!session) return;

    const deadKey = session.stream.url || session.stream.infoHash;
    const remaining = (session.candidates ?? []).filter(
      (c) => (c.url || c.infoHash) !== deadKey,
    );
    const attempt = session.attempt ?? 0;
    const next = remaining[0];

    if (next && attempt < 3) {
      this.showPlaybackToast("Stream didn't start — trying another…");
      this.startPlayback(
        session.media,
        next,
        session.season,
        session.episode,
        session.episodeName,
        remaining.slice(1),
        attempt + 1,
      );
      return;
    }

    const media = session.media;
    this.closePlayer();
    this.showPlaybackToast("Couldn't start playback automatically", 4000);
    this.#openMediaDetail?.(media);
  }

  /** Cancels an in-progress quickPlay: aborts the in-flight stream fetch,
   * bumps the stale-token counter so any completions that slip through the
   * abort window hit the existing guards and exit silently, and clears the
   * loading overlay. Safe to call at any time; no-ops when idle.
   */
  cancelQuickPlay(): void {
    this.#quickPlayAbort?.abort();
    this.#quickPlayToken++;
    this.quickPlayPending = null;
  }

  closePlayer(): void {
    Player.setFullscreen(false);
    this.playerSession = null;
    this.playerMode = null;
  }
}

// Preserve the PlaybackStore instance across Vite HMR module re-evaluations
// so in-progress playback sessions survive hot reloads in development. (Same
// pattern as player.svelte.ts / MpvPlayer.)
function makeOrReusePlayback(): PlaybackStore {
  if (import.meta.hot) {
    import.meta.hot.data.playback ??= new PlaybackStore();
    return import.meta.hot.data.playback as PlaybackStore;
  }
  return new PlaybackStore();
}
export const playback = makeOrReusePlayback();
