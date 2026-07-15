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
  if (import.meta.env.VITE_MOBILE === "1") return true;
  return false;
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
  if (import.meta.env.VITE_TV === "1") return true;
  return false;
}

/**
 * Ask the native Android shell to move the app to the background.
 * No-op when called outside the Android WebView (the JS interface is absent).
 */
export function minimizeApp(): void {
  window.__coveApp?.minimizeApp();
}
