<script lang="ts">
  import type { Media } from "$lib/types/tmdb";

  let {
    media,
    index,
    selected,
    onclick,
  }: {
    media: Media;
    index: number;
    selected: boolean;
    onclick: () => void;
  } = $props();

  const title = $derived(media.title ?? media.name ?? "");
</script>

<div
  role="button"
  tabindex="0"
  style="width: 180px; height: 270px;"
  class="m-2 relative shrink-0 cursor-pointer overflow-hidden rounded-xl transition-all duration-200
    {selected
      ? 'ring-2 ring-accent scale-[1.03]'
      : 'opacity-70 hover:opacity-90'}"
  {onclick}
  onkeydown={(e) => e.key === "Enter" && onclick()}
>
  {#if media.poster_path}
    <img src={media.poster_path} alt={title} class="h-full w-full object-cover" />
  {:else}
    <div
      class="flex h-full w-full items-center justify-center bg-muted p-2 text-center text-xs text-muted-foreground"
    >
      {title}
    </div>
  {/if}

  <!-- Faint rank number bottom-right -->
  <div
    class="absolute bottom-2 right-2 font-black leading-none text-foreground/75 text-6xl"
  >
    #{index + 1}
  </div>
</div>
