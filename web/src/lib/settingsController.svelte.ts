// $lib/settingsController.svelte.ts
//
// Shared data layer for the settings screens: the settings draft and its
// save/reset cycle, Stremio addon management, Nuvio plugin repos, the custom
// discovery-algorithm test, and the bandwidth speed test.
//
// SettingsPage.svelte and tv/pages/TvSettingsPage.svelte carried identical
// copies of all of this. What stays in each shell is genuinely
// platform-specific: desktop keeps the Trakt device-flow and the mpv.conf
// editor, TV keeps its section navigation and switch-styling helpers.
//
// Unlike StreamsListController nothing here crosses a reactive prop boundary —
// the settings store is used imperatively (load/save/getCurrent/subscribe),
// never through $settings — so the components construct this and call methods
// directly, with no getter plumbing and no $effect.

import { api } from "$lib/api";
import { languageDisplayName, normalizeAppLocale } from "$lib/i18n";
import * as m from "$lib/paraglide/messages.js";
import { settings } from "$lib/stores/settings";
import { KindProvider, type AddonEntry } from "$lib/types/addons";
import type { Repo as NuvioRepo, Scraper as NuvioScraper } from "$lib/types/nuvio";
import type { Settings } from "$lib/types/settings";

// Re-exported so the settings templates keep importing their track-language
// lists from one place; the definitions live in $lib/mediaLanguages.
export { AUDIO_LANGUAGES, LANGUAGES } from "$lib/mediaLanguages";

export function langLabel(value: string): string {
  if (value === "original") return m.common_original();
  return languageDisplayName(value);
}

export class SettingsController {
  // ── Settings draft ───────────────────────────────────────────────────────
  draft = $state<Settings | null>(null);
  saved = $state(false);
  saveError = $state<string | null>(null);
  #saveTimer: ReturnType<typeof setTimeout> | undefined;

  // Auto-update toggle — native pref, lives outside the Go settings store so
  // it is readable before the backend is up. Only rendered on Android /
  // Android TV.
  autoUpdateEnabled = $state(true);

  // ── Addon management ─────────────────────────────────────────────────────
  addons = $state<AddonEntry[]>([]);
  addAddonUrl = $state("");
  addAddonError = $state<string | null>(null);
  addAddonLoading = $state(false);
  refreshingAddonId = $state<string | null>(null);
  configureAddon = $state<AddonEntry | null>(null);

  // ── Nuvio plugin repos ───────────────────────────────────────────────────
  nuvioRepos = $state<NuvioRepo[]>([]);
  addRepoUrl = $state("");
  addRepoError = $state<string | null>(null);
  addRepoLoading = $state(false);
  refreshingRepoId = $state<string | null>(null);
  // Which (repoId, scraperId) pair is showing its "this runs third-party JS"
  // confirmation instead of the plain switch — cleared on confirm or cancel.
  pendingConfirm = $state<{ repoId: string; scraperId: string } | null>(null);

  // ── Remote access token reveal ───────────────────────────────────────────
  // The backend returns "***" for the token when set; we only fetch the real
  // value when the user explicitly clicks Show or Copy.
  revealedToken = $state<string | null>(null);
  tokenVisible = $state(false);
  revealingToken = $state(false);
  tokenCopied = $state(false);
  #tokenCopyTimer: ReturnType<typeof setTimeout> | undefined;

  // ── Discovery algorithm ──────────────────────────────────────────────────
  testingAlgorithm = $state(false);
  algorithmTestResult = $state<{ ok: boolean; error?: string } | null>(null);

  // ── Speed test ───────────────────────────────────────────────────────────
  testingSpeed = $state(false);
  speedTestError = $state<string | null>(null);

  providerAddons = $derived(this.addons.filter((a) => a.kind === KindProvider));

  // Nuvio scraper streams carry AddonName = "Nuvio: <scraper name>" (see
  // internal/nuvio/manager.go) — an entirely separate namespace from Stremio
  // addon manifest names, so they need their own dropdown entries in that
  // exact string form for the preferred-provider match (streamSelection.ts,
  // streamsList.svelte.ts) to ever hit. Only enabled repos/scrapers are
  // listed, same gating as what actually produces streams. Deduped in case the
  // same scraper name appears in more than one enabled repo.
  nuvioProviderOptions = $derived.by(() =>
    Array.from(
      // Transient dedupe set, dropped at the end of this derive.
      // eslint-disable-next-line svelte/prefer-svelte-reactivity
      new Set(
        this.nuvioRepos
          .filter((r) => r.enabled)
          .flatMap((r) =>
            r.scrapers.filter((s) => s.enabled).map((s) => `Nuvio: ${s.name}`),
          ),
      ),
    ),
  );

