import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, api, formatPosition, setTokenSource } from "$lib/api";

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    headers: { "Content-Type": "application/json" },
    ...init,
  });
}

describe("API request invariants", () => {
  beforeEach(() => {
    setTokenSource(() => null);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    setTokenSource(() => null);
    api.clearInflight();
  });

  it("coalesces concurrent GETs and authenticates the shared request", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ onboardingDone: true }));
    vi.stubGlobal("fetch", fetchMock);
    setTokenSource(() => "test-access-token");

    const [first, second] = await Promise.all([
      api.getSettings(),
      api.getSettings(),
    ]);

    expect(first).toEqual(second);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://127.0.0.1:6969/api/settings");
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer test-access-token",
    );
  });

  it("delivers the same result to all concurrent callers sharing a coalesced GET", async () => {
    // Four simultaneous identical GETs → one fetch, all callers get the result.
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ onboardingDone: true }));
    vi.stubGlobal("fetch", fetchMock);

    const results = await Promise.all([
      api.getSettings(),
      api.getSettings(),
      api.getSettings(),
      api.getSettings(),
    ]);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    for (const r of results) {
      expect(r.onboardingDone).toBe(true);
    }
  });

  it("does not serve a stale coalesced response after the first request settles", async () => {
    // Each call to fetch returns a different body so we can tell them apart.
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ onboardingDone: false }))
      .mockResolvedValueOnce(jsonResponse({ onboardingDone: true }));
    vi.stubGlobal("fetch", fetchMock);

    const first = await api.getSettings();
    // The in-flight map is evicted on settlement, so the next sequential call
    // must hit the network fresh rather than receiving a cached result.
    const second = await api.getSettings();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(first.onboardingDone).toBe(false);
    expect(second.onboardingDone).toBe(true);
  });

  it("never coalesces library mutations", async () => {
    const entry = {
      tmdb_id: 603,
      media_type: "movie",
      title: "The Matrix",
      poster_path: "/matrix.jpg",
      status: "watch_later",
    } as const;
    const fetchMock = vi
      .fn()
      .mockImplementation(() => Promise.resolve(jsonResponse(entry)));
    vi.stubGlobal("fetch", fetchMock);

    await Promise.all([api.libraryUpsert(entry), api.libraryUpsert(entry)]);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    for (const [url, init] of fetchMock.mock.calls as [string, RequestInit][]) {
      expect(url).toBe("http://127.0.0.1:6969/api/library");
      expect(init.method).toBe("POST");
      expect(JSON.parse(String(init.body))).toEqual(entry);
    }
  });

  it("preserves the status, response body, and path on HTTP failures", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(new Response("sync unavailable", { status: 503 })),
    );

    const error = await api.authSync().catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 503,
      body: "sync unavailable",
      path: "/auth/sync",
    });
  });

  it("preserves existing headers while adding the latest auth token", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation(() => Promise.resolve(jsonResponse({ results: [] })));
    vi.stubGlobal("fetch", fetchMock);
    let token = "first-token";
    setTokenSource(() => token);

    await api.probeStreams([], 900);
    token = "second-token";
    await api.probeStreams([], 900);

    const firstHeaders = new Headers(
      (fetchMock.mock.calls[0][1] as RequestInit).headers,
    );
    const secondHeaders = new Headers(
      (fetchMock.mock.calls[1][1] as RequestInit).headers,
    );
    expect(firstHeaders.get("Content-Type")).toBe("application/json");
    expect(firstHeaders.get("Authorization")).toBe("Bearer first-token");
    expect(secondHeaders.get("Authorization")).toBe("Bearer second-token");
  });

  it("handles an unreadable HTTP error body without masking response metadata", async () => {
    const bodyFailure = new Error("body stream failed");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 502,
        text: vi.fn().mockRejectedValue(bodyFailure),
      } as unknown as Response),
    );

    const error = await api.authSync().catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 502,
      body: "",
      path: "/auth/sync",
      message: "API 502 on /auth/sync",
    });
  });

  it("evicts a failed JSON response so the next GET can recover", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response("{not-json"))
      .mockResolvedValueOnce(jsonResponse({ onboardingDone: true }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(api.getSettings()).rejects.toBeInstanceOf(SyntaxError);
    await expect(api.getSettings()).resolves.toMatchObject({
      onboardingDone: true,
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("accepts an empty successful response for void endpoints", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );

    await expect(api.authLogout()).resolves.toBeUndefined();
  });
});

