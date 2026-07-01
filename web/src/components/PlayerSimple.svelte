<script module lang="ts">
  // Vidstack rejects pending media requests with "provider destroyed"
  // when a player is torn down mid-flight. These are harmless; swallow
  // only this exact message so real rejections still surface.
  if (typeof window !== "undefined") {
    window.addEventListener("unhandledrejection", (e) => {
      const msg =
        typeof e.reason === "string" ? e.reason : e.reason?.message;
      if (msg === "provider destroyed") e.preventDefault();
    });
  }
</script>

<script lang="ts">
  import "vidstack/bundle";
  import "vidstack/svelte";
  import "vidstack/player/styles/base.css";
  import "vidstack/player/styles/default/theme.css";
  import "vidstack/player/styles/default/layouts/video.css";
  import {
    Maximize,
    Minimize,
    Pause,
    Play,
    Volume2,
    VolumeOff,
  } from "lucide-svelte";
  import type { MediaPlayerElement } from "vidstack/elements";

  let {
    src,
    autoplay = true,
    controls = false,
    loop = true,
    muted = $bindable(true),
    paused = $bindable(true),
    bg = "",
    class: Class = "",
    onProgress = (_current: number, _duration: number) => {},
    onDuration = (_seconds: number) => {},
    onEnded = () => {},
  } = $props();

  let player = $state<MediaPlayerElement | null>(null);
  // True once the current player element has fired can-play. Reset whenever
  // {#key src} recreates the element (via the player-change effect below).
  // Gates the paused-watcher so it never calls play() on a not-yet-ready element.
  let mediaReady = $state(false);

  // play() rejects with "provider destroyed" if the player is torn down
  // before it resolves. These rejections are safe to ignore.
  function safePlay(p: MediaPlayerElement): void {
    void p.play().catch(() => {});
  }

  // Reset readiness whenever the underlying element is replaced ({#key src}).
  $effect(() => {
    player; // establish dependency
    mediaReady = false;
  });

  // Reset paused intent when the source changes so the old play-state doesn't
  // bleed into a new element that isn't loaded yet.
  $effect(() => {
    src; // establish dependency
    paused = true;
  });

  // Apply the current `muted` value to whatever provider is in use.
  //
  // TMDB clips are YouTube videos, so Vidstack mounts its YouTube provider,
  // which is an <iframe> — there is no <video> element and no `.muted` to
  // set. We drive it through the YouTube IFrame API via postMessage
  // (enablejsapi=1 is already on the embed URL, and the iframe lives in the
  // light DOM so we can reach it). For native providers (HLS / file), fall
  // back to the real media element behind the provider's shadow root.
  function applyMuted(): void {
    const p = player;
    if (!p) return;

    const iframe = p.querySelector(
      "iframe.vds-youtube",
    ) as HTMLIFrameElement | null;
    if (iframe?.contentWindow) {
      iframe.contentWindow.postMessage(
        JSON.stringify({
          event: "command",
          func: muted ? "mute" : "unMute",
          args: [],
        }),
        "*",
      );
      return;
    }

    const media = p
      .querySelector("media-provider")
      ?.shadowRoot?.querySelector("video, audio") as HTMLMediaElement | null;
    if (media) media.muted = muted;
  }

  $effect(() => {
    const p = player;
    if (!p) return undefined;

    // Vidstack's YouTube provider emits a volume-change reflecting its
    // default (muted:false) during init. Ignore volume events until we've
    // asserted our intended muted state at can-play, or that event clobbers
    // the bindable `muted` value via the parent binding.
    let muteApplied = false;

    const handleDuration = () => {
      if (p.duration > 0) onDuration(p.duration);
    };
    const handleTime = () => {
      if (p.duration > 0) onProgress(p.currentTime, p.duration);
    };
    const handleEnded = () => onEnded();
    const handleProvider = () => applyMuted();
    const handleCanPlay = () => {
      applyMuted();
      muteApplied = true;
      // Signal that this element is ready. The native `autoplay` attribute on
      // <media-player> handles the actual play() call so we never call it here
      // — vidstack knows the exact safe moment, avoiding "media is not ready".
      mediaReady = true;
    };
    // Re-assert at play — this is the moment audio would otherwise start,
    // and YouTube ignores mute commands sent before its API is ready.
    const handlePlay = () => {
      applyMuted();
      paused = false;
    };
    const handlePause = () => (paused = true);
    const handleVolume = () => {
      if (!muteApplied) return; // drop Vidstack's init volume-change
      muted = p.muted;
    };

    p.addEventListener("duration-change", handleDuration);
    p.addEventListener("time-update", handleTime);
    p.addEventListener("ended", handleEnded);
    p.addEventListener("provider-change", handleProvider);
    p.addEventListener("can-play", handleCanPlay);
    p.addEventListener("play", handlePlay);
    p.addEventListener("pause", handlePause);
    p.addEventListener("volume-change", handleVolume);

    return () => {
      p.removeEventListener("duration-change", handleDuration);
      p.removeEventListener("time-update", handleTime);
      p.removeEventListener("ended", handleEnded);
      p.removeEventListener("provider-change", handleProvider);
      p.removeEventListener("can-play", handleCanPlay);
      p.removeEventListener("play", handlePlay);
      p.removeEventListener("pause", handlePause);
      p.removeEventListener("volume-change", handleVolume);
    };
  });

  // Keep the player muted-state in sync with the `muted` prop. Forcing the
  // attribute (not the property) gives Vidstack the value before the YouTube
  // iframe is built, so it embeds with mute=1; applyMuted() then enforces it
  // at runtime and when the parent's mute button toggles.
  $effect(() => {
    const p = player;
    if (!p) return;
    if (muted) p.setAttribute("muted", "");
    else p.removeAttribute("muted");
    applyMuted();
  });

  // Apply parent-driven play/pause — but only after the element is ready.
  // Gated on `mediaReady` so a stale `paused = false` from a previous source
  // never triggers play() on a freshly-created, not-yet-loaded element.
  $effect(() => {
    const p = player;
    if (!p || !mediaReady) return;
    if (paused && !p.paused) void Promise.resolve(p.pause()).catch(() => {});
    else if (!paused && p.paused) safePlay(p);
  });

  $effect(() => {
    const p = player;
    if (!p) return;

    const observer = new IntersectionObserver(
            ([entry]) => {
              if (entry.isIntersecting) {
                if (!paused) safePlay(p); // only resume if not manually paused
              } else {
                void Promise.resolve(p.pause()).catch(() => {});
              }
            },
            { threshold: 0.5 }
    );

    observer.observe(p);
    return () => observer.disconnect();
  });
