import type {
  Details,
  Media,
  MediaImages,
  MediaVideos,
  TVEpisode,
} from "$lib/types/tmdb";
import type {
  AddonEntry,
  CatalogRef,
  Stream,
  TimestampData,
  WatchOption,
} from "$lib/types/addons";
import type { Repo as NuvioRepo } from "$lib/types/nuvio";
import type { Settings } from "$lib/types/settings"; // tygo-generated
import type { LibraryEntry, WatchProgress } from "$lib/types/library"; // tygo-generated
import type { Profile } from "$lib/types/profiles"; // tygo-generated
import type { Stats as ActivityStats, TitleSeconds } from "$lib/types/activity"; // tygo-generated
import type { CheckResult as UpdateCheckResult } from "$lib/types/updater"; // tygo-generated
import type { CalendarItem } from "$lib/types/calendar"; // tygo-generated
import * as m from "$lib/paraglide/messages.js";
export type { ActivityStats, TitleSeconds, UpdateCheckResult };

// Single source of truth for the backend origin. Override per-environment with
// VITE_API_BASE (e.g. in .env.production); falls back to 127.0.0.1 (the same
// host the backend embeds in its JSON) so there is one CSP entry and one
// Chromium connection pool. localhost is kept as a fallback only in the CSP
// for browser-dev setups that override VITE_API_BASE to point at localhost.
// Everything in this module — fetches and the URL builders handed to <video>,
// <track>, hls.js, and EventSource — is derived from this, so the host appears
// exactly once in the frontend.
const BASE =
  (import.meta as unknown as { env?: Record<string, string | undefined> }).env
    ?.VITE_API_BASE ?? "http://127.0.0.1:6969/api";

// Auth token getter — set by setTokenSource() on startup; called on every request
// so it always reads the current value without a $effect timing gap.
let _getToken: (() => string | null) | null = null;

/** Wire up the auth token source. Called once in App.svelte on mount. */
export function setTokenSource(getter: () => string | null): void {
  _getToken = getter;
}

// ── Request helpers ───────────────────────────────────────────────────────────

// Concurrency limiter. A full homepage (Continue Watching + every taste row,
// each packed with cards) fires hundreds of metadata fetches at once —
// getImages + libraryGet per card, plus getDetails / getMediaByID per item.
// Chromium can't track that many pending requests and starts failing them with
// net::ERR_INSUFFICIENT_RESOURCES (which surfaces as "TypeError: Failed to
// fetch"). We cap how many fetches are actually in flight; the rest wait in a
// cheap in-memory queue rather than as pending browser requests.
//
// Only request/requestOrNull go through this. Long-lived streams (the
// progress SSE, speedtest) deliberately bypass it — they'd hold a slot open
// indefinitely and starve everything else.
const MAX_CONCURRENT = 8;
let inFlight = 0;
const waiters: Array<() => void> = [];

function acquireSlot(): Promise<void> {
  if (inFlight < MAX_CONCURRENT) {
    inFlight++;
    return Promise.resolve();
  }
  return new Promise<void>((resolve) => waiters.push(resolve));
}

function releaseSlot(): void {
  const next = waiters.shift();
  // Hand the freed slot straight to the next waiter (inFlight unchanged), or
  // give it back to the pool if nobody's waiting.
  if (next) next();
  else inFlight--;
}

// Every fetch through the limiter gets a hard 20s ceiling — otherwise a
// stalled request (dead addon, unreachable torrent tracker, etc.) never
// releases its slot, and with only MAX_CONCURRENT slots, 8 stalled fetches
// deadlock the entire pool for every other caller. Combined with any signal
// the caller already passed so both can still abort the request.
const FETCH_TIMEOUT_MS = 20_000;

function withTimeout(init?: RequestInit): RequestInit {
  const timeoutSignal = AbortSignal.timeout(FETCH_TIMEOUT_MS);
  const signal = init?.signal
    ? AbortSignal.any([init.signal, timeoutSignal])
    : timeoutSignal;
  return { ...init, signal };
}

/** fetch(), but never more than MAX_CONCURRENT calls outstanding at once. */
async function limitedFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  await acquireSlot();
  try {
    return await fetch(input, withTimeout(init));
  } finally {
    releaseSlot();
  }
}

// In-flight request coalescing. A title often appears in several rows at once
// (Continue Watching + a genre row + the tastes row), so the same getDetails /
// getImages / getMediaByID fires multiple times simultaneously. We share one
// pending promise per identical request instead of duplicating the fetch.
//
// Only GETs are coalesced — mutations (PUT/POST/PATCH/DELETE) must each run.
// Entries are evicted the moment the request settles, so this collapses
// concurrent bursts without ever serving a cached/stale response: a request
// made after the previous one finished always hits the network fresh.
const inflight = new Map<string, Promise<unknown>>();

