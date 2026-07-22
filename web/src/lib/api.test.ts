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
