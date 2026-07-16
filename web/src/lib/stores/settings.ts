// src/lib/stores/settings.ts
import { writable, Subscriber, Unsubscriber } from "svelte/store";
import type { Settings } from "$lib/types/settings";
import { api } from "$lib/api";

const DEFAULTS: Settings = {
  openOnMute: false,
  defaultVolume: 1.0,
  autoPlay: false,
  rememberPosition: true,
  defaultProvider: "",
  subtitlesEnabled: false,
  defaultSubtitleLang: "en",
  defaultAudioLang: "en",
  showStreamDetails: true,
  autoSelectStream: true,
  streamSelectionMode: null,
  measuredBandwidthMbps: 0,
  sourcePreference: "",
  subtitleSize: 150,
  subtitlePosition: 8,
  subtitleBackground: true,
  hideSpoilers: false,
  autoSkipIntro: false,
  autoSkipRecap: false,
  autoSkipCredits: false,
  autoSkipPreview: false,
  onboardingDone: false,
  discoveryAlgorithm: "smart",
  customAlgorithmUrl: "",
  prefetchStreams: true,
  prefetchNextEpisode: true,
  allowUploading: true,
  probeStreams: true,
  updatedAt: "",
  remoteAccessEnabled: false,
  remoteAccessToken: ""
};

function createSettingsStore(): {
  subscribe: (
    this: void,
    run: Subscriber<Settings>,
    invalidate?: () => void,
  ) => Unsubscriber;
  load: () => Promise<void>;
  save: (patch: Partial<Settings>) => Promise<void>;
} {
  const { subscribe, set } = writable<Settings>(DEFAULTS);
  // Mirror of the store's current value, kept for load()'s no-change check —
  // svelte stores have no synchronous read without a subscribe round-trip.
  let current: Settings = DEFAULTS;

  async function load(): Promise<void> {
    try {
      const next = await api.getSettings();
      // Skip the store update when nothing changed. load() runs after every
      // auth sync (periodic while signed in), and an unconditional set()
      // wakes every $settings subscriber even for identical content.
      if (JSON.stringify(next) === JSON.stringify(current)) return;
      current = next;
      set(next);
    } catch (e) {
      console.error("Failed to load settings:", e);
    }
  }

  function save(patch: Partial<Settings>): Promise<void> {
    const next: Settings = { ...current, ...patch };
    current = next;
    set(next);
    // Optimistic update is already applied above; persist in the background.
    // The .catch ensures non-awaiting callers never produce unhandled rejections.
    return api
      .updateSettings(next)
      .then(() => undefined)
      .catch((e) => { console.error("Failed to save settings:", e); });
  }

  return { subscribe, load, save };
}

export const settings = createSettingsStore();