describe("concurrency limiter", () => {
  beforeEach(() => {
    setTokenSource(() => null);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    setTokenSource(() => null);
    api.clearInflight();
  });

  it("queues requests beyond the 8-slot maximum and drains them as slots free", async () => {
    // We need 9 distinct GET paths so they are never coalesced with each other.
    // api.search(q) maps to GET /search?q=<q> — a different URL per query.
    const resolvers: Array<() => void> = [];
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(
        () =>
          new Promise<Response>((res) => {
            resolvers.push(() =>
              res(
                new Response(JSON.stringify([]), {
                  headers: { "Content-Type": "application/json" },
                }),
              ),
            );
          }),
      ),
    );

    // Fire 9 requests — one more than MAX_CONCURRENT (8).
    const queries = Array.from({ length: 9 }, (_, i) => `keyword-${i}`);
    const requests = queries.map((q) => api.search(q));

    // Drain the microtask queue so all 9 coroutines have had a chance to
    // call acquireSlot().  Eight of them proceed immediately; the ninth waits.
    for (let i = 0; i < 5; i++) await Promise.resolve();

    const fetchSpy = vi.mocked(globalThis.fetch);
    expect(fetchSpy).toHaveBeenCalledTimes(8);

    // Resolve one slot — the queued 9th request should now start.
    resolvers[0]();
    for (let i = 0; i < 5; i++) await Promise.resolve();
    expect(fetchSpy).toHaveBeenCalledTimes(9);

    // Clean up: settle all remaining requests so inFlight returns to 0.
    resolvers.slice(1).forEach((r) => r());
    await Promise.allSettled(requests);
  });

  it("releases a slot even when fetch rejects", async () => {
    // Two concurrent requests to different URLs: the first rejects, the second
    // must still complete rather than being stuck waiting for a freed slot.
    const resolvers: Array<() => void> = [];
    let callCount = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(
        () =>
          new Promise<Response>((res, rej) => {
            callCount++;
            if (callCount === 1) {
              resolvers.push(() => rej(new TypeError("Network error")));
            } else {
              resolvers.push(() =>
                res(
                  new Response(JSON.stringify([]), {
                    headers: { "Content-Type": "application/json" },
                  }),
                ),
              );
            }
          }),
      ),
    );

    const r1 = api.search("fail-query").catch(() => null);
    const r2 = api.search("ok-query");

    for (let i = 0; i < 5; i++) await Promise.resolve();
    resolvers[0](); // first rejects — slot must be freed in finally
    for (let i = 0; i < 5; i++) await Promise.resolve();
    resolvers[1](); // second resolves

    const [, result] = await Promise.all([r1, r2]);
    expect(Array.isArray(result)).toBe(true);
  });
});

