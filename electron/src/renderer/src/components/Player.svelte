<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import { Spinner } from "$lib/components/ui/spinner";
  import * as Popover from "$lib/components/ui/popover";
  import {
    Settings,
    Headphones,
    ChevronLeft,
    Play,
    Pause,
    Volume2,
    VolumeOff,
    Maximize,
    Minimize,
  } from "lucide-svelte";
  import "vidstack/bundle";
  import "vidstack/player/styles/base.css";
  import "vidstack/player/styles/default/theme.css";
  import "vidstack/player/styles/default/layouts/video.css";
  import Hls from "hls.js";

  // ─── Types ─────────────────────────────────────────────────────────────────

  type AudioTrackInfo = {
    index: number;
    language: string;
    title: string;
    codec: string;
  };

  type SubtitleTrackInfo = {
    index: number;
    language: string;
    title: string;
    codec: string;
  };

  type SubtitleSettings = {
    size: number;
    line: number;
    background: boolean;
    offset: number;
  };

  const UNSUPPORTED_AUDIO_CODECS = new Set([
    "ac3",
    "eac3",
    "dts",
    "truehd",
    "mlp",
  ]);

  // ─── Props ──────────────────────────────────────────────────────────────────

  let {
    src,
    media,
    externalSubtitles = [],
  }: {
    src: string;
    media?: Media;
    externalSubtitles?: { id: string; url: string; lang: string }[];
  } = $props();

  // ─── Audio tracks (from probe, used to decide HLS vs direct) ────────────────

  let audioTracks = $state<AudioTrackInfo[]>([]);

  // ─── Built-in subtitle tracks (from probe) ───────────────────────────────────

  let builtInSubtitles = $state<
    { id: string; url: string; lang: string; label: string }[]
  >([]);

  // ─── Vidstack audio track list (populated from HLS manifest) ────────────────

  let vidstackAudioTracks = $state<
    { id: string; label: string; language: string; selected: boolean }[]
  >([]);

  // ─── DOM refs ───────────────────────────────────────────────────────────────

  let playerEl = $state<any>(null);

  // ─── Source derivations ─────────────────────────────────────────────────────

  const isHash = $derived(!src.startsWith("http"));

  const baseInput = $derived(
    isHash ? `http://localhost:6969/api/play?hash=${src}` : src,
  );

  const needsHLS = $derived(
    audioTracks.length > 1 ||
      audioTracks.some((t) =>
        UNSUPPORTED_AUDIO_CODECS.has(t.codec.toLowerCase()),
      ),
  );

  // ─── Playback state (driven by Vidstack events) ─────────────────────────────

  let canPlay = $state(false);
  let waiting = $state(false);
  let error = $state<string | null>(null);
  let fakeProgress = $state(0);
  let probedDuration = $state<number | null>(null);

  // ─── HLS session ────────────────────────────────────────────────────────────

  let hlsSessionID = $state<string | null>(null);
  let hlsLoading = $state(false); // true while waiting for POST /api/hls/start

  const activeStreamURL = $derived.by(() => {
    if (needsHLS && hlsSessionID) {
      return `http://localhost:6969/api/hls/${hlsSessionID}/master.m3u8`;
    }
    if (!needsHLS && audioTracks.length > 0) {
      // No ffmpeg needed, stream directly
      return isHash ? `http://localhost:6969/api/play?hash=${src}` : src;
    }
    return null; // not ready yet (probe still running)
  });

  // ─── Subtitle fine-tuning ───────────────────────────────────────────────────

  let subtitleSettings = $state<SubtitleSettings>({
    size: 100,
    line: 85,
    background: true,
    offset: 0,
  });
  let subtitleSettingsOpen = $state(false);
  let subtitleView = $state<"tracks" | "settings">("tracks");

  // ─── Other state ────────────────────────────────────────────────────────────

  let logoUrl = $state<string | null>(null);
  let torrentProgress = $state(0);
  let peers = $state(0);
  let torrentSpeed = $state("0 B/s");

  const title = $derived(
    media ? (media.media_type === "tv" ? media.name : media.title) : "",
  );

  // ─── Probe audio tracks ─────────────────────────────────────────────────────

  $effect(() => {
    if (!src) return () => {};
    audioTracks = [];
    builtInSubtitles = [];
    hlsSessionID = null;
    canPlay = false;
    error = null;

    const probeURL = isHash
      ? `http://localhost:6969/api/probe?hash=${src}`
      : `http://localhost:6969/api/probe?url=${encodeURIComponent(src)}`;

    const controller = new AbortController();
    fetch(probeURL, { signal: controller.signal })
      .then((r) => r.json())
      .then(
        (data: {
          audio: AudioTrackInfo[];
          subtitles: SubtitleTrackInfo[];
          duration: number;
        }) => {
          audioTracks = data.audio ?? [];
          probedDuration = data.duration ?? null;

          const base = isHash
            ? `http://localhost:6969/api/subtitle/extract?hash=${src}`
            : `http://localhost:6969/api/subtitle/extract?url=${encodeURIComponent(src)}`;

          builtInSubtitles = (data.subtitles ?? []).map((t) => ({
            id: `builtin-${t.index}`,
            url: `${base}&index=${t.index}`,
            lang: t.language || `track${t.index}`,
            label:
              t.title ||
              (t.language ? langName(t.language) : `Track ${t.index + 1}`),
          }));
        },
      )
      .catch(() => {
        audioTracks = [];
        builtInSubtitles = [];
        probedDuration = null;
      });

    return () => controller.abort();
  });

  // ─── Start HLS session when needed ──────────────────────────────────────────

  $effect(() => {
    if (!needsHLS || audioTracks.length === 0 || probedDuration === null) return;

    hlsLoading = true;
    hlsSessionID = null;

    fetch("http://localhost:6969/api/hls/start", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        input: baseInput,
        tracks: audioTracks,
        duration: probedDuration,
      }),
    })
      .then((r) => r.json())
      .then((d: { sessionID: string }) => {
        hlsSessionID = d.sessionID;
      })
      .catch((e) => {
        error = "Failed to start HLS session.";
        console.error(e);
      })
      .finally(() => {
        hlsLoading = false;
      });
  });

  // ─── Update Vidstack source when activeStreamURL changes ────────────────────

  $effect(() => {
    if (!playerEl) return () => {};

    function onProviderChange(e: any): void {
      if (e.detail?.type === "hls") {
        e.detail.library = Hls;
        e.detail.config = {
          maxBufferLength: 60,
          maxMaxBufferLength: 120,
          maxBufferSize: 60000000,
          appendErrorMaxRetry: 10,
          fragLoadingMaxRetry: 10,
          fragLoadingTimeOut: 60000,
          manifestLoadingTimeOut: 60000,
          levelLoadingTimeOut: 60000,
        };

        e.detail.addEventListener?.("hls-instance", (ev: any) => {
          const hls = ev.detail;
          hls.on(Hls.Events.ERROR, (_, data) => { /* recovery */ });
        });
      }
    }

    playerEl.addEventListener("provider-change", onProviderChange);
    playerEl.addEventListener("can-play", onCanPlay);
    playerEl.addEventListener("waiting", onWaiting);
    playerEl.addEventListener("playing", onPlaying);
    playerEl.addEventListener("media-error", onError);
    playerEl.addEventListener("audio-track-change", onAudioTrackChange);
    playerEl.addEventListener("text-track-change", syncTextTracks);

    return () => {
      playerEl.removeEventListener("provider-change", onProviderChange);
      playerEl.removeEventListener("can-play", onCanPlay);
      playerEl.removeEventListener("waiting", onWaiting);
      playerEl.removeEventListener("playing", onPlaying);
      playerEl.removeEventListener("media-error", onError);
      playerEl.removeEventListener("audio-track-change", onAudioTrackChange);
      playerEl.removeEventListener("text-track-change", syncTextTracks);
    };
  });

  $effect(() => {
    if (!playerEl || !activeStreamURL) return;
    canPlay = false;
    fakeProgress = 0;
    error = null;

    playerEl.src = activeStreamURL;

    // Inject built-in and external subtitle tracks
    playerEl.textTracks.clear?.();
    for (const sub of builtInSubtitles) {
      // built-in subtitles are served directly from /api/subtitle/extract (already VTT)
      playerEl.textTracks.add({
        kind: "subtitles",
        src: sub.url,
        srclang: sub.lang,
        label: sub.label,
      });
    }
    for (const sub of externalSubtitles) {
      // external subtitles go through the proxy for SRT→VTT conversion
      playerEl.textTracks.add({
        kind: "subtitles",
        src: `http://localhost:6969/api/subtitle-proxy?url=${encodeURIComponent(sub.url)}`,
        srclang: sub.lang,
        label: sub.lang.toUpperCase(),
      });
    }
  });

  // ─── Vidstack event handlers ─────────────────────────────────────────────────

  function onCanPlay(): void {
    canPlay = true;
    waiting = false;
    fakeProgress = 100;
    syncAudioTracks();
    syncTextTracks(); // add this
  }

  function onWaiting(): void {
    if (canPlay) waiting = true;
  }

  function onPlaying(): void {
    waiting = false;
    canPlay = true;
  }

  function onError(e: CustomEvent): void {
    error = e.detail?.message ?? "Failed to load stream.";
    canPlay = false;
    console.error("Vidstack error:", e.detail);
  }

  function onAudioTrackChange(): void {
    syncAudioTracks();
  }

  function syncAudioTracks(): void {
    if (!playerEl) return;
    const tracks = playerEl.audioTracks as any[];
    if (!tracks) return;
    vidstackAudioTracks = Array.from(tracks).map((t: any) => ({
      id: t.id,
      label: t.label,
      language: t.language,
      selected: t.selected,
    }));
  }

  function switchAudioTrack(id: string): void {
    if (!playerEl) return;
    const tracks = playerEl.audioTracks as any[];
    if (!tracks) return;
    for (const t of Array.from(tracks)) {
      if ((t as any).id === id) (t as any).selected = true;
    }
    syncAudioTracks();
  }

  // ─── Subtitle cue adjustments ────────────────────────────────────────────────

  const cueOriginalTimes = new WeakMap<any, { start: number; end: number }>();

  function applySubtitleAdjustments(): void {
    if (!playerEl) return;
    const video = playerEl.querySelector?.("video");
    if (!video) return;
    for (const track of Array.from(video.textTracks as TextTrackList)) {
      if ((track as TextTrack).mode !== "showing") continue;
      const cues = (track as TextTrack).cues;
      if (!cues) continue;
      for (const cue of Array.from(cues)) {
        const v = cue as any;
        v.snapToLines = false;
        v.line = subtitleSettings.line;
        if (!cueOriginalTimes.has(cue)) {
          cueOriginalTimes.set(cue, { start: v.startTime, end: v.endTime });
        }
        const orig = cueOriginalTimes.get(cue)!;
        v.startTime = Math.max(0, orig.start + subtitleSettings.offset);
        v.endTime = Math.max(0, orig.end + subtitleSettings.offset);
      }
    }
  }

  $effect(() => {
    const { line, offset, size, background } = subtitleSettings;
    applySubtitleAdjustments();
  });

  $effect(() => {
    const el = document.createElement("style");
    el.textContent = `::cue {
      font-size: ${subtitleSettings.size}%;
      background-color: ${subtitleSettings.background ? "rgba(0,0,0,0.75)" : "transparent"};
    }`;
    document.head.appendChild(el);
    return () => el.remove();
  });

  // ─── Logo fetch ──────────────────────────────────────────────────────────────

  $effect(() => {
    if (!media) return;
    fetch(
      `http://localhost:6969/api/logos?id=${media.id}&type=${media.media_type}`,
    )
      .then((r) => r.json())
      .then((logos: string[]) => {
        if (logos?.length) logoUrl = logos[0];
      });
  });

  // ─── Fake loading bar animation ───────────────────────────────────────────────

  $effect(() => {
    if (canPlay) {
      fakeProgress = 100;
      return () => {};
    }
    fakeProgress = 0;
    const id = setInterval(() => {
      fakeProgress += (85 - fakeProgress) * (fakeProgress < 40 ? 0.03 : 0.01);
    }, 100);
    return () => clearInterval(id);
  });

  // ─── Torrent progress polling ────────────────────────────────────────────────

  $effect(() => {
    if (!isHash) return () => {};
    const id = setInterval(async () => {
      const d = await fetch(
        `http://localhost:6969/api/progress?hash=${src}`,
      ).then((r) => r.json());
      if (d.found) {
        torrentProgress = d.progress ?? 0;
        peers = d.peers ?? 0;
        torrentSpeed = d.speed ?? "0 B/s";
      }
    }, 2000);
    return () => clearInterval(id);
  });

  // ─── Helpers ──────────────────────────────────────────────────────────────────

  function langName(code: string): string {
    try {
      return (
        new Intl.DisplayNames(["en"], { type: "language" }).of(code) ?? code
      );
    } catch {
      return code;
    }
  }

  const loadingMessage = $derived.by(() => {
    if (hlsLoading) return "Preparing tracks…";
    if (needsHLS && !hlsSessionID) return "Starting stream…";
    if (isHash)
      return peers > 0
        ? `Connecting · ${peers} peers · ${torrentSpeed}`
        : "Connecting to peers…";
    return "Buffering…";
  });

  let textTracks = $state<
    { id: string; label: string; language: string; mode: string }[]
  >([]);

  function syncTextTracks(): void {
    if (!playerEl) return;
    const tracks = Array.from(playerEl.textTracks as TextTrackList);
    textTracks = tracks.map((t: any) => ({
      id: t.id,
      label: t.label || t.language || "Unknown",
      language: t.language,
      mode: t.mode,
    }));
  }

  function selectTextTrack(id: string): void {
    if (!playerEl) return;
    for (const t of Array.from(playerEl.textTracks as TextTrackList) as any[]) {
      t.mode = t.id === id ? "showing" : "disabled";
    }
    syncTextTracks();
  }
