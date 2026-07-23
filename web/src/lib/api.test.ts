import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, api, setTokenSource } from "$lib/api";

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