function coalesce<T>(
  key: string,
  init: RequestInit | undefined,
  exec: () => Promise<T>,
): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();
  if (method !== "GET") return exec();
  // A caller-supplied signal means this request has its own cancellation
  // lifecycle — sharing a coalesced promise would let one caller's abort
  // reject every other caller waiting on the same key, even ones that never
  // asked to be cancelled. Skip coalescing entirely for signalled requests;
  // each runs (and can be aborted) independently.
  if (init?.signal) return exec();

  const existing = inflight.get(key) as Promise<T> | undefined;
  if (existing) return existing;

  const p = exec().finally(() => inflight.delete(key));
  inflight.set(key, p);
  return p;
}

/** Thrown for any non-2xx response so callers can distinguish HTTP failures. */
export class ApiError extends Error {
  constructor(
    public status: number,
    public body: string,
    public path: string,
  ) {
    super(`API ${status} on ${path}${body ? `: ${body}` : ""}`);
    this.name = "ApiError";
  }
}

function withAuth(init?: RequestInit): RequestInit {
  const token = _getToken?.() ?? null;
  if (!token) return init ?? {};
  const headers = new Headers(init?.headers);
  headers.set("Authorization", `Bearer ${token}`);
  return { ...init, headers };
}

/** fetch + ok-check + JSON parse. Throws ApiError on non-2xx. */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const exec = async (): Promise<T> => {
    const res = await limitedFetch(`${BASE}${path}`, withAuth(init));
    if (!res.ok) {
      throw new ApiError(res.status, await res.text().catch(() => ""), path);
    }
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  };
  return coalesce(`request:${path}`, init, exec);
}

/**
 * Like request, but treats 404 / empty body as a normal `null` rather than an
 * error — for endpoints where "nothing saved yet" is an expected outcome.
 * A non-404 error status still throws so genuine server errors surface.
 */
async function requestOrNull<T>(
  path: string,
  init?: RequestInit,
): Promise<T | null> {
  const exec = async (): Promise<T | null> => {
    const res = await limitedFetch(`${BASE}${path}`, withAuth(init));
    if (res.status === 404) return null;
    if (!res.ok) {
      throw new ApiError(res.status, await res.text().catch(() => ""), path);
    }
    const text = await res.text();
    return text ? (JSON.parse(text) as T) : null;
  };
  return coalesce(`requestOrNull:${path}`, init, exec);
}

/** A torrent src is a bare infohash; anything starting with http is a direct URL. */
function isHashSrc(src: string): boolean {
  return !/^https?:\/\//i.test(src);
}

// ── Library: TypeScript-only types ────────────────────────────────────────────
//
// LibraryStatus can't be a Go union type, so we define it here and let tygo
// emit `string` for the Status field in LibraryEntry. Both are correct at
// runtime; the union just gives callers better autocomplete.

export type LibraryStatus = "watch_later" | "watching" | "finished" | "dropped";

export function statusLabel(status: LibraryStatus): string {
  switch (status) {
    case "watch_later":
      return m.my_list_watch_later();
    case "watching":
      return m.my_list_watching();
    case "finished":
      return m.my_list_finished();
    case "dropped":
      return m.my_list_dropped();
  }
}

/** Accent color per library status, for at-a-glance color coding across the UI. */
export const STATUS_COLORS: Record<
  LibraryStatus,
  { dot: string; text: string }
> = {
  watch_later: { dot: "bg-amber-400", text: "text-amber-400" },
  watching: { dot: "bg-sky-400", text: "text-sky-400" },
  finished: { dot: "bg-emerald-400", text: "text-emerald-400" },
  dropped: { dot: "bg-rose-400", text: "text-rose-400" },
};

