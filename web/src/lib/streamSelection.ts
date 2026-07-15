// $lib/streamSelection.ts
//
// Shared logic for parsing and ranking addon streams. Used by StreamsList for
// manual sort/filter, and by the "auto-select best stream" feature to pick a
// winner without the user touching the list at all.

import type { Stream } from "$lib/types/addons";
import { inferQuality } from "$lib/utils";
import { codecCaps } from "$lib/platform";
import { api } from "$lib/api";

export type StreamSelectionMode =
  | "balanced"
  | "seeders"
  | "quality"
  | "smallest"
  | "bandwidth";

export const STREAM_SELECTION_MODES: {
  value: StreamSelectionMode;
  label: string;
  description: string;
}[] = [
  {
    value: "balanced",
    label: "Most Seeders & Lowest Size",
    description:
      "Favors well-seeded streams without picking an unnecessarily large file.",
  },
  {
    value: "seeders",
    label: "Most Seeders",
    description:
      "Prioritizes the most reliable, fastest-starting stream available.",
  },
  {
    value: "quality",
    label: "Highest Quality",
    description: "Always picks the best resolution, regardless of file size.",
  },
  {
    value: "smallest",
    label: "Smallest File Size",
    description: "Minimizes storage and bandwidth use.",
  },
  {
    value: "bandwidth",
    label: "Match My Internet Speed",
    description:
      "Picks the highest quality your measured connection speed can comfortably handle.",
  },
];

const QUALITY_RANK: Record<string, number> = {
  "4k dv": 7,
  "4k hdr": 6,
  "4k": 5,
  "1080p": 4,
  "720p": 3,
  "480p": 2,
  ts: 1,
  cam: 0,
};

function qualityRank(q: string | null): number {
  if (!q) return -1;
  return QUALITY_RANK[q] ?? -1;
}

// ── Torrent vs. direct-HTTP streams ──────────────────────────────────────────
//
// Seeders/size are torrent-swarm concepts parsed out of addon-supplied
// titles (see getSeeders/getSizeBytes below) — a direct HTTP stream (Nuvio
// scrapers, or any non-torrent addon) has neither, and infoHash is the one
// field that reliably tells them apart: it's only ever populated for
// torrents.

export function isTorrentStream(stream: Stream): boolean {
  return !!stream.infoHash;
}

// ── Parsing ──────────────────────────────────────────────────────────────────
//
// Addon titles encode seeders/size as emoji-prefixed tokens, e.g.
// "👤 42  💾 2.1 GB". There's no structured field for these, so regex it is.

export function getSeeders(stream: Stream): number {
  const match = stream.title.match(/👤\s*(\d+)/);
  return match ? Number(match[1]) : 0;
}

export function getSizeBytes(stream: Stream): number {
  // Structured size (currently only Nuvio scrapers) is authoritative when
  // present — the 💾-regex below is a fallback for addons that only ever put
  // size in free-text titles.
  if (stream.sizeBytes && stream.sizeBytes > 0) return stream.sizeBytes;
  const match = stream.title.match(/💾\s*([\d.]+)\s*(TB|GB|MB)/i);
  if (match) {
    const value = Number(match[1]);
    switch (match[2].toUpperCase()) {
      case "TB":
        return value * 1024 ** 4;
      case "GB":
        return value * 1024 ** 3;
      case "MB":
        return value * 1024 ** 2;
      default:
        return 0;
    }
  }
  // Plain-text size tokens ("1.4 GB") for non-torrent streams only. Torrent
  // titles are excluded to avoid false positives: release names and pack
  // metadata often contain number+unit strings that don't represent file size.
  if (!isTorrentStream(stream)) {
    const plainMatch = stream.title.match(/\b([\d.]+)\s*(TB|GB|MB)\b/i);
    if (plainMatch) {
      const value = Number(plainMatch[1]);
      switch (plainMatch[2].toUpperCase()) {
        case "TB":
          return value * 1024 ** 4;
        case "GB":
          return value * 1024 ** 3;
        case "MB":
          return value * 1024 ** 2;
        default:
          return 0;
      }
    }
  }
  return 0;
}

