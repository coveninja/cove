import { get } from "svelte/store";
import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({
  getSettings: vi.fn(),
  updateSettings: vi.fn(),
}));

vi.mock("$lib/api", () => ({ api: apiMock }));

import { createSettingsStore, DEFAULT_SETTINGS } from "$lib/stores/settings";
import type { Settings } from "$lib/types/settings";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe("settings store", () => {
  beforeEach(() => {
    apiMock.getSettings.mockReset();
    apiMock.updateSettings.mockReset();
  });

  it("starts with defaults and skips notifying subscribers for an unchanged load", async () => {
    const store = createSettingsStore();
    const subscriber = vi.fn();
    const unsubscribe = store.subscribe(subscriber);
    apiMock.getSettings.mockResolvedValue({ ...DEFAULT_SETTINGS });

    await store.load();

    expect(get(store)).toEqual(DEFAULT_SETTINGS);
    expect(subscriber).toHaveBeenCalledTimes(1);
    unsubscribe();
  });

  it("applies changed server settings on load", async () => {
    const store = createSettingsStore();
    const server = {
      ...DEFAULT_SETTINGS,
      defaultVolume: 0.4,
      updatedAt: "server-time",
    };
    apiMock.getSettings.mockResolvedValue(server);

    await store.load();

    expect(get(store)).toEqual(server);
  });

  it("keeps the newest save when overlapping server responses arrive out of order", async () => {
    const store = createSettingsStore();
    const first = deferred<Settings>();
    const second = deferred<Settings>();
    apiMock.updateSettings
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);

    const firstSave = store.save({ defaultVolume: 0.3 });
    const secondSave = store.save({ defaultVolume: 0.8 });
    expect(get(store).defaultVolume).toBe(0.8);

    first.resolve({
      ...DEFAULT_SETTINGS,
      defaultVolume: 0.3,
      updatedAt: "old-response",
    });
    await firstSave;
    expect(get(store).defaultVolume).toBe(0.8);

    second.resolve({
      ...DEFAULT_SETTINGS,
      defaultVolume: 0.8,
      updatedAt: "new-response",
    });
    await secondSave;
    expect(get(store)).toMatchObject({
      defaultVolume: 0.8,
      updatedAt: "new-response",
    });
  });

  it("does not let a load started before a save overwrite the optimistic save", async () => {
    const store = createSettingsStore();
    const load = deferred<Settings>();
    const save = deferred<Settings>();
    apiMock.getSettings.mockReturnValue(load.promise);
    apiMock.updateSettings.mockReturnValue(save.promise);

    const loading = store.load();
    const saving = store.save({ autoPlay: true });
    load.resolve({ ...DEFAULT_SETTINGS, autoPlay: false });
    await loading;

    expect(get(store).autoPlay).toBe(true);

    save.resolve({ ...DEFAULT_SETTINGS, autoPlay: true });
    await saving;
    expect(get(store).autoPlay).toBe(true);
  });

  it("retains an optimistic value and reports persistence failures", async () => {
    const store = createSettingsStore();
    const error = new Error("disk full");
    apiMock.updateSettings.mockRejectedValue(error);
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => {});

    await expect(store.save({ hideSpoilers: true })).resolves.toBe(false);

    expect(get(store).hideSpoilers).toBe(true);
    expect(consoleError).toHaveBeenCalledWith(
      "Failed to save settings:",
      error,
    );
  });

  it("exposes the latest snapshot without a subscription round trip", async () => {
    const store = createSettingsStore();
    apiMock.updateSettings.mockResolvedValue({
      ...DEFAULT_SETTINGS,
      uiLanguage: "tr",
    });

    await expect(store.save({ uiLanguage: "tr" })).resolves.toBe(true);

    expect(store.getCurrent().uiLanguage).toBe("tr");
  });
});
