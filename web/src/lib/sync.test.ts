import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  authSync: vi.fn(),
  settingsLoad: vi.fn(),
  libraryUpdate: vi.fn(),
  auth: { isGuest: false },
}));

vi.mock("$lib/api", () => ({ api: { authSync: mocks.authSync } }));
vi.mock("$lib/stores/auth.svelte", () => ({ auth: mocks.auth }));
vi.mock("$lib/stores/settings", () => ({
  settings: { load: mocks.settingsLoad },
}));
vi.mock("$lib/stores/library", () => ({
  libraryChanged: { update: mocks.libraryUpdate },
}));

import { startAutoSync } from "$lib/sync";

async function settle(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

describe("startAutoSync", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:00:00Z"));
    mocks.auth.isGuest = false;
    mocks.authSync.mockReset();
    mocks.settingsLoad.mockReset().mockResolvedValue(undefined);
    mocks.libraryUpdate.mockReset();
    Object.defineProperty(document, "hidden", {
      configurable: true,
      value: false,
    });
  });

  it("does not contact sync while signed out", () => {
    mocks.auth.isGuest = true;
    const stop = startAutoSync(vi.fn());
    expect(mocks.authSync).not.toHaveBeenCalled();
    stop();
  });

  it("coalesces generations and reports each push error only once", async () => {
    mocks.authSync
      .mockResolvedValueOnce({
        library_generation: 7,
        push_error: "upload failed",
      })
      .mockResolvedValueOnce({
        library_generation: 7,
        push_error: "upload failed",
      })
      .mockResolvedValueOnce({ library_generation: 8, push_error: "" });
    const onPushError = vi.fn();
    const stop = startAutoSync(onPushError);

    await settle();
    expect(mocks.libraryUpdate).toHaveBeenCalledTimes(1);
    expect(onPushError).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(60_000);
    await settle();
    expect(mocks.libraryUpdate).toHaveBeenCalledTimes(1);
    expect(onPushError).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(60_000);
    await settle();
    expect(mocks.libraryUpdate).toHaveBeenCalledTimes(2);
    expect(mocks.settingsLoad).toHaveBeenCalledTimes(3);
    stop();
  });

  it("removes listeners and polling on cleanup", async () => {
    mocks.authSync.mockResolvedValue({ library_generation: 1, push_error: "" });
    const removeWindow = vi.spyOn(window, "removeEventListener");
    const removeDocument = vi.spyOn(document, "removeEventListener");
    const stop = startAutoSync(vi.fn());
    await settle();
    stop();
    vi.advanceTimersByTime(120_000);

    expect(mocks.authSync).toHaveBeenCalledTimes(1);
    expect(removeWindow).toHaveBeenCalledWith("focus", expect.any(Function));
    expect(removeDocument).toHaveBeenCalledWith(
      "visibilitychange",
      expect.any(Function),
    );
  });
});