describe("API boundary behavior", () => {
  beforeEach(() => {
    setTokenSource(() => null);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    setTokenSource(() => null);
    api.clearInflight();
  });

  it("treats 404 and successful empty responses as absent optional data", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(new Response("missing", { status: 404 }))
        .mockResolvedValueOnce(new Response(null, { status: 204 })),
    );

    await expect(api.libraryGet(603, "movie")).resolves.toBeNull();
    await expect(api.progressGet(603, "movie")).resolves.toBeNull();
  });

  it("throws for non-404 optional-data failures even when the body is unreadable", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        text: vi.fn().mockRejectedValue(new Error("body unavailable")),
      } as unknown as Response),
    );

    await expect(api.libraryGet(603, "movie")).rejects.toMatchObject({
      status: 500,
      body: "",
      path: "/library/603/movie",
    });
  });

  it("does not coalesce GET requests with caller-owned abort signals", async () => {
    const fetchMock = vi.fn().mockImplementation(() => jsonResponse([]));
    vi.stubGlobal("fetch", fetchMock);
    const first = new AbortController();
    const second = new AbortController();

    await Promise.all([
      api.getStreams(1396, { type: "tv", season: 2, episode: 3 }, first.signal),
      api.getStreams(
        1396,
        { type: "tv", season: 2, episode: 3 },
        second.signal,
      ),
    ]);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[0][0]).toBe(
      "http://127.0.0.1:6969/api/streams?id=1396&type=tv&season=2&episode=3",
    );
    expect((fetchMock.mock.calls[0][1] as RequestInit).signal).not.toBe(
      first.signal,
    );
  });

  it("builds encoded player and progress URLs without losing zero-valued options", () => {
    expect(
      api.playUrl("torrent hash", { season: 0, episode: 0, fileIdx: 0 }),
    ).toBe(
      "http://127.0.0.1:6969/api/play?hash=torrent+hash&season=0&episode=0&fileIdx=0",
    );
    expect(
      api.progressStreamUrl("torrent hash", {
        season: 1,
        episode: 2,
        fileIdx: 0,
      }),
    ).toBe(
      "http://127.0.0.1:6969/api/progress/stream?hash=torrent+hash&season=1&episode=2&fileIdx=0",
    );
    expect(api.playUrl("https://cdn.test/video file.mkv")).toBe(
      "https://cdn.test/video file.mkv",
    );
    expect(api.playProxyUrl("https://cdn.test/v?a=1&b=2")).toBe(
      "http://127.0.0.1:6969/api/play?url=https%3A%2F%2Fcdn.test%2Fv%3Fa%3D1%26b%3D2",
    );
    expect(api.subtitleProxyUrl("https://subs.test/a b.srt")).toBe(
      "http://127.0.0.1:6969/api/subtitle-proxy?url=https%3A%2F%2Fsubs.test%2Fa%20b.srt",
    );
    expect(api.playUrl("HTTPS://CDN.TEST/video.mkv")).toBe(
      "HTTPS://CDN.TEST/video.mkv",
    );
    expect(api.playUrl("http-stream-like-hash")).toBe(
      "http://127.0.0.1:6969/api/play?hash=http-stream-like-hash",
    );
  });

  it("parses chunked NDJSON, ignores malformed frames, and flushes the final frame", async () => {
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            '{"id":"movie:1","quality":"4K"}\nnot-json\n{"wrong":"shape"}\n{"id":"tv:',
          ),
        );
        controller.enqueue(encoder.encode('2","quality":"1080p"}'));
        controller.close();
      },
    });
    const fetchMock = vi.fn().mockResolvedValue(new Response(body));
    vi.stubGlobal("fetch", fetchMock);
    setTokenSource(() => "quality-token");
    const entries: Array<[string, string]> = [];

    await api.streamQualityBatch(["movie:1", "tv:2"], (id, quality) =>
      entries.push([id, quality]),
    );

    expect(entries).toEqual([
      ["movie:1", "4K"],
      ["tv:2", "1080p"],
    ]);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(
      "http://127.0.0.1:6969/api/quality/batch?ids=movie%3A1,tv%3A2",
    );
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer quality-token",
    );
  });

  it("skips an empty quality batch without opening a request", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    await api.streamQualityBatch([], vi.fn());

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("cancels a quality reader and absorbs AbortError", async () => {
    const abortError = new DOMException("cancelled", "AbortError");
    const reader = {
      read: vi.fn().mockRejectedValue(abortError),
      cancel: vi.fn().mockResolvedValue(undefined),
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        body: { getReader: () => reader },
      } as unknown as Response),
    );

    await expect(
      api.streamQualityBatch(["movie:1"], vi.fn()),
    ).resolves.toBeUndefined();
    expect(reader.cancel).toHaveBeenCalledTimes(1);
  });

  it("cancels a failed quality reader and propagates non-abort failures", async () => {
    const failure = new Error("stream failed");
    const reader = {
      read: vi.fn().mockRejectedValue(failure),
      cancel: vi.fn().mockResolvedValue(undefined),
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        body: { getReader: () => reader },
      } as unknown as Response),
    );

    await expect(api.streamQualityBatch(["movie:1"], vi.fn())).rejects.toBe(
      failure,
    );
    expect(reader.cancel).toHaveBeenCalledTimes(1);
  });

  it("forces a fresh GET after an identity change clears coalesced requests", async () => {
    let resolveFirst!: (response: Response) => void;
    const firstResponse = new Promise<Response>((resolve) => {
      resolveFirst = resolve;
    });
    const fetchMock = vi
      .fn()
      .mockReturnValueOnce(firstResponse)
      .mockResolvedValueOnce(jsonResponse({ onboardingDone: true }));
    vi.stubGlobal("fetch", fetchMock);

    const stale = api.getSettings();
    api.clearInflight();
    const fresh = api.getSettings();

    await expect(fresh).resolves.toMatchObject({ onboardingDone: true });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    resolveFirst(jsonResponse({ onboardingDone: false }));
    await expect(stale).resolves.toMatchObject({ onboardingDone: false });
  });

  it("absorbs an unavailable optional Trakt integration but not other errors", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(new Response("not compiled", { status: 503 }))
        .mockResolvedValueOnce(new Response("broken", { status: 500 })),
    );

    await expect(api.traktStatus()).resolves.toBeNull();
    await expect(api.traktStatus()).rejects.toMatchObject({ status: 500 });
  });

  it("formats playback positions at second, minute, and hour boundaries", () => {
    expect(formatPosition(Number.NaN)).toBe("0s");
    expect(formatPosition(Number.POSITIVE_INFINITY)).toBe("0s");
    expect(formatPosition(-1)).toBe("0s");
    expect(formatPosition(8.9)).toBe("8s");
    expect(formatPosition(252)).toBe("4m 12s");
    expect(formatPosition(4980)).toBe("1h 23m");
  });
});
