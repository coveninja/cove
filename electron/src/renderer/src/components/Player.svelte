<script lang="ts">
  import * as PlyrModule from "plyr";
  import type { Media } from "$lib/types/tmdb";
  import { Spinner } from "$lib/components/ui/spinner";
  import * as Popover from "$lib/components/ui/popover";
  import { Settings, Headphones } from "lucide-svelte";
  import { SvelteMap } from "svelte/reactivity";
  const Plyr = (PlyrModule as any).default || PlyrModule;
  import { untrack } from "svelte";

  // ─── Types ────────────────────────────────────────────────────────────────

  type AudioTrackInfo = {
    index: number;
    language: string;
    title: string;
    codec: string;
  };

  export type SubtitleSettings = {
    size: number; // 50–200 (font size %)
    line: number; // 5–95  (vertical position % from top)
    background: boolean;
    offset: number; // seconds, can be negative
  };

  const UNSUPPORTED_AUDIO_CODECS = new Set([
    "ac3",
    "eac3",
    "dts",
    "truehd",
    "mlp",
  ]);

  // ─── Props ────────────────────────────────────────────────────────────────

  let {
    src,
    media,
    externalSubtitles = [],
  }: {
    src: string;
    media?: Media;
    externalSubtitles?: { id: string; url: string; lang: string }[];
  } = $props();

  // ─── URL derivations ──────────────────────────────────────────────────────

  const isHash = $derived(!src.startsWith("http"));

  const baseStreamURL = $derived(
    isHash
      ? `http://localhost:6969/api/play?hash=${src}`
      : `http://localhost:6969/api/play?url=${encodeURIComponent(src)}`,
  );

  // ─── DOM refs ─────────────────────────────────────────────────────────────

  let videoEl = $state<HTMLVideoElement | null>(null);
  let plyr = $state<Plyr | null>(null);

  // ─── Playback state (fed by Plyr events) ─────────────────────────────────

  let canPlay = $state(false);
  let waiting = $state(false);
  let playing = $state(false);
  let fakeProgress = $state(0);
  let error = $state<string | null>(null);
  let controlsVisible = $state(false);

  // ─── Audio tracks (backend URL-based, custom UI) ──────────────────────────

  let audioTracks = $state<AudioTrackInfo[]>([]);
  let activeAudioTrack = $state(0);

  const needsFFmpeg = $derived(
    audioTracks.length > 1 ||
      audioTracks.some((t) =>
        UNSUPPORTED_AUDIO_CODECS.has(t.codec.toLowerCase()),
      ),
  );

  const activeStreamURL = $derived(
    needsFFmpeg ? `${baseStreamURL}&audio=${activeAudioTrack}` : baseStreamURL,
  );

  // ─── Subtitle fine-tuning (custom panel; Plyr owns track selection) ───────

  let subtitleSettings = $state<SubtitleSettings>({
    size: 100,
    line: 85,
    background: true,
    offset: 0,
  });
  let subtitleSettingsOpen = $state(false);
  let subtitleView = $state<"languages" | "tracks" | "settings">("languages");
  let selectedLang = $state<string | null>(null);

  // ─── Other state ──────────────────────────────────────────────────────────

  let logoUrl = $state<string | null>(null);
  let torrentProgress = $state(0);
  let peers = $state(0);
  let torrentSpeed = $state("0 B/s");

  const title = $derived(
    media ? (media.media_type === "tv" ? media.name : media.title) : "",
  );

  const loadingProgress = $derived(fakeProgress);

  let customControlsEl = $state<HTMLElement | null>(null);
  let audioMenuOpen = $state(false);
  let torrentMenuOpen = $state(false);

  const showCustomControls = $derived(
    canPlay &&
      (controlsVisible ||
        subtitleSettingsOpen ||
        audioMenuOpen ||
        torrentMenuOpen),
  );

  // ─── Subtitle cue adjustments ─────────────────────────────────────────────

  const cueOriginalTimes = new WeakMap<
    TextTrackCue,
    { start: number; end: number }
  >();

  function applySubtitleAdjustments(track: TextTrack): void {
    const run = (): void => {
      if (!track.cues) return;
      Array.from(track.cues).forEach((cue) => {
        const v = cue as VTTCue;
        v.snapToLines = false;
        v.line = subtitleSettings.line;
        if (!cueOriginalTimes.has(cue)) {
          cueOriginalTimes.set(cue, { start: v.startTime, end: v.endTime });
        }
        const orig = cueOriginalTimes.get(cue)!;
        v.startTime = Math.max(0, orig.start + subtitleSettings.offset);
        v.endTime = Math.max(0, orig.end + subtitleSettings.offset);
      });
    };
    run();
    setTimeout(run, 600);
  }

  function applyAdjustmentsToActiveTrack(): void {
    if (!videoEl) return;
    Array.from(videoEl.textTracks)
      .filter((t) => t.mode === "showing")
      .forEach(applySubtitleAdjustments);
  }

  // ::cue styles (font size + background)
  $effect(() => {
    const el = document.createElement("style");
    el.textContent = `::cue {
      font-size: ${subtitleSettings.size}%;
      background-color: ${subtitleSettings.background ? "rgba(0,0,0,0.75)" : "transparent"};
    }`;
    document.head.appendChild(el);
    return () => el.remove();
  });

  // Re-apply whenever settings change
  $effect(() => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { line, offset } = subtitleSettings;
    applyAdjustmentsToActiveTrack();
  });

  // ─── Plyr initialisation ──────────────────────────────────────────────────

  $effect(() => {
    if (!videoEl) return () => {};

    const p = new Plyr(videoEl, {
      controls: [
        "play",
        "rewind",
        "fast-forward",
        "progress",
        "current-time",
        "mute",
        "volume",
        "captions",
        "settings",
        "pip",
        "fullscreen",
      ],
      settings: ["captions", "speed"],
      speed: { selected: 1, options: [0.5, 0.75, 1, 1.25, 1.5, 2] },
      invertTime: false,
      toggleInvert: false,
      keyboard: { focused: true, global: true },
      tooltips: { controls: true, seek: true },
      displayDuration: true,
      autoplay: true,
      clickToPlay: true,
    });

    if (customControlsEl && p.elements.container) {
      p.elements.container.appendChild(customControlsEl);
    }

    p.on("canplay", () => {
      canPlay = true;
      waiting = false;
      fakeProgress = 100;
    });
    p.on("waiting", () => {
      if (canPlay) waiting = true;
    });
    p.on("playing", () => {
      playing = true;
      canPlay = true;
      waiting = false;
    });
    p.on("pause", () => {
      playing = false;
    });
    p.on("error", () => {
      error = "Failed to load stream.";
      canPlay = false;
    });
    p.on("controlsshown", () => {
      controlsVisible = true;
    });
    p.on("controlshidden", () => {
      controlsVisible = false;
    });
    p.on("captionsenabled", applyAdjustmentsToActiveTrack);
    p.on("languagechange", applyAdjustmentsToActiveTrack);

    plyr = p;
    return () => {
      p.destroy();
      plyr = null;
    };
  });

  // ─── Source update (reacts to activeStreamURL or externalSubtitles) ───────

  $effect(() => {
    if (!plyr) return;

    // We want this to re-run if activeStreamURL or externalSubtitles change
    const url = activeStreamURL;
    const subs = externalSubtitles;

    canPlay = false;
    fakeProgress = 0;
    error = null;

    plyr.source = {
      type: "video",
      sources: [{ src: url, type: "video/mp4" }],
      tracks: subs.map((sub) => ({
        kind: "subtitles" as const,
        src: `http://localhost:6969/api/subtitle-proxy?url=${encodeURIComponent(sub.url)}`,
        srclang: sub.lang,
        label: sub.lang.toUpperCase(),
        default: false,
      })),
    };

    // Force play after source is loaded
    plyr.once("canplay", () => {
      plyr?.play();
    });
  });

  // ─── Audio probe ──────────────────────────────────────────────────────────

  $effect(() => {
    if (!src) return () => {};
    audioTracks = [];
    activeAudioTrack = 0;

    const probeURL = isHash
      ? `http://localhost:6969/api/probe?hash=${src}`
      : `http://localhost:6969/api/probe?url=${encodeURIComponent(src)}`;

    const controller = new AbortController();
    fetch(probeURL, { signal: controller.signal })
      .then((r) => r.json())
      .then((tracks: AudioTrackInfo[]) => {
        audioTracks = tracks;
      })
      .catch(() => {
        audioTracks = [];
      });

    return () => controller.abort();
  });

  function setAudioTrack(index: number): void {
    if (index === activeAudioTrack || !plyr) return;
    const savedTime = plyr.currentTime;
    activeAudioTrack = index;
    plyr.once("canplay", () => {
      if (plyr) plyr.currentTime = savedTime;
    });
  }

  // ─── Logo fetch ───────────────────────────────────────────────────────────

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

  // ─── Fake loading progress animation ─────────────────────────────────────

  $effect(() => {
    if (canPlay) {
      fakeProgress = 100;
      return () => {};
    }
    fakeProgress = 0;
    const interval = setInterval(() => {
      const target = 85;
      const step = fakeProgress < 40 ? 0.03 : 0.01;
      fakeProgress += (target - fakeProgress) * step;
    }, 100);
    return () => clearInterval(interval);
  });

  // ─── Torrent progress polling ─────────────────────────────────────────────

  $effect(() => {
    if (!isHash) return () => {};
    const interval = setInterval(async () => {
      const res = await fetch(`http://localhost:6969/api/progress?hash=${src}`);
      const d = await res.json();
      if (d.found) {
        torrentProgress = d.progress ?? 0;
        peers = d.peers ?? 0;
        torrentSpeed = d.speed ?? "0 B/s";
      }
    }, 2000);
    return () => clearInterval(interval);
  });

  // ─── Subtitle panel helpers ───────────────────────────────────────────────

  const subtitleTracks = $derived.by(() => {
    if (!videoEl) return [] as TextTrack[];
    return Array.from(videoEl.textTracks).filter(
      (t) => t.kind === "subtitles" || t.kind === "captions",
    );
  });

  const tracksByLang = $derived.by(() => {
    const map = new SvelteMap<string, { track: TextTrack; index: number }[]>();
    subtitleTracks.forEach((track, i) => {
      const lang = track.language || track.label || "unknown";
      if (!map.has(lang)) map.set(lang, []);
      map.get(lang)!.push({ track, index: i });
    });
    return map;
  });

  function langName(code: string): string {
    try {
      return (
        new Intl.DisplayNames(["en"], { type: "language" }).of(code) ?? code
      );
    } catch {
      return code;
    }
  }

  function audioTrackLabel(track: AudioTrackInfo, index: number): string {
    if (track.title) return track.title;
    if (track.language) return langName(track.language);
    return `Track ${index + 1}`;
  }
