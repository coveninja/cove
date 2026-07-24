import { get } from "svelte/store";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

async function loadPlatform() {
  vi.resetModules();
  return import("$lib/platform");
}

describe("platform helpers", () => {
  beforeEach(() => {
    localStorage.clear();
    history.replaceState({}, "", "/");
    delete window.__covePlatform;
    delete window.__coveApp;
    delete window.__coveCaps;
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("returns injected codec capabilities by reference and null otherwise", async () => {
    const platform = await loadPlatform();
    expect(platform.codecCaps()).toBeNull();

    const caps = { hevcMain10: false, av1: true, vp9: false };
    window.__coveCaps = caps;
    expect(platform.codecCaps()).toBe(caps);
  });

  it("detects Android from the shell marker or mobile query parameter", async () => {
    const platform = await loadPlatform();
    expect(platform.isAndroid()).toBe(false);

    history.replaceState({}, "", "/?mobile=1");
    expect(platform.isAndroid()).toBe(true);

    history.replaceState({}, "", "/?mobile=0");
    window.__covePlatform = "android";
    expect(platform.isAndroid()).toBe(true);
  });

  it("detects Android TV from the shell marker or TV query parameter", async () => {
    const platform = await loadPlatform();
    expect(platform.isAndroidTV()).toBe(false);

    history.replaceState({}, "", "/?tv=1");
    expect(platform.isAndroidTV()).toBe(true);

    history.replaceState({}, "", "/?tv=0");
    window.__covePlatform = "androidtv";
    expect(platform.isAndroidTV()).toBe(true);
  });

  it("delegates minimize to the native shell and is safe without it", async () => {
    const platform = await loadPlatform();
    expect(() => platform.minimizeApp()).not.toThrow();

    const minimizeApp = vi.fn();
    window.__coveApp = { minimizeApp };
    platform.minimizeApp();
    expect(minimizeApp).toHaveBeenCalledTimes(1);
  });

  it("resolves real and desktop TV modes independently", async () => {
    localStorage.setItem("cove-tv-ui", "1");
    const platform = await loadPlatform();

    expect(platform.isTvMode()).toBe(true);
    expect(platform.isDesktopTvMode()).toBe(true);

    window.__covePlatform = "androidtv";
    localStorage.removeItem("cove-tv-ui");
    expect(platform.isTvMode()).toBe(true);
    expect(platform.isDesktopTvMode()).toBe(false);
  });

  it("persists TV mode and reloads for both enable and disable", async () => {
    const platform = await loadPlatform();
    const reload = vi.fn();
    vi.stubGlobal("location", { search: "", reload });

    platform.setTvMode(true);
    expect(localStorage.getItem("cove-tv-ui")).toBe("1");
    platform.setTvMode(false);
    expect(localStorage.getItem("cove-tv-ui")).toBeNull();
    expect(reload).toHaveBeenCalledTimes(2);
  });

  it("initializes, persists, and reactively updates switch visibility", async () => {
    localStorage.setItem("cove-tv-switch", "0");
    const hidden = await loadPlatform();
    expect(get(hidden.tvSwitchVisible)).toBe(false);

    hidden.setTvSwitchVisible(true);
    expect(get(hidden.tvSwitchVisible)).toBe(true);
    expect(localStorage.getItem("cove-tv-switch")).toBe("1");

    hidden.setTvSwitchVisible(false);
    expect(get(hidden.tvSwitchVisible)).toBe(false);
    expect(localStorage.getItem("cove-tv-switch")).toBe("0");
  });
});
