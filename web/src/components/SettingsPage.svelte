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
  import { isAndroid, isAndroidTV } from "$lib/platform";
  import { STREAM_SELECTION_MODES, SOURCE_PREFERENCES } from "$lib/streamSelection";
  import { DISCOVERY_ALGORITHMS } from "$lib/discoveryAlgorithms";
  import { api, type TraktStatus, type TraktDeviceCode } from "$lib/api";
  import type { AddonEntry } from "$lib/types/addons";
  import {
    KindProvider,
    KindTimestamps,
    SourceOfficial,
  } from "$lib/types/addons";
  import { Badge } from "$lib/components/ui/badge/index.js";
  import { Input } from "$lib/components/ui/input/index.js";
  import { ScrollArea } from "$lib/components/ui/scroll-area/index.js";
  import { Trash2, Plus, RefreshCw, TriangleAlert, Eye, EyeOff, Copy, Check as CheckIcon } from "lucide-svelte";
  import type {
    Repo as NuvioRepo,
    Scraper as NuvioScraper,
  } from "$lib/types/nuvio";

  let draft = $state<Settings | null>(null);
  let saved = $state(false);
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
    saved = true;
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => (saved = false), 2000);
    await settings.save(draft);
    // Pull server-generated fields back into the draft — enabling remote
    // access makes the backend mint a token that only exists in the PUT
    // response (masked as "***"); without this the draft keeps its stale ""
    // and the token row never appears until a reload.
    const unsub = settings.subscribe((v) => {
      if (draft) {
        draft = { ...draft, remoteAccessToken: v.remoteAccessToken, updatedAt: v.updatedAt };
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
    addons = await api.getAddons();
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
      addAddonError = e instanceof Error ? e.message : "Failed to add addon";
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

  async function handleToggleCatalog(addon: AddonEntry, key: string, enabled: boolean) {
    try {
      await api.toggleCatalog(addon.id, key, enabled);
      addons = addons.map((a) =>
        a.id === addon.id
          ? { ...a, disabledCatalogs: { ...(a.disabledCatalogs ?? {}), [key]: !enabled } }
          : a,
      );
    } catch (e) {
      console.error("handleToggleCatalog failed", e);
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
    nuvioRepos = await api.getNuvioRepos();
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
        e instanceof Error ? e.message : "Failed to add repository";
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
        error: e instanceof Error ? e.message : "Test failed",
      };
    } finally {
      testingAlgorithm = false;
    }
  }

  const LANGUAGES = [
    { value: "en", label: "English" },
    { value: "es", label: "Spanish" },
    { value: "fr", label: "French" },
    { value: "de", label: "German" },
    { value: "pt", label: "Portuguese" },
    { value: "it", label: "Italian" },
    { value: "ja", label: "Japanese" },
    { value: "ko", label: "Korean" },
    { value: "zh", label: "Chinese" },
    { value: "ar", label: "Arabic" },
    { value: "ru", label: "Russian" },
  ];

  // Audio-only: "original" plays whatever track matches the title's TMDB
  // original_language, instead of a fixed language — see Player.svelte's
  // audio auto-select effect. Subtitles have no equivalent concept (TMDB
  // doesn't publish an "original subtitle language").
  const AUDIO_LANGUAGES = [
    { value: "original", label: "Original" },
    ...LANGUAGES,
  ];

  function langLabel(value: string) {
    return AUDIO_LANGUAGES.find((l) => l.value === value)?.label ?? value;
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
  let traktFlowState = $state<"idle" | "polling" | "expired" | "denied">("idle");
  let traktConnectError = $state<string | null>(null);
  let traktSyncLoading = $state(false);
  let traktUnlinkLoading = $state(false);
  let traktPollInterval: ReturnType<typeof setInterval> | null = null;
  let traktFlowTimeout: ReturnType<typeof setTimeout> | null = null;
  let traktPollIntervalMs = 0;

  async function loadTraktStatus() {
    traktStatus = await api.traktStatus(); // null on 503 (not configured)
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
</script>

<ScrollArea class="h-full w-full">
  <div class="mx-auto max-w-2xl space-y-6 p-6 pt-18 pb-16">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-semibold tracking-tight">Settings</h1>
      <div class="flex gap-2">
        <Button variant="outline" onclick={handleReset}>Reset</Button>
        <Button onclick={handleSave}>{saved ? "Saved ✓" : "Save"}</Button>
      </div>
    </div>

    {#if draft}
      <Tabs.Root value="playback">
        <Tabs.List class="w-full">
          <Tabs.Trigger value="playback">Playback</Tabs.Trigger>
          <Tabs.Trigger value="streaming">Streaming</Tabs.Trigger>
          <Tabs.Trigger value="subtitles">Subtitles & Audio</Tabs.Trigger>
          <Tabs.Trigger value="interface">Interface</Tabs.Trigger>
          <Tabs.Trigger value="addons">Addons</Tabs.Trigger>
          <Tabs.Trigger value="plugins">Plugins</Tabs.Trigger>
          <Tabs.Trigger value="trakt">Trakt</Tabs.Trigger>
        </Tabs.List>

        <!-- ── Playback ── -->
        <Tabs.Content value="playback" class="mt-4 space-y-1">
          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="open-muted" class="text-sm font-medium"
                >Open videos muted</Label
              >
              <p class="text-xs text-muted-foreground">
                Start every video with audio muted.
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
              <Label class="text-sm font-medium">Default volume</Label>
              <p class="text-xs text-muted-foreground">Initial volume level.</p>
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
                >Autoplay next episode</Label
              >
              <p class="text-xs text-muted-foreground">
                Automatically start the next episode when one finishes.
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
                >Remember position</Label
              >
              <p class="text-xs text-muted-foreground">
                Resume from where you left off.
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
            <Label class="text-sm font-medium">Auto-skip segments</Label>
            <p class="mb-3 text-xs text-muted-foreground">
              Automatically skip segments when timestamps are available via
              IntroDB. A skip button always appears when inside a segment.
            </p>
            <div class="space-y-2">
              <div class="flex items-center justify-between">
                <Label for="skip-intro" class="text-sm text-muted-foreground"
                  >Skip intro</Label
                >
                <Switch
                  id="skip-intro"
                  checked={draft.autoSkipIntro}
                  onCheckedChange={(v) => patch("autoSkipIntro", v)}
                />
              </div>
              <div class="flex items-center justify-between">
                <Label for="skip-recap" class="text-sm text-muted-foreground"
                  >Skip recap</Label
                >
                <Switch
                  id="skip-recap"
                  checked={draft.autoSkipRecap}
                  onCheckedChange={(v) => patch("autoSkipRecap", v)}
                />
              </div>
              <div class="flex items-center justify-between">
                <Label for="skip-credits" class="text-sm text-muted-foreground"
                  >Skip credits</Label
                >
                <Switch
                  id="skip-credits"
                  checked={draft.autoSkipCredits}
                  onCheckedChange={(v) => patch("autoSkipCredits", v)}
                />
              </div>
              <div class="flex items-center justify-between">
                <Label for="skip-preview" class="text-sm text-muted-foreground"
                  >Skip preview</Label
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
                >Automatically pick best stream</Label
              >
              <p class="text-xs text-muted-foreground">
                Skip the stream list — start playing immediately when you press
                Watch.
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
                >Prefetch streams</Label
              >
              <p class="text-xs text-muted-foreground">
                Pre-fetch streams for shows you're likely to watch next.
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
                >Pre-download next episode</Label
              >
              <p class="text-xs text-muted-foreground">
                When an episode finishes downloading, quietly download the
                next one so it starts instantly.
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
                >Upload while streaming</Label
              >
              <p class="text-xs text-muted-foreground">
                Share downloaded pieces back to peers (max 1 MiB/s). Improves
                download speed; turn off to never upload.
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
                >Verify streams before auto-selecting</Label
              >
              <p class="text-xs text-muted-foreground">
                Checks that top candidates are reachable before playback
                starts. Adds a brief delay but avoids picking dead links.
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
                >Allow LAN stream sources</Label
              >
              <p class="text-xs text-muted-foreground">
                Lets streams play from private/LAN addresses. Only enable
                if you run a stream source on your own network — this
                weakens protection against malicious addons probing your
                network.
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
                  >Remote access</Label
                >
                <p class="text-xs text-muted-foreground">
                  Opens a LAN port (6970) so the mobile app or other
                  devices on your network can connect to this backend.
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
                  >Access token</Label
                >
                {#if draft.remoteAccessToken === ""}
                  <p class="text-xs text-muted-foreground">
                    No token set — save settings to generate one.
                  </p>
                {:else}
                  <div class="flex items-center gap-2">
                    <code class="flex-1 truncate rounded bg-muted px-2 py-1 text-xs font-mono">
                      {tokenVisible && revealedToken ? revealedToken : "•".repeat(32)}
                    </code>
                    <Button
                      variant="outline"
                      size="icon"
                      class="shrink-0"
                      onclick={handleRevealToken}
                      disabled={revealingToken}
                      title={tokenVisible ? "Hide token" : "Show token"}
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
                      title="Copy token"
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
              <Label class="text-sm font-medium">Selection strategy</Label>
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
                )?.label ?? "Choose…"}
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
              <Label class="text-sm font-medium">Source preference</Label>
              <p class="text-xs text-muted-foreground">
                {draft.sourcePreference
                  ? "Gives torrents or direct streams a small ranking boost — doesn't exclude the other kind."
                  : "No boost — torrents and direct streams rank purely on the selection strategy above."}
              </p>
            </div>
            <Select.Root type="single" bind:value={draft.sourcePreference}>
              <Select.Trigger class="w-56 shrink-0">
                {SOURCE_PREFERENCES.find(
                  (p) => p.value === draft.sourcePreference,
                )?.label ?? "No preference"}
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
              <Label class="text-sm font-medium">Preferred provider</Label>
              <p class="text-xs text-muted-foreground">
                {#if providerAddons.length === 0 && nuvioProviderOptions.length === 0}
                  Add a provider addon in the Addons tab to set a preference.
                {:else}
                  Its streams are shown first and favored by auto-select.
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
                {draft.defaultProvider || "No preference"}
              </Select.Trigger>
              <Select.Content>
                <Select.Item value="">No preference</Select.Item>
                {#each providerAddons as a (a.id)}
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
              <Label class="text-sm font-medium">Connection speed</Label>
              {#if draft.measuredBandwidthMbps > 0}
                <p class="text-xs text-muted-foreground">
                  Last measured at {draft.measuredBandwidthMbps} Mbps. Used by "Match
                  My Internet Speed".
                </p>
              {:else}
                <p class="text-xs text-muted-foreground">
                  Not measured yet — needed for "Match My Internet Speed".
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
              {testingSpeed ? "Testing…" : "Test My Speed"}
            </Button>
          </div>
        </Tabs.Content>

        <!-- ── Subtitles & Audio ── -->
        <Tabs.Content value="subtitles" class="mt-4 space-y-1">
          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="subs-enabled" class="text-sm font-medium"
                >Enable subtitles by default</Label
              >
              <p class="text-xs text-muted-foreground">
                Show subtitles automatically when available.
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
                >Preferred subtitle language</Label
              >
              <p class="text-xs text-muted-foreground">
                Auto-select this language when subtitles are available.
              </p>
            </div>
            <Select.Root type="single" bind:value={draft.defaultSubtitleLang}>
              <Select.Trigger class="w-36"
                >{langLabel(draft.defaultSubtitleLang)}</Select.Trigger
              >
              <Select.Content>
                {#each LANGUAGES as l}
                  <Select.Item value={l.value}>{l.label}</Select.Item>
                {/each}
              </Select.Content>
            </Select.Root>
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div>
              <Label class="text-sm font-medium">Preferred audio language</Label
              >
              <p class="text-xs text-muted-foreground">
                Auto-select this audio track when multiple are available.
              </p>
            </div>
            <Select.Root type="single" bind:value={draft.defaultAudioLang}>
              <Select.Trigger class="w-36"
                >{langLabel(draft.defaultAudioLang)}</Select.Trigger
              >
              <Select.Content>
                {#each AUDIO_LANGUAGES as l}
                  <Select.Item value={l.value}>{l.label}</Select.Item>
                {/each}
              </Select.Content>
            </Select.Root>
          </div>
        </Tabs.Content>

        <!-- ── Interface ── -->
        <Tabs.Content value="interface" class="mt-4 space-y-1">
          <div class="flex items-center justify-between py-3">
            <div>
              <Label for="stream-details" class="text-sm font-medium"
                >Show stream details</Label
              >
              <p class="text-xs text-muted-foreground">
                Display codec, resolution, and size badges on the stream list.
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
                >Hide Spoilers</Label
              >
              <p class="text-xs text-muted-foreground">
                Hide not-seen episode thumbnails, descriptions, and episode
                names;
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
              <Label class="text-sm font-medium">Discovery algorithm</Label>
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
                )?.label ?? "Choose…"}
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
                >Custom algorithm URL</Label
              >
              <p class="mb-3 text-xs text-muted-foreground">
                Cove POSTs your taste profile and a pre-filtered candidate list
                to this URL and expects relevance scores back. Falls back to
                Cove Smart if the endpoint is unreachable or errors.
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
                  {testingAlgorithm ? "Testing…" : "Test connection"}
                </Button>
              </div>
              {#if algorithmTestResult}
                <p
                  class="mt-2 text-xs {algorithmTestResult.ok
                    ? 'text-green-500'
                    : 'text-red-500'}"
                >
                  {algorithmTestResult.ok
                    ? "Connected successfully."
                    : `Failed: ${algorithmTestResult.error}`}
                </p>
              {/if}
            </div>
          {/if}

          {#if isAndroid() || isAndroidTV()}
            <Separator class="my-2" />
            <div class="flex items-center justify-between py-3">
              <div>
                <Label for="auto-update" class="text-sm font-medium"
                  >Auto-update</Label
                >
                <p class="text-xs text-muted-foreground">
                  Automatically download and install updates on launch.
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
        </Tabs.Content>

        <!-- ── Addons ── -->
        <Tabs.Content value="addons" class="mt-4 space-y-4">
          <!-- Add new addon -->
          <div class="rounded-lg border border-border p-4">
            <Label class="mb-2 block text-sm font-medium"
              >Add Stremio addon</Label
            >
            <p class="mb-3 text-xs text-muted-foreground">
              Paste a Stremio-compatible addon manifest URL.
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
                {addAddonLoading ? "Adding…" : "Add"}
              </Button>
            </div>
            {#if addAddonError}
              <p class="mt-2 text-xs text-red-500">{addAddonError}</p>
            {/if}
          </div>

          <!-- Addon list -->
          <div class="space-y-2">
            {#each addons as addon (addon.id)}
              <div class="rounded-lg border border-border bg-secondary/30 p-3">
                <div class="flex items-center gap-3">
                  <div class="min-w-0 flex-1">
                    <div class="flex items-center gap-2">
                      <span class="text-sm font-medium"
                        >{addon.manifest.name ||
                          addon.url ||
                          addon.id ||
                          "Unknown addon"}</span
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
                          ? "Provider"
                          : addon.kind === KindTimestamps
                            ? "Timestamps"
                            : "Subtitles"}
                      </Badge>
                      {#if addon.source === SourceOfficial}
                        <Badge
                          variant="outline"
                          class="border-green-500/30 bg-green-500/20 text-green-400"
                          >Built-in</Badge
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

                  <!-- Remove (stremio only) -->
                  {#if addon.source !== SourceOfficial}
                    <Button
                      variant="ghost"
                      size="icon"
                      class="shrink-0 text-muted-foreground hover:text-destructive"
                      onclick={() => handleRemoveAddon(addon)}
                      title="Remove"
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
                          <span class="ml-1.5 text-xs text-muted-foreground">({cat.type})</span>
                        </div>
                        <Switch
                          checked={!addon.disabledCatalogs?.[key]}
                          disabled={!addon.enabled}
                          onCheckedChange={(v) => handleToggleCatalog(addon, key, v)}
                          class="shrink-0"
                        />
                      </div>
                    {/each}
                  </div>
                {/if}
              </div>
            {:else}
              <p class="py-4 text-center text-sm text-muted-foreground">
                No addons yet. Add a Stremio-compatible addon above.
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
            <p>
              Plugins are community-maintained JavaScript stream scrapers. Cove runs this code on your device to find streams
              — it has not been reviewed by Cove and its safety and quality vary
              by repository. Nothing runs until you explicitly enable a scraper
              below.
            </p>
          </div>

          <!-- Add new repository -->
          <div class="rounded-lg border border-border p-4">
            <Label class="mb-2 block text-sm font-medium"
              >Add plugin repository</Label
            >
            <p class="mb-3 text-xs text-muted-foreground">
              Paste a GitHub repository URL (e.g. github.com/owner/repo) that
              publishes a manifest.json.
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
                {addRepoLoading ? "Adding…" : "Add"}
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
                        >{repo.scrapers.length} scraper{repo.scrapers.length ===
                        1
                          ? ""
                          : "s"}</Badge
                      >
                    </div>
                    {#if repo.fetchErr}
                      <p class="mt-0.5 text-xs text-red-500">
                        Last refresh failed: {repo.fetchErr}
                      </p>
                    {/if}
                  </div>

                  <Button
                    variant="ghost"
                    size="icon"
                    class="shrink-0 text-muted-foreground"
                    onclick={() => handleRefreshRepo(repo)}
                    disabled={refreshingRepoId === repo.id}
                    title="Refresh manifest"
                  >
                    <RefreshCw
                      class={`size-4 ${refreshingRepoId === repo.id ? "animate-spin" : ""}`}
                    />
                  </Button>

                  <Switch
                    checked={repo.enabled}
                    onCheckedChange={() => handleToggleRepo(repo)}
                    class="shrink-0"
                    title="Enable this repository"
                  />

                  <Button
                    variant="ghost"
                    size="icon"
                    class="shrink-0 text-muted-foreground hover:text-destructive"
                    onclick={() => handleRemoveRepo(repo)}
                    title="Remove"
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
                            >Run third-party JS from {repo.owner}/{repo.repo}?</span
                          >
                          <Button
                            size="sm"
                            variant="outline"
                            onclick={() => (pendingConfirm = null)}
                            >Cancel</Button
                          >
                          <Button
                            size="sm"
                            onclick={() =>
                              handleSetScraperEnabled(repo, scraper, true)}
                            >Enable</Button
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
                      No scrapers found in this repository's manifest.
                    </p>
                  {/each}
                </div>
              </div>
            {:else}
              <p class="py-4 text-center text-sm text-muted-foreground">
                No plugin repositories yet. Add one above.
              </p>
            {/each}
          </div>
        </Tabs.Content>
        <!-- ── Trakt.tv ── -->
        <Tabs.Content value="trakt" class="mt-4 space-y-4">
          {#if traktStatus === undefined}
            <p class="text-sm text-muted-foreground">Loading…</p>
          {:else if traktStatus === null}
            <p class="text-sm text-muted-foreground">
              Trakt is not configured in this build.
            </p>
          {:else if traktStatus.connected}
            <!-- Connected state -->
            <div class="rounded-lg border border-border bg-secondary/30 p-4 space-y-4">
              <p class="text-sm font-medium">
                Connected as <span class="text-primary">{traktStatus.username}</span>
              </p>

              <Separator />

              <div class="flex items-center justify-between">
                <div>
                  <Label for="trakt-scrobble" class="text-sm font-medium">Scrobble</Label>
                  <p class="text-xs text-muted-foreground">
                    Send watch events to Trakt as you watch.
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
                  <Label for="trakt-sync" class="text-sm font-medium">Two-way sync</Label>
                  <p class="text-xs text-muted-foreground">
                    Sync watch history and watchlist with Trakt.
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
                  {traktSyncLoading ? "Syncing…" : "Sync now"}
                </Button>
              {/if}

              <Separator />

              <Button
                variant="outline"
                size="sm"
                onclick={handleTraktDisconnect}
                disabled={traktUnlinkLoading}
              >
                {traktUnlinkLoading ? "Disconnecting…" : "Disconnect"}
              </Button>
            </div>
          {:else}
            <!-- Not connected state -->
            {#if traktFlowState === "idle"}
              <div class="rounded-lg border border-border p-4 space-y-3">
                <Label class="text-sm font-medium">Connect your Trakt account</Label>
                <p class="text-xs text-muted-foreground">
                  Trakt tracks your watch history and watchlist across apps and
                  devices. Scrobbling sends watch events as you play.
                </p>
                {#if traktConnectError}
                  <p class="text-xs text-red-500">{traktConnectError}</p>
                {/if}
                <Button size="sm" onclick={handleTraktConnect}>
                  Connect Trakt account
                </Button>
              </div>
            {:else if traktFlowState === "polling"}
              <!-- Device flow: show code + URL while polling -->
              <div class="rounded-lg border border-border p-4 space-y-4">
                <Label class="text-sm font-medium">Authorize Cove on Trakt</Label>
                <div class="space-y-2">
                  <p class="text-xs text-muted-foreground">1. Open this URL in a browser:</p>
                  <a
                    href={traktFlow?.verification_url}
                    target="_blank"
                    rel="noopener noreferrer"
                    class="text-sm font-medium text-primary hover:underline break-all"
                  >{traktFlow?.verification_url}</a>
                  <p class="text-xs text-muted-foreground">2. Enter this code:</p>
                  <code
                    class="block rounded bg-muted px-4 py-3 text-center text-2xl font-mono tracking-widest font-semibold"
                  >{traktFlow?.user_code}</code>
                </div>
                <p class="text-xs text-muted-foreground">Waiting for authorization…</p>
              </div>
            {:else if traktFlowState === "expired"}
              <div class="rounded-lg border border-border p-4 space-y-3">
                <p class="text-sm text-muted-foreground">
                  The authorization request expired. Start a new one to try again.
                </p>
                <Button size="sm" onclick={handleTraktConnect}>Try again</Button>
              </div>
            {:else if traktFlowState === "denied"}
              <div class="rounded-lg border border-border p-4 space-y-3">
                <p class="text-sm text-muted-foreground">
                  Authorization was denied or is invalid. Start a new request to
                  try again.
                </p>
                <Button size="sm" onclick={handleTraktConnect}>Try again</Button>
              </div>
            {/if}
          {/if}
        </Tabs.Content>
      </Tabs.Root>
    {:else}
      <p class="text-muted-foreground">Loading settings…</p>
    {/if}
  </div>
</ScrollArea>