</script>

<!-- ─── Template ──────────────────────────────────────────────────────────── -->

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="plyr-wrap relative h-full w-full bg-black">
  <!-- Plyr wraps this element and builds its control bar around it -->
  <!-- svelte-ignore a11y_media_has_caption -->
  <video
    bind:this={videoEl}
    crossorigin="anonymous"
    playsinline
    class="h-full w-full"
  >
  </video>

  <!-- ── Error ──────────────────────────────────────────────────────────── -->
  {#if error}
    <div
      class="pointer-events-none absolute inset-0 z-30 flex items-center justify-center"
    >
      <p class="text-sm text-red-400">{error}</p>
    </div>
  {/if}

  <!-- ── Loading screen (covers Plyr entirely until canPlay) ────────────── -->
  {#if !canPlay || waiting}
    <div
      class="absolute inset-0 z-20 flex flex-col items-center justify-center transition-opacity duration-700 {canPlay &&
      !waiting
        ? 'pointer-events-none opacity-0'
        : ''}"
    >
      {#if media?.poster_path}
        <div
          class="absolute inset-0 scale-110 bg-cover bg-center"
          style="background-image: url('{media.poster_path}'); filter: blur(40px); opacity: 1;"
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
            class="col-start-1 row-start-1 max-h-32 max-w-sm object-contain transition-all duration-500 {fakeProgress >
            40
              ? 'animate-pulse'
              : ''}"
            style="clip-path: inset(0 {100 - loadingProgress}% 0 0)"
          />
        </div>
      {:else if title}
        <div
          class="relative z-10 grid place-items-center px-8 text-center select-none"
        >
          <span
            class="col-start-1 row-start-1 block text-4xl font-bold tracking-widest text-white/20 md:text-6xl"
          >
            {title}
          </span>
          <span
            class="col-start-1 row-start-1 block overflow-hidden text-4xl font-bold tracking-widest text-white transition-all duration-500 md:text-6xl"
            style="clip-path: inset(0 {100 - loadingProgress}% 0 0)"
          >
            {title}
          </span>
        </div>
      {:else}
        <Spinner class="relative z-10 size-14" />
      {/if}

      <div class="relative z-10 mt-6 text-sm text-white/50">
        {#if needsFFmpeg && fakeProgress < 5}
          Preparing audio track...
        {:else if isHash}
          {#if peers > 0}
            Connecting · {peers} peers · {torrentSpeed}
          {:else}
            Connecting to peers...
          {/if}
        {:else}
          Buffering...
        {/if}
      </div>
    </div>
  {/if}

  <!-- ── Buffering spinner (shown mid-playback while seeking etc.) ─────── -->
  {#if waiting && canPlay}
    <div
      class="pointer-events-none absolute inset-0 z-10 flex items-center justify-center"
    >
      <Spinner class="size-14" />
    </div>
  {/if}

  <!-- ── Custom overlay: audio track + subtitle fine-tuning ────────────── -->
  <!--
    Positioned at bottom-[52px] so it sits just above Plyr's 44px control bar.
    Visibility synced with Plyr's own controlsshown / controlshidden events.
  -->
  <div
    bind:this={customControlsEl}
    class="absolute right-3 bottom-13 z-30 flex items-center gap-1 transition-opacity duration-200 {showCustomControls
      ? 'pointer-events-auto'
      : 'pointer-events-none'}"
    style="opacity: {showCustomControls ? 1 : 0}"
  >
    {#if audioTracks.length > 1}
      <div>
        <Popover.Root bind:open={audioMenuOpen}>
          <Popover.Trigger
            onclick={(e) => e.stopPropagation()}
            class="flex items-center gap-1.5 rounded-md px-2 py-1 text-white/70 transition-colors hover:bg-white/10 hover:text-white"
          >
            <Headphones class="size-4" />
            <span class="text-xs">
              {audioTrackLabel(audioTracks[activeAudioTrack], activeAudioTrack)}
            </span>
          </Popover.Trigger>
          <Popover.Content side="top" class="w-52 p-0">
            <div class="border-b border-border px-3 py-2">
              <span class="text-xs font-medium text-muted-foreground"
                >Audio Track</span
              >
            </div>
            <div class="py-1">
              {#each audioTracks as track, i (i)}
                <button
                  onclick={(e) => {
                    e.stopPropagation();
                    setAudioTrack(i);
                  }}
                  class="flex w-full items-center gap-2 rounded px-3 py-1.5 text-left text-sm transition-colors hover:bg-secondary {activeAudioTrack ===
                  i
                    ? 'font-semibold'
                    : ''}"
                >
                  <span class="flex-1 truncate"
                    >{audioTrackLabel(track, i)}</span
                  >
                  <span class="text-xs text-muted-foreground uppercase"
                    >{track.codec}</span
                  >
                  {#if activeAudioTrack === i}
                    <span class="size-1.5 shrink-0 rounded-full bg-white"
                    ></span>
                  {/if}
                </button>
              {/each}
            </div>
          </Popover.Content>
        </Popover.Root>
      </div>
    {/if}

    {#if subtitleTracks.length > 0}
      <div>
        <Popover.Root
          bind:open={subtitleSettingsOpen}
          onOpenChange={() => {
            subtitleView = "languages";
            selectedLang = null;
          }}
        >
          <Popover.Trigger
            onclick={(e) => e.stopPropagation()}
            class="flex items-center justify-center rounded-md p-1.5 transition-colors hover:bg-white/10 {subtitleSettingsOpen
              ? 'text-white'
              : 'text-white/50'}"
            aria-label="Subtitle settings"
          >
            <Settings class="size-4" />
          </Popover.Trigger>
        </Popover.Root>
      </div>
    {/if}

    {#if isHash && torrentProgress > 0 && torrentProgress < 100}
      <div>
        <Popover.Root bind:open={torrentMenuOpen}>
          <Popover.Trigger
            onclick={(e) => e.stopPropagation()}
            class="flex items-center gap-1.5 rounded-md px-2 py-1 text-white/70 transition-colors hover:bg-white/10 hover:text-white"
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
                class="h-full rounded-full bg-green-500 transition-all duration-500"
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
      </div>
    {/if}
  </div>
</div>

<style>
  /*
   * Make Plyr fill the parent container.
   * Plyr wraps <video> in .plyr > .plyr__video-wrapper, so we need these to
   * inherit dimensions rather than size to the video's intrinsic size.
   */
  .plyr-wrap :global(.plyr) {
    height: 100%;
    width: 100%;
  }

  .plyr-wrap :global(.plyr__caption) {
    font-family: "Open Sans", sans-serif;
    font-weight: 500;
    font-size: var(--text-2xl);
    letter-spacing: -0.01em;
    border-radius: 4px;
    padding: 4px 12px;
  }

  /* ── Plyr theme overrides ── */
  .plyr-wrap :global(.plyr--video) {
    --plyr-color-main: rgba(255, 255, 255, 0.9);
    --plyr-video-background: transparent;
    --plyr-font-family: inherit;

    /* Progress / volume fill */
    --plyr-range-fill-background: rgba(255, 255, 255, 0.9);

    /* Control bar gradient — matches the existing design */
    --plyr-video-controls-background: linear-gradient(
      rgba(0, 0, 0, 0),
      rgba(0, 0, 0, 0.75)
    );

    /* Tooltips */
    --plyr-tooltip-background: rgba(0, 0, 0, 0.9);
    --plyr-tooltip-color: #fff;
    --plyr-tooltip-radius: 6px;

    /* Control sizing */
    --plyr-control-icon-size: 17px;
    --plyr-control-spacing: 10px;
  }

  /* Keep the loading overlay above Plyr's built-in loading spinner */
  .plyr-wrap :global(.plyr__poster),
  .plyr-wrap :global(.plyr__preview-thumb) {
    display: none;
  }
</style>
