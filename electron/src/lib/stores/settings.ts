// src/lib/stores/settings.ts
import { writable, derived } from "svelte/store";
import type { Settings } from "$lib/types/settings";
import { api } from "$lib/api";

const DEFAULTS: Settings = {
  openOnMute: false,
  defaultVolume: 1.0,
  autoPlay: false,
  rememberPosition: true,
  defaultProvider: "torrentio",
  preferHLS: true,
  subtitlesEnabled: false,
  defaultSubtitleLang: "en",
  defaultAudioLang: "en",
  showStreamDetails: true,
};

function createSettingsStore() {
  const { subscribe, set, update } = writable<Settings>(DEFAULTS);

  async function load() {
    try {
      set(await api.getSettings());
    } catch (e) {
      console.error("Failed to load settings:", e);
    }
  }

  function save(patch: Partial<Settings>) {
    update((current) => {
      const next = { ...current, ...patch };
      // Optimistic update — persist in the background.
      api.updateSettings(next).catch((e) =>
        console.error("Failed to save settings:", e),
      );
      return next;
    });
  }

  return { subscribe, load, save };
}

export const settings = createSettingsStore();

// Convenience derived stores for single-flag subscriptions.
export const openOnMute = derived(settings, ($s) => $s.openOnMute);
export const preferHLS = derived(settings, ($s) => $s.preferHLS);
export const defaultProvider = derived(settings, ($s) => $s.defaultProvider);
export const autoPlay = derived(settings, ($s) => $s.autoPlay);