/** One-line summary for logging — "seeders / size / quality". */
export function formatStreamSummary(stream: Stream): string {
  const sizeBytes = getSizeBytes(stream);
  const sizeGB = sizeBytes / 1024 ** 3;
  const sizeStr =
    sizeBytes > 0
      ? sizeGB >= 1
        ? `${sizeGB.toFixed(2)} GB`
        : `${(sizeBytes / 1024 ** 2).toFixed(0)} MB`
      : "unknown size";
  const quality = inferQuality(stream) ?? "unknown quality";
  const seedersStr = isTorrentStream(stream)
    ? `${getSeeders(stream)} seeders`
    : "direct stream";
  return `${seedersStr}, ${sizeStr}, ${quality}`;
}

interface ScoredStream {
  stream: Stream;
  isTorrent: boolean;
  seeders: number;
  sizeBytes: number;
  quality: string | null;
  isPreferred: boolean;
  /** Additive ranking bonus from preferred-provider + source-preference
   * matches, precomputed once per candidate so rankByBoosted doesn't need to
   * know about either concept. */
  boost: number;
  /** True when confirmed dead by the pre-playback probe. Dead streams have
   * DEAD_LINK_PENALTY subtracted from their boost so they sink below all live
   * candidates while remaining in the ranked list for watchdog fallback. */
  dead: boolean;
}

/** "" (none) | "torrent" | "direct" — see Settings.sourcePreference. */
export type SourcePreference = "" | "torrent" | "direct";

export const SOURCE_PREFERENCES: {
  value: SourcePreference;
  label: string;
}[] = [
  { value: "", label: "No preference" },
  { value: "torrent", label: "Prefer torrents" },
  { value: "direct", label: "Prefer direct streams" },
];

// A preferred-provider stream only wins a close call — this bonus is small
// enough that a real quality/seeder gap from another provider still wins.
const PROVIDER_BOOST = 0.15;

// Same magnitude as PROVIDER_BOOST — a source-type preference is a similarly
// soft nudge, not a hard filter. The two boosts stack, so a stream that's
// both the preferred provider AND the preferred source type gets 0.3.
const SOURCE_BOOST = 0.15;

// Streams the device cannot hardware-decode sink hard: mode metrics are
// normalized 0..1 and the positive boosts cap at 0.3, so -0.5 pushes an
// unsupported release below every supported alternative while still letting
// it play when it's genuinely the only candidate (never a hard filter).
const UNSUPPORTED_CODEC_PENALTY = 0.5;

// Sinks confirmed-dead links below every live candidate (including
// codec-penalized ones) while keeping them ranked so the watchdog fallback
// list still has entries.
const DEAD_LINK_PENALTY = 2.0;

// Reliability scores for direct-URL (non-torrent) streams:
//   - debrid-cached: instant retrieval, effectively always available
//   - uncached debrid: goes through a download queue, much less reliable
//   - plain direct HTTP: moderately reliable (single origin, no swarm backing)
const CACHED_RELIABILITY = 0.95;
const UNCACHED_DEBRID_RELIABILITY = 0.3;
const DIRECT_RELIABILITY = 0.6;

// Release-name heuristics for codecs that need explicit hardware support.
// 10-bit HEVC ("x265 10bit", "HEVC Main 10", "Hi10P") software-decodes at
// well under realtime on phone SoCs whose decoder lacks the Main 10 profile,
// and AV1 is the same story on anything without an AV1 block.
const TEN_BIT_RE = /10.?bits?\b|\bhi10p?\b|\bmain ?10\b/i;
const AV1_RE = /\bav1\b/i;

/** True when the device is known (via the Android shell's MediaCodecList
 * probe) to lack hardware decode for what this release name advertises.
 * Desktop/browser have no probe — codecCaps() is null and nothing sinks. */
function isUnsupportedCodec(s: Stream): boolean {
  const caps = codecCaps();
  if (!caps) return false;
  const text = `${s.name} ${s.title}`;
  if (!caps.hevcMain10 && TEN_BIT_RE.test(text)) return true;
  if (!caps.av1 && AV1_RE.test(text)) return true;
  return false;
}

