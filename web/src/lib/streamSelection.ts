// $lib/streamSelection.ts
//
// Shared logic for parsing and ranking addon streams. Used by StreamsList for
// manual sort/filter, and by the "auto-select best stream" feature to pick a
// winner without the user touching the list at all.

import type { Stream } from "$lib/types/addons";
import { inferQuality } from "$lib/utils";
import { codecCaps } from "$lib/platform";
import { parseStreamMeta, type ParsedStreamMeta } from "$lib/streamMeta";
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

export function qualityRank(q: string | null): number {
  if (!q) return -1;
  return QUALITY_RANK[q] ?? -1;
}

// ── Manual list sorting ──────────────────────────────────────────────────────
//
// The stream lists' user-facing sort dropdown/cycle. Distinct from the
// auto-select modes above: these are simple deterministic comparators over
// already-parsed rows, not scored rankings.

export type StreamSortMode =
  | "seeders"
  | "largest"
  | "smallest"
  | "quality"
  | "language"
  | "cached";

export const STREAM_SORT_MODES: { value: StreamSortMode; label: string }[] = [
  { value: "seeders", label: "Seeders" },
  { value: "largest", label: "Largest" },
  { value: "smallest", label: "Smallest" },
  { value: "quality", label: "Quality" },
  { value: "language", label: "Language" },
  { value: "cached", label: "Cached" },
];

/** The row shape the list components derive per stream — structural subset of
 * their local ParsedStream interfaces. */
export interface SortableStream {
  stream: Stream;
  seeders: number;
  sizeBytes: number;
  quality: string | null;
  meta: ParsedStreamMeta;
}

/** Comparator for the manual sort modes. `preferredLang` (ISO 639-1) only
 * affects "language" mode — without it that mode degrades to seeders. */
