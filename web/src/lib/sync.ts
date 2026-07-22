import { api } from "$lib/api";
import { auth } from "$lib/stores/auth.svelte";
import { libraryChanged } from "$lib/stores/library";
import { settings } from "$lib/stores/settings";

// Interval-based polling is the actual fix for cross-device sync: focus events
// alone leave an open-but-unfocused desktop stale indefinitely while the user
// watches on another device. The interval fires every 60 s while the window is
// visible; focus and visibilitychange listeners handle resume events on
// mobile/TV (Android onResume → window focus; tab switch → visibilitychange).
export function startAutoSync(onPushError: (msg: string) => void): () => void {
  let lastSyncMs = 0;
  let lastGen: number | null = null;
  let lastShownPushErr = "";
  let inFlight = false;

  function syncNow(): void {
    if (auth.isGuest) return;
    if (inFlight) return;
    if (Date.now() - lastSyncMs < 45_000) return;
    lastSyncMs = Date.now();
    inFlight = true;
    api
      .authSync()
      .then((res) => {
        // Only bump when the library actually changed remotely. Older
        // backends / a noop build (503) omit library_generation entirely —
        // fall back to the old always-bump behavior for those.
        if (typeof res.library_generation === "number") {
          if (res.library_generation !== lastGen) {
            lastGen = res.library_generation;
            libraryChanged.update((n) => n + 1);
          }
        } else {
          libraryChanged.update((n) => n + 1);
        }
        // Pull merged settings (including onboardingDone) into the frontend store.
        settings.load().catch(() => {});
        // Surface push errors — only when the message is non-empty and
        // differs from what we already told the user this session (no spam).
        if (res.push_error && res.push_error !== lastShownPushErr) {
          lastShownPushErr = res.push_error;
          console.warn("Sync push error:", res.push_error);
          onPushError("Sync issue: some data failed to upload");
        }
      })
      .catch(() => {})
      .finally(() => {
        inFlight = false;
      });
  }

  const onFocus = () => syncNow();
  const onVisibility = () => {
    if (!document.hidden) syncNow();
  };
  // Poll every 60 s while visible — the primary fix for a desktop that stays
  // open without receiving focus events while the user watches on Android/TV.
  const intervalId = setInterval(() => {
    if (!document.hidden) syncNow();
  }, 60_000);

  window.addEventListener("focus", onFocus);
  document.addEventListener("visibilitychange", onVisibility);

  // Initial pull immediately after session restore, without waiting for an event.
  syncNow();

  return () => {
    clearInterval(intervalId);
    window.removeEventListener("focus", onFocus);
    document.removeEventListener("visibilitychange", onVisibility);
  };
}