function scoreCandidates(
  streams: Stream[],
  preferredProvider?: string,
  sourcePreference?: string,
  deadUrls?: ReadonlySet<string>,
  probedSizes?: ReadonlyMap<string, number>,
): ScoredStream[] {
  return streams.map((s) => {
    const isTorrent = isTorrentStream(s);
    const isPreferred = !!preferredProvider && s.addonName === preferredProvider;
    const matchesSource =
      (sourcePreference === "torrent" && isTorrent) ||
      (sourcePreference === "direct" && !isTorrent);
    const dead = !!s.url && !!deadUrls?.has(s.url);
    // Use probed Content-Length to fill unknown sizes (probe results; probe
    // only covers non-torrent direct-URL candidates).
    const baseSizeBytes = getSizeBytes(s);
    const sizeBytes =
      baseSizeBytes > 0 ? baseSizeBytes : (s.url ? (probedSizes?.get(s.url) ?? 0) : 0);
    return {
      stream: s,
      isTorrent,
      seeders: getSeeders(s),
      sizeBytes,
      quality: inferQuality(s),
      isPreferred,
      dead,
      boost:
        (isPreferred ? PROVIDER_BOOST : 0) +
        (matchesSource ? SOURCE_BOOST : 0) -
        (isUnsupportedCodec(s) ? UNSUPPORTED_CODEC_PENALTY : 0) -
        (dead ? DEAD_LINK_PENALTY : 0),
    };
  });
}

/** Sorts by a mode's normalized 0..1 metric (higher = better), plus each
 * candidate's precomputed boost (preferred provider / preferred source),
 * falling back to `tiebreak` on ties. Returns the whole sorted pool —
 * callers that only want the winner take `[0]`. */
function rankByBoosted(
  pool: ScoredStream[],
  normalize: (c: ScoredStream) => number,
  tiebreak: (a: ScoredStream, b: ScoredStream) => number,
): ScoredStream[] {
  return pool.toSorted((a, b) => {
    const boostedA = normalize(a) + a.boost;
    const boostedB = normalize(b) + b.boost;
    const diff = boostedB - boostedA;
    return diff !== 0 ? diff : tiebreak(a, b);
  });
}

/** A candidate's stable identity for dedup purposes — mirrors how the rest of
 * the app distinguishes streams (see StreamsList/App.svelte candidate lists). */
function streamKey(c: ScoredStream): string {
  return c.stream.url || c.stream.infoHash || c.stream.title;
}

/**
 * Ranks `primary` (the mode's preferred candidate pool) first, then appends
 * whatever `all` contains that isn't already in `primary` — the streams a
 * mode's filters excluded (zero-seeder torrents, out-of-budget, sub-480p) —
 * ranked by the same metric, as last-resort fallbacks. Deduped by
 * url/infoHash so a candidate never appears twice.
 */
function finalizeRanking(
  primary: ScoredStream[],
  all: ScoredStream[],
  normalize: (c: ScoredStream) => number,
  tiebreak: (a: ScoredStream, b: ScoredStream) => number,
): Stream[] {
  const primaryKeys = new Set(primary.map(streamKey));
  const fallback = all.filter((c) => !primaryKeys.has(streamKey(c)));
  const ranked = [
    ...rankByBoosted(primary, normalize, tiebreak),
    ...rankByBoosted(fallback, normalize, tiebreak),
  ];

  const seen = new Set<string>();
  const out: Stream[] = [];
  for (const c of ranked) {
    const key = streamKey(c);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(c.stream);
  }
  return out;
}

export interface PickBestOptions {
  /** From the in-app speed test. Undefined/0 means "never measured". */
  measuredBandwidthMbps?: number;
  /** Runtime estimate used only by "bandwidth" mode's bitrate-budget math. */
  estimatedMinutes?: number;
  /** Matched against Stream.addonName — see Settings.defaultProvider. */
  preferredProvider?: string;
  /** "" | "torrent" | "direct" — see Settings.sourcePreference. */
  sourcePreference?: string;
  /** URLs confirmed dead by the pre-playback probe; matched streams are demoted. */
  deadUrls?: ReadonlySet<string>;
  /** Probe results: contentLength fills unknown sizes when getSizeBytes returns 0. */
  probedSizes?: ReadonlyMap<string, number>;
}

