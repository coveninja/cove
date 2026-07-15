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
  const { subscribe, set, update } = writable<Settings>(DEFAULTS);

  async function load(): Promise<void> {
    try {
      set(await api.getSettings());
    } catch (e) {
      console.error("Failed to load settings:", e);
    }
  }

  function save(patch: Partial<Settings>): Promise<void> {
    let next!: Settings;
    update((current) => {
      next = { ...current, ...patch };
      return next;
    });
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
