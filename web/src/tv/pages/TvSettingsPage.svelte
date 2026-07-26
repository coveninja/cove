<script lang="ts">
  import { onMount } from "svelte";
  import { settings } from "$lib/stores/settings";
  import type { Settings } from "$lib/types/settings";
  import { isAndroid, isAndroidTV, isDesktopTvMode, setTvMode } from "$lib/platform";
  import { STREAM_SELECTION_MODES, SOURCE_PREFERENCES } from "$lib/streamSelection";
  import { DISCOVERY_ALGORITHMS } from "$lib/discoveryAlgorithms";
  import { api } from "$lib/api";
  import type { AddonEntry } from "$lib/types/addons";
  import {
    KindProvider,
    KindTimestamps,
    SourceOfficial,
  } from "$lib/types/addons";
  import type {
    Repo as NuvioRepo,
    Scraper as NuvioScraper,
  } from "$lib/types/nuvio";
  import { focusGroup, focusable } from "../focus/actions";
  import { TriangleAlert, RefreshCw, Trash2, Cog, X } from "lucide-svelte";
  import * as m from "$lib/paraglide/messages.js";
  import {
    LOCALES,
    languageDisplayName,
    normalizeAppLocale,
    type AppLocale,
  } from "$lib/i18n";

  // ── Section navigation ────────────────────────────────────────────────────────
  type SectionId =
    | "playback"
    | "streaming"
    | "subtitles"
    | "interface"
    | "addons"
    | "plugins";

  let activeSection = $state<SectionId>("playback");

  const SECTIONS: { id: SectionId; label: string }[] = [
    { id: "playback", label: m.settings_playback() },
    { id: "streaming", label: m.settings_streaming() },
    { id: "subtitles", label: m.settings_subtitles_audio() },
    { id: "interface", label: m.settings_interface() },
    { id: "addons", label: m.settings_addons() },
    { id: "plugins", label: m.settings_plugins() },
  ];

  // ── Settings draft ────────────────────────────────────────────────────────────
  let draft = $state<Settings | null>(null);
  let saved = $state(false);
  let saveError = $state<string | null>(null);
  let saveTimer: ReturnType<typeof setTimeout>;

  // Auto-update toggle — native pref, lives outside the Go settings store.
  // Only rendered on Android / Android TV.
  let autoUpdateEnabled = $state(true);

  onMount(async () => {
    await settings.load();
    const unsub = settings.subscribe((v) => {
      if (!draft) draft = { ...v };
    });
    unsub();
    loadAddons();
    loadNuvioRepos();
    const nativeVal = window.__coveApp?.getAutoUpdateEnabled?.();
    if (typeof nativeVal === "boolean") autoUpdateEnabled = nativeVal;
  });

  function patch<K extends keyof Settings>(key: K, value: Settings[K]) {
    if (!draft) return;
    draft = { ...draft, [key]: value };
  }

  async function handleSave() {
    if (!draft) return;
    const previousLanguage = normalizeAppLocale(settings.getCurrent().uiLanguage) ?? "en";
    const nextLanguage = normalizeAppLocale(draft.uiLanguage) ?? "en";
    saveError = null;
    const persisted = await settings.save(draft);
    if (!persisted) {
      saved = false;
      saveError = m.language_save_error();
      return;
    }
    if (nextLanguage !== previousLanguage) {
      window.location.reload();
      return;
    }
    saved = true;
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => (saved = false), 2000);
    const unsub = settings.subscribe((v) => {
      if (draft) {
        draft = {
          ...draft,
          remoteAccessToken: v.remoteAccessToken,
          updatedAt: v.updatedAt,
        };
      }
    });
    unsub();
  }

  function handleReset() {
    draft = null;
    settings.load().then(() => {
      const unsub = settings.subscribe((v) => {
        draft = { ...v };
      });
      unsub();
    });
  }

  // ── Addon management ──────────────────────────────────────────────────────────
  let addons = $state<AddonEntry[]>([]);
  let addAddonUrl = $state("");
  let addAddonError = $state<string | null>(null);
  let addAddonLoading = $state(false);

  async function loadAddons() {
    try {
      addons = await api.getAddons();
    } catch {
      addons = [];
    }
  }

  const providerAddons = $derived(addons.filter((a) => a.kind === KindProvider));

  async function handleAddAddon() {
    if (!addAddonUrl.trim()) return;
    addAddonLoading = true;
    addAddonError = null;
    try {
      const entry = await api.addAddon(addAddonUrl.trim());
      addons = [...addons.filter((a) => a.id !== entry.id), entry];
      addAddonUrl = "";
    } catch (e) {
      addAddonError = e instanceof Error ? e.message : m.common_failed_message({ error: m.settings_addons() });
    } finally {
      addAddonLoading = false;
    }
  }

  async function handleToggleAddon(addon: AddonEntry) {
    await api.toggleAddon(addon.id, !addon.enabled, addon.url);
    addons = addons.map((a) =>
      a.id === addon.id && a.url === addon.url
        ? { ...a, enabled: !a.enabled }
        : a,
    );
  }

  async function handleRemoveAddon(addon: AddonEntry) {
    await api.removeAddon(addon.id, addon.url);
    addons = addons.filter(
      (a) => !(a.id === addon.id && a.url === addon.url),
    );
  }

  async function handleToggleCatalog(
    addon: AddonEntry,
    key: string,
    enabled: boolean,
  ) {
    try {
      await api.toggleCatalog(addon.id, key, enabled, addon.url);
      addons = addons.map((a) =>
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

  let refreshingAddonId = $state<string | null>(null);
  let configureAddon = $state<AddonEntry | null>(null);

  async function handleRefreshAddon(addon: AddonEntry) {
    refreshingAddonId = addon.id;
    try {
      await api.refreshAddon(addon.id, addon.url);
      await loadAddons();
    } catch (e) {
      console.error("handleRefreshAddon failed", e);
    } finally {
      refreshingAddonId = null;
    }
  }

  // ── Nuvio plugin repos ────────────────────────────────────────────────────────
  let nuvioRepos = $state<NuvioRepo[]>([]);
  let addRepoUrl = $state("");
  let addRepoError = $state<string | null>(null);
  let addRepoLoading = $state(false);
  let refreshingRepoId = $state<string | null>(null);
  let pendingConfirm = $state<{ repoId: string; scraperId: string } | null>(
    null,
  );

  async function loadNuvioRepos() {
    try {
      nuvioRepos = await api.getNuvioRepos();
    } catch {
      nuvioRepos = [];
    }
  }

  const nuvioProviderOptions = $derived(
    Array.from(
      new Set(
        nuvioRepos
          .filter((r) => r.enabled)
          .flatMap((r) =>
            r.scrapers
              .filter((s) => s.enabled)
              .map((s) => `Nuvio: ${s.name}`),
          ),
      ),
    ),
  );

  async function handleAddRepo() {
    if (!addRepoUrl.trim()) return;
    addRepoLoading = true;
    addRepoError = null;
    try {
      const repo = await api.addNuvioRepo(addRepoUrl.trim());
      nuvioRepos = [...nuvioRepos.filter((r) => r.id !== repo.id), repo];
      addRepoUrl = "";
    } catch (e) {
      addRepoError =
        e instanceof Error ? e.message : m.common_failed_message({ error: m.settings_plugins() });
    } finally {
      addRepoLoading = false;
    }
  }

  async function handleToggleRepo(repo: NuvioRepo) {
    await api.setNuvioRepoEnabled(repo.id, !repo.enabled);
    nuvioRepos = nuvioRepos.map((r) =>
      r.id === repo.id ? { ...r, enabled: !r.enabled } : r,
    );
  }

  async function handleRemoveRepo(repo: NuvioRepo) {
    await api.removeNuvioRepo(repo.id);
    nuvioRepos = nuvioRepos.filter((r) => r.id !== repo.id);
  }

  async function handleRefreshRepo(repo: NuvioRepo) {
    refreshingRepoId = repo.id;
    try {
      await api.refreshNuvioRepo(repo.id);
      nuvioRepos = nuvioRepos.map((r) =>
        r.id === repo.id ? { ...r, fetchedAt: new Date().toISOString() } : r,
      );
      await loadNuvioRepos();
    } finally {
      refreshingRepoId = null;
    }
  }

  function requestEnableScraper(repo: NuvioRepo, scraper: NuvioScraper) {
    if (scraper.enabled) {
      handleSetScraperEnabled(repo, scraper, false);
      return;
    }
    pendingConfirm = { repoId: repo.id, scraperId: scraper.id };
  }

  async function handleSetScraperEnabled(
    repo: NuvioRepo,
    scraper: NuvioScraper,
    enabled: boolean,
  ) {
    pendingConfirm = null;
    await api.setNuvioScraperEnabled(repo.id, scraper.id, enabled);
    nuvioRepos = nuvioRepos.map((r) =>
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

  // ── Discovery algorithm ───────────────────────────────────────────────────────
  let testingAlgorithm = $state(false);
  let algorithmTestResult = $state<{ ok: boolean; error?: string } | null>(
    null,
  );

  async function handleTestAlgorithm() {
    if (!draft?.customAlgorithmUrl.trim()) return;
    testingAlgorithm = true;
    algorithmTestResult = null;
    try {
      algorithmTestResult = await api.testDiscoveryAlgorithm(
        draft.customAlgorithmUrl.trim(),
      );
    } catch (e) {
      algorithmTestResult = {
        ok: false,
        error: e instanceof Error ? e.message : m.common_error(),
      };
    } finally {
      testingAlgorithm = false;
    }
  }

  const LANGUAGES = [
    { value: "en" },
    { value: "es" },
    { value: "fr" },
    { value: "de" },
    { value: "pt" },
    { value: "it" },
    { value: "ja" },
    { value: "ko" },
    { value: "zh" },
    { value: "ar" },
    { value: "ru" },
  ];

  const AUDIO_LANGUAGES = [
    { value: "original" },
    ...LANGUAGES,
  ];

  // ── Speed test ────────────────────────────────────────────────────────────────
  let testingSpeed = $state(false);
  let speedTestError = $state<string | null>(null);

  async function runSpeedTest() {
    if (!draft) return;
    testingSpeed = true;
    speedTestError = null;
    try {
      const start = performance.now();
      const res = await fetch(api.speedtestUrl(), { cache: "no-store" });
      const blob = await res.blob();
      const seconds = (performance.now() - start) / 1000;
      const mbps = (blob.size * 8) / 1_000_000 / seconds;
      patch("measuredBandwidthMbps", Math.round(mbps * 10) / 10);
    } catch {
      speedTestError = "Speed test failed — check your connection.";
    } finally {
      testingSpeed = false;
    }
  }

  // ── Toggle button class helpers ───────────────────────────────────────────────
  // Avoids {@const} which Svelte 5 only allows inside control-flow blocks.
  function trackBg(on: boolean) {
    return on ? "bg-accent" : "bg-white/20";
  }
  function thumb(on: boolean) {
    return on ? "translate-x-8" : "translate-x-1";
  }

  // ── Remote access token ───────────────────────────────────────────────────────
  let revealedToken = $state<string | null>(null);
  let tokenVisible = $state(false);
  let revealingToken = $state(false);
  let tokenCopied = $state(false);
  let tokenCopyTimer: ReturnType<typeof setTimeout>;

  async function handleRevealToken(): Promise<void> {
    if (revealedToken !== null) {
      tokenVisible = !tokenVisible;
      return;
    }
    revealingToken = true;
    try {
      revealedToken = await api.revealRemoteAccessToken();
      tokenVisible = true;
    } catch (e) {
      console.error("revealRemoteAccessToken:", e);
    } finally {
      revealingToken = false;
    }
  }

  async function handleCopyToken(): Promise<void> {
    let token = revealedToken;
    if (!token) {
      revealingToken = true;
      try {
        token = await api.revealRemoteAccessToken();
        revealedToken = token;
        tokenVisible = true;
      } catch (e) {
        console.error("revealRemoteAccessToken:", e);
        return;
      } finally {
        revealingToken = false;
      }
    }
    await navigator.clipboard.writeText(token);
    tokenCopied = true;
    clearTimeout(tokenCopyTimer);
    tokenCopyTimer = setTimeout(() => (tokenCopied = false), 2000);
  }

  $effect(() => {
    if (draft?.remoteAccessToken === "") {
      revealedToken = null;
      tokenVisible = false;
    }
  });
</script>

<!--
  TvSettingsPage — always-mounted; parent toggles visibility via class:hidden.
  Root is flex h-full overflow-hidden per the TV page contract.
-->
<div class="flex h-full overflow-hidden bg-background text-foreground">
  <!-- ── Left section navigation rail ── -->
  <nav
    class="flex w-52 shrink-0 flex-col gap-0.5 border-r border-border/50 p-4 pt-8"
    use:focusGroup={{ id: "settings-nav", policy: { type: "column" } }}
    aria-label={m.settings_title()}
  >
    <p class="mb-3 px-4 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
      {m.settings_title()}
    </p>
    {#each SECTIONS as section (section.id)}
      <button
        type="button"
        use:focusable={{ groupId: "settings-nav" }}
        onclick={() => (activeSection = section.id)}
        class="flex w-full items-center rounded-2xl px-4 py-4 text-base font-medium text-left
               transition-colors duration-150
               focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-1 focus:ring-offset-background
               {activeSection === section.id
                 ? 'bg-accent/20 text-accent'
                 : 'text-white/60 hover:text-white/90 focus:text-accent'}"
      >
        {section.label}
      </button>
    {/each}
  </nav>

  <!-- ── Right panel ── -->
  <div class="flex min-h-0 flex-1 flex-col overflow-hidden">
    <!-- Section header with save / reset actions -->
    <div
      class="flex shrink-0 items-center justify-between border-b border-border/50 px-8 py-5"
      use:focusGroup={{ id: "settings-save", policy: { type: "row" } }}
    >
      <h1 class="text-2xl font-bold">
        {SECTIONS.find((s) => s.id === activeSection)?.label}
      </h1>
      {#if draft}
        <div class="flex items-center gap-3">
          <button
            type="button"
            use:focusable={{ groupId: "settings-save" }}
            onclick={handleReset}
            class="rounded-xl bg-secondary px-6 py-3 text-base font-medium text-muted-foreground
                   transition-colors hover:text-foreground
                   focus:outline-none focus:ring-2 focus:ring-accent"
          >
            {m.common_reset()}
          </button>
          <button
            type="button"
            use:focusable={{ groupId: "settings-save" }}
            onclick={handleSave}
            class="rounded-xl bg-accent px-6 py-3 text-base font-semibold text-accent-foreground
                   transition-colors hover:bg-accent/90
                   focus:outline-none focus:ring-2 focus:ring-accent"
          >
            {saved ? `${m.common_saved()} ✓` : m.common_save()}
          </button>
        </div>
      {/if}
    </div>

    <!-- Scrollable section content — column focusGroup; native elements auto-discovered -->
    <div
      class="flex-1 min-h-0 overflow-y-auto scrollbar-none [&::-webkit-scrollbar]:hidden px-8 pb-16"
      use:focusGroup={{ id: "settings-content", policy: { type: "column" }, rememberFocus: false }}
    >
      {#if saveError}
        <p role="alert" class="pt-4 text-sm text-red-400">{saveError}</p>
      {/if}
      {#if draft}

        <!-- ══ Playback ══ -->
        <div class:hidden={activeSection !== "playback"} class="pt-4">

          <!-- Open videos muted -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_open_muted()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_open_muted_description()}</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.openOnMute}
              aria-label={m.settings_toggle_setting({ setting: m.settings_open_muted() })}
              onclick={() => patch("openOnMute", !draft.openOnMute)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.openOnMute)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.openOnMute)}"></span>
            </button>
          </div>

          <!-- Default volume -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_default_volume()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_default_volume_description()}</p>
            </div>
            <div class="flex shrink-0 items-center gap-2">
              <button
                type="button"
                onclick={() => patch("defaultVolume", Math.max(0, draft!.defaultVolume - 0.05))}
                aria-label={m.settings_decrease_volume()}
                class="flex h-12 w-12 items-center justify-center rounded-xl bg-secondary text-xl font-bold
                       transition-colors hover:bg-secondary/70
                       focus:outline-none focus:ring-2 focus:ring-accent"
              >−</button>
              <span class="w-14 text-center text-lg font-medium tabular-nums">
                {Math.round(draft.defaultVolume * 100)}%
              </span>
              <button
                type="button"
                onclick={() => patch("defaultVolume", Math.min(1, draft!.defaultVolume + 0.05))}
                aria-label={m.settings_increase_volume()}
                class="flex h-12 w-12 items-center justify-center rounded-xl bg-secondary text-xl font-bold
                       transition-colors hover:bg-secondary/70
                       focus:outline-none focus:ring-2 focus:ring-accent"
              >+</button>
            </div>
          </div>

          <!-- Autoplay next episode -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_autoplay()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_autoplay_description()}</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.autoPlay}
              aria-label={m.settings_toggle_setting({ setting: m.settings_autoplay() })}
              onclick={() => patch("autoPlay", !draft.autoPlay)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.autoPlay)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.autoPlay)}"></span>
            </button>
          </div>

          <!-- Remember position -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_remember_position()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_remember_position_description()}</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.rememberPosition}
              aria-label={m.settings_toggle_setting({ setting: m.settings_remember_position() })}
              onclick={() => patch("rememberPosition", !draft.rememberPosition)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.rememberPosition)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.rememberPosition)}"></span>
            </button>
          </div>

          <!-- Auto-skip segments header -->
          <div class="border-b border-border/40 pb-1 pt-6">
            <p class="text-lg font-medium">{m.settings_auto_skip()}</p>
            <p class="mt-0.5 text-sm leading-snug text-muted-foreground">
              {m.settings_auto_skip_description()}
            </p>
          </div>

          <!-- Skip intro -->
          <div class="flex min-h-[68px] items-center justify-between gap-8 border-b border-border/40 py-4 pl-4">
            <p class="text-base font-medium text-muted-foreground">{m.settings_skip_intro()}</p>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.autoSkipIntro}
              aria-label={m.settings_toggle_setting({ setting: m.settings_skip_intro() })}
              onclick={() => patch("autoSkipIntro", !draft.autoSkipIntro)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.autoSkipIntro)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.autoSkipIntro)}"></span>
            </button>
          </div>

          <!-- Skip recap -->
          <div class="flex min-h-[68px] items-center justify-between gap-8 border-b border-border/40 py-4 pl-4">
            <p class="text-base font-medium text-muted-foreground">{m.settings_skip_recap()}</p>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.autoSkipRecap}
              aria-label={m.settings_toggle_setting({ setting: m.settings_skip_recap() })}
              onclick={() => patch("autoSkipRecap", !draft.autoSkipRecap)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.autoSkipRecap)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.autoSkipRecap)}"></span>
            </button>
          </div>

          <!-- Skip credits -->
          <div class="flex min-h-[68px] items-center justify-between gap-8 border-b border-border/40 py-4 pl-4">
            <p class="text-base font-medium text-muted-foreground">{m.settings_skip_credits()}</p>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.autoSkipCredits}
              aria-label={m.settings_toggle_setting({ setting: m.settings_skip_credits() })}
              onclick={() => patch("autoSkipCredits", !draft.autoSkipCredits)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.autoSkipCredits)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.autoSkipCredits)}"></span>
            </button>
          </div>

          <!-- Skip preview -->
          <div class="flex min-h-[68px] items-center justify-between gap-8 py-4 pl-4">
            <p class="text-base font-medium text-muted-foreground">{m.settings_skip_preview()}</p>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.autoSkipPreview}
              aria-label={m.settings_toggle_setting({ setting: m.settings_skip_preview() })}
              onclick={() => patch("autoSkipPreview", !draft.autoSkipPreview)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.autoSkipPreview)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.autoSkipPreview)}"></span>
            </button>
          </div>

        </div><!-- /playback -->

        <!-- ══ Streaming ══ -->
        <div class:hidden={activeSection !== "streaming"} class="pt-4">

          <!-- Auto-select stream -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_auto_select()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_auto_select_description()}</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.autoSelectStream}
              aria-label={m.settings_toggle_setting({ setting: m.settings_auto_select() })}
              onclick={() => patch("autoSelectStream", !draft.autoSelectStream)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.autoSelectStream)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.autoSelectStream)}"></span>
            </button>
          </div>

          <!-- Prefetch streams -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_prefetch()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_prefetch_description()}</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.prefetchStreams}
              aria-label={m.settings_toggle_setting({ setting: m.settings_prefetch() })}
              onclick={() => patch("prefetchStreams", !draft.prefetchStreams)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.prefetchStreams)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.prefetchStreams)}"></span>
            </button>
          </div>

          <!-- Pre-download next episode -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_predownload()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_predownload_description()}</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.prefetchNextEpisode}
              aria-label={m.settings_toggle_setting({ setting: m.settings_predownload() })}
              onclick={() => patch("prefetchNextEpisode", !draft.prefetchNextEpisode)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.prefetchNextEpisode)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.prefetchNextEpisode)}"></span>
            </button>
          </div>

          <!-- Upload while streaming -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_upload()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_upload_description()}</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.allowUploading}
              aria-label={m.settings_toggle_setting({ setting: m.settings_upload() })}
              onclick={() => patch("allowUploading", !draft.allowUploading)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.allowUploading)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.allowUploading)}"></span>
            </button>
          </div>

          <!-- Verify streams before auto-selecting -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_probe()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_probe_description()}</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.probeStreams}
              aria-label={m.settings_toggle_setting({ setting: m.settings_probe() })}
              onclick={() => patch("probeStreams", !draft.probeStreams)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.probeStreams)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.probeStreams)}"></span>
            </button>
          </div>

          <!-- Allow LAN stream sources -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_allow_lan()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_allow_lan_description()}</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.allowLanStreamSources}
              aria-label={m.settings_toggle_setting({ setting: m.settings_allow_lan() })}
              onclick={() => patch("allowLanStreamSources", !draft.allowLanStreamSources)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.allowLanStreamSources)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.allowLanStreamSources)}"></span>
            </button>
          </div>

          <!-- Remote access -->
          <div class="border-b border-border/40 py-5">
            <div class="flex min-h-[56px] items-center justify-between gap-8">
              <div class="min-w-0 flex-1">
                <p class="text-lg font-medium">{m.settings_remote_access()}</p>
                <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_remote_access_description()}</p>
              </div>
                            <button
                type="button"
                role="switch"
                aria-checked={draft.remoteAccessEnabled}
                aria-label={m.settings_toggle_setting({ setting: m.settings_remote_access() })}
                onclick={() => patch("remoteAccessEnabled", !draft.remoteAccessEnabled)}
                class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                       focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                       {trackBg(draft.remoteAccessEnabled)}"
              >
                <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.remoteAccessEnabled)}"></span>
              </button>
            </div>

            {#if draft.remoteAccessEnabled}
              <div class="mt-3 rounded-xl bg-secondary/50 p-4">
                <p class="mb-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">{m.settings_access_token()}</p>
                {#if draft.remoteAccessToken === ""}
                  <p class="text-sm text-muted-foreground">{m.settings_no_token()}</p>
                {:else}
                  <code class="block truncate rounded-lg bg-black/30 px-3 py-2 text-sm font-mono text-foreground/80">
                    {tokenVisible && revealedToken ? revealedToken : "•".repeat(32)}
                  </code>
                  <div class="mt-3 flex gap-2">
                    <button
                      type="button"
                      onclick={handleRevealToken}
                      disabled={revealingToken}
                      class="rounded-xl bg-secondary px-5 py-2.5 text-sm font-medium text-muted-foreground
                             transition-colors hover:text-foreground disabled:opacity-50
                             focus:outline-none focus:ring-2 focus:ring-accent"
                    >
                      {revealingToken
                        ? m.common_loading()
                        : tokenVisible
                          ? m.settings_hide_token()
                          : m.settings_show_token()}
                    </button>
                    <button
                      type="button"
                      onclick={handleCopyToken}
                      disabled={revealingToken}
                      class="rounded-xl bg-secondary px-5 py-2.5 text-sm font-medium text-muted-foreground
                             transition-colors hover:text-foreground disabled:opacity-50
                             focus:outline-none focus:ring-2 focus:ring-accent"
                    >
                      {tokenCopied ? m.common_copied() : m.common_copy()}
                    </button>
                  </div>
                {/if}
              </div>
            {/if}
          </div>

          <!-- Selection strategy -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_selection_strategy()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">
                {STREAM_SELECTION_MODES.find((m) => m.value === draft.streamSelectionMode)?.description ?? ""}
              </p>
            </div>
            <select
              onchange={(e) => patch("streamSelectionMode", (e.currentTarget as HTMLSelectElement).value)}
              class="shrink-0 rounded-xl bg-secondary px-4 py-3 text-base text-foreground
                     focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {#each STREAM_SELECTION_MODES as m (m.value)}
                <option value={m.value} selected={m.value === (draft.streamSelectionMode ?? "balanced")}>{m.label}</option>
              {/each}
            </select>
          </div>

          <!-- Source preference -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_source_preference()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">
                {draft.sourcePreference
                  ? m.settings_source_boost_description()
                  : m.settings_source_neutral_description()}
              </p>
            </div>
            <select
              onchange={(e) => patch("sourcePreference", (e.currentTarget as HTMLSelectElement).value)}
              class="shrink-0 rounded-xl bg-secondary px-4 py-3 text-base text-foreground
                     focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {#each SOURCE_PREFERENCES as p (p.value)}
                <option value={p.value} selected={p.value === draft.sourcePreference}>{p.label}</option>
              {/each}
            </select>
          </div>

          <!-- Preferred provider -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_preferred_provider()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">
                {#if providerAddons.length === 0 && nuvioProviderOptions.length === 0}
                  {m.settings_provider_missing()}
                {:else}
                  {m.settings_provider_description()}
                {/if}
              </p>
            </div>
            <select
              disabled={providerAddons.length === 0 && nuvioProviderOptions.length === 0}
              onchange={(e) => patch("defaultProvider", (e.currentTarget as HTMLSelectElement).value)}
              class="shrink-0 rounded-xl bg-secondary px-4 py-3 text-base text-foreground
                     disabled:opacity-40
                     focus:outline-none focus:ring-2 focus:ring-accent"
            >
              <option value="" selected={!draft.defaultProvider}>{m.common_no_preference()}</option>
              {#each providerAddons as a (a.url || a.id)}
                <option value={a.manifest.name} selected={a.manifest.name === draft.defaultProvider}>{a.manifest.name}</option>
              {/each}
              {#each nuvioProviderOptions as name (name)}
                <option value={name} selected={name === draft.defaultProvider}>{name}</option>
              {/each}
            </select>
          </div>

          <!-- Connection speed -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_connection_speed()}</p>
              {#if draft.measuredBandwidthMbps > 0}
                <p class="mt-0.5 text-sm leading-snug text-muted-foreground">
                  {m.settings_speed_measured({ speed: draft.measuredBandwidthMbps })}
                </p>
              {:else}
                <p class="mt-0.5 text-sm leading-snug text-muted-foreground">
                  {m.settings_speed_unmeasured()}
                </p>
              {/if}
              {#if speedTestError}
                <p class="mt-1 text-sm text-red-400">{speedTestError}</p>
              {/if}
            </div>
            <button
              type="button"
              onclick={runSpeedTest}
              disabled={testingSpeed}
              class="shrink-0 rounded-xl bg-secondary px-5 py-3 text-base font-medium text-muted-foreground
                     transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50
                     focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {testingSpeed ? m.settings_speed_testing() : m.settings_speed_test()}
            </button>
          </div>

        </div><!-- /streaming -->

        <!-- ══ Subtitles & Audio ══ -->
        <div class:hidden={activeSection !== "subtitles"} class="pt-4">

          <!-- Enable subtitles by default -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_subtitles_default()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_subtitles_default_description()}</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.subtitlesEnabled}
              aria-label={m.settings_toggle_setting({ setting: m.settings_subtitles_default() })}
              onclick={() => patch("subtitlesEnabled", !draft.subtitlesEnabled)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.subtitlesEnabled)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.subtitlesEnabled)}"></span>
            </button>
          </div>

          <!-- Preferred subtitle language -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_subtitle_language()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_subtitle_language_description()}</p>
            </div>
            <select
              onchange={(e) => patch("defaultSubtitleLang", (e.currentTarget as HTMLSelectElement).value)}
              class="shrink-0 rounded-xl bg-secondary px-4 py-3 text-base text-foreground
                     focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {#each LANGUAGES as l (l.value)}
                <option value={l.value} selected={l.value === draft.defaultSubtitleLang}>{languageDisplayName(l.value)}</option>
              {/each}
            </select>
          </div>

          <!-- Preferred audio language -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_audio_language()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_audio_language_description()}</p>
            </div>
            <select
              onchange={(e) => patch("defaultAudioLang", (e.currentTarget as HTMLSelectElement).value)}
              class="shrink-0 rounded-xl bg-secondary px-4 py-3 text-base text-foreground
                     focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {#each AUDIO_LANGUAGES as l (l.value)}
                <option value={l.value} selected={l.value === draft.defaultAudioLang}>{l.value === "original" ? m.common_original() : languageDisplayName(l.value)}</option>
              {/each}
            </select>
          </div>

        </div><!-- /subtitles -->

        <!-- ══ Interface ══ -->
        <div class:hidden={activeSection !== "interface"} class="pt-4">
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.language_label()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.language_description()}</p>
            </div>
            <select
              aria-label={m.language_label()}
              onchange={(event) => patch("uiLanguage", (event.currentTarget as HTMLSelectElement).value as AppLocale)}
              class="shrink-0 rounded-xl bg-secondary px-4 py-3 text-base text-foreground
                     focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {#each LOCALES as locale (locale.appLocale)}
                <option
                  value={locale.appLocale}
                  selected={locale.appLocale === (normalizeAppLocale(draft.uiLanguage) ?? "en")}
                >{locale.nativeName}</option>
              {/each}
            </select>
          </div>

          <!-- Show stream details -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_stream_details()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_stream_details_description()}</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.showStreamDetails}
              aria-label={m.settings_stream_details()}
              onclick={() => patch("showStreamDetails", !draft.showStreamDetails)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.showStreamDetails)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.showStreamDetails)}"></span>
            </button>
          </div>

          <!-- Hide spoilers -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_hide_spoilers()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_hide_spoilers_description()}</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.hideSpoilers}
              aria-label={m.settings_hide_spoilers()}
              onclick={() => patch("hideSpoilers", !draft.hideSpoilers)}
              class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                     focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                     {trackBg(draft.hideSpoilers)}"
            >
              <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(draft.hideSpoilers)}"></span>
            </button>
          </div>

          <!-- Discovery algorithm -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">{m.settings_discovery_algorithm()}</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">
                {DISCOVERY_ALGORITHMS.find((a) => a.value === draft.discoveryAlgorithm)?.description ?? ""}
              </p>
            </div>
            <select
              onchange={(e) => patch("discoveryAlgorithm", (e.currentTarget as HTMLSelectElement).value)}
              class="shrink-0 rounded-xl bg-secondary px-4 py-3 text-base text-foreground
                     focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {#each DISCOVERY_ALGORITHMS as a (a.value)}
                <option value={a.value} selected={a.value === draft.discoveryAlgorithm}>{a.label}</option>
              {/each}
            </select>
          </div>

          <!-- Custom algorithm URL (only when custom is selected) -->
          {#if draft.discoveryAlgorithm === "custom"}
            <div class="border-b border-border/40 py-5">
              <p class="mb-1 text-lg font-medium">{m.settings_custom_algorithm_url()}</p>
              <p class="mb-3 text-sm leading-snug text-muted-foreground">
                {m.settings_custom_algorithm_description()}
              </p>
              <div class="flex gap-3">
                <input
                  type="url"
                  placeholder="https://..."
                  bind:value={draft.customAlgorithmUrl}
                  class="min-w-0 flex-1 rounded-xl bg-secondary px-4 py-3 text-base text-foreground
                         placeholder:text-muted-foreground
                         focus:outline-none focus:ring-2 focus:ring-accent"
                />
                <button
                  type="button"
                  onclick={handleTestAlgorithm}
                  disabled={testingAlgorithm || !draft.customAlgorithmUrl.trim()}
                  class="shrink-0 rounded-xl bg-secondary px-5 py-3 text-base font-medium text-muted-foreground
                         transition-colors hover:text-foreground disabled:opacity-50
                         focus:outline-none focus:ring-2 focus:ring-accent"
                >
                  {testingAlgorithm ? m.common_testing() : m.common_test_connection()}
                </button>
              </div>
              {#if algorithmTestResult}
                <p class="mt-2 text-sm {algorithmTestResult.ok ? 'text-green-400' : 'text-red-400'}">
                  {algorithmTestResult.ok
                    ? m.common_connected_success()
                    : m.common_failed_message({ error: algorithmTestResult.error })}
                </p>
              {/if}
            </div>
          {/if}

          <!-- Auto-update (Android / Android TV only) -->
          {#if isAndroid() || isAndroidTV()}
            <div class="flex min-h-[76px] items-center justify-between gap-8 py-5">
              <div class="min-w-0 flex-1">
                <p class="text-lg font-medium">{m.settings_auto_update()}</p>
                <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_auto_update_description()}</p>
              </div>
              <button
                type="button"
                role="switch"
                aria-label={m.settings_auto_update()}
                aria-checked={autoUpdateEnabled}
                onclick={() => {
                  const newVal = !autoUpdateEnabled;
                  autoUpdateEnabled = newVal;
                  window.__coveApp?.setAutoUpdateEnabled?.(newVal);
                }}
                class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                       focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                       {trackBg(autoUpdateEnabled)}"
              >
                <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(autoUpdateEnabled)}"></span>
              </button>
            </div>
          {/if}

          <!-- Desktop interface — shown only when the user opted in via desktop Settings
               or the --tv Qt flag; never visible on a real Android TV device. -->
          {#if isDesktopTvMode()}
            <div class="flex min-h-[76px] items-center justify-between gap-8 py-5">
              <div class="min-w-0 flex-1">
                <p class="text-lg font-medium">{m.settings_desktop_switch()}</p>
                <p class="mt-0.5 text-sm leading-snug text-muted-foreground">{m.settings_desktop_switch_description()}</p>
              </div>
              <button
                type="button"
                onclick={() => setTvMode(false)}
                class="shrink-0 rounded-xl bg-secondary px-5 py-3 text-base font-medium text-muted-foreground
                       transition-colors hover:text-foreground
                       focus:outline-none focus:ring-2 focus:ring-accent"
              >
                {m.settings_switch_desktop()}
              </button>
            </div>
          {/if}

        </div><!-- /interface -->

        <!-- ══ Addons ══ -->
        <div class:hidden={activeSection !== "addons"} class="space-y-6 pt-4">

          <!-- Add new addon -->
          <div class="rounded-2xl bg-secondary/30 p-5">
            <p class="mb-1 text-lg font-medium">{m.settings_add_stremio()}</p>
            <p class="mb-4 text-sm leading-snug text-muted-foreground">{m.settings_add_stremio_description()}</p>
            <div class="flex gap-3">
              <input
                type="url"
                placeholder="https://..."
                bind:value={addAddonUrl}
                onkeydown={(e) => e.key === "Enter" && handleAddAddon()}
                class="min-w-0 flex-1 rounded-xl bg-secondary px-4 py-3 text-base text-foreground
                       placeholder:text-muted-foreground
                       focus:outline-none focus:ring-2 focus:ring-accent"
              />
              <button
                type="button"
                onclick={handleAddAddon}
                disabled={addAddonLoading || !addAddonUrl.trim()}
                class="shrink-0 rounded-xl bg-accent px-5 py-3 text-base font-semibold text-accent-foreground
                       transition-colors hover:bg-accent/90 disabled:opacity-50
                       focus:outline-none focus:ring-2 focus:ring-accent"
              >
                {addAddonLoading ? m.common_adding() : m.common_add()}
              </button>
            </div>
            {#if addAddonError}
              <p class="mt-2 text-sm text-red-400">{addAddonError}</p>
            {/if}
          </div>

          <!-- Addon list -->
          <div class="space-y-3">
            {#each addons as addon (addon.url || addon.id)}
              <div class="rounded-2xl border border-border/50 bg-secondary/20 p-4">
                <!-- Addon header row: name + badges + toggle + remove -->
                <div class="flex items-center gap-4">
                  <div class="min-w-0 flex-1">
                    <div class="flex flex-wrap items-center gap-2">
                      <span class="text-base font-semibold">
                        {addon.manifest.name || addon.url || addon.id || m.common_unknown_addon()}
                      </span>
                      <span
                        class="rounded-full px-2.5 py-0.5 text-xs font-medium
                               {addon.kind === KindProvider
                                 ? 'bg-blue-500/20 text-blue-400'
                                 : addon.kind === KindTimestamps
                                   ? 'bg-amber-500/20 text-amber-400'
                                   : 'bg-purple-500/20 text-purple-400'}"
                      >
                        {addon.kind === KindProvider
                          ? m.common_provider()
                          : addon.kind === KindTimestamps
                            ? m.common_timestamps()
                            : m.player_subtitles()}
                      </span>
                      {#if addon.source === SourceOfficial}
                        <span class="rounded-full bg-green-500/20 px-2.5 py-0.5 text-xs font-medium text-green-400">
                          {m.common_builtin()}
                        </span>
                      {/if}
                    </div>
                    {#if addon.manifest.description}
                      <p class="mt-0.5 text-sm text-muted-foreground">{addon.manifest.description}</p>
                    {/if}
                  </div>

                  <!-- Enable toggle -->
                  <button
                    type="button"
                    role="switch"
                    aria-label={m.settings_toggle_addon()}
                    aria-checked={addon.enabled}
                    onclick={() => handleToggleAddon(addon)}
                    class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                           focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                           {trackBg(addon.enabled)}"
                  >
                    <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(addon.enabled)}"></span>
                  </button>

                  <!-- Configure / Refresh / Remove (Stremio addons only) -->
                  {#if addon.source !== SourceOfficial}
                    {#if addon.manifest.behaviorHints?.configurable}
                      <button
                        type="button"
                        onclick={() => (configureAddon = addon)}
                        title={m.common_configure()}
                        class="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-muted-foreground
                               transition-colors hover:text-foreground
                               focus:outline-none focus:ring-2 focus:ring-accent"
                      >
                        <Cog class="size-5" />
                      </button>
                    {/if}
                    <button
                      type="button"
                      onclick={() => handleRefreshAddon(addon)}
                      disabled={refreshingAddonId === addon.id}
                      title={m.common_refresh()}
                      class="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-muted-foreground
                             transition-colors hover:text-foreground disabled:opacity-50
                             focus:outline-none focus:ring-2 focus:ring-accent"
                    >
                      <RefreshCw
                        class="size-5 {refreshingAddonId === addon.id
                          ? 'animate-spin'
                          : ''}"
                      />
                    </button>
                    <button
                      type="button"
                      onclick={() => handleRemoveAddon(addon)}
                      title={m.settings_remove_addon()}
                      class="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-muted-foreground
                             transition-colors hover:bg-destructive/10 hover:text-destructive
                             focus:outline-none focus:ring-2 focus:ring-accent"
                    >
                      <Trash2 class="size-5" />
                    </button>
                  {/if}
                </div>

                <!-- Per-catalog toggles -->
                {#if addon.manifest.catalogs?.length}
                  <div class="mt-3 space-y-1 border-t border-border/40 pt-3">
                    {#each addon.manifest.catalogs as cat (`${cat.type}/${cat.id}`)}
                      {@const key = `${cat.type}/${cat.id}`}
                      {@const catOn = !addon.disabledCatalogs?.[key]}
                      <div class="flex min-h-[56px] items-center gap-4 pl-2">
                        <div class="min-w-0 flex-1">
                          <span class="text-sm font-medium">{cat.name}</span>
                          <span class="ml-1.5 text-xs text-muted-foreground">({cat.type})</span>
                        </div>
                        <button
                          type="button"
                          role="switch"
                          aria-label={m.settings_toggle_catalog()}
                          aria-checked={catOn}
                          disabled={!addon.enabled}
                          onclick={() => handleToggleCatalog(addon, key, !catOn)}
                          class="relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                                 disabled:cursor-not-allowed disabled:opacity-40
                                 focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                                 {catOn ? 'bg-accent' : 'bg-white/20'}"
                        >
                          <span class="pointer-events-none inline-block h-6 w-6 transform rounded-full bg-white shadow transition-transform {catOn ? 'translate-x-7' : 'translate-x-1'}"></span>
                        </button>
                      </div>
                    {/each}
                  </div>
                {/if}
              </div>
            {:else}
              <p class="py-6 text-center text-base text-muted-foreground">
                {m.settings_no_addons()}
              </p>
            {/each}
          </div>

        </div><!-- /addons -->

        <!-- ══ Plugins (Nuvio) ══ -->
        <div class:hidden={activeSection !== "plugins"} class="space-y-6 pt-4">

          <!-- Third-party code warning -->
          <div class="flex items-start gap-3 rounded-2xl border border-amber-500/30 bg-amber-500/10 p-4 text-sm text-amber-400">
            <TriangleAlert class="mt-0.5 size-5 shrink-0" />
            <p class="leading-snug">{m.settings_plugin_warning()}</p>
          </div>

          <!-- Add new repository -->
          <div class="rounded-2xl bg-secondary/30 p-5">
            <p class="mb-1 text-lg font-medium">{m.settings_add_repository()}</p>
            <p class="mb-4 text-sm leading-snug text-muted-foreground">
              {m.settings_repository_url_description()}
            </p>
            <div class="flex gap-3">
              <input
                type="url"
                placeholder="https://github.com/owner/repo"
                bind:value={addRepoUrl}
                onkeydown={(e) => e.key === "Enter" && handleAddRepo()}
                class="min-w-0 flex-1 rounded-xl bg-secondary px-4 py-3 text-base text-foreground
                       placeholder:text-muted-foreground
                       focus:outline-none focus:ring-2 focus:ring-accent"
              />
              <button
                type="button"
                onclick={handleAddRepo}
                disabled={addRepoLoading || !addRepoUrl.trim()}
                class="shrink-0 rounded-xl bg-accent px-5 py-3 text-base font-semibold text-accent-foreground
                       transition-colors hover:bg-accent/90 disabled:opacity-50
                       focus:outline-none focus:ring-2 focus:ring-accent"
              >
                {addRepoLoading ? m.common_adding() : m.common_add()}
              </button>
            </div>
            {#if addRepoError}
              <p class="mt-2 text-sm text-red-400">{addRepoError}</p>
            {/if}
          </div>

          <!-- Repo list -->
          <div class="space-y-4">
            {#each nuvioRepos as repo (repo.id)}
              <div class="rounded-2xl border border-border/50 bg-secondary/20 p-4">
                <!-- Repo header row -->
                <div class="flex items-center gap-3">
                  <div class="min-w-0 flex-1">
                    <div class="flex flex-wrap items-center gap-2">
                      <span class="text-base font-semibold">{repo.owner}/{repo.repo}</span>
                      <span class="rounded-full bg-purple-500/20 px-2.5 py-0.5 text-xs font-medium text-purple-400">
                        {repo.scrapers.length === 1
                          ? m.settings_scraper_count_one()
                          : m.settings_scrapers_count({
                              count: repo.scrapers.length,
                            })}
                      </span>
                    </div>
                    {#if repo.fetchErr}
                      <p class="mt-0.5 text-xs text-red-400">
                        {m.settings_refresh_failed({ error: repo.fetchErr })}
                      </p>
                    {/if}
                  </div>

                  <!-- Refresh button -->
                  <button
                    type="button"
                    onclick={() => handleRefreshRepo(repo)}
                    disabled={refreshingRepoId === repo.id}
                    title={m.settings_refresh_manifest()}
                    class="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-muted-foreground
                           transition-colors hover:text-foreground disabled:opacity-50
                           focus:outline-none focus:ring-2 focus:ring-accent"
                  >
                    <RefreshCw class="size-5 {refreshingRepoId === repo.id ? 'animate-spin' : ''}" />
                  </button>

                  <!-- Repo enable toggle -->
                  <button
                    type="button"
                    role="switch"
                    aria-label={m.settings_enable_repository()}
                    aria-checked={repo.enabled}
                    onclick={() => handleToggleRepo(repo)}
                    class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                           focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                           {trackBg(repo.enabled)}"
                  >
                    <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(repo.enabled)}"></span>
                  </button>

                  <!-- Remove repo button -->
                  <button
                    type="button"
                    onclick={() => handleRemoveRepo(repo)}
                    title={m.settings_remove_repository_confirm()}
                    class="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-muted-foreground
                           transition-colors hover:bg-destructive/10 hover:text-destructive
                           focus:outline-none focus:ring-2 focus:ring-accent"
                  >
                    <Trash2 class="size-5" />
                  </button>
                </div>

                <!-- Per-scraper toggles -->
                <div class="mt-3 space-y-1 border-t border-border/40 pt-3">
                  {#each repo.scrapers as scraper (scraper.id)}
                    <div class="flex min-h-[60px] items-center gap-4 py-1 pl-2">
                      <div class="min-w-0 flex-1">
                        <span class="text-sm font-medium">{scraper.name}</span>
                        {#if scraper.description}
                          <span class="ml-2 text-xs text-muted-foreground">{scraper.description}</span>
                        {/if}
                        {#if scraper.codeErr}
                          <p class="mt-0.5 text-xs text-red-400">{scraper.codeErr}</p>
                        {/if}
                      </div>

                      {#if pendingConfirm?.repoId === repo.id && pendingConfirm?.scraperId === scraper.id}
                        <!-- Third-party JS confirmation -->
                        <div class="flex shrink-0 items-center gap-2">
                          <span class="text-xs text-amber-400">
                            {m.settings_run_third_party({
                              repository: `${repo.owner}/${repo.repo}`,
                            })}
                          </span>
                          <button
                            type="button"
                            onclick={() => (pendingConfirm = null)}
                            class="rounded-xl bg-secondary px-4 py-2 text-sm font-medium text-muted-foreground
                                   transition-colors hover:text-foreground
                                   focus:outline-none focus:ring-2 focus:ring-accent"
                          >
                            {m.common_cancel()}
                          </button>
                          <button
                            type="button"
                            onclick={() => handleSetScraperEnabled(repo, scraper, true)}
                            class="rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground
                                   transition-colors hover:bg-accent/90
                                   focus:outline-none focus:ring-2 focus:ring-accent"
                          >
                            {m.common_enable()}
                          </button>
                        </div>
                      {:else}
                        <!-- Normal scraper toggle -->
                        {@const scraperOn = scraper.enabled}
                        <button
                          type="button"
                          role="switch"
                          aria-label={m.settings_toggle_scraper()}
                          aria-checked={scraperOn}
                          onclick={() => requestEnableScraper(repo, scraper)}
                          class="relative inline-flex h-8 w-14 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                                 focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                                 {scraperOn ? 'bg-accent' : 'bg-white/20'}"
                        >
                          <span class="pointer-events-none inline-block h-6 w-6 transform rounded-full bg-white shadow transition-transform {scraperOn ? 'translate-x-7' : 'translate-x-1'}"></span>
                        </button>
                      {/if}
                    </div>
                  {:else}
                    <p class="py-3 text-center text-sm text-muted-foreground">
                      {m.settings_no_scrapers()}
                    </p>
                  {/each}
                </div>
              </div>
            {:else}
              <p class="py-6 text-center text-base text-muted-foreground">
                {m.settings_no_repositories()}
              </p>
            {/each}
          </div>

        </div><!-- /plugins -->

      {:else}
        <p class="py-16 text-center text-lg text-muted-foreground">{m.settings_loading()}</p>
      {/if}
    </div><!-- /settings-content -->
  </div><!-- /right panel -->
</div>

{#if configureAddon}
  <!-- Configure addon overlay (TV) -->
  <div
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm"
    role="presentation"
    onclick={(e) => {
      if (e.target === e.currentTarget) configureAddon = null;
    }}
    onkeydown={(e) => {
      if (e.key === "Escape") configureAddon = null;
    }}
  >
    <div
      class="relative flex w-[90vw] max-w-5xl flex-col rounded-2xl border border-border bg-background shadow-2xl"
      style="height: 85vh;"
    >
      <!-- Header -->
      <div
        class="flex shrink-0 items-center justify-between border-b border-border px-5 py-4"
      >
        <span class="truncate text-base font-semibold">
          {configureAddon.manifest.name || configureAddon.url}
        </span>
        <button
          type="button"
          class="ml-3 shrink-0 rounded-xl p-2 text-muted-foreground hover:text-foreground focus:outline-none focus:ring-2 focus:ring-accent"
          onclick={() => (configureAddon = null)}
          aria-label={m.common_close()}
        >
          <X class="size-5" />
        </button>
      </div>

      <!-- Hint -->
      <p class="shrink-0 px-5 py-2 text-sm text-muted-foreground">
        {m.settings_addon_config_hint()}
      </p>

      <!-- iframe -->
      <div class="min-h-0 flex-1 px-5 pb-3">
        <iframe
          src={`${configureAddon.url}/configure`}
          class="h-full w-full rounded-xl border border-border"
          title={m.settings_addon_configuration()}
        ></iframe>
      </div>

      <!-- Fallback link -->
      <div class="shrink-0 px-5 pb-4 text-sm text-muted-foreground">
        <a
          href={`${configureAddon.url}/configure`}
          target="_blank"
          rel="noopener noreferrer"
          class="text-primary underline">{m.common_open_browser()}</a
        >
        {m.settings_addon_fallback()}
      </div>
    </div>
  </div>
{/if}