/**
 * Ranks every stream in `streams` best-first according to the given
 * strategy, returning the full ordered pool (never null/empty unless the
 * input is). Candidates a mode's filters would normally exclude entirely
 * (zero-seeder torrents, out-of-budget, sub-480p) aren't dropped — they're
 * appended at the tail in their own sorted order as last-resort fallbacks,
 * so a caller doing candidate-list fallback (B2's watchdog/auto-advance)
 * always has somewhere further to go. Deduped by url || infoHash.
 */
export function rankStreams(
  streams: Stream[],
  mode: StreamSelectionMode,
  opts: PickBestOptions = {},
): Stream[] {
  if (streams.length === 0) return [];

  const all = scoreCandidates(streams, opts.preferredProvider, opts.sourcePreference, opts.deadUrls, opts.probedSizes);
  // A zero-seeder torrent will likely never actually start downloading, so
  // it's excluded from the primary pool — unless it's literally the only
  // option. Direct HTTP streams (Nuvio, etc.) have no seeder concept at
  // all — they're never excluded by this check, torrent or not isn't a
  // reliability signal for them one way or the other.
  const withSeeders = all.filter((c) => !c.isTorrent || c.seeders > 0);
  const pool = withSeeders.length > 0 ? withSeeders : all;

  // Normalized 0..1 "will this actually start playing" score. Torrents need
  // peers to ramp up, so it's their seeder count relative to the best
  // available. For direct HTTP streams the score reflects how reliably the
  // origin delivers the file: debrid-cached streams are instant, uncached
  // debrid goes through a download queue, and plain HTTP is moderate (single
  // origin, no swarm backing). Constants are defined near the top of the file.
  const reliability = (c: ScoredStream, maxSeeders: number) =>
    c.isTorrent
      ? c.seeders / maxSeeders
      : c.stream.cached
        ? CACHED_RELIABILITY
        : c.stream.debrid
          ? UNCACHED_DEBRID_RELIABILITY
          : DIRECT_RELIABILITY;

  const qualityTiebreak = (a: ScoredStream, b: ScoredStream) => {
    const qDiff = qualityRank(b.quality) - qualityRank(a.quality);
    return qDiff !== 0 ? qDiff : b.seeders - a.seeders;
  };

  switch (mode) {
    case "seeders": {
      const maxSeeders = Math.max(1, ...pool.map((c) => c.seeders));
      return finalizeRanking(
        pool,
        all,
        (c) => reliability(c, maxSeeders),
        (a, b) => b.seeders - a.seeders,
      );
    }

    case "smallest": {
      // Don't let "smallest" devolve into picking a cam-quality rip just
      // because it's tiny, if a reasonable-quality option exists.
      const decent = pool.filter(
        (c) => qualityRank(c.quality) >= qualityRank("480p"),
      );
      const fromPool = decent.length > 0 ? decent : pool;
      const knownSizes = fromPool.map((c) => c.sizeBytes).filter((b) => b > 0);
      const maxSize = Math.max(1, ...knownSizes);
      // Size-unknown streams tiebreak LAST, not first — sizeBytes 0 isn't
      // "0 bytes, smallest possible," it's "we don't know," and a mode whose
      // entire point is picking the smallest file shouldn't gamble on an
      // unmeasured one ranking ahead of a stream with a known small size.
      const tiebreakSize = (c: ScoredStream) =>
        c.sizeBytes > 0 ? c.sizeBytes : Number.MAX_SAFE_INTEGER;
      return finalizeRanking(
        fromPool,
        all,
        (c) => (c.sizeBytes > 0 ? 1 - c.sizeBytes / maxSize : 0.5),
        (a, b) => tiebreakSize(a) - tiebreakSize(b),
      );
    }

    case "quality": {
      const maxRank = Math.max(1, ...pool.map((c) => qualityRank(c.quality)));
      return finalizeRanking(
        pool,
        all,
        (c) => qualityRank(c.quality) / maxRank,
        qualityTiebreak,
      );
    }

    case "bandwidth": {
      const mbps = opts.measuredBandwidthMbps;
      if (!mbps || mbps <= 0) {
        // No measurement on file — guessing a quality/bandwidth match without
        // data isn't meaningfully better than just balancing seeders & size.
        return rankStreams(streams, "balanced", opts);
      }
      const minutes = opts.estimatedMinutes ?? 90;
      const seconds = minutes * 60;
      // 30% headroom so playback isn't sitting right at the edge of
      // saturating the link (buffering, other devices on the network, etc).
      const budgetBytes = ((mbps * 1_000_000) / 8) * seconds * 0.7;
      const withinBudget = pool.filter(
        (c) =>
          (c.sizeBytes > 0 && c.sizeBytes <= budgetBytes) ||
          // Size-unknown DIRECT streams aren't excluded — a torrent's title
          // reliably carries a size, so an unknown-size torrent is almost
          // always just an unparsed title on a huge file (risky to guess
          // "fits"). A direct HTTP stream's "title" is scraper-authored free
          // text with no size convention at all — excluding those outright
          // would mean "bandwidth" mode drops every Nuvio-style stream
          // regardless of actual size.
          (c.sizeBytes === 0 && !c.isTorrent),
      );
      const fromPool = withinBudget.length > 0 ? withinBudget : pool;
      const maxRank = Math.max(
        1,
        ...fromPool.map((c) => qualityRank(c.quality)),
      );
      return finalizeRanking(
        fromPool,
        all,
        (c) => qualityRank(c.quality) / maxRank,
        qualityTiebreak,
      );
    }

    case "balanced":
    default: {
      const maxSeeders = Math.max(1, ...pool.map((c) => c.seeders));
      const knownSizes = pool.map((c) => c.sizeBytes).filter((b) => b > 0);
      const maxSize = Math.max(1, ...knownSizes);
      return finalizeRanking(
        pool,
        all,
        (c) => {
          const seederScore = reliability(c, maxSeeders);
          // Streams with no parsed size aren't penalized or rewarded — treat
          // as a neutral midpoint rather than guessing.
          const sizeScore = c.sizeBytes > 0 ? 1 - c.sizeBytes / maxSize : 0.5;
          return seederScore * 0.6 + sizeScore * 0.4;
        },
        () => 0,
      );
    }
  }
}