  /**
   * The shared part of each page's onMount: load settings into the draft,
   * fetch addons and Nuvio repos, and read the native auto-update pref. Each
   * shell adds its own platform-only loads around this call (desktop: Trakt
   * status and mpv.conf).
   */
  async init(): Promise<void> {
    await settings.load();
    const unsub = settings.subscribe((v) => {
      if (!this.draft) this.draft = { ...v };
    });
    unsub();
    this.loadAddons();
    this.loadNuvioRepos();
    // Read the native auto-update preference. The method is optional — absent
    // on desktop where __coveApp is undefined.
    const nativeVal = window.__coveApp?.getAutoUpdateEnabled?.();
    if (typeof nativeVal === "boolean") this.autoUpdateEnabled = nativeVal;
  }

  patch<K extends keyof Settings>(key: K, value: Settings[K]): void {
    if (!this.draft) return;
    this.draft = { ...this.draft, [key]: value };
  }

  async handleSave(): Promise<void> {
    if (!this.draft) return;
    const previousLanguage =
      normalizeAppLocale(settings.getCurrent().uiLanguage) ?? "en";
    const nextLanguage = normalizeAppLocale(this.draft.uiLanguage) ?? "en";
    this.saveError = null;
    const persisted = await settings.save(this.draft);
    if (!persisted) {
      this.saved = false;
      this.saveError = m.language_save_error();
      return;
    }
    if (nextLanguage !== previousLanguage) {
      window.location.reload();
      return;
    }
    this.saved = true;
    clearTimeout(this.#saveTimer);
    this.#saveTimer = setTimeout(() => (this.saved = false), 2000);
    // Pull server-generated fields back into the draft — enabling remote
    // access makes the backend mint a token that only exists in the PUT
    // response (masked as "***"); without this the draft keeps its stale ""
    // and the token row never appears until a reload.
    const unsub = settings.subscribe((v) => {
      if (this.draft) {
        this.draft = {
          ...this.draft,
          remoteAccessToken: v.remoteAccessToken,
          updatedAt: v.updatedAt,
        };
      }
    });
    unsub();
  }

  handleReset(): void {
    this.draft = null;
    settings.load().then(() => {
      const unsub = settings.subscribe((v) => {
        this.draft = { ...v };
      });
      unsub();
    });
  }

  // ── Addons ───────────────────────────────────────────────────────────────

  async loadAddons(): Promise<void> {
    try {
      this.addons = await api.getAddons();
    } catch {
      this.addons = [];
    }
  }

  async handleAddAddon(): Promise<void> {
    if (!this.addAddonUrl.trim()) return;
    this.addAddonLoading = true;
    this.addAddonError = null;
    try {
      const entry = await api.addAddon(this.addAddonUrl.trim());
      this.addons = [...this.addons.filter((a) => a.id !== entry.id), entry];
      this.addAddonUrl = "";
    } catch (e) {
      this.addAddonError =
        e instanceof Error
          ? e.message
          : m.common_failed_message({ error: m.settings_addons() });
    } finally {
      this.addAddonLoading = false;
    }
  }

  async handleToggleAddon(addon: AddonEntry): Promise<void> {
    await api.toggleAddon(addon.id, !addon.enabled, addon.url);
    this.addons = this.addons.map((a) =>
      a.id === addon.id && a.url === addon.url
        ? { ...a, enabled: !a.enabled }
        : a,
    );
  }

  async handleRemoveAddon(addon: AddonEntry): Promise<void> {
    await api.removeAddon(addon.id, addon.url);
    this.addons = this.addons.filter(
      (a) => !(a.id === addon.id && a.url === addon.url),
    );
  }

  async handleToggleCatalog(
    addon: AddonEntry,
    key: string,
    enabled: boolean,
  ): Promise<void> {
    try {
      await api.toggleCatalog(addon.id, key, enabled, addon.url);
      this.addons = this.addons.map((a) =>
        (addon.url ? a.url === addon.url : a.id === addon.id)
          ? {
              ...a,
              disabledCatalogs: {
                ...(a.disabledCatalogs ?? {}),
                [key]: !enabled,
              },
            }
          : a,
      );
    } catch (e) {
      console.error("handleToggleCatalog failed", e);
    }
  }

  async handleRefreshAddon(addon: AddonEntry): Promise<void> {
    this.refreshingAddonId = addon.id;
    try {
      await api.refreshAddon(addon.id, addon.url);
      await this.loadAddons();
    } catch (e) {
      console.error("handleRefreshAddon failed", e);
    } finally {
      this.refreshingAddonId = null;
    }
  }

  // ── Nuvio repos ──────────────────────────────────────────────────────────

  async loadNuvioRepos(): Promise<void> {
    try {
      this.nuvioRepos = await api.getNuvioRepos();
    } catch {
      this.nuvioRepos = [];
    }
  }

  async handleAddRepo(): Promise<void> {
    if (!this.addRepoUrl.trim()) return;
    this.addRepoLoading = true;
    this.addRepoError = null;
    try {
      const repo = await api.addNuvioRepo(this.addRepoUrl.trim());
      this.nuvioRepos = [
        ...this.nuvioRepos.filter((r) => r.id !== repo.id),
        repo,
      ];
      this.addRepoUrl = "";
    } catch (e) {
      this.addRepoError =
        e instanceof Error
          ? e.message
          : m.common_failed_message({ error: m.settings_plugins() });
    } finally {
      this.addRepoLoading = false;
    }
  }

  async handleToggleRepo(repo: NuvioRepo): Promise<void> {
    await api.setNuvioRepoEnabled(repo.id, !repo.enabled);
    this.nuvioRepos = this.nuvioRepos.map((r) =>
      r.id === repo.id ? { ...r, enabled: !r.enabled } : r,
    );
  }

  async handleRemoveRepo(repo: NuvioRepo): Promise<void> {
    await api.removeNuvioRepo(repo.id);
    this.nuvioRepos = this.nuvioRepos.filter((r) => r.id !== repo.id);
  }

  async handleRefreshRepo(repo: NuvioRepo): Promise<void> {
    this.refreshingRepoId = repo.id;
    try {
      await api.refreshNuvioRepo(repo.id);
      this.nuvioRepos = this.nuvioRepos.map((r) =>
        r.id === repo.id
          ? // Formatted to a string immediately — the Date never escapes.
            // eslint-disable-next-line svelte/prefer-svelte-reactivity
            { ...r, fetchedAt: new Date().toISOString() }
          : r,
      );
      await this.loadNuvioRepos();
    } finally {
      this.refreshingRepoId = null;
    }
  }

  requestEnableScraper(repo: NuvioRepo, scraper: NuvioScraper): void {
    if (scraper.enabled) {
      this.handleSetScraperEnabled(repo, scraper, false);
      return;
    }
    this.pendingConfirm = { repoId: repo.id, scraperId: scraper.id };
  }

  async handleSetScraperEnabled(
    repo: NuvioRepo,
    scraper: NuvioScraper,
    enabled: boolean,
  ): Promise<void> {
    this.pendingConfirm = null;
    await api.setNuvioScraperEnabled(repo.id, scraper.id, enabled);
    this.nuvioRepos = this.nuvioRepos.map((r) =>
      r.id === repo.id
        ? {
            ...r,
            scrapers: r.scrapers.map((s) =>
              s.id === scraper.id ? { ...s, enabled } : s,
            ),
          }
        : r,
    );
  }

  // ── Remote access token ──────────────────────────────────────────────────

  async handleRevealToken(): Promise<void> {
    if (this.revealedToken !== null) {
      this.tokenVisible = !this.tokenVisible;
      return;
    }
    this.revealingToken = true;
    try {
      this.revealedToken = await api.revealRemoteAccessToken();
      this.tokenVisible = true;
    } catch (e) {
      console.error("revealRemoteAccessToken:", e);
    } finally {
      this.revealingToken = false;
    }
  }

  async handleCopyToken(): Promise<void> {
    let token = this.revealedToken;
    if (!token) {
      this.revealingToken = true;
      try {
        token = await api.revealRemoteAccessToken();
        this.revealedToken = token;
        this.tokenVisible = true;
      } catch (e) {
        console.error("revealRemoteAccessToken:", e);
        return;
      } finally {
        this.revealingToken = false;
      }
    }
    await navigator.clipboard.writeText(token);
    this.tokenCopied = true;
    clearTimeout(this.#tokenCopyTimer);
    this.#tokenCopyTimer = setTimeout(() => (this.tokenCopied = false), 2000);
  }

  /**
   * Clear the revealed token whenever settings are reloaded (e.g. the "***"
   * sentinel from a fresh getSettings() should not clobber a local reveal).
   * Call from one component $effect.
   */
  clearRevealOnTokenReset(): void {
    if (this.draft?.remoteAccessToken === "") {
      // Token was cleared server-side (regenerated or disabled); forget the
      // local reveal.
      this.revealedToken = null;
      this.tokenVisible = false;
    }
  }

  // ── Discovery algorithm ──────────────────────────────────────────────────

  async handleTestAlgorithm(): Promise<void> {
    if (!this.draft?.customAlgorithmUrl.trim()) return;
    this.testingAlgorithm = true;
    this.algorithmTestResult = null;
    try {
      this.algorithmTestResult = await api.testDiscoveryAlgorithm(
        this.draft.customAlgorithmUrl.trim(),
      );
    } catch (e) {
      this.algorithmTestResult = {
        ok: false,
        error: e instanceof Error ? e.message : m.common_error(),
      };
    } finally {
      this.testingAlgorithm = false;
    }
  }

  // ── Speed test ───────────────────────────────────────────────────────────

  async runSpeedTest(): Promise<void> {
    if (!this.draft) return;
    this.testingSpeed = true;
    this.speedTestError = null;
    try {
      const start = performance.now();
      const res = await fetch(api.speedtestUrl(), { cache: "no-store" });
      const blob = await res.blob();
      const seconds = (performance.now() - start) / 1000;
      const mbps = (blob.size * 8) / 1_000_000 / seconds;
      this.patch("measuredBandwidthMbps", Math.round(mbps * 10) / 10);
    } catch {
      this.speedTestError = "Speed test failed — check your connection.";
    } finally {
      this.testingSpeed = false;
    }
  }
}
