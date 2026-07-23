import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  progressGet: vi.fn(),
  progressSave: vi.fn(),
}));

vi.mock("$lib/api", () => ({
  api: {
    progressGet: mocks.progressGet,
    progressSave: mocks.progressSave,
  },
}));

import { ProgressSaver } from "./progressSaver.svelte";
import type { ProgressContext } from "./progressSaver.svelte";

const ctx: ProgressContext = {
  tmdbId: 603,
  mediaType: "movie",
  title: "The Matrix",
  posterPath: "/matrix.jpg",
  voteAverage: 8.7,
  lastAirDate: "",
  season: null,
  episode: null,
  probedDuration: null,
};

function makeCtx(
  overrides: Partial<ProgressContext> = {},
): () => ProgressContext {
  return () => ({ ...ctx, ...overrides });
}

beforeEach(() => {
  vi.useFakeTimers();
  // Start at t=10_001 so Date.now() - #lastSaveMs (0) >= 10_000 on the
  // very first maybeSave call — otherwise the throttle guard suppresses it.
  vi.setSystemTime(10_001);
  mocks.progressGet.mockReset();
  mocks.progressSave.mockReset().mockResolvedValue(undefined);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("ProgressSaver.load", () => {
  it("sets savedPosition when the server returns a meaningful in-progress record", async () => {
    mocks.progressGet.mockResolvedValue({
      completed: false,
      position_seconds: 300,
    });
    const saver = new ProgressSaver();
    await saver.load(603, "movie", null, null);
    expect(saver.savedPosition).toBe(300);
  });

  it("does not set savedPosition when position is ≤ 10 seconds", async () => {
    mocks.progressGet.mockResolvedValue({
      completed: false,
      position_seconds: 8,
    });
    const saver = new ProgressSaver();
    await saver.load(603, "movie", null, null);
    expect(saver.savedPosition).toBeNull();
  });

  it("does not set savedPosition when the record is marked completed", async () => {
    mocks.progressGet.mockResolvedValue({
      completed: true,
      position_seconds: 3500,
    });
    const saver = new ProgressSaver();
    await saver.load(603, "movie", null, null);
    expect(saver.savedPosition).toBeNull();
  });

  it("does not set savedPosition when progressGet returns null", async () => {
    mocks.progressGet.mockResolvedValue(null);
    const saver = new ProgressSaver();
    await saver.load(603, "movie", null, null);
    expect(saver.savedPosition).toBeNull();
  });

  it("ignores a response that was superseded by reset()", async () => {
    let resolveFetch!: (v: unknown) => void;
    mocks.progressGet.mockReturnValueOnce(
      new Promise((r) => {
        resolveFetch = r;
      }),
    );

    const saver = new ProgressSaver();
    const loadPromise = saver.load(603, "movie", null, null);
    saver.reset(); // invalidate the in-flight load
    resolveFetch({ completed: false, position_seconds: 400 });
    await loadPromise;
    expect(saver.savedPosition).toBeNull();
  });
});

describe("ProgressSaver.resume", () => {
  it("calls seek with the saved position", async () => {
    mocks.progressGet.mockResolvedValue({
      completed: false,
      position_seconds: 120,
    });
    const saver = new ProgressSaver();
    await saver.load(603, "movie", null, null);
    const seek = vi.fn();
    saver.resume(seek);
    expect(seek).toHaveBeenCalledWith(120);
    expect(saver.hasSeekedToSaved).toBe(true);
  });

  it("does not call seek when no position is saved", () => {
    const saver = new ProgressSaver();
    const seek = vi.fn();
    saver.resume(seek);
    expect(seek).not.toHaveBeenCalled();
  });

  it("only seeks once even if resume is called multiple times", async () => {
    mocks.progressGet.mockResolvedValue({
      completed: false,
      position_seconds: 60,
    });
    const saver = new ProgressSaver();
    await saver.load(603, "movie", null, null);
    const seek = vi.fn();
    saver.resume(seek);
    saver.resume(seek);
    expect(seek).toHaveBeenCalledTimes(1);
  });
});

describe("ProgressSaver.maybeSave", () => {
  it("saves on the first call", async () => {
    const saver = new ProgressSaver();
    await saver.maybeSave(30, 100, makeCtx());
    expect(mocks.progressSave).toHaveBeenCalledTimes(1);
  });

  it("throttles saves within the 10-second window", async () => {
    const saver = new ProgressSaver();
    await saver.maybeSave(30, 100, makeCtx());
    vi.advanceTimersByTime(5_000);
    await saver.maybeSave(35, 100, makeCtx());
    expect(mocks.progressSave).toHaveBeenCalledTimes(1);
  });

  it("saves again after 10 seconds have passed", async () => {
    const saver = new ProgressSaver();
    await saver.maybeSave(30, 100, makeCtx());
    vi.advanceTimersByTime(10_001);
    await saver.maybeSave(40, 100, makeCtx());
    expect(mocks.progressSave).toHaveBeenCalledTimes(2);
  });

  it("marks completed when position is ≥ 90% of duration", async () => {
    const saver = new ProgressSaver();
    await saver.maybeSave(91, 100, makeCtx());
    const call = mocks.progressSave.mock.calls[0][0] as {
      completed: boolean;
      position_seconds: number;
    };
    expect(call.completed).toBe(true);
    // Completed records store duration as position.
    expect(call.position_seconds).toBe(100);
  });

  it("does not mark completed below the 90% threshold", async () => {
    const saver = new ProgressSaver();
    await saver.maybeSave(85, 100, makeCtx());
    const call = mocks.progressSave.mock.calls[0][0] as {
      completed: boolean;
      position_seconds: number;
    };
    expect(call.completed).toBe(false);
    expect(call.position_seconds).toBe(85);
  });

  it("skips the save when position is below 5 seconds", async () => {
    const saver = new ProgressSaver();
    await saver.maybeSave(3, 100, makeCtx());
    expect(mocks.progressSave).not.toHaveBeenCalled();
  });
});

describe("ProgressSaver.saveNow", () => {
  it("saves immediately regardless of the throttle window", async () => {
    const saver = new ProgressSaver();
    await saver.maybeSave(20, 100, makeCtx()); // sets lastSaveMs
    // Still within the 10-second window but saveNow bypasses it.
    await saver.saveNow(25, 100, makeCtx(), false);
    expect(mocks.progressSave).toHaveBeenCalledTimes(2);
  });

  it("does not downgrade a completed record with an incomplete one", async () => {
    const saver = new ProgressSaver();
    await saver.saveNow(100, 100, makeCtx(), true); // completed
    vi.advanceTimersByTime(15_000);
    // position=50/100=50% < 90% → not auto-upgraded → isCompleted=false → skipped
    await saver.saveNow(50, 100, makeCtx(), false);
    expect(mocks.progressSave).toHaveBeenCalledTimes(1);
  });

  it("passes the completed flag through to the API", async () => {
    const saver = new ProgressSaver();
    await saver.saveNow(50, 100, makeCtx(), true);
    const call = mocks.progressSave.mock.calls[0][0] as {
      completed: boolean;
    };
    expect(call.completed).toBe(true);
  });
});

describe("ProgressSaver.reset", () => {
  it("clears savedPosition and hasSeekedToSaved", async () => {
    mocks.progressGet.mockResolvedValue({
      completed: false,
      position_seconds: 200,
    });
    const saver = new ProgressSaver();
    await saver.load(603, "movie", null, null);
    saver.resume(vi.fn());
    expect(saver.savedPosition).toBe(200);
    expect(saver.hasSeekedToSaved).toBe(true);
    saver.reset();
    expect(saver.savedPosition).toBeNull();
    expect(saver.hasSeekedToSaved).toBe(false);
  });
});
