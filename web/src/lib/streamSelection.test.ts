import { afterEach, describe, expect, it, vi } from "vitest";

import { api } from "$lib/api";
import { parseStreamMeta } from "$lib/streamMeta";
import {
  compareStreamsBy,
  formatAutoPickReason,
  formatStreamSummary,
  getSeeders,
  getSizeBytes,
  isCodecHardDisabled,
  isTorrentStream,
  pickBestStream,
  qualityRank,
  rankStreams,
  rankStreamsWithProbe,
  type SortableStream,
} from "$lib/streamSelection";
import type { Stream } from "$lib/types/addons";

const MB = 1024 ** 2;
const GB = 1024 ** 3;

let streamSequence = 0;

function makeStream(overrides: Partial<Stream> = {}): Stream {
  streamSequence++;
  return {
    name: "Provider\n1080p",
    title: "Movie.1080p.x264",
    url: `https://cdn.example/${streamSequence}`,
    infoHash: "",
    addonName: "Provider",
    ...overrides,
  };
}

function sortable(
  stream: Stream,
  overrides: Partial<Omit<SortableStream, "stream" | "meta">> = {},
): SortableStream {
  return {
    stream,
    seeders: getSeeders(stream),
    sizeBytes: getSizeBytes(stream),
    quality: stream.name.toLowerCase().includes("4k") ? "4k" : "1080p",
    meta: parseStreamMeta(stream),
    ...overrides,
  };
}

afterEach(() => {
  delete window.__coveCaps;
  vi.restoreAllMocks();
});

describe("stream metadata helpers", () => {
  it("ranks known qualities and rejects unknown values", () => {
    expect(qualityRank("4k dv")).toBeGreaterThan(qualityRank("4k"));
    expect(qualityRank("1080p")).toBeGreaterThan(qualityRank("cam"));
    expect(qualityRank(null)).toBe(-1);
    expect(qualityRank("8k")).toBe(-1);
  });

  it("distinguishes torrents and parses their seeder counts", () => {
    const torrent = makeStream({
      url: "",
      infoHash: "hash",
      title: "Movie.1080p 👤 127 💾 2.5 GB",
    });
    const direct = makeStream();

    expect(isTorrentStream(torrent)).toBe(true);
    expect(isTorrentStream(direct)).toBe(false);
    expect(getSeeders(torrent)).toBe(127);
    expect(getSeeders(direct)).toBe(0);
  });

  it("prefers structured sizes and parses addon size conventions", () => {
    expect(
      getSizeBytes(
        makeStream({
          sizeBytes: 42,
          title: "Movie 👤 1 💾 99 GB",
        }),
      ),
    ).toBe(42);
    expect(getSizeBytes(makeStream({ title: "Movie 💾 1.5 TB" }))).toBe(
      1.5 * 1024 ** 4,
    );
    expect(getSizeBytes(makeStream({ title: "Movie 💾 2.5 GB" }))).toBe(
      2.5 * GB,
    );
    expect(getSizeBytes(makeStream({ title: "Movie 💾 700 MB" }))).toBe(
      700 * MB,
    );
  });

  it("accepts plain-text sizes only for direct streams", () => {
    const direct = makeStream({ title: "Movie release 1.4 GB" });
    const torrent = makeStream({
      url: "",
      infoHash: "plain-size-hash",
      title: "Movie release 1.4 GB",
    });

    expect(getSizeBytes(direct)).toBe(1.4 * GB);
    expect(getSizeBytes(torrent)).toBe(0);
  });

  it("formats human-readable direct and torrent summaries", () => {
    const torrent = makeStream({
      url: "",
      infoHash: "summary-hash",
      name: "Provider\n1080p",
      title: "Movie.1080p.x265.MULTi 👤 42 💾 2.1 GB",
    });
    const direct = makeStream({
      name: "Provider\n720p",
      title: "Movie.720p",
      sizeBytes: 700 * MB,
    });

    expect(formatStreamSummary(torrent)).toBe("42 seeders, 2.10 GB, 1080p");
    expect(formatStreamSummary(direct)).toBe("direct stream, 700 MB, 720p");
    expect(formatAutoPickReason(torrent)).toContain("[h265 multi]");
  });
});

