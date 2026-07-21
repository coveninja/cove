<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import { Spinner } from "$lib/components/ui/spinner";
  import { fade } from "svelte/transition";

  let {
    media = undefined,
    title,
    logoUrl,
    loadingMessage,
    takingAWhile,
    onCancel,
  }: {
    media?: Media;
    title: string;
    logoUrl: string | null;
    loadingMessage: string;
    takingAWhile: boolean;
    onCancel: () => void;
  } = $props();
</script>

<div class="absolute inset-0 z-20 flex flex-col items-center justify-center">
  {#if media?.poster_path}
    <div
      class="absolute inset-0 scale-110 bg-cover bg-center"
      style="background-image: url('{media.poster_path}'); filter: blur(6px); opacity: 0.3;"
    ></div>
  {/if}
  <div class="absolute inset-0 bg-black/70"></div>
  {#if logoUrl}
    <img
      src={logoUrl}
      alt={title}
      class="relative z-10 max-h-48 max-w-[60vw] object-contain drop-shadow-2xl"
    />
  {:else if media?.poster_path}
    <img
      src={media.poster_path}
      alt={title}
      class="relative z-10 h-56 w-36 rounded-xl object-cover shadow-2xl"
    />
  {:else if title}
    <span class="relative z-10 px-8 text-center text-3xl font-bold text-white">{title}</span>
  {/if}
  <Spinner class="relative z-10 mt-8 size-14 text-white" />
  <p class="relative z-10 mt-4 text-base text-white/50">{loadingMessage}</p>
  <p class="relative z-10 mt-2 text-sm text-white/40">Press Back to cancel</p>
  {#if takingAWhile}
    <p
      class="relative z-10 mt-2 text-sm text-white/40"
      transition:fade={{ duration: 150 }}
    >
      This is taking a while…
    </p>
    <button
      type="button"
      class="relative z-10 mt-5 rounded-xl border border-white/30 bg-white/10 px-6 py-3 text-base text-white hover:bg-white/20 focus:bg-white/20"
      onclick={onCancel}
    >
      Cancel
    </button>
  {/if}
</div>