</script>

<!-- ─── Template ──────────────────────────────────────────────────────────────── -->

<div class="relative h-full w-full bg-black">
  <!-- Vidstack player — only mounted once we have a URL to give it -->
  {#if activeStreamURL}
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <media-player
      bind:this={playerEl}
      autoplay
      playsinline
      streamType="on-demand"
      class="h-full w-full"
    >
      <media-provider class="h-full w-full"></media-provider>

      <media-controls
        class="absolute inset-0 z-10 flex flex-col justify-end bg-linear-to-t from-black/80 via-black/10 to-transparent opacity-0 transition-opacity duration-200 data-visible:opacity-100"
      >
        <div
          class="pointer-events-auto flex w-full items-center gap-2 px-3 pb-3 text-white"
        >
          <media-play-button
            class="group flex size-8 shrink-0 cursor-pointer items-center justify-center rounded transition hover:bg-white/20"
          >
            <Pause class="block size-4 group-data-paused:hidden" />
            <Play class="hidden size-4 group-data-paused:block" />
          </media-play-button>

          <media-mute-button
            class="group flex size-8 shrink-0 cursor-pointer items-center justify-center rounded transition hover:bg-white/20"
          >
            <VolumeOff class="hidden size-4 group-data-muted:block" />
            <Volume2 class="block size-4 group-data-muted:hidden" />
          </media-mute-button>

          <media-volume-slider
            class="group relative flex h-6 w-20 cursor-pointer touch-none items-center outline-none select-none"
          >
            <div
              class="relative h-1 w-full rounded-sm bg-white/30 transition-[height] group-data-focus:h-1.5"
            >
              <div
                class="absolute h-full w-(--slider-fill) rounded-sm bg-white"
              ></div>
            </div>
          </media-volume-slider>

          <media-time class="text-sm tabular-nums" type="current"></media-time>
          <span class="text-sm text-white/50">/</span>
          <media-time class="text-sm text-white/70 tabular-nums" type="duration"
          ></media-time>

          <media-time-slider
            class="group relative flex h-6 flex-1 cursor-pointer touch-none items-center outline-none select-none"
          >
            <div
              class="relative h-1 w-full rounded-sm bg-white/30 transition-[height] group-data-focus:h-1.5"
            >
              <div
                class="absolute h-full w-(--slider-fill) rounded-sm bg-white"
              ></div>
              <div
                class="absolute h-full w-(--slider-buffered) rounded-sm bg-white/40"
              ></div>
            </div>
          </media-time-slider>

          <!-- ── Custom: audio track selector ── -->
          {#if vidstackAudioTracks.length > 1}
            <Popover.Root>
              <Popover.Trigger
                onclick={(e) => e.stopPropagation()}
                class="flex items-center gap-1.5 rounded-md px-2 py-1 text-white/70 transition hover:bg-white/10 hover:text-white"
              >
                <Headphones class="size-4" />
                <span class="text-xs">
                  {vidstackAudioTracks.find((t) => t.selected)?.label ??
                    "Audio"}
                </span>
              </Popover.Trigger>
              <Popover.Content side="top" class="w-52 p-0">
                <div class="border-b border-border px-3 py-2">
                  <span class="text-xs font-medium text-muted-foreground"
                    >Audio Track</span
                  >
                </div>
                <div class="p-1">
                  {#each vidstackAudioTracks as track (track.id)}
                    <button
                      onclick={(e) => {
                        e.stopPropagation();
                        switchAudioTrack(track.id);
                      }}
                      class="flex w-full items-center gap-2 rounded px-3 py-1.5 text-left text-sm transition hover:bg-secondary {track.selected
                        ? 'font-semibold'
                        : ''}"
                    >
                      <span class="flex-1 truncate">
                        {track.label || langName(track.language) || "Unknown"}
                      </span>
                      {#if track.selected}
                        <span class="size-1.5 shrink-0 rounded-full bg-white"
                        ></span>
                      {/if}
                    </button>
                  {/each}
                </div>
              </Popover.Content>
            </Popover.Root>
          {/if}

          <!-- ── Custom: subtitle fine-tuning ── -->
          <Popover.Root
            bind:open={subtitleSettingsOpen}
            onOpenChange={() => (subtitleView = "tracks")}
          >
            <Popover.Trigger
              onclick={(e) => e.stopPropagation()}
              class="flex size-8 items-center justify-center rounded transition hover:bg-white/10 {subtitleSettingsOpen
                ? 'text-white'
                : 'text-white/50'}"
              aria-label="Subtitle settings"
            >
              <Settings class="size-4" />
            </Popover.Trigger>

            <Popover.Content side="top" class="w-52 p-0">
              <div
                class="flex items-center justify-between border-b border-border px-3 py-2"
              >
                <span class="text-xs font-medium text-muted-foreground">
                  {subtitleView === "settings"
                    ? "Subtitle Settings"
                    : "Subtitles"}
                </span>
                {#if subtitleView === "tracks"}
                  <button
                    onclick={(e) => {
                      e.stopPropagation();
                      subtitleView = "settings";
                    }}
                    class="rounded p-0.5 text-muted-foreground transition hover:bg-secondary hover:text-foreground"
                  >
                    <Settings class="size-3.5" />
                  </button>
                {:else}
                  <button
                    onclick={(e) => {
                      e.stopPropagation();
                      subtitleView = "tracks";
                    }}
                    class="rounded p-0.5 text-muted-foreground transition hover:bg-secondary hover:text-foreground"
                  >
                    <ChevronLeft class="size-3.5" />
                  </button>
                {/if}
              </div>

              {#if subtitleView === "settings"}
                <div class="space-y-4 p-3">
                  <!-- Font size -->
                  <div class="space-y-1.5">
                    <div class="flex justify-between text-xs">
                      <span class="text-muted-foreground">Font size</span>
                      <span>{subtitleSettings.size}%</span>
                    </div>
                    <input
                      type="range"
                      min="50"
                      max="200"
                      step="10"
                      bind:value={subtitleSettings.size}
                      onclick={(e) => e.stopPropagation()}
                      class="w-full accent-white"
                    />
                  </div>
                  <!-- Position -->
                  <div class="space-y-1.5">
                    <div class="flex justify-between text-xs">
                      <span class="text-muted-foreground">Position</span>
                      <span>{subtitleSettings.line}%</span>
                    </div>
                    <input
                      type="range"
                      min="5"
                      max="95"
                      step="5"
                      bind:value={subtitleSettings.line}
                      onclick={(e) => e.stopPropagation()}
                      class="w-full accent-white"
                    />
                  </div>
                  <!-- Background -->
                  <div class="flex items-center justify-between text-xs">
                    <span class="text-muted-foreground">Background</span>
                    <button
                      aria-label="bg"
                      onclick={(e) => {
                        e.stopPropagation();
                        subtitleSettings.background =
                          !subtitleSettings.background;
                      }}
                      class="h-5 w-9 rounded-full transition-colors {subtitleSettings.background
                        ? 'bg-white'
                        : 'bg-white/20'}"
                    >
                      <span
                        class="block size-4 translate-x-0.5 rounded-full bg-black transition-transform {subtitleSettings.background
                          ? 'translate-x-4'
                          : ''}"
                      ></span>
                    </button>
                  </div>
                  <!-- Offset -->
                  <div class="space-y-1.5">
                    <div class="flex justify-between text-xs">
                      <span class="text-muted-foreground">Time offset</span>
                      <span
                        class={subtitleSettings.offset !== 0
                          ? "text-yellow-400"
                          : ""}
                      >
                        {subtitleSettings.offset > 0
                          ? "+"
                          : ""}{subtitleSettings.offset.toFixed(1)}s
                      </span>
                    </div>
                    <div class="flex items-center gap-2">
                      <button
                        onclick={(e) => {
                          e.stopPropagation();
                          subtitleSettings.offset = Math.max(
                            -30,
                            +(subtitleSettings.offset - 0.5).toFixed(1),
                          );
                        }}
                        class="flex h-6 w-6 items-center justify-center rounded bg-secondary hover:bg-secondary/80"
                        >−</button
                      >
                      <div class="h-1 flex-1 rounded-full bg-white/20">
                        <div
                          class="h-full rounded-full bg-yellow-400 transition-all"
                          style="width: {((subtitleSettings.offset + 30) / 60) *
                            100}%"
                        ></div>
                      </div>
                      <button
                        onclick={(e) => {
                          e.stopPropagation();
                          subtitleSettings.offset = Math.min(
                            30,
                            +(subtitleSettings.offset + 0.5).toFixed(1),
                          );
                        }}
                        class="flex h-6 w-6 items-center justify-center rounded bg-secondary hover:bg-secondary/80"
                        >+</button
                      >
                    </div>
                    {#if subtitleSettings.offset !== 0}
                      <button
                        onclick={(e) => {
                          e.stopPropagation();
                          subtitleSettings.offset = 0;
                        }}
                        class="w-full rounded py-0.5 text-center text-xs text-muted-foreground hover:text-foreground"
                        >Reset</button
                      >
                    {/if}
                  </div>
                </div>
              {:else}
                <div class="p-1">
                  <button
                    onclick={(e) => {
                      e.stopPropagation();
                      selectTextTrack("");
                    }}
                    class="flex w-full items-center rounded px-3 py-1.5 text-left text-sm transition hover:bg-secondary {textTracks.every(
                      (t) => t.mode !== 'showing',
                    )
                      ? 'font-semibold'
                      : ''}"
                  >
                    Off
                  </button>
                  {#each textTracks as track (track.id)}
                    <button
                      onclick={(e) => {
                        e.stopPropagation();
                        selectTextTrack(track.id);
                        subtitleSettingsOpen = false;
                      }}
                      class="flex w-full items-center rounded px-3 py-1.5 text-left text-sm transition hover:bg-secondary {track.mode ===
                      'showing'
                        ? 'font-semibold'
                        : ''}"
                    >
                      <span class="flex-1 truncate">{track.label}</span>
                      {#if track.mode === "showing"}
                        <span class="size-1.5 shrink-0 rounded-full bg-white"
                        ></span>
                      {/if}
                    </button>
                  {/each}
                </div>
              {/if}
            </Popover.Content>
          </Popover.Root>

          <!-- Torrent progress indicator -->
          {#if isHash && torrentProgress > 0 && torrentProgress < 100}
            <Popover.Root>
              <Popover.Trigger
                onclick={(e) => e.stopPropagation()}
                class="flex items-center gap-1.5 rounded-md px-2 py-1 text-white/70 transition hover:bg-white/10 hover:text-white"
              >
                <svg viewBox="0 0 12 12" class="size-3.5">
                  <circle
                    cx="6"
                    cy="6"
                    r="5"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.5"
                    class="text-white/20"
                  />
                  <circle
                    cx="6"
                    cy="6"
                    r="5"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.5"
                    stroke-dasharray="{(torrentProgress / 100) * 31.4} 31.4"
                    stroke-linecap="round"
                    transform="rotate(-90 6 6)"
                    class="text-green-400 transition-all duration-500"
                  />
                </svg>
                <span class="text-xs">{torrentProgress.toFixed(0)}%</span>
              </Popover.Trigger>
              <Popover.Content class="w-52" side="top">
                <p class="mb-2 text-sm font-medium">Download Progress</p>
                <div class="mb-1 h-1.5 w-full rounded-full bg-secondary">
                  <div
                    class="h-full rounded-full bg-green-500 transition-all"
                    style="width: {torrentProgress}%"
                  ></div>
                </div>
                <div class="flex justify-between text-xs text-muted-foreground">
                  <span>{torrentProgress.toFixed(1)}%</span>
                  {#if peers > 0}<span>{peers} peers</span>{/if}
                </div>
                <div class="mt-1 text-xs text-muted-foreground">
                  ↓ {torrentSpeed}
                </div>
              </Popover.Content>
            </Popover.Root>
          {/if}

          <media-fullscreen-button
            class="group flex size-8 shrink-0 cursor-pointer items-center justify-center rounded transition hover:bg-white/20"
          >
            <Minimize class="hidden size-4 group-data-active:block" />
            <Maximize class="block size-4 group-data-active:hidden" />
          </media-fullscreen-button>
        </div>
      </media-controls>
    </media-player>
  {/if}

  <!-- ── Error ─────────────────────────────────────────────────────────────── -->
  {#if error}
    <div
      class="pointer-events-none absolute inset-0 z-30 flex items-center justify-center"
    >
      <p class="rounded bg-black/60 px-4 py-2 text-sm text-red-400">{error}</p>
    </div>
  {/if}

  <!-- ── Buffering spinner (mid-playback) ──────────────────────────────────── -->
  {#if waiting && canPlay}
    <div
      class="pointer-events-none absolute inset-0 z-20 flex items-center justify-center"
    >
      <Spinner class="size-14" />
    </div>
  {/if}

  <!-- ── Loading screen ────────────────────────────────────────────────────── -->
  {#if !canPlay}
    <div
      class="pointer-events-none absolute inset-0 z-20 flex flex-col items-center justify-center transition-opacity duration-700"
    >
      {#if media?.poster_path}
        <div
          class="absolute inset-0 scale-110 bg-cover bg-center"
          style="background-image: url('{media.poster_path}'); filter: blur(40px); opacity: 0.4;"
        ></div>
      {/if}
      <div class="absolute inset-0 bg-black/60"></div>

      {#if logoUrl}
        <div class="relative z-10 grid place-items-center px-8 select-none">
          <img
            src={logoUrl}
            alt={title}
            class="col-start-1 row-start-1 max-h-32 max-w-sm object-contain opacity-20"
          />
          <img
            src={logoUrl}
            alt={title}
            class="col-start-1 row-start-1 max-h-32 max-w-sm object-contain transition-all duration-500"
            style="clip-path: inset(0 {100 - fakeProgress}% 0 0)"
          />
        </div>
      {:else if title}
        <div
          class="relative z-10 grid place-items-center px-8 text-center select-none"
        >
          <span
            class="col-start-1 row-start-1 block text-4xl font-bold tracking-widest text-white/20 md:text-6xl"
            >{title}</span
          >
          <span
            class="col-start-1 row-start-1 block overflow-hidden text-4xl font-bold tracking-widest text-white transition-all duration-500 md:text-6xl"
            style="clip-path: inset(0 {100 - fakeProgress}% 0 0)">{title}</span
          >
        </div>
      {:else}
        <Spinner class="relative z-10 size-14" />
      {/if}

      <p class="relative z-10 mt-6 text-sm text-white/50">{loadingMessage}</p>
    </div>
  {/if}
</div>