describe("manual stream sorting", () => {
  it("sorts by seeders, size, quality, language, and cache status", () => {
    const english = makeStream({
      title: "Movie.1080p.ENG 👤 10 💾 2 GB",
    });
    const french = makeStream({
      title: "Movie.4K.FRENCH 👤 20 💾 4 GB",
      cached: true,
    });
    const unknownSize = makeStream({ title: "Movie.1080p 👤 30" });
    const rows = [
      sortable(english, { quality: "1080p" }),
      sortable(french, { quality: "4k" }),
      sortable(unknownSize, { quality: "1080p" }),
    ];

    expect(rows.toSorted(compareStreamsBy("seeders"))[0].stream).toBe(
      unknownSize,
    );
    expect(rows.toSorted(compareStreamsBy("largest"))[0].stream).toBe(french);
    expect(rows.toSorted(compareStreamsBy("smallest"))[0].stream).toBe(english);
    expect(rows.toSorted(compareStreamsBy("smallest"))[2].stream).toBe(
      unknownSize,
    );
    expect(rows.toSorted(compareStreamsBy("quality"))[0].stream).toBe(french);
    expect(rows.toSorted(compareStreamsBy("language", "en"))[0].stream).toBe(
      english,
    );
    expect(rows.toSorted(compareStreamsBy("cached"))[0].stream).toBe(french);
  });

  it("falls back to seeders for language ties", () => {
    const first = sortable(makeStream({ title: "Movie.ENG 👤 5 💾 1 GB" }));
    const second = sortable(makeStream({ title: "Movie.MULTi 👤 15 💾 1 GB" }));

    expect(
      [first, second].toSorted(compareStreamsBy("language", "en"))[0].stream,
    ).toBe(second.stream);
    expect(
      [first, second].toSorted(compareStreamsBy("language"))[0].stream,
    ).toBe(second.stream);
  });
});

describe("automatic stream ranking", () => {
  it("returns no candidate for an empty input", () => {
    expect(rankStreams([], "balanced")).toEqual([]);
    expect(pickBestStream([], "quality")).toBeNull();
  });

  it("ranks each strategy by its defining signal", () => {
    const reliable = makeStream({
      url: "",
      infoHash: "reliable",
      name: "Provider\n1080p",
      title: "Movie.1080p 👤 100 💾 8 GB",
    });
    const highestQuality = makeStream({
      url: "",
      infoHash: "quality",
      name: "Provider\n4K",
      title: "Movie.4K 👤 10 💾 20 GB",
    });
    const smallest = makeStream({
      url: "",
      infoHash: "smallest",
      name: "Provider\n720p",
      title: "Movie.720p 👤 20 💾 1 GB",
    });
    const cachedDirect = makeStream({
      name: "Provider\n1080p",
      title: "Movie.1080p",
      cached: true,
      sizeBytes: 2 * GB,
    });
    const streams = [highestQuality, smallest, reliable, cachedDirect];

    expect(rankStreams(streams, "seeders")[0]).toBe(reliable);
    expect(rankStreams(streams, "quality")[0]).toBe(highestQuality);
    expect(rankStreams(streams, "smallest")[0]).toBe(smallest);
    expect(rankStreams(streams, "balanced")[0]).toBe(cachedDirect);
    expect(
      rankStreams(streams, "bandwidth", {
        measuredBandwidthMbps: 5,
        estimatedMinutes: 90,
      })[0],
    ).toBe(cachedDirect);
    expect(rankStreams(streams, "bandwidth")[0]).toBe(cachedDirect);
  });

  it("puts unknown sizes after known sizes in smallest mode", () => {
    const known = makeStream({
      name: "Provider\n1080p",
      title: "Movie.1080p",
      sizeBytes: 2 * GB,
    });
    const unknown = makeStream({
      name: "Provider\n1080p",
      title: "Movie.1080p",
    });

    expect(rankStreams([unknown, known], "smallest")[0]).toBe(known);
  });

  it("keeps zero-seeder torrents as last-resort fallbacks", () => {
    const deadSwarm = makeStream({
      url: "",
      infoHash: "zero-seeders",
      name: "Provider\n4K",
      title: "Movie.4K 👤 0 💾 2 GB",
    });
    const liveSwarm = makeStream({
      url: "",
      infoHash: "live-seeders",
      name: "Provider\n720p",
      title: "Movie.720p 👤 2 💾 2 GB",
    });

    expect(rankStreams([deadSwarm, liveSwarm], "quality")).toEqual([
      liveSwarm,
      deadSwarm,
    ]);
    expect(rankStreams([deadSwarm], "seeders")).toEqual([deadSwarm]);
  });

  it("deduplicates torrent infohashes using the strongest swarm", () => {
    const weak = makeStream({
      url: "",
      infoHash: "same-hash",
      title: "Movie.1080p 👤 3 💾 2 GB",
    });
    const strong = makeStream({
      url: "",
      infoHash: "same-hash",
      title: "Movie.1080p 👤 30 💾 2 GB",
    });

    expect(rankStreams([weak, strong], "seeders")).toEqual([strong]);
  });

  it("uses provider, source, and audio preferences as soft boosts", () => {
    const preferred = makeStream({
      addonName: "Preferred",
      title: "Movie.1080p.ENG",
    });
    const other = makeStream({
      addonName: "Other",
      title: "Movie.1080p.FRENCH",
    });
    const torrent = makeStream({
      url: "",
      infoHash: "preference-torrent",
      addonName: "Other",
      title: "Movie.1080p 👤 1 💾 2 GB",
    });

    expect(
      rankStreams([other, preferred], "quality", {
        preferredProvider: "Preferred",
      })[0],
    ).toBe(preferred);
    expect(
      rankStreams([other, preferred], "quality", {
        defaultAudioLang: "en",
      })[0],
    ).toBe(preferred);
    expect(
      rankStreams([torrent, other], "quality", {
        sourcePreference: "direct",
      })[0],
    ).toBe(other);
  });

  it("demotes links confirmed dead without removing fallbacks", () => {
    const dead = makeStream({ title: "Movie.4K", name: "Provider\n4K" });
    const live = makeStream({
      title: "Movie.1080p",
      name: "Provider\n1080p",
    });

    expect(
      rankStreams([dead, live], "quality", {
        deadUrls: new Set([dead.url]),
      }),
    ).toEqual([live, dead]);
  });

  it("hard-disables unsupported codecs unless every choice is unsupported", () => {
    window.__coveCaps = {
      hevcMain10: true,
      av1: false,
      hevc: true,
      vp9: true,
    };
    const av1 = makeStream({
      name: "Provider\n4K",
      title: "Movie.4K.AV1",
    });
    const h264 = makeStream({
      name: "Provider\n720p",
      title: "Movie.720p.x264",
    });
    const anotherAV1 = makeStream({
      name: "Provider\n1080p",
      title: "Movie.1080p.AV1",
    });

    expect(isCodecHardDisabled(av1)).toBe(true);
    expect(isCodecHardDisabled(h264)).toBe(false);
    expect(rankStreams([av1, h264], "quality")).toEqual([h264, av1]);
    expect(rankStreams([anotherAV1, av1], "quality")[0]).toBe(av1);
  });
});

