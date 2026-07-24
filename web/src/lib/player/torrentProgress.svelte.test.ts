import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { TorrentProgress } from "$lib/player/torrentProgress.svelte";

class FakeEventSource {
  static instances: FakeEventSource[] = [];

  onmessage: ((event: MessageEvent<string>) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  close = vi.fn();

  constructor(public url: string) {
    FakeEventSource.instances.push(this);
  }

  message(data: unknown): void {
    this.onmessage?.({
      data: typeof data === "string" ? data : JSON.stringify(data),
    } as MessageEvent<string>);
  }

  error(): void {
    this.onerror?.(new Event("error"));
  }
}

describe("TorrentProgress", () => {
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal("EventSource", FakeEventSource);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("tracks complete progress frames and defaults missing optional fields", () => {
    const progress = new TorrentProgress();
    const cleanup = progress.start("abc123", {
      season: 1,
      episode: 2,
      fileIdx: 0,
    });
    const source = FakeEventSource.instances[0];

    expect(source.url).toBe(
      "http://127.0.0.1:6969/api/progress/stream?hash=abc123&season=1&episode=2&fileIdx=0",
    );
    source.message({
      found: true,
      progress: 42.5,
      peers: 7,
      speed: "3 MB/s",
      seeders: 4,
      totalPeers: 9,
      speedBps: 3_000_000,
      downloadedBytes: 400,
      totalBytes: 1_000,
    });
    expect(progress).toMatchObject({
      progress: 42.5,
      peers: 7,
      speed: "3 MB/s",
      seeders: 4,
      totalPeers: 9,
      speedBps: 3_000_000,
      downloadedBytes: 400,
      totalBytes: 1_000,
      stalled: false,
    });

    source.message({ found: true, progress: 50 });
    expect(progress).toMatchObject({
      progress: 50,
      peers: 0,
      speed: "0 B/s",
      seeders: 0,
      totalPeers: 0,
      speedBps: 0,
      downloadedBytes: 0,
      totalBytes: 0,
    });

    cleanup();
    expect(source.close).toHaveBeenCalledTimes(1);
  });

  it("ignores malformed and not-found frames", () => {
    const progress = new TorrentProgress();
    progress.start("abc123");
    const source = FakeEventSource.instances[0];
    progress.progress = 12;

    source.message("not-json");
    source.message({ found: false, progress: 99 });

    expect(progress.progress).toBe(12);
  });

  it("only stalls after five consecutive errors and a message resets the count", () => {
    const progress = new TorrentProgress();
    progress.start("abc123");
    const source = FakeEventSource.instances[0];

    for (let i = 0; i < 4; i++) source.error();
    expect(progress.stalled).toBe(false);
    expect(source.close).not.toHaveBeenCalled();

    source.message({ found: false });
    for (let i = 0; i < 4; i++) source.error();
    expect(progress.stalled).toBe(false);

    source.error();
    expect(progress.stalled).toBe(true);
    expect(source.close).toHaveBeenCalledTimes(1);
  });

  it("closes the previous stream on restart and resets all prior state", () => {
    const progress = new TorrentProgress();
    const staleCleanup = progress.start("first");
    const first = FakeEventSource.instances[0];
    first.message({
      found: true,
      progress: 88,
      peers: 5,
      speed: "1 MB/s",
      totalBytes: 100,
    });
    for (let i = 0; i < 5; i++) first.error();

    const cleanup = progress.start("second");
    const second = FakeEventSource.instances[1];

    expect(first.close).toHaveBeenCalled();
    expect(progress).toMatchObject({
      progress: 0,
      peers: 0,
      speed: "0 B/s",
      totalBytes: 0,
      stalled: false,
    });

    staleCleanup();
    expect(second.close).not.toHaveBeenCalled();
    cleanup();
    expect(second.close).toHaveBeenCalledTimes(1);
  });
});