export function compareStreamsBy(
  mode: StreamSortMode,
  preferredLang?: string,
): (a: SortableStream, b: SortableStream) => number {
  const bySeeders = (a: SortableStream, b: SortableStream) =>
    b.seeders - a.seeders;
  switch (mode) {
    case "largest":
      return (a, b) => b.sizeBytes - a.sizeBytes;
    case "smallest":
      // Unknown sizes (0) sort last — "we don't know" isn't "smallest".
      return (a, b) =>
        (a.sizeBytes > 0 ? a.sizeBytes : Number.MAX_SAFE_INTEGER) -
        (b.sizeBytes > 0 ? b.sizeBytes : Number.MAX_SAFE_INTEGER);
    case "quality":
      return (a, b) => {
        const diff = qualityRank(b.quality) - qualityRank(a.quality);
        return diff !== 0 ? diff : bySeeders(a, b);
      };
    case "language":
      return (a, b) => {
        if (preferredLang) {
          const matches = (s: SortableStream) =>
            s.meta.isMulti || s.meta.langs.includes(preferredLang) ? 1 : 0;
          const diff = matches(b) - matches(a);
          if (diff !== 0) return diff;
        }
        return bySeeders(a, b);
      };
    case "cached":
      return (a, b) => {
        const diff = (b.stream.cached ? 1 : 0) - (a.stream.cached ? 1 : 0);
        return diff !== 0 ? diff : bySeeders(a, b);
      };
    case "seeders":
    default:
      return bySeeders;
  }
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
  /** True when the device probe confirms this release's codec can't be
   * hardware-decoded — see isCodecHardDisabled. Excluded from every mode's
   * pool and appended at the absolute tail of the ranking. */
  isHardDisabled: boolean;
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

// A release advertising the user's preferred audio language (or MULTi/dual
// audio) wins close calls; one that *explicitly* advertises only other
// languages sinks a little. Untagged releases are neutral — title parsing is
// lossy and absence of a tag usually just means an original-language release.
const LANG_BOOST = 0.2;
const LANG_PENALTY = 0.15;

// Dolby Vision Profile 5 has no HDR10 fallback layer — on a device whose
// probe confirms no DV decoder it plays with green/purple tinting. Soft
// penalty (not a hard disable) because the video itself still decodes.
const DV_P5_PENALTY = 0.3;

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

/** True when the device is known (via the Android shell's MediaCodecList
 * probe) to lack hardware decode for what this release name advertises —
 * software decode at these bitrates is well under realtime on the affected
 * SoCs. Hard-disabled streams are excluded from auto-selection and rendered
 * unselectable in the stream lists (with a per-row "Play anyway" override).
 * Desktop/browser have no probe — codecCaps() is null and nothing disables.
 * Optional caps fields left undefined by an older shell mean "unknown,
 * assume supported": only an explicit `false` disables. */
export function isCodecHardDisabled(s: Stream): boolean {
  const caps = codecCaps();
  if (!caps) return false;
  const meta = parseStreamMeta(s);
  // Any 10-bit marker (x265 10bit, Main 10, Hi10P) needs the Main 10 decode
  // path — this intentionally also catches 10-bit releases whose codec token
  // didn't parse.
  if (meta.is10bit && caps.hevcMain10 === false) return true;
  if (meta.codec === "av1" && caps.av1 === false) return true;
  if (meta.codec === "vp9" && caps.vp9 === false) return true;
  if (meta.codec === "h265" && !meta.is10bit && caps.hevc === false) return true;
  return false;
}

function scoreCandidates(
  streams: Stream[],
  preferredProvider?: string,
  sourcePreference?: string,
  deadUrls?: ReadonlySet<string>,
  probedSizes?: ReadonlyMap<string, number>,
  defaultAudioLang?: string,
): ScoredStream[] {
  const caps = codecCaps();
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
    const meta = parseStreamMeta(s);
    // Language preference is a soft nudge in both directions: only a release
    // that explicitly tags other languages *without* a multi/preferred tag is
    // penalized — untagged means "unknown", not "wrong language".
    let langBoost = 0;
    if (defaultAudioLang && meta.hasLangTags) {
      langBoost =
        meta.isMulti || meta.langs.includes(defaultAudioLang)
          ? LANG_BOOST
          : -LANG_PENALTY;
    }
    const dvP5Penalty =
      meta.isDvProfile5 && caps?.dolbyVision === false ? DV_P5_PENALTY : 0;
    return {
      stream: s,
      isTorrent,
      seeders: getSeeders(s),
      sizeBytes,
      quality: inferQuality(s),
      isPreferred,
      dead,
      isHardDisabled: isCodecHardDisabled(s),
      boost:
        (isPreferred ? PROVIDER_BOOST : 0) +
        (matchesSource ? SOURCE_BOOST : 0) +
        langBoost -
        dvP5Penalty -
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
  /** ISO 639-1 preferred audio language — see Settings.defaultAudioLang.
   * Callers resolve the "original" mode to a concrete code (via TMDB
   * original_language) before passing it here; empty/undefined disables
   * language-aware ranking. */
  defaultAudioLang?: string;
}

/** Multiple addons routinely list the same torrent (identical infohash) with
 * diverging seeder counts. For ranking, collapse each infohash to the copy
 * with the highest parsed seeder count so the swarm is counted once and the
 * auto-pick doesn't flip between duplicate entries. First-seen order is
 * preserved; direct-URL streams pass through untouched. */
function dedupeByInfoHash(streams: Stream[]): Stream[] {
  const bestByHash = new Map<string, Stream>();
  for (const s of streams) {
    if (!s.infoHash) continue;
    const current = bestByHash.get(s.infoHash);
    if (!current || getSeeders(s) > getSeeders(current)) {
      bestByHash.set(s.infoHash, s);
    }
  }
  if (bestByHash.size === 0) return streams;
  const emitted = new Set<string>();
  const out: Stream[] = [];
  for (const s of streams) {
    if (!s.infoHash) {
      out.push(s);
    } else if (!emitted.has(s.infoHash)) {
      emitted.add(s.infoHash);
      out.push(bestByHash.get(s.infoHash)!);
    }
  }
  return out;
}

/**
 * Ranks every stream in `streams` best-first according to the given
 * strategy, returning the full ordered pool (never null/empty unless the
 * input is). Candidates a mode's filters would normally exclude entirely
 * (zero-seeder torrents, out-of-budget, sub-480p) aren't dropped — they're
 * appended at the tail in their own sorted order as last-resort fallbacks,
 * so a caller doing candidate-list fallback (B2's watchdog/auto-advance)
 * always has somewhere further to go. Deduped by url || infoHash.
 *
 * Streams the device provably cannot hardware-decode (isCodecHardDisabled)
 * never rank: they're excluded from every mode's pool and appended at the
 * absolute tail — unless *every* candidate is hard-disabled, in which case
 * disabling is waived (trying is better than offering nothing).
 */
export function rankStreams(
  streams: Stream[],
  mode: StreamSelectionMode,
  opts: PickBestOptions = {},
): Stream[] {
  if (streams.length === 0) return [];

  const scored = scoreCandidates(
    dedupeByInfoHash(streams),
    opts.preferredProvider,
    opts.sourcePreference,
    opts.deadUrls,
    opts.probedSizes,
    opts.defaultAudioLang,
  );
  const disabled = scored.filter((c) => c.isHardDisabled);
  const eligible =
    disabled.length === 0 || disabled.length === scored.length
      ? scored
      : scored.filter((c) => !c.isHardDisabled);

  const ranked = rankScored(eligible, mode, opts);
  if (eligible.length === scored.length) return ranked;

  // Hard-disabled tail, sorted by boost/seeders — only reachable by the
  // watchdog when fewer than five eligible candidates exist.
  const rankedKeys = new Set(ranked.map((s) => s.url || s.infoHash || s.title));
  const tail = rankByBoosted(
    disabled,
    () => 0,
    (a, b) => b.seeders - a.seeders,
  )
    .map((c) => c.stream)
    .filter((s) => !rankedKeys.has(s.url || s.infoHash || s.title));
  return [...ranked, ...tail];
}

/** The per-mode ranking core, operating on pre-scored (and pre-filtered)
 * candidates. Split out of rankStreams so the hard-disable partition and
 * infohash dedupe happen exactly once, including across the bandwidth →
 * balanced fallback. */
function rankScored(
  all: ScoredStream[],
  mode: StreamSelectionMode,
  opts: PickBestOptions,
): Stream[] {
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
        // Unknown is deliberately below the normalized 0..1 range so the
        // soft provider/source boosts can never turn "we have no size" into
        // the smallest known file. It still remains in the fallback ranking.
        (c) => (c.sizeBytes > 0 ? 1 - c.sizeBytes / maxSize : -1),
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
        return rankScored(all, "balanced", opts);
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

/** formatStreamSummary plus the parsed codec/language tags, e.g.
 * "42 seeders, 2.10 GB, 1080p [h265 10bit multi]" — used by the auto-select
 * log line so the pick's rationale is visible. */
export function formatAutoPickReason(stream: Stream): string {
  const meta = parseStreamMeta(stream);
  const tags: string[] = [];
  if (meta.codec !== "unknown") {
    tags.push(meta.is10bit ? `${meta.codec} 10bit` : meta.codec);
  }
  if (meta.isMulti) {
    tags.push("multi");
  } else if (meta.langs.length > 0) {
    tags.push(meta.langs.join("+"));
  }
  const summary = formatStreamSummary(stream);
  return tags.length > 0 ? `${summary} [${tags.join(" ")}]` : summary;
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