describe("live stream probing", () => {
  it("skips probes when disabled or when only torrents are present", async () => {
    const probe = vi.spyOn(api, "probeStreams");
    const direct = makeStream();
    const torrent = makeStream({
      url: "",
      infoHash: "probe-torrent",
      title: "Movie.1080p 👤 10 💾 2 GB",
    });

    await expect(
      rankStreamsWithProbe([direct], "quality", { probeEnabled: false }),
    ).resolves.toEqual([direct]);
    await expect(
      rankStreamsWithProbe([torrent], "quality", { probeEnabled: true }),
    ).resolves.toEqual([torrent]);
    expect(probe).not.toHaveBeenCalled();
  });

  it("probes at most five direct candidates and demotes dead results", async () => {
    const streams = Array.from({ length: 7 }, (_, index) =>
      makeStream({
        name: `Provider\n${index === 0 ? "4K" : "1080p"}`,
        title: `Movie.${index === 0 ? "4K" : "1080p"}`,
      }),
    );
    const signal = new AbortController().signal;
    const probe = vi.spyOn(api, "probeStreams").mockResolvedValue({
      results: streams.slice(0, 5).map((stream, index) => ({
        url: stream.url,
        alive: index !== 0,
        contentLength: index === 0 ? 0 : GB,
      })),
    });

    const ranked = await rankStreamsWithProbe(
      streams,
      "quality",
      { probeEnabled: true },
      signal,
    );

    expect(probe).toHaveBeenCalledWith(
      streams.slice(0, 5).map((stream) => ({ url: stream.url })),
      700,
      signal,
    );
    expect(ranked[0]).not.toBe(streams[0]);
    expect(ranked).toContain(streams[0]);
  });

  it("uses probed sizes and falls back cleanly when probing fails", async () => {
    const unknown = makeStream({ title: "Movie.1080p" });
    const known = makeStream({
      title: "Movie.1080p",
      sizeBytes: 2 * GB,
    });
    const probe = vi.spyOn(api, "probeStreams").mockResolvedValueOnce({
      results: [
        { url: unknown.url, alive: true, contentLength: 500 * MB },
        { url: known.url, alive: true, contentLength: 2 * GB },
      ],
    });

    await expect(
      rankStreamsWithProbe([known, unknown], "smallest", {
        probeEnabled: true,
      }),
    ).resolves.toEqual([unknown, known]);

    probe.mockRejectedValueOnce(new Error("probe unavailable"));
    await expect(
      rankStreamsWithProbe([known, unknown], "quality", {
        probeEnabled: true,
      }),
    ).resolves.toEqual(rankStreams([known, unknown], "quality"));
  });
});
