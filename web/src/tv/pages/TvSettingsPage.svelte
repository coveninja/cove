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
  import { TriangleAlert, RefreshCw, Trash2 } from "lucide-svelte";

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
    { id: "playback", label: "Playback" },
    { id: "streaming", label: "Streaming" },
    { id: "subtitles", label: "Subtitles & Audio" },
    { id: "interface", label: "Interface" },
    { id: "addons", label: "Addons" },
    { id: "plugins", label: "Plugins" },
  ];

  // ── Settings draft ────────────────────────────────────────────────────────────
  let draft = $state<Settings | null>(null);
  let saved = $state(false);
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
    saved = true;
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => (saved = false), 2000);
    await settings.save(draft);
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
    addons = await api.getAddons();
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
    nuvioRepos = await api.getNuvioRepos();
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

  const AUDIO_LANGUAGES = [
    { value: "original", label: "Original" },
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
    aria-label="Settings sections"
  >
    <p class="mb-3 px-4 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
      Settings
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
            Reset
          </button>
          <button
            type="button"
            use:focusable={{ groupId: "settings-save" }}
            onclick={handleSave}
            class="rounded-xl bg-accent px-6 py-3 text-base font-semibold text-accent-foreground
                   transition-colors hover:bg-accent/90
                   focus:outline-none focus:ring-2 focus:ring-accent"
          >
            {saved ? "Saved ✓" : "Save"}
          </button>
        </div>
      {/if}
    </div>

    <!-- Scrollable section content — column focusGroup; native elements auto-discovered -->
    <div
      class="flex-1 min-h-0 overflow-y-auto scrollbar-none [&::-webkit-scrollbar]:hidden px-8 pb-16"
      use:focusGroup={{ id: "settings-content", policy: { type: "column" }, rememberFocus: false }}
    >
      {#if draft}

        <!-- ══ Playback ══ -->
        <div class:hidden={activeSection !== "playback"} class="pt-4">

          <!-- Open videos muted -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">Open videos muted</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Start every video with audio muted.</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.openOnMute}
              aria-label="Toggle open videos muted"
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
              <p class="text-lg font-medium">Default volume</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Initial volume level (5% steps).</p>
            </div>
            <div class="flex shrink-0 items-center gap-2">
              <button
                type="button"
                onclick={() => patch("defaultVolume", Math.max(0, draft!.defaultVolume - 0.05))}
                aria-label="Decrease volume"
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
                aria-label="Increase volume"
                class="flex h-12 w-12 items-center justify-center rounded-xl bg-secondary text-xl font-bold
                       transition-colors hover:bg-secondary/70
                       focus:outline-none focus:ring-2 focus:ring-accent"
              >+</button>
            </div>
          </div>

          <!-- Autoplay next episode -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">Autoplay next episode</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Automatically start the next episode when one finishes.</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.autoPlay}
              aria-label="Toggle autoplay next episode"
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
              <p class="text-lg font-medium">Remember position</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Resume from where you left off.</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.rememberPosition}
              aria-label="Toggle remember position"
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
            <p class="text-lg font-medium">Auto-skip segments</p>
            <p class="mt-0.5 text-sm leading-snug text-muted-foreground">
              Automatically skip segments when timestamps are available via IntroDB.
              A skip button always appears when inside a segment.
            </p>
          </div>

          <!-- Skip intro -->
          <div class="flex min-h-[68px] items-center justify-between gap-8 border-b border-border/40 py-4 pl-4">
            <p class="text-base font-medium text-muted-foreground">Skip intro</p>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.autoSkipIntro}
              aria-label="Toggle skip intro"
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
            <p class="text-base font-medium text-muted-foreground">Skip recap</p>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.autoSkipRecap}
              aria-label="Toggle skip recap"
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
            <p class="text-base font-medium text-muted-foreground">Skip credits</p>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.autoSkipCredits}
              aria-label="Toggle skip credits"
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
            <p class="text-base font-medium text-muted-foreground">Skip preview</p>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.autoSkipPreview}
              aria-label="Toggle skip preview"
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
              <p class="text-lg font-medium">Automatically pick best stream</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Skip the stream list — start playing immediately when you press Watch.</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.autoSelectStream}
              aria-label="Toggle auto-select stream"
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
              <p class="text-lg font-medium">Prefetch streams</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Pre-fetch streams for shows you're likely to watch next.</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.prefetchStreams}
              aria-label="Toggle prefetch streams"
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
              <p class="text-lg font-medium">Pre-download next episode</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">When an episode finishes downloading, quietly download the next one so it starts instantly.</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.prefetchNextEpisode}
              aria-label="Toggle pre-download next episode"
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
              <p class="text-lg font-medium">Upload while streaming</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Share downloaded pieces back to peers (max 1 MiB/s). Improves download speed; turn off to never upload.</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.allowUploading}
              aria-label="Toggle upload while streaming"
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
              <p class="text-lg font-medium">Verify streams before auto-selecting</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Checks that top candidates are reachable before playback starts. Adds a brief delay but avoids picking dead links.</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.probeStreams}
              aria-label="Toggle verify streams before auto-selecting"
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
              <p class="text-lg font-medium">Allow LAN stream sources</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Lets streams play from private/LAN addresses. Only enable if you run a stream source on your own network.</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.allowLanStreamSources}
              aria-label="Toggle allow LAN stream sources"
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
                <p class="text-lg font-medium">Remote access</p>
                <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Opens a LAN port (6970) so the mobile app or other devices on your network can connect to this backend.</p>
              </div>
                            <button
                type="button"
                role="switch"
                aria-checked={draft.remoteAccessEnabled}
                aria-label="Toggle remote access"
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
                <p class="mb-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">Access token</p>
                {#if draft.remoteAccessToken === ""}
                  <p class="text-sm text-muted-foreground">No token set — save settings to generate one.</p>
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
                      {revealingToken ? "Loading…" : tokenVisible ? "Hide" : "Show"}
                    </button>
                    <button
                      type="button"
                      onclick={handleCopyToken}
                      disabled={revealingToken}
                      class="rounded-xl bg-secondary px-5 py-2.5 text-sm font-medium text-muted-foreground
                             transition-colors hover:text-foreground disabled:opacity-50
                             focus:outline-none focus:ring-2 focus:ring-accent"
                    >
                      {tokenCopied ? "Copied ✓" : "Copy"}
                    </button>
                  </div>
                {/if}
              </div>
            {/if}
          </div>

          <!-- Selection strategy -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">Selection strategy</p>
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
              <p class="text-lg font-medium">Source preference</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">
                {draft.sourcePreference
                  ? "Gives torrents or direct streams a small ranking boost — doesn't exclude the other kind."
                  : "No boost — torrents and direct streams rank purely on the selection strategy above."}
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
              <p class="text-lg font-medium">Preferred provider</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">
                {#if providerAddons.length === 0 && nuvioProviderOptions.length === 0}
                  Add a provider addon in the Addons section to set a preference.
                {:else}
                  Its streams are shown first and favored by auto-select.
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
              <option value="" selected={!draft.defaultProvider}>No preference</option>
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
              <p class="text-lg font-medium">Connection speed</p>
              {#if draft.measuredBandwidthMbps > 0}
                <p class="mt-0.5 text-sm leading-snug text-muted-foreground">
                  Last measured at {draft.measuredBandwidthMbps} Mbps. Used by "Match My Internet Speed".
                </p>
              {:else}
                <p class="mt-0.5 text-sm leading-snug text-muted-foreground">
                  Not measured yet — needed for "Match My Internet Speed".
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
              {testingSpeed ? "Testing…" : "Test My Speed"}
            </button>
          </div>

        </div><!-- /streaming -->

        <!-- ══ Subtitles & Audio ══ -->
        <div class:hidden={activeSection !== "subtitles"} class="pt-4">

          <!-- Enable subtitles by default -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">Enable subtitles by default</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Show subtitles automatically when available.</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.subtitlesEnabled}
              aria-label="Toggle subtitles enabled"
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
              <p class="text-lg font-medium">Preferred subtitle language</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Auto-select this language when subtitles are available.</p>
            </div>
            <select
              onchange={(e) => patch("defaultSubtitleLang", (e.currentTarget as HTMLSelectElement).value)}
              class="shrink-0 rounded-xl bg-secondary px-4 py-3 text-base text-foreground
                     focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {#each LANGUAGES as l (l.value)}
                <option value={l.value} selected={l.value === draft.defaultSubtitleLang}>{l.label}</option>
              {/each}
            </select>
          </div>

          <!-- Preferred audio language -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">Preferred audio language</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Auto-select this audio track when multiple are available.</p>
            </div>
            <select
              onchange={(e) => patch("defaultAudioLang", (e.currentTarget as HTMLSelectElement).value)}
              class="shrink-0 rounded-xl bg-secondary px-4 py-3 text-base text-foreground
                     focus:outline-none focus:ring-2 focus:ring-accent"
            >
              {#each AUDIO_LANGUAGES as l (l.value)}
                <option value={l.value} selected={l.value === draft.defaultAudioLang}>{l.label}</option>
              {/each}
            </select>
          </div>

        </div><!-- /subtitles -->

        <!-- ══ Interface ══ -->
        <div class:hidden={activeSection !== "interface"} class="pt-4">

          <!-- Show stream details -->
          <div class="flex min-h-[76px] items-center justify-between gap-8 border-b border-border/40 py-5">
            <div class="min-w-0 flex-1">
              <p class="text-lg font-medium">Show stream details</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Display codec, resolution, and size badges on the stream list.</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.showStreamDetails}
              aria-label="Toggle show stream details"
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
              <p class="text-lg font-medium">Hide spoilers</p>
              <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Hide not-seen episode thumbnails, descriptions, and episode names.</p>
            </div>
                        <button
              type="button"
              role="switch"
              aria-checked={draft.hideSpoilers}
              aria-label="Toggle hide spoilers"
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
              <p class="text-lg font-medium">Discovery algorithm</p>
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
              <p class="mb-1 text-lg font-medium">Custom algorithm URL</p>
              <p class="mb-3 text-sm leading-snug text-muted-foreground">
                Cove POSTs your taste profile and a pre-filtered candidate list to this URL
                and expects relevance scores back. Falls back to Cove Smart if the endpoint
                is unreachable or errors.
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
                  {testingAlgorithm ? "Testing…" : "Test connection"}
                </button>
              </div>
              {#if algorithmTestResult}
                <p class="mt-2 text-sm {algorithmTestResult.ok ? 'text-green-400' : 'text-red-400'}">
                  {algorithmTestResult.ok
                    ? "Connected successfully."
                    : `Failed: ${algorithmTestResult.error}`}
                </p>
              {/if}
            </div>
          {/if}

          <!-- Auto-update (Android / Android TV only) -->
          {#if isAndroid() || isAndroidTV()}
            <div class="flex min-h-[76px] items-center justify-between gap-8 py-5">
              <div class="min-w-0 flex-1">
                <p class="text-lg font-medium">Auto-update</p>
                <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Automatically download and install updates on launch.</p>
              </div>
              <button
                type="button"
                role="switch"
                aria-label="Toggle auto-update"
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
                <p class="text-lg font-medium">Desktop interface</p>
                <p class="mt-0.5 text-sm leading-snug text-muted-foreground">Return to the mouse-and-keyboard desktop interface.</p>
              </div>
              <button
                type="button"
                onclick={() => setTvMode(false)}
                class="shrink-0 rounded-xl bg-secondary px-5 py-3 text-base font-medium text-muted-foreground
                       transition-colors hover:text-foreground
                       focus:outline-none focus:ring-2 focus:ring-accent"
              >
                Switch to desktop interface
              </button>
            </div>
          {/if}

        </div><!-- /interface -->

        <!-- ══ Addons ══ -->
        <div class:hidden={activeSection !== "addons"} class="space-y-6 pt-4">

          <!-- Add new addon -->
          <div class="rounded-2xl bg-secondary/30 p-5">
            <p class="mb-1 text-lg font-medium">Add Stremio addon</p>
            <p class="mb-4 text-sm leading-snug text-muted-foreground">Paste a Stremio-compatible addon manifest URL.</p>
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
                {addAddonLoading ? "Adding…" : "Add"}
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
                        {addon.manifest.name || addon.url || addon.id || "Unknown addon"}
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
                          ? "Provider"
                          : addon.kind === KindTimestamps
                            ? "Timestamps"
                            : "Subtitles"}
                      </span>
                      {#if addon.source === SourceOfficial}
                        <span class="rounded-full bg-green-500/20 px-2.5 py-0.5 text-xs font-medium text-green-400">
                          Built-in
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
                    aria-label="Toggle addon enabled"
                    aria-checked={addon.enabled}
                    onclick={() => handleToggleAddon(addon)}
                    class="relative inline-flex h-9 w-16 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200
                           focus:outline-none focus:ring-2 focus:ring-accent focus:ring-offset-2 focus:ring-offset-background
                           {trackBg(addon.enabled)}"
                  >
                    <span class="pointer-events-none inline-block h-7 w-7 transform rounded-full bg-white shadow transition-transform {thumb(addon.enabled)}"></span>
                  </button>

                  <!-- Remove button (Stremio addons only) -->
                  {#if addon.source !== SourceOfficial}
                    <button
                      type="button"
                      onclick={() => handleRemoveAddon(addon)}
                      title="Remove addon"
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
                          aria-label="Toggle catalog enabled"
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
                No addons yet. Add a Stremio-compatible addon above.
              </p>
            {/each}
          </div>

        </div><!-- /addons -->

        <!-- ══ Plugins (Nuvio) ══ -->
        <div class:hidden={activeSection !== "plugins"} class="space-y-6 pt-4">

          <!-- Third-party code warning -->
          <div class="flex items-start gap-3 rounded-2xl border border-amber-500/30 bg-amber-500/10 p-4 text-sm text-amber-400">
            <TriangleAlert class="mt-0.5 size-5 shrink-0" />
            <p class="leading-snug">
              Plugins are community-maintained JavaScript stream scrapers. Cove runs this code
              on your device to find streams — it has not been reviewed by Cove and its safety
              and quality vary by repository. Nothing runs until you explicitly enable a scraper below.
            </p>
          </div>

          <!-- Add new repository -->
          <div class="rounded-2xl bg-secondary/30 p-5">
            <p class="mb-1 text-lg font-medium">Add plugin repository</p>
            <p class="mb-4 text-sm leading-snug text-muted-foreground">
              Paste a GitHub repository URL (e.g. github.com/owner/repo) that publishes a manifest.json.
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
                {addRepoLoading ? "Adding…" : "Add"}
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
                        {repo.scrapers.length} scraper{repo.scrapers.length === 1 ? "" : "s"}
                      </span>
                    </div>
                    {#if repo.fetchErr}
                      <p class="mt-0.5 text-xs text-red-400">Last refresh failed: {repo.fetchErr}</p>
                    {/if}
                  </div>

                  <!-- Refresh button -->
                  <button
                    type="button"
                    onclick={() => handleRefreshRepo(repo)}
                    disabled={refreshingRepoId === repo.id}
                    title="Refresh manifest"
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
                    aria-label="Toggle repository enabled"
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
                    title="Remove repository"
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
                            Run third-party JS from {repo.owner}/{repo.repo}?
                          </span>
                          <button
                            type="button"
                            onclick={() => (pendingConfirm = null)}
                            class="rounded-xl bg-secondary px-4 py-2 text-sm font-medium text-muted-foreground
                                   transition-colors hover:text-foreground
                                   focus:outline-none focus:ring-2 focus:ring-accent"
                          >
                            Cancel
                          </button>
                          <button
                            type="button"
                            onclick={() => handleSetScraperEnabled(repo, scraper, true)}
                            class="rounded-xl bg-accent px-4 py-2 text-sm font-semibold text-accent-foreground
                                   transition-colors hover:bg-accent/90
                                   focus:outline-none focus:ring-2 focus:ring-accent"
                          >
                            Enable
                          </button>
                        </div>
                      {:else}
                        <!-- Normal scraper toggle -->
                        {@const scraperOn = scraper.enabled}
                        <button
                          type="button"
                          role="switch"
                          aria-label="Toggle scraper enabled"
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
                      No scrapers found in this repository's manifest.
                    </p>
                  {/each}
                </div>
              </div>
            {:else}
              <p class="py-6 text-center text-base text-muted-foreground">
                No plugin repositories yet. Add one above.
              </p>
            {/each}
          </div>

        </div><!-- /plugins -->

      {:else}
        <p class="py-16 text-center text-lg text-muted-foreground">Loading settings…</p>
      {/if}
    </div><!-- /settings-content -->
  </div><!-- /right panel -->
</div>
