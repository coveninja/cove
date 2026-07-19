import { writable } from "svelte/store";

declare global {
  interface Window {
    __covePlatform?: string;
    __coveApp?: {
      minimizeApp(): void;
      getAutoUpdateEnabled?(): boolean;
      setAutoUpdateEnabled?(enabled: boolean): void;
    };
    __coveCaps?: CodecCaps;
  }
}

/** Hardware video-decoder support probed by the Android shell (MediaCodecList). */
export interface CodecCaps {
  hevcMain10: boolean;
  av1: boolean;
}

/**
 * The device's hardware decode capabilities, or null when unknown (desktop,
 * browser dev). Injected by MpvBridge's SHIM_JS on Android. Null means
 * "assume everything is supported" — only a confirmed gap should influence
 * stream ranking.
 */
export function codecCaps(): CodecCaps | null {
  return window.__coveCaps ?? null;
}

/**
 * Returns true when the web UI is running inside the Cove Android WebView.
 *
 * Detection order:
 *   1. `window.__covePlatform === 'android'`  — injected by MpvBridge's SHIM_JS.
 *   2. `?mobile=1` query param                — handy for browser dev.
 *   3. `VITE_MOBILE=1` build-time env var     — handy for CI / Storybook.
 */
export function isAndroid(): boolean {
  if (window.__covePlatform === "android") return true;
  if (new URLSearchParams(location.search).get("mobile") === "1") return true;
  return import.meta.env.VITE_MOBILE === "1";
}

/**
 * Returns true when the web UI is running inside the Cove Android TV WebView.
 *
 * Detection order:
 *   1. `window.__covePlatform === 'androidtv'`  — injected by MpvBridge's SHIM_JS.
 *   2. `?tv=1` query param                      — handy for browser dev.
 *   3. `VITE_TV=1` build-time env var            — handy for CI / Storybook.
 */
export function isAndroidTV(): boolean {
  if (window.__covePlatform === "androidtv") return true;
  if (new URLSearchParams(location.search).get("tv") === "1") return true;
  return import.meta.env.VITE_TV === "1";
}

/**
 * Ask the native Android shell to move the app to the background.
 * No-op when called outside the Android WebView (the JS interface is absent).
 */
export function minimizeApp(): void {
  window.__coveApp?.minimizeApp();
}

// localStorage key used to persist the desktop TV-interface preference.
const TV_UI_KEY = "cove-tv-ui";

/**
 * Returns true when the TV/D-pad shell should be mounted.
 *
 * Detection order:
 *   1. `isAndroidTV()` — real Android TV device; TV shell is always correct.
 *   2. `localStorage "cove-tv-ui" === "1"` — desktop opt-in via Settings
 *      or the Qt `--tv` flag (which appends `?tvui=1` and persists the key).
 */
export function isTvMode(): boolean {
  if (isAndroidTV()) return true;
  return localStorage.getItem(TV_UI_KEY) === "1";
}

/**
 * Returns true when the desktop TV-interface opt-in is active and this is
 * not a real Android TV device. Used to show the "switch back to desktop"
 * control inside TvSettingsPage so it is never visible on real Android TV.
 */
export function isDesktopTvMode(): boolean {
  return (
    localStorage.getItem(TV_UI_KEY) === "1" &&
    window.__covePlatform !== "androidtv"
  );
}

/**
 * Enable or disable the desktop TV-interface mode. Persists the choice to
 * localStorage and immediately reloads the page so the correct shell mounts.
 */
export function setTvMode(on: boolean): void {
  if (on) {
    localStorage.setItem(TV_UI_KEY, "1");
  } else {
    localStorage.removeItem(TV_UI_KEY);
  }
  location.reload();
}

// localStorage key used to persist the "TV interface switch" visibility preference.
const TV_SWITCH_KEY = "cove-tv-switch";

/**
 * Controls whether the TopBar shows the TV-interface switch button.
 * Device-local like the TV-mode preference itself, hence localStorage
 * rather than backend settings.
 */
export const tvSwitchVisible = writable(
  localStorage.getItem(TV_SWITCH_KEY) !== "0",
);

/** Persist and reactively update the TV-interface switch visibility. */
export function setTvSwitchVisible(on: boolean): void {
  localStorage.setItem(TV_SWITCH_KEY, on ? "1" : "0");
  tvSwitchVisible.set(on);
}