/** "1h 23m" / "4m 12s" / "8s" */
export function formatPosition(seconds: number): string {
  const totalSeconds = Number.isFinite(seconds)
    ? Math.max(0, Math.floor(seconds))
    : 0;
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

// ── Probe response shape ──────────────────────────────────────────────────────
//
// probe() is generic so callers keep their own AudioTrackInfo/SubtitleTrackInfo
// types without this module having to own them.

export interface StreamQuery {
  type?: string;
  season?: number;
  episode?: number;
}

/** Addon subtitle shape — matches the Go Subtitle struct and PlayerSession.subtitles. */
export interface AddonSubtitle {
  id: string;
  url: string;
  lang: string;
}

export interface Taste {
  id: number;
  name: string;
  score: number;
}

export type DiscoverProfile = "adult" | "kid";

export interface LibraryStats {
  total: number;
  by_type: Record<string, number>;
  by_status: Record<string, number>;
  finished: Record<string, number>;
  dismissed: number;
  rated: number;
  avg_rating: number;
  movie_share: number;
  tv_share: number;
}

export interface DiscoverInsights {
  top_movie_genres: Taste[];
  top_tv_genres: Taste[];
  disliked_genres: Taste[];
  top_keywords: Taste[];
  top_people: Taste[];
  signals_used: number;
  top_studios: StudioEntry[];
  top_contributors: ContributingTitle[];
  negative_contributors: ContributingTitle[];
}

export interface StudioEntry {
  id: number;
  name: string;
  count: number;
}

export interface ContributingTitle {
  tmdb_id: number;
  media_type: string;
  title: string;
  poster_path: string;
  weight: number;
}

// A /search/person result. profile_path / known_for posters arrive as fully
// qualified URLs (the backend absolutises them).
export interface Person {
  id: number;
  name: string;
  profile_path: string;
  known_for_department: string;
  popularity: number;
  known_for: Media[];
}

// A streaming/rental service from /watch/providers; logo_path is a full URL.
export interface Provider {
  provider_id: number;
  provider_name: string;
  logo_path: string;
  display_priority: number;
}

// Sectioned payload from /api/search/multi.
export interface SearchResults {
  movies: Media[];
  tv: Media[];
  people: Person[];
  providers: Provider[];
  title_order: string[];
}

// Full person payload for the person overlay (bio + filmography).
export interface PersonDetails {
  id: number;
  name: string;
  biography: string;
  profile_path: string;
  known_for_department: string;
  birthday: string;
  place_of_birth: string;
  credits: Media[];
}

// ── Trakt.tv types ───────────────────────────────────────────────────────────

export interface TraktStatus {
  connected: boolean;
  username: string;
  expires_at: string;
}

export interface TraktDeviceCode {
  device_code: string;
  user_code: string;
  verification_url: string;
  expires_in: number;
  interval: number;
}

export interface TraktPollResult {
  status:
    | "pending"
    | "authorized"
    | "expired"
    | "denied"
    | "slow_down"
    | "invalid";
  username?: string;
}

// ── API ────────────────────────────────────────────────────────────────────────

export const api = {
  // ── TMDB ────────────────────────────────────────────────────────────────────
  search: (q: string): Promise<Media[]> =>
    request(`/search?q=${encodeURIComponent(q)}`),

  // Sectioned search: titles (split movie/tv), people, and providers.
  searchMulti: (q: string): Promise<SearchResults> =>
    request(`/search/multi?q=${encodeURIComponent(q)}`),

  // Person bio + filmography for the person overlay.
  getPerson: (id: number): Promise<PersonDetails> =>
    request(`/person?id=${id}`),

  // Popular titles available on a watch provider (US region).
  providerTitles: (id: number, limit?: number): Promise<Media[]> => {
    const p = new URLSearchParams({ id: String(id) });
    if (limit != null) p.set("limit", String(limit));
    return request(`/provider?${p}`);
  },

  getKeywords: (q: string): Promise<{ id: number; name: string }[]> =>
    request(`/keywords?q=${encodeURIComponent(q)}`),

  getSimilar: (media: Media): Promise<Media[]> =>
    request(`/similar?id=${media.id}&type=${media.media_type}`),

  // Fetches a genuine, fully-populated Media object by ID — for places that
  // only have a bare tmdb_id (e.g. a LibraryEntry) and would otherwise have
  // to reconstruct a partial Media object by hand.
  getMediaByID: (tmdbId: number, mediaType: string): Promise<Media> =>
    request(`/media?id=${tmdbId}&type=${mediaType}`),

  getDetails: (media: Media): Promise<Details> =>
    request(`/details?id=${media.id}&type=${media.media_type}`),

  getImages: (media: Media): Promise<MediaImages> =>
    request(`/images?id=${media.id}&type=${media.media_type}`),

  getVideos: (media: Media): Promise<MediaVideos> =>
    request(`/videos?id=${media.id}&type=${media.media_type}`),

  getLogos: (id: number, mediaType: string): Promise<string[]> =>
    request(`/logos?id=${id}&type=${mediaType}`),

  tvSeasons: <T = unknown>(id: number): Promise<T[]> =>
    request(`/tv/seasons?id=${id}`),

  tvEpisodes: (id: number, season: number): Promise<TVEpisode[]> =>
    request(`/tv/episodes?id=${id}&season=${season}`),

  // ── Streams & subtitles (addons) ──────────────────────────────────────────────
  getStreams: (
    tmdbId: number,
    opts: StreamQuery = {},
    signal?: AbortSignal,
  ): Promise<Stream[]> => {
    const p = new URLSearchParams({ id: String(tmdbId) });
    if (opts.type) p.set("type", opts.type);
    if (opts.season != null) p.set("season", String(opts.season));
    if (opts.episode != null) p.set("episode", String(opts.episode));
    return request(`/streams?${p}`, signal ? { signal } : undefined);
  },

  getSubtitles: (p: {
    id: number;
    type: string;
    season?: number;
    episode?: number;
  }): Promise<AddonSubtitle[]> => {
    const q = new URLSearchParams({ id: String(p.id), type: p.type });
    if (p.season != null) q.set("season", String(p.season));
    if (p.episode != null) q.set("episode", String(p.episode));
    return request(`/subtitles?${q}`);
  },

  // Streams NDJSON quality-badge results for a batch of typed ids
  // ("movie:603", "tv:1396") from /api/quality/batch, calling onEntry for
  // each line as it arrives. Deliberately bypasses the concurrency limiter —
  // same rationale as the progress SSE and speedtest above: this is a
  // long-lived streaming connection that would otherwise hold one of the 8
  // slots open for its whole duration and starve every other fetch on the
  // page. Swallows AbortError so callers can just pass a signal and not
  // special-case cancellation.
  streamQualityBatch: async (
    ids: string[],
    onEntry: (id: string, quality: string) => void,
    signal?: AbortSignal,
  ): Promise<void> => {
    if (ids.length === 0) return;
    let reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
    try {
      const res = await fetch(
        `${BASE}/quality/batch?ids=${ids.map(encodeURIComponent).join(",")}`,
        withAuth({ signal }),
      );
      if (!res.ok || !res.body) return;
      reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      const emitLine = (line: string): void => {
        if (!line.trim()) return;
        try {
          const { id, quality } = JSON.parse(line);
          if (typeof id !== "string" || typeof quality !== "string") return;
          onEntry(id, quality);
        } catch {
          /* ignore malformed frames */
        }
      };
      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          // NDJSON producers normally terminate every frame with a newline,
          // but accepting a final unterminated frame avoids silently dropping
          // the last quality result when a proxy closes the stream cleanly.
          buffer += decoder.decode();
          emitLine(buffer);
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() ?? "";
        for (const line of lines) emitLine(line);
      }
    } catch (e) {
      // Cancel the reader on any error/abort so the locked ReadableStream is
      // released promptly instead of being held until GC collects it.
      reader?.cancel().catch(() => {});
      if ((e as { name?: string } | null)?.name === "AbortError") return;
      throw e;
    }
  },

  // Probes the liveness and Content-Length of a batch of direct-URL stream
  // candidates. Results are returned in input order; URLs the backend's stream
  // registry doesn't recognise come back with alive: false. Used by
  // rankStreamsWithProbe to demote dead links before auto-select commits.
  probeStreams: (
    streams: { url: string }[],
    timeoutMs = 700,
    signal?: AbortSignal,
  ): Promise<{
    results: { url: string; alive: boolean; contentLength: number }[];
  }> =>
    request(`/streams/probe`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ streams, timeoutMs }),
      ...(signal ? { signal } : {}),
    }),

  // ── Player: source URL builders ───────────────────────────────────────────────
  //
  // These return strings rather than fetching — the URL is handed to mpv, a
  // <track src>, or EventSource, which handle their own loading.

  /**
   * Direct torrent stream (or the original URL if src is already absolute).
   * For a hash src, season/episode (D1) let the backend pick the right file
   * out of a season-pack torrent instead of always streaming its largest
   * file — omitted entirely for a movie or an already-absolute src.
   */
  playUrl: (
    src: string,
    opts?: { season?: number; episode?: number; fileIdx?: number },
  ): string => {
    if (!isHashSrc(src)) return src;
    const p = new URLSearchParams({ hash: src });
    if (opts?.season != null) p.set("season", String(opts.season));
    if (opts?.episode != null) p.set("episode", String(opts.episode));
    if (opts?.fileIdx != null) p.set("fileIdx", String(opts.fileIdx));
    return `${BASE}/play?${p}`;
  },

  subtitleProxyUrl: (externalUrl: string): string =>
    `${BASE}/subtitle-proxy?url=${encodeURIComponent(externalUrl)}`,

  /**
   * Direct-URL stream routed through the backend proxy. Needed when the
   * origin requires extra headers (Referer/Origin) that mpv wouldn't send —
   * the backend remembered them when it listed the stream and re-attaches
   * them. Only URLs the backend itself returned from /api/streams are
   * accepted.
   */
  playProxyUrl: (streamUrl: string): string =>
    `${BASE}/play?url=${encodeURIComponent(streamUrl)}`,

  /**
   * SSE progress endpoint. season/episode (mirrors playUrl) let the backend
   * report progress on just the selected episode's file in a season-pack
   * torrent, instead of the whole torrent's (misleading) aggregate.
   */
  progressStreamUrl: (
    src: string,
    opts?: { season?: number; episode?: number; fileIdx?: number },
  ): string => {
    const p = new URLSearchParams({ hash: src });
    if (opts?.season != null) p.set("season", String(opts.season));
    if (opts?.episode != null) p.set("episode", String(opts.episode));
    if (opts?.fileIdx != null) p.set("fileIdx", String(opts.fileIdx));
    return `${BASE}/progress/stream?${p}`;
  },

  /** Fixed-size payload endpoint for the in-app bandwidth test. Caller measures blob size vs. elapsed time. */
  speedtestUrl: (): string => `${BASE}/speedtest`,

  /**
   * Routes a raw TMDB image path (e.g. a JustWatch provider logoPath, which —
   * unlike Media.poster_path — never goes through the backend, so it still
   * arrives as a bare TMDB-relative path) through the backend's image-cache
   * proxy (internal/imgcache/F4), instead of building an image.tmdb.org URL
   * directly. BASE is module-private, so this is the one place allowed to
   * construct an /api/img/ URL — everywhere else must go through this helper
   * rather than hardcoding the backend origin.
   */
  imgUrl: (size: string, path: string): string => `${BASE}/img/${size}${path}`,

  /**
   * Fire-and-forget: tells the backend to start background-downloading a
   * torrent's selected file (F7's next-episode prefetch). The backend starts
   * the download in a goroutine and responds 202 immediately — this resolves
   * as soon as the request is accepted, not when the download finishes.
   */
  prefetchDownload: (
    hash: string,
    opts?: { season?: number; episode?: number; fileIdx?: number },
  ): Promise<{ started: boolean }> => {
    const p = new URLSearchParams({ hash });
    if (opts?.season != null) p.set("season", String(opts.season));
    if (opts?.episode != null) p.set("episode", String(opts.episode));
    if (opts?.fileIdx != null) p.set("fileIdx", String(opts.fileIdx));
    return request(`/prefetch-download?${p}`, { method: "POST" });
  },

  // ── Settings ─────────────────────────────────────────────────────────────────
  getSettings: (): Promise<Settings> => request(`/settings`),

  updateSettings: (s: Settings): Promise<Settings> =>
    request(`/settings`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(s),
    }),

  // GET/PUT /api/settings/mpv-conf — device-global mpv.conf content.
  // GET returns an empty string when the file doesn't exist yet.
  getMpvConf: (): Promise<string> => request<string>(`/settings/mpv-conf`),

  setMpvConf: (content: string): Promise<void> =>
    request<void>(`/settings/mpv-conf`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(content),
    }),

  // GET /api/settings returns remoteAccessToken as "***" when set (empty when
  // unset). Call this to fetch the real value — only when the user explicitly
  // clicks show/copy, not on every settings load.
  revealRemoteAccessToken: (): Promise<string> =>
    request<{ token: string }>(`/settings/reveal-token`, {
      method: "POST",
    }).then((r) => r.token),

  testDiscoveryAlgorithm: (
    url: string,
  ): Promise<{ ok: boolean; error?: string }> =>
    request(`/discover/algorithm/test`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url }),
    }),

  // ── Addons ───────────────────────────────────────────────────────────────────
  getAddons: (): Promise<AddonEntry[]> => request(`/addons`),

  addAddon: (url: string): Promise<AddonEntry> =>
    request(`/addons`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url }),
    }),

  removeAddon: (id: string, url?: string): Promise<void> => {
    const p = new URLSearchParams();
    if (id) p.set("id", id);
    if (url) p.set("url", url);
    return request(`/addons?${p}`, { method: "DELETE" });
  },

  toggleAddon: (id: string, enabled: boolean, url?: string): Promise<void> => {
    const p = new URLSearchParams();
    if (id) p.set("id", id);
    if (url) p.set("url", url);
    return request(`/addons?${p}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled }),
    });
  },

  getCatalogs: (): Promise<CatalogRef[]> => request(`/catalogs`),

  catalogPage: (
    addonId: string,
    catalogType: string,
    catalogId: string,
    skip: number,
    limit?: number,
    addonUrl?: string,
  ): Promise<{ medias: Media[]; nextSkip: number }> => {
    const p = new URLSearchParams({
      addonId,
      catalogType,
      catalogId,
      skip: String(skip),
    });
    if (limit != null) p.set("limit", String(limit));
    if (addonUrl) p.set("addonUrl", addonUrl);
    return request(`/catalog?${p}`);
  },

  toggleCatalog: (
    addonId: string,
    catalogKey: string,
    enabled: boolean,
    addonUrl?: string,
  ): Promise<void> => {
    const p = new URLSearchParams({ id: addonId, catalog: catalogKey });
    if (addonUrl) p.set("url", addonUrl);
    return request(`/addons/catalog?${p}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled }),
    });
  },

  refreshAddon: (id: string, url?: string): Promise<AddonEntry> => {
    const p = new URLSearchParams();
    if (id) p.set("id", id);
    if (url) p.set("url", url);
    return request(`/addons/refresh?${p}`, { method: "POST" });
  },

  // ── Nuvio plugin repos ───────────────────────────────────────────────────────
  getNuvioRepos: (): Promise<NuvioRepo[]> => request(`/nuvio/repos`),

  addNuvioRepo: (url: string): Promise<NuvioRepo> =>
    request(`/nuvio/repos`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url }),
    }),

  removeNuvioRepo: (id: string): Promise<void> =>
    request(`/nuvio/repos?${new URLSearchParams({ id })}`, {
      method: "DELETE",
    }),

  setNuvioRepoEnabled: (id: string, enabled: boolean): Promise<void> =>
    request(`/nuvio/repos?${new URLSearchParams({ id })}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled }),
    }),

  refreshNuvioRepo: (id: string): Promise<void> =>
    request(`/nuvio/repos/refresh?${new URLSearchParams({ id })}`, {
      method: "POST",
    }),

  setNuvioScraperEnabled: (
    repoId: string,
    scraperId: string,
    enabled: boolean,
  ): Promise<void> =>
    request(`/nuvio/scrapers?${new URLSearchParams({ repoId, scraperId })}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled }),
    }),

  getWatchOptions: (
    tmdbId: number,
    mediaType: string,
  ): Promise<WatchOption[]> =>
    request(`/watch-options?id=${tmdbId}&type=${mediaType}`),

  getTimestamps: (
    tmdbId: number,
    opts: { season?: number; episode?: number } = {},
  ): Promise<TimestampData> => {
    const p = new URLSearchParams({ id: String(tmdbId) });
    if (opts.season != null) p.set("season", String(opts.season));
    if (opts.episode != null) p.set("episode", String(opts.episode));
    return request(`/timestamps?${p}`);
  },

  // ── Library ──────────────────────────────────────────────────────────────────
  libraryCalendar: (): Promise<CalendarItem[]> => request(`/library/calendar`),

  libraryList: (status?: LibraryStatus): Promise<LibraryEntry[]> =>
    request(`/library${status ? `?status=${status}` : ""}`),

  libraryUpsert: (p: {
    tmdb_id: number;
    media_type: string;
    title: string;
    poster_path: string;
    vote_average?: number;
    last_air_date?: string;
    last_aired_season?: number | null;
    last_aired_episode?: number | null;
    status?: LibraryStatus;
  }): Promise<LibraryEntry> =>
    request(`/library`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(p),
    }),

  // Returns null only when the title has no entry AND no progress history.
  // entry can be null if the user removed the title from their list but
  // progress records (watch history) still exist.
  libraryGet: (
    tmdbId: number,
    mediaType: string,
  ): Promise<{
    entry: LibraryEntry | null;
    progress: WatchProgress[];
    dismissed: boolean;
  } | null> => requestOrNull(`/library/${tmdbId}/${mediaType}`),

  libraryRemove: (tmdbId: number, mediaType: string): Promise<void> =>
    request(`/library/${tmdbId}/${mediaType}`, { method: "DELETE" }),

  librarySetStatus: (
    tmdbId: number,
    mediaType: string,
    status: LibraryStatus,
  ): Promise<LibraryEntry> =>
    request(`/library/${tmdbId}/${mediaType}/status`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status }),
    }),

  librarySetRating: (
    tmdbId: number,
    mediaType: string,
    rating: number | null,
  ): Promise<LibraryEntry> =>
    request(`/library/${tmdbId}/${mediaType}/rating`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ rating }),
    }),

  // ── Watch progress ────────────────────────────────────────────────────────────
  // Returns null when no progress has been saved yet (not an error).
  progressGet: (
    tmdbId: number,
    mediaType: string,
    season?: number | null,
    episode?: number | null,
  ): Promise<WatchProgress | null> => {
    const p = new URLSearchParams({
      tmdb_id: String(tmdbId),
      media_type: mediaType,
    });
    if (season != null) p.set("season", String(season));
    if (episode != null) p.set("episode", String(episode));
    return requestOrNull(`/library/progress?${p}`);
  },

  // Upserts a progress record. Also auto-creates a "watching" library entry
  // if one doesn't already exist (handled server-side).
  progressSave: (p: {
    tmdb_id: number;
    media_type: string;
    title?: string;
    poster_path?: string;
    vote_average?: number;
    last_air_date?: string;
    last_aired_season?: number | null;
    last_aired_episode?: number | null;
    season?: number | null;
    episode?: number | null;
    position_seconds: number;
    duration_seconds: number;
    completed: boolean;
  }): Promise<WatchProgress> =>
    request(`/library/progress`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(p),
    }),

  // Atomically marks a whole movie/TV title watched, or resets every saved
  // progress row for it. TV callers provide the aired episode positions when
  // marking watched; reset operations use the server's existing rows.
  progressBulkSave: (p: {
    tmdb_id: number;
    media_type: string;
    title?: string;
    poster_path?: string;
    vote_average?: number;
    completed: boolean;
    status?: LibraryStatus;
    duration_seconds?: number;
    episodes?: {
      season: number;
      episode: number;
      duration_seconds: number;
    }[];
  }): Promise<{ entry: LibraryEntry | null; progress: WatchProgress[] }> =>
    request(`/library/progress/bulk`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(p),
    }),

  // ── Discovery ────────────────────────────────────────────────────────────────
  discover: (
    type: "movie" | "tv" | "all",
    opts: { limit?: number; profile?: DiscoverProfile } = {},
  ): Promise<Media[]> => {
    const p = new URLSearchParams({ type });
    if (opts.limit != null) p.set("limit", String(opts.limit));
    if (opts.profile) p.set("profile", opts.profile);
    return request(`/discover?${p}`);
  },

  discoverByGenre: (
    type: "movie" | "tv",
    genreId: number,
    opts: { limit?: number; profile?: DiscoverProfile } = {},
  ): Promise<Media[]> => {
    const p = new URLSearchParams({ type, genre: String(genreId) });
    if (opts.limit != null) p.set("limit", String(opts.limit));
    if (opts.profile) p.set("profile", opts.profile);
    return request(`/discover/genre?${p}`);
  },

  discoverByKeyword: (
    type: "movie" | "tv",
    keywordId: number,
    opts: { limit?: number; profile?: DiscoverProfile } = {},
  ): Promise<Media[]> => {
    const p = new URLSearchParams({ type, keyword: String(keywordId) });
    if (opts.limit != null) p.set("limit", String(opts.limit));
    if (opts.profile) p.set("profile", opts.profile);
    return request(`/discover/keyword?${p}`);
  },

  discoverTopGenres: (
    type: "movie" | "tv",
    limit?: number,
  ): Promise<Taste[]> => {
    const p = new URLSearchParams({ type });
    if (limit != null) p.set("limit", String(limit));
    return request(`/discover/genres?${p}`);
  },

  discoverTopKeywords: (limit?: number): Promise<Taste[]> =>
    request(`/discover/keywords${limit ? `?limit=${limit}` : ""}`),

  genreList: (type: "movie" | "tv"): Promise<{ id: number; name: string }[]> =>
    request(`/genres?type=${type}`),

  notInterested: (media: Media): Promise<void> =>
    request(`/library/dismiss`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tmdb_id: media.id, media_type: media.media_type }),
    }),

  undoNotInterested: (media: Media): Promise<void> =>
    request(`/library/dismiss`, {
      method: "DELETE",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ tmdb_id: media.id, media_type: media.media_type }),
    }),

  // ── Profile / insights ───────────────────────────────────────────────────────
  libraryStats: (): Promise<LibraryStats> => request(`/library/stats`),

  discoverInsights: (): Promise<DiscoverInsights> =>
    request(`/discover/insights`),

  activityStats: (): Promise<ActivityStats> => request(`/library/activity`),

  // ── Auto-update ──────────────────────────────────────────────────────────────

  checkUpdate: (): Promise<UpdateCheckResult> => request(`/update/check`),

  // Bypasses the concurrency limiter — this blocks for the full download +
  // extraction (potentially 30–60 s). On success the backend exits with code 42
  // and the Qt shell restarts; the connection drop is expected.
  applyUpdate: async (): Promise<void> => {
    const res = await fetch(`${BASE}/update/apply`, { method: "POST" });
    if (!res.ok) {
      throw new ApiError(
        res.status,
        await res.text().catch(() => ""),
        "/update/apply",
      );
    }
  },

  // ── Profiles ──────────────────────────────────────────────────────────────────
  profilesList: (): Promise<{
    profiles: Profile[];
    active_profile_id: string;
  }> => request(`/profiles`),

  profileCreate: (name: string): Promise<Profile> =>
    request(`/profiles`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
    }),

  profileRename: (
    id: string,
    name: string,
  ): Promise<{ id: string; name: string }> =>
    request(`/profiles/${id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
    }),

  profileDelete: (id: string): Promise<void> =>
    request(`/profiles/${id}`, { method: "DELETE" }),

  profileActivate: (id: string): Promise<Profile> =>
    request(`/profiles/${id}/activate`, { method: "POST" }),

  // ── Auth ─────────────────────────────────────────────────────────────────────

  // Returns a full session when email confirmation is disabled in Supabase, or
  // { confirmation_required: true } when Supabase sent a confirmation email.
  authRegister: (
    email: string,
    password: string,
    profile_name?: string,
  ): Promise<
    { access_token: string; profile: Profile } | { confirmation_required: true }
  > =>
    request(`/auth/register`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password, profile_name }),
    }),

  // Submits the 6-digit OTP from the signup confirmation email.
  // Returns a full session on success.
  authConfirmRegister: (
    email: string,
    token: string,
    password: string,
    profile_name?: string,
  ): Promise<{
    access_token: string;
    refresh_token: string;
    profile: Profile;
  }> =>
    request(`/auth/register/confirm`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, token, password, profile_name }),
    }),

  authLogin: (
    email: string,
    password: string,
  ): Promise<{
    access_token: string;
    refresh_token: string;
    profiles: Profile[];
    active: Profile;
  }> =>
    request(`/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password }),
    }),

  authSendOTP: (email: string): Promise<{ status: string }> =>
    request(`/auth/otp`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email }),
    }),

  authVerifyOTP: (
    email: string,
    token: string,
  ): Promise<{
    access_token: string;
    refresh_token: string;
    profiles: Profile[];
    active: Profile;
  }> =>
    request(`/auth/verify-otp`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, token }),
    }),

  authLogout: (): Promise<{ status: string }> =>
    request(`/auth/logout`, { method: "POST" }),

  authMe: (): Promise<{ profile: Profile; linked: boolean }> =>
    request(`/auth/me`),

  // library_generation is absent on older backends / a noop (non-proprietary)
  // build that 503s — callers fall back accordingly.
  // push_error reflects the PREVIOUS completed push (the current one is async);
  // "" or absent means the last push fully succeeded.
  authSync: (): Promise<{
    status: string;
    library_generation?: number;
    push_error?: string;
  }> => request(`/auth/sync`, { method: "POST" }),

  // Persistent client session — stored by the Go backend as a JSON file in
  // the OS user-config dir (~/.config/cove/session.json). More reliable than
  // Qt WebEngine localStorage, which may use in-memory storage.
  clientSessionGet: (): Promise<{
    accessToken: string;
    refreshToken: string;
    email: string;
  }> => request(`/client-session`),
  clientSessionSave: (data: {
    accessToken: string;
    refreshToken: string;
    email: string;
  }): Promise<void> =>
    request(`/client-session`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data),
    }),
  clientSessionDelete: (): Promise<void> =>
    request(`/client-session`, { method: "DELETE" }),

  // Clears the in-flight GET-coalescing map. Call whenever the auth token or
  // active profile changes so a pending response from the old identity can't
  // be handed to the new one. Does NOT key by token — just empties the map
  // so the next caller hits the network fresh under the new identity.
  clearInflight: (): void => {
    inflight.clear();
  },

  // ── Trakt.tv ─────────────────────────────────────────────────────────────────
  // All trakt endpoints return 503 when credentials aren't compiled in.
  // traktStatus absorbs 503 and returns null so callers can gate the UI
  // without catching raw errors; all other methods throw on error as usual.

  traktStatus: async (): Promise<TraktStatus | null> => {
    try {
      return await request<TraktStatus>(`/trakt/status`);
    } catch (e) {
      if (e instanceof ApiError && e.status === 503) return null;
      throw e;
    }
  },

  traktStartDeviceFlow: (): Promise<TraktDeviceCode> =>
    request(`/trakt/device-code`, { method: "POST" }),

  traktPoll: (device_code: string): Promise<TraktPollResult> =>
    request(`/trakt/poll`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ device_code }),
    }),

  traktUnlink: (): Promise<void> =>
    request(`/trakt/unlink`, { method: "POST" }),

  traktScrobble: (p: {
    action: "start" | "pause" | "stop";
    tmdb_id: number;
    media_type: string;
    season?: number | null;
    episode?: number | null;
    progress: number;
  }): Promise<void> =>
    request(`/trakt/scrobble`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(p),
    }),

  traktSyncNow: (): Promise<void> => request(`/trakt/sync`, { method: "POST" }),
};
