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
  let { src, controls = false, bg = "" } = $props();
</script>

{#key src}
  <media-player
    {src}
    autoplay
    muted={true}
    loop
    playsinline
    class="group/player relative aspect-video w-full bg-black"
  >
    <media-provider class="h-full w-full object-cover"></media-provider>
    <button
      type="button"
      aria-label="Toggle playback"
      class="absolute inset-0 z-20 h-full w-full cursor-pointer appearance-none border-none bg-transparent p-0"
      onclick={(e) => {
        e.stopPropagation();
        const player = e.currentTarget.parentElement as any;

        if (player.paused) {
          player.play();
        } else {
          player.pause();
        }
      }}
    ></button>

    {#if bg}
      <img
        src={bg}
        class="absolute inset-0 z-20 h-full w-full object-cover transition-opacity duration-300 group-data-started/player:pointer-events-none group-data-[started]/player:opacity-0"
        alt="bg"
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
