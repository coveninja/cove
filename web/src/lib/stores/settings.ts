// src/lib/stores/settings.ts
import { writable, Subscriber, Unsubscriber } from "svelte/store";
import type { Settings } from "$lib/types/settings";
import { api } from "$lib/api";

export const DEFAULT_SETTINGS: Settings = {
  openOnMute: false,
  defaultVolume: 1.0,
  autoPlay: false,
  rememberPosition: true,
  defaultProvider: "",
  subtitlesEnabled: false,
  defaultSubtitleLang: "en",
  defaultAudioLang: "en",
  uiLanguage: "",
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
  remoteAccessToken: "",
  allowLanStreamSources: false,
  traktScrobbleEnabled: true,
  traktSyncEnabled: false,
};

export function createSettingsStore(): {
  subscribe: (
    this: void,
    run: Subscriber<Settings>,
    invalidate?: () => void,
  ) => Unsubscriber;
  load: () => Promise<void>;
  save: (patch: Partial<Settings>) => Promise<boolean>;
  getCurrent: () => Settings;
} {
  const { subscribe, set } = writable<Settings>(DEFAULT_SETTINGS);
  // Mirror of the store's current value, kept for load()'s no-change check —
  // svelte stores have no synchronous read without a subscribe round-trip.
  let current: Settings = DEFAULT_SETTINGS;

  // Guards a load that started before a save from overwriting the newer
  // optimistic value when its stale response arrives later.
  let saveSeq = 0;

  async function load(): Promise<void> {
    const seqAtStart = saveSeq;
    try {
      const next = await api.getSettings();
      if (seqAtStart !== saveSeq) return;
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

  // Guards the PUT response against overwriting a newer optimistic save.
  function save(patch: Partial<Settings>): Promise<boolean> {
    const next: Settings = { ...current, ...patch };
    current = next;
    set(next);
    const seq = ++saveSeq;
    // Optimistic update is already applied above; persist in the background.
    // The server response is authoritative — it carries fields the client
    // can't produce (a freshly generated remoteAccessToken, returned masked
    // as "***", and the server-stamped updatedAt), so apply it unless a newer
    // save has started since.
    // The .catch ensures non-awaiting callers never produce unhandled rejections.
    return api
      .updateSettings(next)
      .then((server) => {
        if (seq !== saveSeq) return true;
        current = server;
        set(server);
        return true;
      })
      .catch((e) => {
        console.error("Failed to save settings:", e);
        return false;
      });
  }

  return { subscribe, load, save, getCurrent: () => current };
}

export const settings = createSettingsStore();
