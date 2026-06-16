<script lang="ts">
  import { onMount } from "svelte";
  import { settings } from "$lib/stores/settings";
  import type { Settings } from "$lib/types/settings";
  import { Button } from "$lib/components/ui/button";
  import { Label } from "$lib/components/ui/label";
  import { Switch } from "$lib/components/ui/switch/index.js";
  import { Slider } from "$lib/components/ui/slider/index.js";
  import { Separator } from "$lib/components/ui/separator/index.js";
  import * as Select from "$lib/components/ui/select/index.js";

  let draft = $state<Settings | null>(null);
  let saved = $state(false);
  let saveTimer: ReturnType<typeof setTimeout>;

  onMount(async () => {
    await settings.load();
    const unsub = settings.subscribe((v) => {
      if (!draft) draft = { ...v };
    });
    unsub();
  });

  function patch<K extends keyof Settings>(key: K, value: Settings[K]) {
    if (!draft) return;
    draft = { ...draft, [key]: value };
  }

  function handleSave() {
    if (!draft) return;
    settings.save(draft);
    saved = true;
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => (saved = false), 2000);
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

  const PROVIDERS = [
    { value: "torrentio", label: "Torrentio" },
    { value: "debrid", label: "Debrid (Real-Debrid / AllDebrid)" },
  ];

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

  function langLabel(value: string) {
    return LANGUAGES.find((l) => l.value === value)?.label ?? value;
  }

  function providerLabel(value: string) {
    return PROVIDERS.find((p) => p.value === value)?.label ?? value;
  }
</script>

<div class="mx-auto max-w-2xl space-y-8 p-6 pb-16 pt-18">
  <!-- Header -->
  <div class="flex items-center justify-between">
    <h1 class="text-2xl font-semibold tracking-tight">Settings</h1>
    <div class="flex gap-2">
      <Button variant="outline" onclick={handleReset}>Reset</Button>
      <Button onclick={handleSave}>{saved ? "Saved ✓" : "Save"}</Button>
    </div>
  </div>

  {#if draft}
    <!-- ── Playback ── -->
    <section class="space-y-4">
      <h2
        class="text-sm font-semibold tracking-wider text-muted-foreground uppercase"
      >
        Playback
      </h2>

      <div class="space-y-1">
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
      </div>
    </section>

    <!-- ── Streaming ── -->
    <section class="space-y-4">
      <h2
        class="text-sm font-semibold tracking-wider text-muted-foreground uppercase"
      >
        Streaming
      </h2>

      <div class="space-y-1">
        <div class="flex items-center justify-between py-3">
          <div>
            <Label class="text-sm font-medium">Default provider</Label>
            <p class="text-xs text-muted-foreground">
              Which addon to prefer when multiple streams are available.
            </p>
          </div>
          <Select.Root type="single" bind:value={draft.defaultProvider}>
            <Select.Trigger class="w-52">
              {providerLabel(draft.defaultProvider)}
            </Select.Trigger>
            <Select.Content>
              {#each PROVIDERS as p}
                <Select.Item value={p.value}>{p.label}</Select.Item>
              {/each}
            </Select.Content>
          </Select.Root>
        </div>
        <Separator />

        <div class="flex items-center justify-between py-3">
          <div>
            <Label for="prefer-hls" class="text-sm font-medium"
              >Use HLS pipeline</Label
            >
            <p class="text-xs text-muted-foreground">
              Re-mux via ffmpeg before playing. Better seek support, higher CPU
              usage.
            </p>
          </div>
          <Switch
            id="prefer-hls"
            checked={draft.preferHLS}
            onCheckedChange={(v) => patch("preferHLS", v)}
          />
        </div>
      </div>
    </section>

    <!-- ── Subtitles & Audio ── -->
    <section class="space-y-4">
      <h2
        class="text-sm font-semibold tracking-wider text-muted-foreground uppercase"
      >
        Subtitles &amp; Audio
      </h2>

      <div class="space-y-1">
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
            <Select.Trigger class="w-36">
              {langLabel(draft.defaultSubtitleLang)}
            </Select.Trigger>
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
            <Label class="text-sm font-medium">Preferred audio language</Label>
            <p class="text-xs text-muted-foreground">
              Auto-select this audio track when multiple are available.
            </p>
          </div>
          <Select.Root type="single" bind:value={draft.defaultAudioLang}>
            <Select.Trigger class="w-36">
              {langLabel(draft.defaultAudioLang)}
            </Select.Trigger>
            <Select.Content>
              {#each LANGUAGES as l}
                <Select.Item value={l.value}>{l.label}</Select.Item>
              {/each}
            </Select.Content>
          </Select.Root>
        </div>
      </div>
    </section>

    <!-- ── Interface ── -->
    <section class="space-y-4">
      <h2
        class="text-sm font-semibold tracking-wider text-muted-foreground uppercase"
      >
        Interface
      </h2>

      <div class="space-y-1">
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
      </div>
    </section>
  {:else}
    <p class="text-muted-foreground">Loading settings…</p>
  {/if}
</div>