</script>

{#key src}
  <media-player
    bind:this={player}
    {src}
    {loop}
    autoplay={autoplay}
    playsinline
    class="group/player relative h-full w-full bg-black {Class}"
  >
    <media-provider class="h-full w-full"></media-provider>
    <button
      type="button"
      aria-label="Toggle playback"
      class="absolute inset-0 z-20 h-full w-full cursor-pointer appearance-none border-none bg-transparent p-0"
      onclick={(e) => {
        e.stopPropagation();
        if (!player) return;
        if (player.paused) safePlay(player);
        else void Promise.resolve(player.pause()).catch(() => {});
      }}
    ></button>

    {#if bg}
      <img
        class="absolute inset-0 z-20 h-full w-full object-cover transition-opacity duration-300 group-data-started/player:pointer-events-none group-data-[started]/player:opacity-0"
        alt="bg"
        src={bg}
      />
    {/if}
    {#if controls}
      <media-controls
        class="absolute inset-0 z-30 flex flex-col justify-end bg-linear-to-t from-black/80 via-black/20 to-transparent p-2 opacity-0 transition-opacity duration-200 data-visible:opacity-100"
      >
        <div
          class="pointer-events-auto flex w-full items-center gap-4 text-white"
        >
          <media-play-button
            class="group flex size-8 cursor-pointer items-center justify-center rounded transition-all outline-none hover:bg-white/20"
          >
            <Pause class="block size-4 group-data-paused:hidden" />
            <Play class="hidden size-4 group-data-paused:block" />
          </media-play-button>

          <media-mute-button
            class="group flex size-8 cursor-pointer items-center justify-center rounded outline-none hover:bg-white/20"
          >
            <VolumeOff class="hidden size-4 group-data-muted:block" />
            <Volume2 class="block size-4 group-data-muted:hidden" />
          </media-mute-button>

          <media-time-slider
            class="group relative flex h-6 flex-1 cursor-pointer touch-none items-center outline-none select-none"
          >
            <div
              class="relative h-1 w-full rounded-sm bg-white/30 transition-[height] group-data-focus:h-1.5"
            >
              <div
                class="absolute h-full w-(--slider-fill) rounded-sm bg-accent"
              ></div>
            </div>
          </media-time-slider>

          <media-fullscreen-button
            class="group flex size-8 cursor-pointer items-center justify-center rounded outline-none hover:bg-white/20"
          >
            <Maximize class="block size-4 group-data-fullscreen:hidden" />
            <Minimize class="hidden size-4 group-data-fullscreen:block" />
          </media-fullscreen-button>
        </div>
      </media-controls>
    {/if}
  </media-player>
{/key}

<style>
  media-player {
    width: 100%;
    height: 100%;
  }

  /* Vidstack renders the <video> at runtime, so reach it with :global */
  media-player :global(video) {
    height: 100%;
    width: 100%;
    object-fit: cover;
  }
</style>
