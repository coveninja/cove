import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  authSync: vi.fn(),
  profilesList: vi.fn(),
  settingsLoad: vi.fn(),
  libraryUpdate: vi.fn(),
  auth: { isGuest: false, setProfiles: vi.fn() },
}));

vi.mock("$lib/api", () => ({
  api: {
    authSync: mocks.authSync,
    profilesList: mocks.profilesList,
  },
}));
vi.mock("$lib/stores/auth.svelte", () => ({ auth: mocks.auth }));
vi.mock("$lib/stores/settings", () => ({
  settings: { load: mocks.settingsLoad },
}));
vi.mock("$lib/stores/library", () => ({
  libraryChanged: { update: mocks.libraryUpdate },
}));

import { startAutoSync, syncAtStartup } from "$lib/sync";

async function settle(): Promise<void> {
  for (let step = 0; step < 5; step++) await Promise.resolve();
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

describe("startAutoSync", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:00:00Z"));
    mocks.auth.isGuest = false;
    mocks.authSync.mockReset();
    mocks.profilesList.mockReset().mockResolvedValue({
      profiles: [{ id: "primary" }],
      active_profile_id: "primary",
    });
    mocks.settingsLoad.mockReset().mockResolvedValue(undefined);
    mocks.libraryUpdate.mockReset();
    mocks.auth.setProfiles.mockReset();
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

  it("starts syncing after a signed-out session becomes authenticated", async () => {
    mocks.auth.isGuest = true;
    mocks.authSync.mockResolvedValue({
      library_generation: 1,
      push_error: "",
    });
    const stop = startAutoSync(vi.fn());

    mocks.auth.isGuest = false;
    window.dispatchEvent(new Event("focus"));
    await settle();

    expect(mocks.authSync).toHaveBeenCalledOnce();
    stop();
  });

  it("can skip the initial pull after startup already awaited one", async () => {
    mocks.authSync.mockResolvedValue({
      library_generation: 1,
      push_error: "",
    });
    const stop = startAutoSync(vi.fn(), { initialSync: false });
    await settle();

    expect(mocks.authSync).not.toHaveBeenCalled();

    window.dispatchEvent(new Event("focus"));
    await settle();
    expect(mocks.authSync).toHaveBeenCalledOnce();
    stop();
  });

  it("throttles focus and visibility-triggered syncs", async () => {
    mocks.authSync.mockResolvedValue({
      library_generation: 1,
      push_error: "",
    });
    const stop = startAutoSync(vi.fn());
    await settle();

    window.dispatchEvent(new Event("focus"));
    await settle();
    expect(mocks.authSync).toHaveBeenCalledOnce();

    vi.advanceTimersByTime(45_000);
    window.dispatchEvent(new Event("focus"));
    await settle();
    expect(mocks.authSync).toHaveBeenCalledTimes(2);

    Object.defineProperty(document, "hidden", {
      configurable: true,
      value: true,
    });
    document.dispatchEvent(new Event("visibilitychange"));
    await settle();
    expect(mocks.authSync).toHaveBeenCalledTimes(2);

    Object.defineProperty(document, "hidden", {
      configurable: true,
      value: false,
    });
    vi.advanceTimersByTime(45_000);
    document.dispatchEvent(new Event("visibilitychange"));
    await settle();
    expect(mocks.authSync).toHaveBeenCalledTimes(3);
    stop();
  });

  it("skips polling while hidden and resumes on a visible interval", async () => {
    mocks.authSync.mockResolvedValue({
      library_generation: 1,
      push_error: "",
    });
    const stop = startAutoSync(vi.fn());
    await settle();

    Object.defineProperty(document, "hidden", {
      configurable: true,
      value: true,
    });
    vi.advanceTimersByTime(60_000);
    await settle();
    expect(mocks.authSync).toHaveBeenCalledOnce();

    Object.defineProperty(document, "hidden", {
      configurable: true,
      value: false,
    });
    vi.advanceTimersByTime(60_000);
    await settle();
    expect(mocks.authSync).toHaveBeenCalledTimes(2);
    stop();
  });

  it("allows only one sync request in flight", async () => {
    const first = deferred<{
      library_generation: number;
      push_error: string;
    }>();
    mocks.authSync
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce({ library_generation: 2, push_error: "" });
    const stop = startAutoSync(vi.fn());

    vi.advanceTimersByTime(60_000);
    await settle();
    expect(mocks.authSync).toHaveBeenCalledOnce();

    first.resolve({ library_generation: 1, push_error: "" });
    await settle();
    vi.advanceTimersByTime(60_000);
    await settle();
    expect(mocks.authSync).toHaveBeenCalledTimes(2);
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

  it("falls back to bumping on every legacy response and retries after errors", async () => {
    mocks.authSync
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce({ push_error: "" })
      .mockResolvedValueOnce({ push_error: "" });
    mocks.settingsLoad.mockRejectedValue(new Error("settings unavailable"));
    const stop = startAutoSync(vi.fn());
    await settle();

    expect(mocks.libraryUpdate).not.toHaveBeenCalled();

    vi.advanceTimersByTime(60_000);
    await settle();
    vi.advanceTimersByTime(60_000);
    await settle();

    expect(mocks.authSync).toHaveBeenCalledTimes(3);
    expect(mocks.libraryUpdate).toHaveBeenCalledTimes(2);
    expect(mocks.settingsLoad).toHaveBeenCalledTimes(2);
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

describe("syncAtStartup", () => {
  beforeEach(() => {
    mocks.auth.isGuest = false;
    mocks.authSync.mockReset();
    mocks.profilesList.mockReset().mockResolvedValue({
      profiles: [{ id: "primary" }, { id: "active" }],
      active_profile_id: "active",
    });
    mocks.settingsLoad.mockReset().mockResolvedValue(undefined);
    mocks.libraryUpdate.mockReset();
    mocks.auth.setProfiles.mockReset();
  });

  it("awaits the account pull before refreshing settings and profiles", async () => {
    const pull = deferred<{
      status: string;
      library_generation: number;
      push_error: string;
    }>();
    mocks.authSync.mockReturnValue(pull.promise);

    const startup = syncAtStartup(vi.fn());
    await settle();
    expect(mocks.settingsLoad).not.toHaveBeenCalled();
    expect(mocks.profilesList).not.toHaveBeenCalled();

    pull.resolve({
      status: "ok",
      library_generation: 3,
      push_error: "",
    });
    await startup;

    expect(mocks.settingsLoad).toHaveBeenCalledOnce();
    expect(mocks.profilesList).toHaveBeenCalledOnce();
    expect(mocks.auth.setProfiles).toHaveBeenCalledWith(
      [{ id: "primary" }, { id: "active" }],
      { id: "active" },
    );
    expect(mocks.libraryUpdate).toHaveBeenCalledOnce();
  });

  it("loads local settings without contacting account sync for a guest", async () => {
    mocks.auth.isGuest = true;

    await syncAtStartup(vi.fn());

    expect(mocks.authSync).not.toHaveBeenCalled();
    expect(mocks.profilesList).not.toHaveBeenCalled();
    expect(mocks.settingsLoad).toHaveBeenCalledOnce();
  });

  it("still refreshes local state when the startup pull is offline", async () => {
    mocks.authSync.mockRejectedValue(new Error("offline"));

    await syncAtStartup(vi.fn());

    expect(mocks.settingsLoad).toHaveBeenCalledOnce();
    expect(mocks.profilesList).toHaveBeenCalledOnce();
    expect(mocks.libraryUpdate).not.toHaveBeenCalled();
  });
});
