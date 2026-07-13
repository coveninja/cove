declare global {
  interface Window {
    __covePlatform?: string;
    __coveApp?: { minimizeApp(): void };
  }
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
 * Ask the native Android shell to move the app to the background.
 * No-op when called outside the Android WebView (the JS interface is absent).
 */
export function minimizeApp(): void {
  window.__coveApp?.minimizeApp();
}