/**
 * Picks the single best stream from a list according to the given strategy.
 * Returns null only if the input list is empty.
 */
export function pickBestStream(
  streams: Stream[],
  mode: StreamSelectionMode,
  opts: PickBestOptions = {},
): Stream | null {
  return rankStreams(streams, mode, opts)[0] ?? null;
}

/**
 * Ranks streams, then live-probes the top direct-URL candidates via the
 * backend and re-ranks with dead links demoted and probed Content-Lengths
 * filling unknown sizes. Falls back to the plain ranking when probing is
 * disabled, fails, or finds no direct candidates.
 */
export async function rankStreamsWithProbe(
  streams: Stream[],
  mode: StreamSelectionMode,
  opts: PickBestOptions & { probeEnabled: boolean },
  signal?: AbortSignal,
): Promise<Stream[]> {
  const initial = rankStreams(streams, mode, opts);
  if (!opts.probeEnabled) return initial;

  // Only probe non-torrent streams that have a concrete URL — torrents are
  // handled by swarm health (seeders), not a HEAD check.
  const candidates = initial
    .filter((s) => !isTorrentStream(s) && !!s.url)
    .slice(0, 5);
  if (candidates.length === 0) return initial;

  try {
    const res = await api.probeStreams(
      candidates.map((s) => ({ url: s.url })),
      700,
      signal,
    );
    const deadUrls = new Set(
      res.results.filter((r) => !r.alive).map((r) => r.url),
    );
    const probedSizes = new Map(
      res.results
        .filter((r) => r.alive && r.contentLength > 0)
        .map((r) => [r.url, r.contentLength] as [string, number]),
    );
    return rankStreams(streams, mode, { ...opts, deadUrls, probedSizes });
  } catch {
    return initial;
  }
}
