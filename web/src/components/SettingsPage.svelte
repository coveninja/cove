<script lang="ts">
  import { onMount, onDestroy } from "svelte";
  import { settings } from "$lib/stores/settings";
  import type { Settings } from "$lib/types/settings";
  import { Button } from "$lib/components/ui/button";
  import { Label } from "$lib/components/ui/label";
  import { Switch } from "$lib/components/ui/switch/index.js";
  import { Slider } from "$lib/components/ui/slider/index.js";
  import { Separator } from "$lib/components/ui/separator/index.js";
  import * as Select from "$lib/components/ui/select/index.js";
  import * as Tabs from "$lib/components/ui/tabs/index.js";
  import { isAndroid, isAndroidTV, tvSwitchVisible, setTvSwitchVisible } from "$lib/platform";
  import {
    STREAM_SELECTION_MODES,
    SOURCE_PREFERENCES,
  } from "$lib/streamSelection";
  import { DISCOVERY_ALGORITHMS } from "$lib/discoveryAlgorithms";
  import { api, type TraktStatus, type TraktDeviceCode } from "$lib/api";
  import { Textarea } from "$lib/components/ui/textarea/index.js";
  import { Player } from "$lib/player/player.svelte";
  import type { AddonEntry } from "$lib/types/addons";
  import {
    KindProvider,
    KindTimestamps,
    SourceOfficial,
  } from "$lib/types/addons";
  import { Badge } from "$lib/components/ui/badge/index.js";
  import { Input } from "$lib/components/ui/input/index.js";
  import { ScrollArea } from "$lib/components/ui/scroll-area/index.js";
  import {
    Trash2,
    Plus,
    RefreshCw,
    TriangleAlert,
    Eye,
    EyeOff,
    Copy,
    Check as CheckIcon,
    Cog,
    X,
  } from "lucide-svelte";
  import type {
    Repo as NuvioRepo,
    Scraper as NuvioScraper,
  } from "$lib/types/nuvio";
  import * as m from "$lib/paraglide/messages.js";
  import {
    LOCALES,
    languageDisplayName,
    normalizeAppLocale,
    type AppLocale,
  } from "$lib/i18n";

  let draft = $state<Settings | null>(null);
  let saved = $state(false);
  let saveError = $state<string | null>(null);
  let saveTimer: ReturnType<typeof setTimeout>;

  // Auto-update toggle — native pref, lives outside the Go settings store so
  // it is readable before the backend is up. Only rendered on Android / Android TV.
  let autoUpdateEnabled = $state(true);

  onMount(async () => {
    await settings.load();
    const unsub = settings.subscribe((v) => {
      if (!draft) draft = { ...v };
    });
    unsub();
    loadAddons();
    loadNuvioRepos();
    loadTraktStatus();
    loadMpvConf();
    // Read the native auto-update preference. The method is optional — absent
    // on desktop where __coveApp is undefined.
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
    // Pull server-generated fields back into the draft — enabling remote
    // access makes the backend mint a token that only exists in the PUT
    // response (masked as "***"); without this the draft keeps its stale ""
    // and the token row never appears until a reload.
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

  // ── Addon management ─────────────────────────────────────────────────────────
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

  const providerAddons = $derived(
    addons.filter((a) => a.kind === KindProvider),
  );

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
    addons = addons.filter((a) => !(a.id === addon.id && a.url === addon.url));
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

  // ── Nuvio plugin repos ───────────────────────────────────────────────────────
  let nuvioRepos = $state<NuvioRepo[]>([]);
  let addRepoUrl = $state("");
  let addRepoError = $state<string | null>(null);
  let addRepoLoading = $state(false);
  let refreshingRepoId = $state<string | null>(null);
  // Which (repoId, scraperId) pair is showing its "this runs third-party JS"
  // confirmation instead of the plain switch — cleared on confirm or cancel.
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

  // Nuvio scraper streams carry AddonName = "Nuvio: <scraper name>" (see
  // internal/nuvio/manager.go) — an entirely separate namespace from Stremio
  // addon manifest names, so they need their own dropdown entries in that
  // exact string form for the preferred-provider match (streamSelection.ts,
  // StreamsList.svelte) to ever hit. Only enabled repos/scrapers are listed,
  // same gating as what actually produces streams. Deduped in case the same
  // scraper name appears in more than one enabled repo.
  const nuvioProviderOptions = $derived(
    Array.from(
      new Set(
        nuvioRepos
          .filter((r) => r.enabled)
          .flatMap((r) =>
            r.scrapers.filter((s) => s.enabled).map((s) => `Nuvio: ${s.name}`),
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

  // Audio-only: "original" plays whatever track matches the title's TMDB
  // original_language, instead of a fixed language — see Player.svelte's
  // audio auto-select effect. Subtitles have no equivalent concept (TMDB
  // doesn't publish an "original subtitle language").
  const AUDIO_LANGUAGES = [
    { value: "original" },
    ...LANGUAGES,
  ];

  function langLabel(value: string) {
    if (value === "original") return m.common_original();
    return languageDisplayName(value);
  }

  let testingSpeed = $state(false);
  let speedTestError = $state<string | null>(null);

  // ── Remote access token reveal ────────────────────────────────────────────────
  // The backend returns "***" for the token when set; we only fetch the real
  // value when the user explicitly clicks Show or Copy.
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

  // Clear the revealed token whenever settings are reloaded (e.g. the "***"
  // sentinel from a fresh getSettings() should not clobber a local reveal).
  $effect(() => {
    if (draft?.remoteAccessToken === "") {
      // Token was cleared server-side (regenerated or disabled); forget local reveal.
      revealedToken = null;
      tokenVisible = false;
    }
  });

  // ── Trakt.tv ─────────────────────────────────────────────────────────────────
  // undefined = still loading, null = not configured (503), object = loaded.
  let traktStatus = $state<TraktStatus | null | undefined>(undefined);
  let traktFlow = $state<TraktDeviceCode | null>(null);
  // 'idle': show connect button; 'polling': device flow in progress;
  // 'expired'/'denied': flow ended without auth.
  let traktFlowState = $state<"idle" | "polling" | "expired" | "denied">(
    "idle",
  );
  let traktConnectError = $state<string | null>(null);
  let traktSyncLoading = $state(false);
  let traktUnlinkLoading = $state(false);
  let traktPollInterval: ReturnType<typeof setInterval> | null = null;
  let traktFlowTimeout: ReturnType<typeof setTimeout> | null = null;
  let traktPollIntervalMs = 0;

  let traktCodeCopied = $state(false);
  let traktCodeCopyTimer: ReturnType<typeof setTimeout> | undefined;

  async function handleCopyTraktCode() {
    const code = traktFlow?.user_code;
    if (!code) return;
    await navigator.clipboard.writeText(code);
    traktCodeCopied = true;
    clearTimeout(traktCodeCopyTimer);
    traktCodeCopyTimer = setTimeout(() => (traktCodeCopied = false), 2000);
  }

  async function loadTraktStatus() {
    try {
      traktStatus = await api.traktStatus(); // null on 503 (not configured)
    } catch {
      traktStatus = null;
    }
  }

  function clearTraktPoll() {
    if (traktPollInterval) {
      clearInterval(traktPollInterval);
      traktPollInterval = null;
    }
    if (traktFlowTimeout) {
      clearTimeout(traktFlowTimeout);
      traktFlowTimeout = null;
    }
  }

  async function pollTraktOnce() {
    if (!traktFlow) return;
    let result: Awaited<ReturnType<typeof api.traktPoll>>;
    try {
      result = await api.traktPoll(traktFlow.device_code);
    } catch {
      return; // transient error — keep polling
    }
    switch (result.status) {
      case "authorized":
        clearTraktPoll();
        traktFlow = null;
        traktFlowState = "idle";
        await loadTraktStatus();
        break;
      case "slow_down":
        // Widen the interval as Trakt requests, then restart the timer.
        traktPollIntervalMs = Math.min(traktPollIntervalMs + 5000, 30_000);
        startTraktPoll();
        break;
      case "expired":
        clearTraktPoll();
        traktFlow = null;
        traktFlowState = "expired";
        break;
      case "denied":
      case "invalid":
        clearTraktPoll();
        traktFlow = null;
        traktFlowState = "denied";
        break;
      // 'pending': do nothing, keep polling on the existing interval
    }
  }

  function startTraktPoll() {
    if (traktPollInterval) clearInterval(traktPollInterval);
    traktPollInterval = setInterval(pollTraktOnce, traktPollIntervalMs);
  }

  async function handleTraktConnect() {
    clearTraktPoll();
    traktFlow = null;
    traktFlowState = "idle";
    traktConnectError = null;
    try {
      const flow = await api.traktStartDeviceFlow();
      traktFlow = flow;
      traktFlowState = "polling";
      traktPollIntervalMs = (flow.interval + 1) * 1000;
      // Expire the UI after the flow's lifetime so the user knows to retry.
      traktFlowTimeout = setTimeout(() => {
        clearTraktPoll();
        traktFlowState = "expired";
      }, flow.expires_in * 1000);
      startTraktPoll();
    } catch (e) {
      traktConnectError =
        e instanceof Error ? e.message : "Failed to start authorization";
    }
  }

  async function handleTraktDisconnect() {
    traktUnlinkLoading = true;
    try {
      await api.traktUnlink();
      await loadTraktStatus();
    } finally {
      traktUnlinkLoading = false;
    }
  }

  async function handleTraktSync() {
    traktSyncLoading = true;
    try {
      await api.traktSyncNow();
    } finally {
      traktSyncLoading = false;
    }
  }

  onDestroy(() => clearTraktPoll());

  async function runSpeedTest() {
    if (!draft) return;
    testingSpeed = true;
    speedTestError = null;
    try {
      const start = performance.now();
      const res = await fetch(api.speedtestUrl(), {
        cache: "no-store",
      });
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

  // ── Advanced / mpv.conf ──────────────────────────────────────────────────────
  // Draft and saved state for the device-global mpv.conf file.
  let mpvConfDraft = $state("");
  let mpvConfSaved = $state("");
  let mpvConfSaving = $state(false);
  let mpvConfSaveOk = $state(false);
  let mpvConfError = $state<string | null>(null);
  let mpvConfSaveTimer: ReturnType<typeof setTimeout>;

  // dirty = draft differs from the last persisted value
  let mpvConfDirty = $derived(mpvConfDraft !== mpvConfSaved);

  async function loadMpvConf() {
    try {
      const val = await api.getMpvConf();
      mpvConfDraft = val;
      mpvConfSaved = val;
    } catch (e) {
      mpvConfError = e instanceof Error ? e.message : "Failed to load mpv.conf";
    }
  }

  async function handleMpvConfSave() {
    if (!mpvConfDirty || mpvConfSaving) return;
    mpvConfSaving = true;
    mpvConfError = null;
    try {
      await api.setMpvConf(mpvConfDraft);
      mpvConfSaved = mpvConfDraft;
      mpvConfSaveOk = true;
      clearTimeout(mpvConfSaveTimer);
      mpvConfSaveTimer = setTimeout(() => (mpvConfSaveOk = false), 2000);
      // Best-effort live apply — only works inside the Qt shell and only on
      // shell builds that expose the reloadMpvConf slot.
      Player.reloadMpvConf();
    } catch (e) {
      mpvConfError = e instanceof Error ? e.message : "Failed to save mpv.conf";
    } finally {
      mpvConfSaving = false;
    }
  }
</script>

<ScrollArea class="h-full w-full">
  <div class="mx-auto max-w-3xl space-y-6 p-6 pt-18 pb-16">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-semibold tracking-tight">{m.settings_title()}</h1>
      <div class="flex gap-2">
        <Button variant="outline" onclick={handleReset}>{m.common_reset()}</Button>
        <Button onclick={handleSave}>{saved ? `${m.common_saved()} ✓` : m.common_save()}</Button>
      </div>
    </div>
    {#if saveError}
      <p role="alert" class="text-sm text-red-500">{saveError}</p>
    {/if}

    {#if draft}
      <Tabs.Root value="playback">
        <!-- Scroll wrapper: the triggers can't shrink (nowrap), so on narrow
             windows the list overflows sideways instead of spilling past its
             pill background — w-max keeps the background behind every tab. -->
        <ScrollArea orientation="horizontal" class="w-full">
          <Tabs.List class="mb-2.5 w-max min-w-full">
            <Tabs.Trigger value="playback">{m.settings_playback()}</Tabs.Trigger>
            <Tabs.Trigger value="streaming">{m.settings_streaming()}</Tabs.Trigger>
            <Tabs.Trigger value="subtitles">{m.settings_subtitles_audio()}</Tabs.Trigger>
            <Tabs.Trigger value="interface">{m.settings_interface()}</Tabs.Trigger>
            <Tabs.Trigger value="addons">{m.settings_addons()}</Tabs.Trigger>
            <Tabs.Trigger value="plugins">{m.settings_plugins()}</Tabs.Trigger>
            <Tabs.Trigger value="trakt">{m.settings_trakt()}</Tabs.Trigger>
            <Tabs.Trigger value="advanced">{m.settings_advanced()}</Tabs.Trigger>
          </Tabs.List>
        </ScrollArea>

        <!-- ── Playback ── -->
        <Tabs.Content value="playback" class="mt-4 space-y-1">
          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="open-muted" class="text-sm font-medium"
                >{m.settings_open_muted()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_open_muted_description()}
              </p>
            </div>
            <Switch
              id="open-muted"
              checked={draft.openOnMute}
              onCheckedChange={(v) => patch("openOnMute", v)}
            />
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label class="text-sm font-medium">{m.settings_default_volume()}</Label>
              <p class="text-xs text-muted-foreground">{m.settings_default_volume_description()}</p>
            </div>
            <div class="flex items-center gap-3">
              <Slider
                type="multiple"
                value={[draft.defaultVolume * 100]}
                min={0}
                max={100}
                step={1}
                class="w-32"
                onValueChange={([v]) => patch("defaultVolume", v / 100)}
              />
              <span
                class="w-9 text-right text-sm text-muted-foreground tabular-nums"
              >
                {Math.round(draft.defaultVolume * 100)}%
              </span>
            </div>
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="autoplay" class="text-sm font-medium"
                >{m.settings_autoplay()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_autoplay_description()}
              </p>
            </div>
            <Switch
              id="autoplay"
              checked={draft.autoPlay}
              onCheckedChange={(v) => patch("autoPlay", v)}
            />
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="remember-pos" class="text-sm font-medium"
                >{m.settings_remember_position()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_remember_position_description()}
              </p>
            </div>
            <Switch
              id="remember-pos"
              checked={draft.rememberPosition}
              onCheckedChange={(v) => patch("rememberPosition", v)}
            />
          </div>
          <Separator />

          <div class="py-3">
            <Label class="text-sm font-medium">{m.settings_auto_skip()}</Label>
            <p class="mb-3 text-xs text-muted-foreground">
              {m.settings_auto_skip_description()}
            </p>
            <div class="space-y-2">
              <div class="flex items-center justify-between">
                <Label for="skip-intro" class="text-sm text-muted-foreground"
                  >{m.settings_skip_intro()}</Label
                >
                <Switch
                  id="skip-intro"
                  checked={draft.autoSkipIntro}
                  onCheckedChange={(v) => patch("autoSkipIntro", v)}
                />
              </div>
              <div class="flex items-center justify-between">
                <Label for="skip-recap" class="text-sm text-muted-foreground"
                  >{m.settings_skip_recap()}</Label
                >
                <Switch
                  id="skip-recap"
                  checked={draft.autoSkipRecap}
                  onCheckedChange={(v) => patch("autoSkipRecap", v)}
                />
              </div>
              <div class="flex items-center justify-between">
                <Label for="skip-credits" class="text-sm text-muted-foreground"
                  >{m.settings_skip_credits()}</Label
                >
                <Switch
                  id="skip-credits"
                  checked={draft.autoSkipCredits}
                  onCheckedChange={(v) => patch("autoSkipCredits", v)}
                />
              </div>
              <div class="flex items-center justify-between">
                <Label for="skip-preview" class="text-sm text-muted-foreground"
                  >{m.settings_skip_preview()}</Label
                >
                <Switch
                  id="skip-preview"
                  checked={draft.autoSkipPreview}
                  onCheckedChange={(v) => patch("autoSkipPreview", v)}
                />
              </div>
            </div>
          </div>
        </Tabs.Content>

        <!-- ── Streaming ── -->
        <Tabs.Content value="streaming" class="mt-4 space-y-1">
          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="auto-select-stream" class="text-sm font-medium"
                >{m.settings_auto_select()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_auto_select_description()}
              </p>
            </div>
            <Switch
              id="auto-select-stream"
              checked={draft.autoSelectStream}
              onCheckedChange={(v) => patch("autoSelectStream", v)}
            />
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="prefetch-streams" class="text-sm font-medium"
                >{m.settings_prefetch()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_prefetch_description()}
              </p>
            </div>
            <Switch
              id="prefetch-streams"
              checked={draft.prefetchStreams}
              onCheckedChange={(v) => patch("prefetchStreams", v)}
            />
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="prefetch-next-episode" class="text-sm font-medium"
                >{m.settings_predownload()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_predownload_description()}
              </p>
            </div>
            <Switch
              id="prefetch-next-episode"
              checked={draft.prefetchNextEpisode}
              onCheckedChange={(v) => patch("prefetchNextEpisode", v)}
            />
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="allow-uploading" class="text-sm font-medium"
                >{m.settings_upload()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_upload_description()}
              </p>
            </div>
            <Switch
              id="allow-uploading"
              checked={draft.allowUploading}
              onCheckedChange={(v) => patch("allowUploading", v)}
            />
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="probe-streams" class="text-sm font-medium"
                >{m.settings_probe()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_probe_description()}
              </p>
            </div>
            <Switch
              id="probe-streams"
              checked={draft.probeStreams}
              onCheckedChange={(v) => patch("probeStreams", v)}
            />
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="allow-lan-sources" class="text-sm font-medium"
                >{m.settings_allow_lan()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_allow_lan_description()}
              </p>
            </div>
            <Switch
              id="allow-lan-sources"
              checked={draft.allowLanStreamSources}
              onCheckedChange={(v) => patch("allowLanStreamSources", v)}
            />
          </div>
          <Separator />

          <!-- Remote access -->
          <div class="py-3 space-y-3">
            <div class="flex items-center justify-between">
              <div>
                <Label for="remote-access" class="text-sm font-medium"
                  >{m.settings_remote_access()}</Label
                >
                <p class="text-xs text-muted-foreground">
                  {m.settings_remote_access_description()}
                </p>
              </div>
              <Switch
                id="remote-access"
                checked={draft.remoteAccessEnabled}
                onCheckedChange={(v) => patch("remoteAccessEnabled", v)}
              />
            </div>

            {#if draft.remoteAccessEnabled}
              <div class="rounded-lg border border-border p-3 space-y-2">
                <Label class="text-xs font-medium text-muted-foreground"
                  >{m.settings_access_token()}</Label
                >
                {#if draft.remoteAccessToken === ""}
                  <p class="text-xs text-muted-foreground">
                    {m.settings_no_token()}
                  </p>
                {:else}
                  <div class="flex items-center gap-2">
                    <code
                      class="flex-1 truncate rounded bg-muted px-2 py-1 text-xs font-mono"
                    >
                      {tokenVisible && revealedToken
                        ? revealedToken
                        : "•".repeat(32)}
                    </code>
                    <Button
                      variant="outline"
                      size="icon"
                      class="shrink-0"
                      onclick={handleRevealToken}
                      disabled={revealingToken}
                      title={tokenVisible
                        ? m.settings_hide_token()
                        : m.settings_show_token()}
                    >
                      {#if tokenVisible}
                        <EyeOff class="size-4" />
                      {:else}
                        <Eye class="size-4" />
                      {/if}
                    </Button>
                    <Button
                      variant="outline"
                      size="icon"
                      class="shrink-0"
                      onclick={handleCopyToken}
                      disabled={revealingToken}
                      title={m.settings_copy_token()}
                    >
                      {#if tokenCopied}
                        <CheckIcon class="size-4 text-green-500" />
                      {:else}
                        <Copy class="size-4" />
                      {/if}
                    </Button>
                  </div>
                {/if}
              </div>
            {/if}
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div class="pr-4">
              <Label class="text-sm font-medium">{m.settings_selection_strategy()}</Label>
              <p class="text-xs text-muted-foreground">
                {STREAM_SELECTION_MODES.find(
                  (m) => m.value === draft.streamSelectionMode,
                )?.description ?? ""}
              </p>
            </div>
            <Select.Root type="single" bind:value={draft.streamSelectionMode}>
              <Select.Trigger class="w-56 shrink-0">
                {STREAM_SELECTION_MODES.find(
                  (m) => m.value === draft.streamSelectionMode,
                )?.label ?? m.common_choose()}
              </Select.Trigger>
              <Select.Content>
                {#each STREAM_SELECTION_MODES as m (m.value)}
                  <Select.Item value={m.value}>{m.label}</Select.Item>
                {/each}
              </Select.Content>
            </Select.Root>
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div class="pr-4">
              <Label class="text-sm font-medium">{m.settings_source_preference()}</Label>
              <p class="text-xs text-muted-foreground">
                {draft.sourcePreference
                  ? m.settings_source_boost_description()
                  : m.settings_source_neutral_description()}
              </p>
            </div>
            <Select.Root type="single" bind:value={draft.sourcePreference}>
              <Select.Trigger class="w-56 shrink-0">
                {SOURCE_PREFERENCES.find(
                  (p) => p.value === draft.sourcePreference,
                )?.label ?? m.common_no_preference()}
              </Select.Trigger>
              <Select.Content>
                {#each SOURCE_PREFERENCES as p (p.value)}
                  <Select.Item value={p.value}>{p.label}</Select.Item>
                {/each}
              </Select.Content>
            </Select.Root>
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div class="pr-4">
              <Label class="text-sm font-medium">{m.settings_preferred_provider()}</Label>
              <p class="text-xs text-muted-foreground">
                {#if providerAddons.length === 0 && nuvioProviderOptions.length === 0}
                  {m.settings_provider_missing()}
                {:else}
                  {m.settings_provider_description()}
                {/if}
              </p>
            </div>
            <Select.Root
              type="single"
              bind:value={draft.defaultProvider}
              disabled={providerAddons.length === 0 &&
                nuvioProviderOptions.length === 0}
            >
              <Select.Trigger class="w-56 shrink-0">
                {draft.defaultProvider || m.common_no_preference()}
              </Select.Trigger>
              <Select.Content>
                <Select.Item value="">{m.common_no_preference()}</Select.Item>
                {#each providerAddons as a (a.url || a.id)}
                  <Select.Item value={a.manifest.name}
                    >{a.manifest.name}</Select.Item
                  >
                {/each}
                {#each nuvioProviderOptions as name (name)}
                  <Select.Item value={name}>{name}</Select.Item>
                {/each}
              </Select.Content>
            </Select.Root>
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div class="pr-4">
              <Label class="text-sm font-medium">{m.settings_connection_speed()}</Label>
              {#if draft.measuredBandwidthMbps > 0}
                <p class="text-xs text-muted-foreground">
                  {m.settings_speed_measured({
                    speed: draft.measuredBandwidthMbps,
                  })}
                </p>
              {:else}
                <p class="text-xs text-muted-foreground">
                  {m.settings_speed_unmeasured()}
                </p>
              {/if}
              {#if speedTestError}
                <p class="text-xs text-red-500">{speedTestError}</p>
              {/if}
            </div>
            <Button
              variant="outline"
              size="sm"
              class="shrink-0"
              onclick={runSpeedTest}
              disabled={testingSpeed}
            >
              {testingSpeed
                ? m.settings_speed_testing()
                : m.settings_speed_test()}
            </Button>
          </div>
        </Tabs.Content>

        <!-- ── Subtitles & Audio ── -->
        <Tabs.Content value="subtitles" class="mt-4 space-y-1">
          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="subs-enabled" class="text-sm font-medium"
                >{m.settings_subtitles_default()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_subtitles_default_description()}
              </p>
            </div>
            <Switch
              id="subs-enabled"
              checked={draft.subtitlesEnabled}
              onCheckedChange={(v) => patch("subtitlesEnabled", v)}
            />
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label class="text-sm font-medium"
                >{m.settings_subtitle_language()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_subtitle_language_description()}
              </p>
            </div>
            <Select.Root type="single" bind:value={draft.defaultSubtitleLang}>
              <Select.Trigger class="w-36"
                >{langLabel(draft.defaultSubtitleLang)}</Select.Trigger
              >
              <Select.Content>
                {#each LANGUAGES as l}
                  <Select.Item value={l.value}>{languageDisplayName(l.value)}</Select.Item>
                {/each}
              </Select.Content>
            </Select.Root>
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label class="text-sm font-medium">{m.settings_subtitle_size()}</Label>
              <p class="text-xs text-muted-foreground">
                {m.settings_subtitle_size_description()}
              </p>
            </div>
            <div class="flex items-center gap-3">
              <Slider
                type="multiple"
                value={[draft.subtitleSize]}
                min={50}
                max={200}
                step={10}
                class="w-32"
                onValueChange={([v]) => patch("subtitleSize", v)}
              />
              <span
                class="w-9 text-right text-sm text-muted-foreground tabular-nums"
              >
                {Math.round(draft.subtitleSize)}%
              </span>
            </div>
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label class="text-sm font-medium">{m.settings_subtitle_position()}</Label>
              <p class="text-xs text-muted-foreground">
                {m.settings_subtitle_position_description()}
              </p>
            </div>
            <div class="flex items-center gap-3">
              <Slider
                type="multiple"
                value={[draft.subtitlePosition]}
                min={2}
                max={90}
                step={1}
                class="w-32"
                onValueChange={([v]) => patch("subtitlePosition", v)}
              />
              <span
                class="w-9 text-right text-sm text-muted-foreground tabular-nums"
              >
                {Math.round(draft.subtitlePosition)}%
              </span>
            </div>
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="subs-background" class="text-sm font-medium"
                >{m.settings_subtitle_background()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_subtitle_background_description()}
              </p>
            </div>
            <Switch
              id="subs-background"
              checked={draft.subtitleBackground}
              onCheckedChange={(v) => patch("subtitleBackground", v)}
            />
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label class="text-sm font-medium">{m.settings_audio_language()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_audio_language_description()}
              </p>
            </div>
            <Select.Root type="single" bind:value={draft.defaultAudioLang}>
              <Select.Trigger class="w-36"
                >{langLabel(draft.defaultAudioLang)}</Select.Trigger
              >
              <Select.Content>
                {#each AUDIO_LANGUAGES as l}
                  <Select.Item value={l.value}>{langLabel(l.value)}</Select.Item>
                {/each}
              </Select.Content>
            </Select.Root>
          </div>
        </Tabs.Content>

        <!-- ── Interface ── -->
        <Tabs.Content value="interface" class="mt-4 space-y-1">
          <div class="flex items-center justify-between py-3">
            <div class="pr-4">
              <Label class="text-sm font-medium">{m.language_label()}</Label>
              <p class="text-xs text-muted-foreground">{m.language_description()}</p>
            </div>
            <Select.Root
              type="single"
              value={normalizeAppLocale(draft.uiLanguage) ?? "en"}
              onValueChange={(value) => patch("uiLanguage", value as AppLocale)}
            >
              <Select.Trigger class="w-44 shrink-0">
                {LOCALES.find((locale) => locale.appLocale === (normalizeAppLocale(draft.uiLanguage) ?? "en"))?.nativeName}
              </Select.Trigger>
              <Select.Content>
                {#each LOCALES as locale (locale.appLocale)}
                  <Select.Item value={locale.appLocale}>{locale.nativeName}</Select.Item>
                {/each}
              </Select.Content>
            </Select.Root>
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="stream-details" class="text-sm font-medium"
                >{m.settings_stream_details()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_stream_details_description()}
              </p>
            </div>
            <Switch
              id="stream-details"
              checked={draft.showStreamDetails}
              onCheckedChange={(v) => patch("showStreamDetails", v)}
            />
          </div>
          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="thumbnail-previes" class="text-sm font-medium"
                >{m.settings_hide_spoilers()}</Label
              >
              <p class="text-xs text-muted-foreground">
                {m.settings_hide_spoilers_description()}
              </p>
            </div>
            <Switch
              id="stream-details"
              checked={draft.hideSpoilers}
              onCheckedChange={(v) => patch("hideSpoilers", v)}
            />
          </div>

          <Separator class="my-2" />

          <div class="flex items-center justify-between py-3">
            <div class="pr-4">
              <Label class="text-sm font-medium">{m.settings_discovery_algorithm()}</Label>
              <p class="text-xs text-muted-foreground">
                {DISCOVERY_ALGORITHMS.find(
                  (a) => a.value === draft.discoveryAlgorithm,
                )?.description ?? ""}
              </p>
            </div>
            <Select.Root type="single" bind:value={draft.discoveryAlgorithm}>
              <Select.Trigger class="w-56 shrink-0">
                {DISCOVERY_ALGORITHMS.find(
                  (a) => a.value === draft.discoveryAlgorithm,
                )?.label ?? m.common_choose()}
              </Select.Trigger>
              <Select.Content>
                {#each DISCOVERY_ALGORITHMS as a (a.value)}
                  <Select.Item value={a.value}>{a.label}</Select.Item>
                {/each}
              </Select.Content>
            </Select.Root>
          </div>

          {#if draft.discoveryAlgorithm === "custom"}
            <div class="rounded-lg border border-border p-4">
              <Label class="mb-2 block text-sm font-medium"
                >{m.settings_custom_algorithm_url()}</Label
              >
              <p class="mb-3 text-xs text-muted-foreground">
                {m.settings_custom_algorithm_description()}
              </p>
              <div class="flex gap-2">
                <Input
                  type="url"
                  placeholder="https://..."
                  bind:value={draft.customAlgorithmUrl}
                  class="flex-1"
                />
                <Button
                  variant="outline"
                  onclick={handleTestAlgorithm}
                  disabled={testingAlgorithm ||
                    !draft.customAlgorithmUrl.trim()}
                  size="sm"
                >
                  {testingAlgorithm ? m.common_testing() : m.common_test_connection()}
                </Button>
              </div>
              {#if algorithmTestResult}
                <p
                  class="mt-2 text-xs {algorithmTestResult.ok
                    ? 'text-green-500'
                    : 'text-red-500'}"
                >
                  {algorithmTestResult.ok
                    ? m.common_connected_success()
                    : m.common_failed_message({ error: algorithmTestResult.error })}
                </p>
              {/if}
            </div>
          {/if}

          {#if isAndroid() || isAndroidTV()}
            <Separator class="my-2" />
            <div class="flex items-center justify-between py-3">
              <div>
                <Label for="auto-update" class="text-sm font-medium"
                  >{m.settings_auto_update()}</Label
                >
                <p class="text-xs text-muted-foreground">
                  {m.settings_auto_update_description()}
                </p>
              </div>
              <Switch
                id="auto-update"
                checked={autoUpdateEnabled}
                onCheckedChange={(v) => {
                  autoUpdateEnabled = v;
                  window.__coveApp?.setAutoUpdateEnabled?.(v);
                }}
              />
            </div>
          {/if}

          {#if !isAndroid() && !isAndroidTV()}
            <Separator class="my-2" />
            <div class="flex items-center justify-between py-3">
              <div>
                <Label for="tv-switch-visible" class="text-sm font-medium"
                  >{m.settings_tv_switch()}</Label
                >
                <p class="text-xs text-muted-foreground">
                  {m.settings_tv_switch_description()}
                </p>
              </div>
              <Switch
                id="tv-switch-visible"
                checked={$tvSwitchVisible}
                onCheckedChange={(v) => setTvSwitchVisible(v)}
              />
            </div>
          {/if}
        </Tabs.Content>

        <!-- ── Addons ── -->
        <Tabs.Content value="addons" class="mt-4 space-y-4">
          <!-- Add new addon -->
          <div class="rounded-lg border border-border p-4">
            <Label class="mb-2 block text-sm font-medium"
              >{m.settings_add_stremio()}</Label
            >
            <p class="mb-3 text-xs text-muted-foreground">
              {m.settings_add_stremio_description()}
            </p>
            <div class="flex gap-2">
              <Input
                type="url"
                placeholder="https://..."
                bind:value={addAddonUrl}
                class="flex-1"
                onkeydown={(e) => e.key === "Enter" && handleAddAddon()}
              />
              <Button
                onclick={handleAddAddon}
                disabled={addAddonLoading || !addAddonUrl.trim()}
                size="sm"
              >
                <Plus class="mr-1 size-4" />
                {addAddonLoading ? m.common_adding() : m.common_add()}
              </Button>
            </div>
            {#if addAddonError}
              <p class="mt-2 text-xs text-red-500">{addAddonError}</p>
            {/if}
          </div>

          <!-- Addon list -->
          <div class="space-y-2">
            {#each addons as addon (addon.url || addon.id)}
              <div class="rounded-lg border border-border bg-secondary/30 p-3">
                <div class="flex items-center gap-3">
                  <div class="min-w-0 flex-1">
                    <div class="flex items-center gap-2">
                      <span class="text-sm font-medium"
                        >{addon.manifest.name ||
                          addon.url ||
                          addon.id ||
                          m.common_unknown_addon()}</span
                      >
                      <Badge
                        variant="outline"
                        class={addon.kind === KindProvider
                          ? "border-blue-500/30 bg-blue-500/20 text-blue-400"
                          : addon.kind === KindTimestamps
                            ? "border-amber-500/30 bg-amber-500/20 text-amber-400"
                            : "border-purple-500/30 bg-purple-500/20 text-purple-400"}
                      >
                        {addon.kind === KindProvider
                          ? m.common_provider()
                          : addon.kind === KindTimestamps
                            ? m.common_timestamps()
                            : m.player_subtitles()}
                      </Badge>
                      {#if addon.source === SourceOfficial}
                        <Badge
                          variant="outline"
                          class="border-green-500/30 bg-green-500/20 text-green-400"
                          >{m.common_builtin()}</Badge
                        >
                      {/if}
                    </div>
                    {#if addon.manifest.description}
                      <p class="mt-0.5 text-xs text-muted-foreground">
                        {addon.manifest.description}
                      </p>
                    {/if}
                  </div>

                  <!-- Toggle -->
                  <Switch
                    checked={addon.enabled}
                    onCheckedChange={() => handleToggleAddon(addon)}
                    class="shrink-0"
                  />

                  <!-- Configure / Refresh / Remove (stremio only) -->
                  {#if addon.source !== SourceOfficial}
                    {#if addon.manifest.behaviorHints?.configurable}
                      <Button
                        variant="ghost"
                        size="icon"
                        class="shrink-0 text-muted-foreground"
                        onclick={() => (configureAddon = addon)}
                        title={m.common_configure()}
                      >
                        <Cog class="size-4" />
                      </Button>
                    {/if}
                    <Button
                      variant="ghost"
                      size="icon"
                      class="shrink-0 text-muted-foreground"
                      onclick={() => handleRefreshAddon(addon)}
                      disabled={refreshingAddonId === addon.id}
                      title={m.common_refresh()}
                    >
                      <RefreshCw
                        class={`size-4 ${refreshingAddonId === addon.id ? "animate-spin" : ""}`}
                      />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      class="shrink-0 text-muted-foreground hover:text-destructive"
                      onclick={() => handleRemoveAddon(addon)}
                      title={m.common_remove()}
                    >
                      <Trash2 class="size-4" />
                    </Button>
                  {/if}
                </div>

                <!-- Per-catalog toggles (only for addons that declare catalogs) -->
                {#if addon.manifest.catalogs?.length}
                  <div class="mt-2 space-y-1 border-t border-border pt-2">
                    {#each addon.manifest.catalogs as cat (`${cat.type}/${cat.id}`)}
                      {@const key = `${cat.type}/${cat.id}`}
                      <div class="flex items-center gap-3 py-1">
                        <div class="min-w-0 flex-1">
                          <span class="text-xs font-medium">{cat.name}</span>
                          <span class="ml-1.5 text-xs text-muted-foreground"
                            >({cat.type})</span
                          >
                        </div>
                        <Switch
                          checked={!addon.disabledCatalogs?.[key]}
                          disabled={!addon.enabled}
                          onCheckedChange={(v) =>
                            handleToggleCatalog(addon, key, v)}
                          class="shrink-0"
                        />
                      </div>
                    {/each}
                  </div>
                {/if}
              </div>
            {:else}
              <p class="py-4 text-center text-sm text-muted-foreground">
                {m.settings_no_addons()}
              </p>
            {/each}
          </div>
        </Tabs.Content>

        <!-- ── Plugins (Nuvio native scrapers) ── -->
        <Tabs.Content value="plugins" class="mt-4 space-y-4">
          <div
            class="flex items-start gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 p-3 text-xs text-amber-400"
          >
            <TriangleAlert class="mt-0.5 size-4 shrink-0" />
            <p>{m.settings_plugin_warning()}</p>
          </div>

          <!-- Add new repository -->
          <div class="rounded-lg border border-border p-4">
            <Label class="mb-2 block text-sm font-medium"
              >{m.settings_add_repository()}</Label
            >
            <p class="mb-3 text-xs text-muted-foreground">
              {m.settings_repository_url_description()}
            </p>
            <div class="flex gap-2">
              <Input
                type="url"
                placeholder="https://github.com/owner/repo"
                bind:value={addRepoUrl}
                class="flex-1"
                onkeydown={(e) => e.key === "Enter" && handleAddRepo()}
              />
              <Button
                onclick={handleAddRepo}
                disabled={addRepoLoading || !addRepoUrl.trim()}
                size="sm"
              >
                <Plus class="mr-1 size-4" />
                {addRepoLoading ? m.common_adding() : m.common_add()}
              </Button>
            </div>
            {#if addRepoError}
              <p class="mt-2 text-xs text-red-500">{addRepoError}</p>
            {/if}
          </div>

          <!-- Repo list -->
          <div class="space-y-3">
            {#each nuvioRepos as repo (repo.id)}
              <div class="rounded-lg border border-border bg-secondary/30 p-3">
                <div class="flex items-center gap-3">
                  <div class="min-w-0 flex-1">
                    <div class="flex items-center gap-2">
                      <span class="text-sm font-medium"
                        >{repo.owner}/{repo.repo}</span
                      >
                      <Badge
                        variant="outline"
                        class="border-purple-500/30 bg-purple-500/20 text-purple-400"
                        >{repo.scrapers.length === 1
                          ? m.settings_scraper_count_one()
                          : m.settings_scrapers_count({
                              count: repo.scrapers.length,
                            })}</Badge
                      >
                    </div>
                    {#if repo.fetchErr}
                      <p class="mt-0.5 text-xs text-red-500">
                        {m.settings_refresh_failed({ error: repo.fetchErr })}
                      </p>
                    {/if}
                  </div>

                  <Button
                    variant="ghost"
                    size="icon"
                    class="shrink-0 text-muted-foreground"
                    onclick={() => handleRefreshRepo(repo)}
                    disabled={refreshingRepoId === repo.id}
                    title={m.settings_refresh_manifest()}
                  >
                    <RefreshCw
                      class={`size-4 ${refreshingRepoId === repo.id ? "animate-spin" : ""}`}
                    />
                  </Button>

                  <Switch
                    checked={repo.enabled}
                    onCheckedChange={() => handleToggleRepo(repo)}
                    class="shrink-0"
                    title={m.settings_enable_repository()}
                  />

                  <Button
                    variant="ghost"
                    size="icon"
                    class="shrink-0 text-muted-foreground hover:text-destructive"
                    onclick={() => handleRemoveRepo(repo)}
                    title={m.common_remove()}
                  >
                    <Trash2 class="size-4" />
                  </Button>
                </div>

                <!-- Per-scraper toggles -->
                <div class="mt-2 space-y-1 border-t border-border pt-2">
                  {#each repo.scrapers as scraper (scraper.id)}
                    <div class="flex items-center gap-3 py-1">
                      <div class="min-w-0 flex-1">
                        <span class="text-xs font-medium">{scraper.name}</span>
                        {#if scraper.description}
                          <span class="ml-1.5 text-xs text-muted-foreground"
                            >{scraper.description}</span
                          >
                        {/if}
                        {#if scraper.codeErr}
                          <p class="text-xs text-red-500">{scraper.codeErr}</p>
                        {/if}
                      </div>

                      {#if pendingConfirm?.repoId === repo.id && pendingConfirm?.scraperId === scraper.id}
                        <div class="flex shrink-0 items-center gap-2">
                          <span class="text-xs text-amber-400"
                            >{m.settings_run_third_party({
                              repository: `${repo.owner}/${repo.repo}`,
                            })}</span
                          >
                          <Button
                            size="sm"
                            variant="outline"
                            onclick={() => (pendingConfirm = null)}
                            >{m.common_cancel()}</Button
                          >
                          <Button
                            size="sm"
                            onclick={() =>
                              handleSetScraperEnabled(repo, scraper, true)}
                            >{m.common_enable()}</Button
                          >
                        </div>
                      {:else}
                        <Switch
                          checked={scraper.enabled}
                          onCheckedChange={() =>
                            requestEnableScraper(repo, scraper)}
                          class="shrink-0"
                        />
                      {/if}
                    </div>
                  {:else}
                    <p class="py-2 text-center text-xs text-muted-foreground">
                      {m.settings_no_scrapers()}
                    </p>
                  {/each}
                </div>
              </div>
            {:else}
              <p class="py-4 text-center text-sm text-muted-foreground">
                {m.settings_no_repositories()}
              </p>
            {/each}
          </div>
        </Tabs.Content>
        <!-- ── Trakt.tv ── -->
        <Tabs.Content value="trakt" class="mt-4 space-y-4">
          {#if traktStatus === undefined}
            <p class="text-sm text-muted-foreground">{m.common_loading()}</p>
          {:else if traktStatus === null}
            <p class="text-sm text-muted-foreground">
              {m.settings_trakt_unconfigured()}
            </p>
          {:else if traktStatus.connected}
            <!-- Connected state -->
            <div
              class="rounded-lg border border-border bg-secondary/30 p-4 space-y-4"
            >
              <p class="text-sm font-medium">
                {m.settings_trakt_connected_as({
                  username: traktStatus.username,
                })}
              </p>

              <Separator />

              <div class="flex items-center justify-between">
                <div>
                  <Label for="trakt-scrobble" class="text-sm font-medium"
                    >{m.settings_trakt_scrobble()}</Label
                  >
                  <p class="text-xs text-muted-foreground">
                    {m.settings_trakt_scrobble_description()}
                  </p>
                </div>
                <Switch
                  id="trakt-scrobble"
                  checked={draft.traktScrobbleEnabled}
                  onCheckedChange={(v) => patch("traktScrobbleEnabled", v)}
                />
              </div>

              <div class="flex items-center justify-between">
                <div>
                  <Label for="trakt-sync" class="text-sm font-medium"
                    >{m.settings_trakt_sync()}</Label
                  >
                  <p class="text-xs text-muted-foreground">
                    {m.settings_trakt_sync_description()}
                  </p>
                </div>
                <Switch
                  id="trakt-sync"
                  checked={draft.traktSyncEnabled}
                  onCheckedChange={(v) => patch("traktSyncEnabled", v)}
                />
              </div>

              {#if draft.traktSyncEnabled}
                <Button
                  variant="outline"
                  size="sm"
                  onclick={handleTraktSync}
                  disabled={traktSyncLoading}
                >
                  {traktSyncLoading ? m.common_syncing() : m.account_sync()}
                </Button>
              {/if}

              <Separator />

              <Button
                variant="outline"
                size="sm"
                onclick={handleTraktDisconnect}
                disabled={traktUnlinkLoading}
              >
                {traktUnlinkLoading
                  ? m.common_disconnecting()
                  : m.common_disconnect()}
              </Button>
            </div>
          {:else}
            <!-- Not connected state -->
            {#if traktFlowState === "idle"}
              <div class="rounded-lg border border-border p-4 space-y-3">
                <Label class="text-sm font-medium"
                  >{m.settings_trakt_connect_description()}</Label
                >
                <p class="text-xs text-muted-foreground">
                  {m.settings_trakt_about()}
                </p>
                {#if traktConnectError}
                  <p class="text-xs text-red-500">{traktConnectError}</p>
                {/if}
                <Button size="sm" onclick={handleTraktConnect}>
                  {m.settings_trakt_connect()}
                </Button>
              </div>
            {:else if traktFlowState === "polling"}
              <!-- Device flow: show code + URL while polling -->
              <div class="rounded-lg border border-border p-4 space-y-4">
                <Label class="text-sm font-medium"
                  >{m.settings_trakt_authorize()}</Label
                >
                <div class="space-y-2">
                  <p class="text-xs text-muted-foreground">
                    {m.settings_trakt_open_url()}
                  </p>
                  <a
                    href={traktFlow?.verification_url}
                    target="_blank"
                    rel="noopener noreferrer"
                    class="text-sm font-medium text-primary hover:underline break-all"
                    >{traktFlow?.verification_url}</a
                  >
                  <p class="text-xs text-muted-foreground">
                    {m.settings_trakt_enter_code()}
                  </p>
                  <div class="flex items-center gap-2">
                    <code
                      class="flex-1 rounded bg-muted px-4 py-3 text-center text-2xl font-mono tracking-widest font-semibold"
                      >{traktFlow?.user_code}</code
                    >
                    <Button
                      variant="outline"
                      size="icon"
                      class="shrink-0"
                      onclick={handleCopyTraktCode}
                      title={m.settings_copy_code()}
                    >
                      {#if traktCodeCopied}
                        <CheckIcon class="size-4 text-green-500" />
                      {:else}
                        <Copy class="size-4" />
                      {/if}
                    </Button>
                  </div>
                </div>
                <p class="text-xs text-muted-foreground">
                  {m.settings_trakt_waiting()}
                </p>
              </div>
            {:else if traktFlowState === "expired"}
              <div class="rounded-lg border border-border p-4 space-y-3">
                <p class="text-sm text-muted-foreground">
                  {m.settings_trakt_expired()}
                </p>
                <Button size="sm" onclick={handleTraktConnect}>{m.settings_trakt_try_again()}</Button
                >
              </div>
            {:else if traktFlowState === "denied"}
              <div class="rounded-lg border border-border p-4 space-y-3">
                <p class="text-sm text-muted-foreground">
                  {m.settings_trakt_denied()}
                </p>
                <Button size="sm" onclick={handleTraktConnect}>{m.settings_trakt_try_again()}</Button
                >
              </div>
            {/if}
          {/if}
        </Tabs.Content>

        <!-- ── Advanced ── -->
        <Tabs.Content value="advanced" class="mt-4 space-y-4">
          <div>
            <Label class="text-sm font-medium">{m.settings_mpv_configuration()}</Label>
            <p class="mt-1 text-xs text-muted-foreground">
              {m.settings_mpv_description()}
              <span> </span>
              <a
                href="https://mpv.io/manual/stable/#configuration-files"
                target="_blank"
                rel="noopener noreferrer"
                class="text-primary hover:underline"
                >{m.settings_mpv_reference()}</a
              >
            </p>
          </div>

          <Textarea
            class="min-h-64 font-mono text-xs"
            spellcheck={false}
            placeholder="# hwdec=auto&#10;# volume=80"
            bind:value={mpvConfDraft}
          />

          {#if mpvConfError}
            <p class="text-xs text-red-500">{mpvConfError}</p>
          {/if}

          <div class="flex items-center gap-3">
            <Button
              size="sm"
              onclick={handleMpvConfSave}
              disabled={mpvConfSaving || !mpvConfDirty}
            >
              {mpvConfSaving
                ? m.common_saving()
                : mpvConfSaveOk
                  ? m.common_saved()
                  : m.common_save()}
            </Button>
          </div>
        </Tabs.Content>
      </Tabs.Root>
    {:else}
      <p class="text-muted-foreground">{m.settings_loading()}</p>
    {/if}
  </div>
</ScrollArea>

{#if configureAddon}
  <!-- Configure addon overlay -->
  <div
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
    role="presentation"
    onclick={(e) => {
      if (e.target === e.currentTarget) configureAddon = null;
    }}
    onkeydown={(e) => {
      if (e.key === "Escape") configureAddon = null;
    }}
  >
    <div
      class="relative flex w-[90vw] max-w-4xl flex-col rounded-xl border border-border bg-background shadow-2xl"
      style="height: 85vh;"
    >
      <!-- Header -->
      <div
        class="flex shrink-0 items-center justify-between border-b border-border px-4 py-3"
      >
        <span class="truncate text-sm font-medium">
          {configureAddon.manifest.name || configureAddon.url}
        </span>
        <button
          type="button"
          class="ml-3 shrink-0 rounded p-1 text-muted-foreground hover:text-foreground"
          onclick={() => (configureAddon = null)}
          aria-label={m.common_close()}
        >
          <X class="size-4" />
        </button>
      </div>

      <!-- Hint -->
      <p class="shrink-0 px-4 py-2 text-xs text-muted-foreground">
        {m.settings_addon_config_hint()}
      </p>

      <!-- iframe -->
      <div class="min-h-0 flex-1 px-4 pb-2">
        <iframe
          src={`${configureAddon.url}/configure`}
          class="h-full w-full rounded border border-border"
          title={m.settings_addon_configuration()}
        ></iframe>
      </div>

      <!-- Fallback link -->
      <div class="shrink-0 px-4 pb-3 text-xs text-muted-foreground">
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
