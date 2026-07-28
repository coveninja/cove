<script lang="ts">
  import { onMount, onDestroy } from "svelte";
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
  import * as m from "$lib/paraglide/messages.js";
  import {
    AUDIO_LANGUAGES,
    LANGUAGES,
    langLabel,
    SettingsController,
  } from "$lib/settingsController.svelte";
  import {
    LOCALES,
    languageDisplayName,
    normalizeAppLocale,
    type AppLocale,
  } from "$lib/i18n";

  // The settings draft, addon management, Nuvio repos, the algorithm test, the
  // speed test and the remote-access token reveal all live in
  // $lib/settingsController.svelte.ts, shared with TvSettingsPage. What stays
  // here is desktop-only: the Trakt device flow and the mpv.conf editor.
  const ctl = new SettingsController();

  onMount(async () => {
    await ctl.init();
    loadTraktStatus();
    loadMpvConf();
  });

  $effect(() => ctl.clearRevealOnTokenReset());

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
        <Button variant="outline" onclick={ctl.handleReset}>{m.common_reset()}</Button>
        <Button onclick={ctl.handleSave}>{ctl.saved ? `${m.common_saved()} ✓` : m.common_save()}</Button>
      </div>
    </div>
    {#if ctl.saveError}
      <p role="alert" class="text-sm text-red-500">{ctl.saveError}</p>
    {/if}

    {#if ctl.draft}
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
              checked={ctl.draft.openOnMute}
              onCheckedChange={(v) => ctl.patch("openOnMute", v)}
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
                value={[ctl.draft.defaultVolume * 100]}
                min={0}
                max={100}
                step={1}
                class="w-32"
                onValueChange={([v]) => ctl.patch("defaultVolume", v / 100)}
              />
              <span
                class="w-9 text-right text-sm text-muted-foreground tabular-nums"
              >
                {Math.round(ctl.draft.defaultVolume * 100)}%
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
              checked={ctl.draft.autoPlay}
              onCheckedChange={(v) => ctl.patch("autoPlay", v)}
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
              checked={ctl.draft.rememberPosition}
              onCheckedChange={(v) => ctl.patch("rememberPosition", v)}
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
                  checked={ctl.draft.autoSkipIntro}
                  onCheckedChange={(v) => ctl.patch("autoSkipIntro", v)}
                />
              </div>
              <div class="flex items-center justify-between">
                <Label for="skip-recap" class="text-sm text-muted-foreground"
                  >{m.settings_skip_recap()}</Label
                >
                <Switch
                  id="skip-recap"
                  checked={ctl.draft.autoSkipRecap}
                  onCheckedChange={(v) => ctl.patch("autoSkipRecap", v)}
                />
              </div>
              <div class="flex items-center justify-between">
                <Label for="skip-credits" class="text-sm text-muted-foreground"
                  >{m.settings_skip_credits()}</Label
                >
                <Switch
                  id="skip-credits"
                  checked={ctl.draft.autoSkipCredits}
                  onCheckedChange={(v) => ctl.patch("autoSkipCredits", v)}
                />
              </div>
              <div class="flex items-center justify-between">
                <Label for="skip-preview" class="text-sm text-muted-foreground"
                  >{m.settings_skip_preview()}</Label
                >
                <Switch
                  id="skip-preview"
                  checked={ctl.draft.autoSkipPreview}
                  onCheckedChange={(v) => ctl.patch("autoSkipPreview", v)}
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
              checked={ctl.draft.autoSelectStream}
              onCheckedChange={(v) => ctl.patch("autoSelectStream", v)}
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
              checked={ctl.draft.prefetchStreams}
              onCheckedChange={(v) => ctl.patch("prefetchStreams", v)}
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
              checked={ctl.draft.prefetchNextEpisode}
              onCheckedChange={(v) => ctl.patch("prefetchNextEpisode", v)}
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
              checked={ctl.draft.allowUploading}
              onCheckedChange={(v) => ctl.patch("allowUploading", v)}
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
              checked={ctl.draft.probeStreams}
              onCheckedChange={(v) => ctl.patch("probeStreams", v)}
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
              checked={ctl.draft.allowLanStreamSources}
              onCheckedChange={(v) => ctl.patch("allowLanStreamSources", v)}
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
                checked={ctl.draft.remoteAccessEnabled}
                onCheckedChange={(v) => ctl.patch("remoteAccessEnabled", v)}
              />
            </div>

            {#if ctl.draft.remoteAccessEnabled}
              <div class="rounded-lg border border-border p-3 space-y-2">
                <Label class="text-xs font-medium text-muted-foreground"
                  >{m.settings_access_token()}</Label
                >
                {#if ctl.draft.remoteAccessToken === ""}
                  <p class="text-xs text-muted-foreground">
                    {m.settings_no_token()}
                  </p>
                {:else}
                  <div class="flex items-center gap-2">
                    <code
                      class="flex-1 truncate rounded bg-muted px-2 py-1 text-xs font-mono"
                    >
                      {ctl.tokenVisible && ctl.revealedToken
                        ? ctl.revealedToken
                        : "•".repeat(32)}
                    </code>
                    <Button
                      variant="outline"
                      size="icon"
                      class="shrink-0"
                      onclick={ctl.handleRevealToken}
                      disabled={ctl.revealingToken}
                      title={ctl.tokenVisible
                        ? m.settings_hide_token()
                        : m.settings_show_token()}
                    >
                      {#if ctl.tokenVisible}
                        <EyeOff class="size-4" />
                      {:else}
                        <Eye class="size-4" />
                      {/if}
                    </Button>
                    <Button
                      variant="outline"
                      size="icon"
                      class="shrink-0"
                      onclick={ctl.handleCopyToken}
                      disabled={ctl.revealingToken}
                      title={m.settings_copy_token()}
                    >
                      {#if ctl.tokenCopied}
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
                  (m) => m.value === ctl.draft.streamSelectionMode,
                )?.description ?? ""}
              </p>
            </div>
            <Select.Root type="single" bind:value={ctl.draft.streamSelectionMode}>
              <Select.Trigger class="w-56 shrink-0">
                {STREAM_SELECTION_MODES.find(
                  (m) => m.value === ctl.draft.streamSelectionMode,
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
                {ctl.draft.sourcePreference
                  ? m.settings_source_boost_description()
                  : m.settings_source_neutral_description()}
              </p>
            </div>
            <Select.Root type="single" bind:value={ctl.draft.sourcePreference}>
              <Select.Trigger class="w-56 shrink-0">
                {SOURCE_PREFERENCES.find(
                  (p) => p.value === ctl.draft.sourcePreference,
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
                {#if ctl.providerAddons.length === 0 && ctl.nuvioProviderOptions.length === 0}
                  {m.settings_provider_missing()}
                {:else}
                  {m.settings_provider_description()}
                {/if}
              </p>
            </div>
            <Select.Root
              type="single"
              bind:value={ctl.draft.defaultProvider}
              disabled={ctl.providerAddons.length === 0 &&
                ctl.nuvioProviderOptions.length === 0}
            >
              <Select.Trigger class="w-56 shrink-0">
                {ctl.draft.defaultProvider || m.common_no_preference()}
              </Select.Trigger>
              <Select.Content>
                <Select.Item value="">{m.common_no_preference()}</Select.Item>
                {#each ctl.providerAddons as a (a.url || a.id)}
                  <Select.Item value={a.manifest.name}
                    >{a.manifest.name}</Select.Item
                  >
                {/each}
                {#each ctl.nuvioProviderOptions as name (name)}
                  <Select.Item value={name}>{name}</Select.Item>
                {/each}
              </Select.Content>
            </Select.Root>
          </div>
          <Separator />

          <div class="flex items-center justify-between py-3">
            <div class="pr-4">
              <Label class="text-sm font-medium">{m.settings_connection_speed()}</Label>
              {#if ctl.draft.measuredBandwidthMbps > 0}
                <p class="text-xs text-muted-foreground">
                  {m.settings_speed_measured({
                    speed: ctl.draft.measuredBandwidthMbps,
                  })}
                </p>
              {:else}
                <p class="text-xs text-muted-foreground">
                  {m.settings_speed_unmeasured()}
                </p>
              {/if}
              {#if ctl.speedTestError}
                <p class="text-xs text-red-500">{ctl.speedTestError}</p>
              {/if}
            </div>
            <Button
              variant="outline"
              size="sm"
              class="shrink-0"
              onclick={ctl.runSpeedTest}
              disabled={ctl.testingSpeed}
            >
              {ctl.testingSpeed
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
              checked={ctl.draft.subtitlesEnabled}
              onCheckedChange={(v) => ctl.patch("subtitlesEnabled", v)}
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
            <Select.Root type="single" bind:value={ctl.draft.defaultSubtitleLang}>
              <Select.Trigger class="w-36"
                >{langLabel(ctl.draft.defaultSubtitleLang)}</Select.Trigger
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
                value={[ctl.draft.subtitleSize]}
                min={50}
                max={200}
                step={10}
                class="w-32"
                onValueChange={([v]) => ctl.patch("subtitleSize", v)}
              />
              <span
                class="w-9 text-right text-sm text-muted-foreground tabular-nums"
              >
                {Math.round(ctl.draft.subtitleSize)}%
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
                value={[ctl.draft.subtitlePosition]}
                min={2}
                max={90}
                step={1}
                class="w-32"
                onValueChange={([v]) => ctl.patch("subtitlePosition", v)}
              />
              <span
                class="w-9 text-right text-sm text-muted-foreground tabular-nums"
              >
                {Math.round(ctl.draft.subtitlePosition)}%
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
              checked={ctl.draft.subtitleBackground}
              onCheckedChange={(v) => ctl.patch("subtitleBackground", v)}
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
            <Select.Root type="single" bind:value={ctl.draft.defaultAudioLang}>
              <Select.Trigger class="w-36"
                >{langLabel(ctl.draft.defaultAudioLang)}</Select.Trigger
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
              value={normalizeAppLocale(ctl.draft.uiLanguage) ?? "en"}
              onValueChange={(value) => ctl.patch("uiLanguage", value as AppLocale)}
            >
              <Select.Trigger class="w-44 shrink-0">
                {LOCALES.find((locale) => locale.appLocale === (normalizeAppLocale(ctl.draft.uiLanguage) ?? "en"))?.nativeName}
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
              checked={ctl.draft.showStreamDetails}
              onCheckedChange={(v) => ctl.patch("showStreamDetails", v)}
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
              checked={ctl.draft.hideSpoilers}
              onCheckedChange={(v) => ctl.patch("hideSpoilers", v)}
            />
          </div>

          <Separator class="my-2" />

          <div class="flex items-center justify-between py-3">
            <div class="pr-4">
              <Label class="text-sm font-medium">{m.settings_discovery_algorithm()}</Label>
              <p class="text-xs text-muted-foreground">
                {DISCOVERY_ALGORITHMS.find(
                  (a) => a.value === ctl.draft.discoveryAlgorithm,
                )?.description ?? ""}
              </p>
            </div>
            <Select.Root type="single" bind:value={ctl.draft.discoveryAlgorithm}>
              <Select.Trigger class="w-56 shrink-0">
                {DISCOVERY_ALGORITHMS.find(
                  (a) => a.value === ctl.draft.discoveryAlgorithm,
                )?.label ?? m.common_choose()}
              </Select.Trigger>
              <Select.Content>
                {#each DISCOVERY_ALGORITHMS as a (a.value)}
                  <Select.Item value={a.value}>{a.label}</Select.Item>
                {/each}
              </Select.Content>
            </Select.Root>
          </div>

          {#if ctl.draft.discoveryAlgorithm === "custom"}
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
                  bind:value={ctl.draft.customAlgorithmUrl}
                  class="flex-1"
                />
                <Button
                  variant="outline"
                  onclick={ctl.handleTestAlgorithm}
                  disabled={ctl.testingAlgorithm ||
                    !ctl.draft.customAlgorithmUrl.trim()}
                  size="sm"
                >
                  {ctl.testingAlgorithm ? m.common_testing() : m.common_test_connection()}
                </Button>
              </div>
              {#if ctl.algorithmTestResult}
                <p
                  class="mt-2 text-xs {ctl.algorithmTestResult.ok
                    ? 'text-green-500'
                    : 'text-red-500'}"
                >
                  {ctl.algorithmTestResult.ok
                    ? m.common_connected_success()
                    : m.common_failed_message({ error: ctl.algorithmTestResult.error })}
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
                checked={ctl.autoUpdateEnabled}
                onCheckedChange={(v) => {
                  ctl.autoUpdateEnabled = v;
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
                bind:value={ctl.addAddonUrl}
                class="flex-1"
                onkeydown={(e) => e.key === "Enter" && ctl.handleAddAddon()}
              />
              <Button
                onclick={ctl.handleAddAddon}
                disabled={ctl.addAddonLoading || !ctl.addAddonUrl.trim()}
                size="sm"
              >
                <Plus class="mr-1 size-4" />
                {ctl.addAddonLoading ? m.common_adding() : m.common_add()}
              </Button>
            </div>
            {#if ctl.addAddonError}
              <p class="mt-2 text-xs text-red-500">{ctl.addAddonError}</p>
            {/if}
          </div>

          <!-- Addon list -->
          <div class="space-y-2">
            {#each ctl.addons as addon (addon.url || addon.id)}
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
                    onCheckedChange={() => ctl.handleToggleAddon(addon)}
                    class="shrink-0"
                  />

                  <!-- Configure / Refresh / Remove (stremio only) -->
                  {#if addon.source !== SourceOfficial}
                    {#if addon.manifest.behaviorHints?.configurable}
                      <Button
                        variant="ghost"
                        size="icon"
                        class="shrink-0 text-muted-foreground"
                        onclick={() => (ctl.configureAddon = addon)}
                        title={m.common_configure()}
                      >
                        <Cog class="size-4" />
                      </Button>
                    {/if}
                    <Button
                      variant="ghost"
                      size="icon"
                      class="shrink-0 text-muted-foreground"
                      onclick={() => ctl.handleRefreshAddon(addon)}
                      disabled={ctl.refreshingAddonId === addon.id}
                      title={m.common_refresh()}
                    >
                      <RefreshCw
                        class={`size-4 ${ctl.refreshingAddonId === addon.id ? "animate-spin" : ""}`}
                      />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      class="shrink-0 text-muted-foreground hover:text-destructive"
                      onclick={() => ctl.handleRemoveAddon(addon)}
                      title={m.common_remove()}
                    >
                      <Trash2 class="size-4" />
                    </Button>
                  {/if}
                </div>

                <!-- Per-catalog toggles (only for ctl.addons that declare catalogs) -->
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
                            ctl.handleToggleCatalog(addon, key, v)}
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
                bind:value={ctl.addRepoUrl}
                class="flex-1"
                onkeydown={(e) => e.key === "Enter" && ctl.handleAddRepo()}
              />
              <Button
                onclick={ctl.handleAddRepo}
                disabled={ctl.addRepoLoading || !ctl.addRepoUrl.trim()}
                size="sm"
              >
                <Plus class="mr-1 size-4" />
                {ctl.addRepoLoading ? m.common_adding() : m.common_add()}
              </Button>
            </div>
            {#if ctl.addRepoError}
              <p class="mt-2 text-xs text-red-500">{ctl.addRepoError}</p>
            {/if}
          </div>

          <!-- Repo list -->
          <div class="space-y-3">
            {#each ctl.nuvioRepos as repo (repo.id)}
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
                    onclick={() => ctl.handleRefreshRepo(repo)}
                    disabled={ctl.refreshingRepoId === repo.id}
                    title={m.settings_refresh_manifest()}
                  >
                    <RefreshCw
                      class={`size-4 ${ctl.refreshingRepoId === repo.id ? "animate-spin" : ""}`}
                    />
                  </Button>

                  <Switch
                    checked={repo.enabled}
                    onCheckedChange={() => ctl.handleToggleRepo(repo)}
                    class="shrink-0"
                    title={m.settings_enable_repository()}
                  />

                  <Button
                    variant="ghost"
                    size="icon"
                    class="shrink-0 text-muted-foreground hover:text-destructive"
                    onclick={() => ctl.handleRemoveRepo(repo)}
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

                      {#if ctl.pendingConfirm?.repoId === repo.id && ctl.pendingConfirm?.scraperId === scraper.id}
                        <div class="flex shrink-0 items-center gap-2">
                          <span class="text-xs text-amber-400"
                            >{m.settings_run_third_party({
                              repository: `${repo.owner}/${repo.repo}`,
                            })}</span
                          >
                          <Button
                            size="sm"
                            variant="outline"
                            onclick={() => (ctl.pendingConfirm = null)}
                            >{m.common_cancel()}</Button
                          >
                          <Button
                            size="sm"
                            onclick={() =>
                              ctl.handleSetScraperEnabled(repo, scraper, true)}
                            >{m.common_enable()}</Button
                          >
                        </div>
                      {:else}
                        <Switch
                          checked={scraper.enabled}
                          onCheckedChange={() =>
                            ctl.requestEnableScraper(repo, scraper)}
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
                  checked={ctl.draft.traktScrobbleEnabled}
                  onCheckedChange={(v) => ctl.patch("traktScrobbleEnabled", v)}
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
                  checked={ctl.draft.traktSyncEnabled}
                  onCheckedChange={(v) => ctl.patch("traktSyncEnabled", v)}
                />
              </div>

              {#if ctl.draft.traktSyncEnabled}
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

{#if ctl.configureAddon}
  <!-- Configure addon overlay -->
  <div
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
    role="presentation"
    onclick={(e) => {
      if (e.target === e.currentTarget) ctl.configureAddon = null;
    }}
    onkeydown={(e) => {
      if (e.key === "Escape") ctl.configureAddon = null;
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
          {ctl.configureAddon.manifest.name || ctl.configureAddon.url}
        </span>
        <button
          type="button"
          class="ml-3 shrink-0 rounded p-1 text-muted-foreground hover:text-foreground"
          onclick={() => (ctl.configureAddon = null)}
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
          src={`${ctl.configureAddon.url}/configure`}
          class="h-full w-full rounded border border-border"
          title={m.settings_addon_configuration()}
        ></iframe>
      </div>

      <!-- Fallback link -->
      <div class="shrink-0 px-4 pb-3 text-xs text-muted-foreground">
        <a
          href={`${ctl.configureAddon.url}/configure`}
          target="_blank"
          rel="noopener noreferrer"
          class="text-primary underline">{m.common_open_browser()}</a
        >
        {m.settings_addon_fallback()}
      </div>
    </div>
  </div>
{/if}
