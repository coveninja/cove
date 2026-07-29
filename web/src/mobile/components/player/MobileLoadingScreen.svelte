<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import { Spinner } from "$lib/components/ui/spinner";
  import { X } from "lucide-svelte";
  import { fade } from "svelte/transition";
  import * as m from "$lib/paraglide/messages.js";

  let {
    media,
    title,
    logoUrl,
    loadingMessage,
    takingAWhile,
    failed = false,
    cancelVisible = false,
    onclose,
    onCancel,
    onRetry = undefined,
    onTryAnother = undefined,
  }: {
    media?: Media;
    title: string;
    logoUrl: string | null;
    loadingMessage: string;
    takingAWhile: boolean;
    failed?: boolean;
    cancelVisible?: boolean;
    onclose?: () => void;
    onCancel: () => void;
    onRetry?: () => void;
    onTryAnother?: () => void;
  } = $props();
</script>

<div class="absolute inset-0 z-20 flex flex-col items-center justify-center">
  <!-- Close button — top-left, matching the controls-bar X position -->
  <button
    type="button"
    class="absolute left-4 z-10 flex size-11 items-center justify-center rounded-full text-white active:bg-white/20"
    style="top: max(1rem, var(--safe-top));"
    onclick={() => onclose?.()}
    aria-label={m.player_close()}
  >
    <X class="size-6" />
  </button>
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
      class="relative z-10 max-h-36 max-w-[80vw] object-contain drop-shadow-2xl"
    />
  {:else if media?.poster_path}
    <img
      src={media.poster_path}
      alt={title}
      class="relative z-10 h-44 w-28 rounded-lg object-cover shadow-2xl"
    />
  {:else if title}
    <span class="relative z-10 px-8 text-center text-2xl font-bold text-white">{title}</span>
  {/if}
  {#if failed}
    <p class="relative z-10 mt-6 text-sm font-medium text-red-300" role="alert">
      {m.player_error()}
    </p>
    <div class="relative z-10 mt-4 flex gap-3">
      {#if onRetry}
        <button
          type="button"
          class="rounded-lg bg-white px-4 py-2 text-sm font-medium text-black"
          onclick={() => onRetry?.()}
        >
          {m.common_retry()}
        </button>
      {/if}
      {#if onTryAnother}
        <button
          type="button"
          class="rounded-lg border border-white/30 bg-white/10 px-4 py-2 text-sm text-white"
          onclick={() => onTryAnother?.()}
        >
          {m.player_try_another()}
        </button>
      {/if}
    </div>
  {:else}
    <Spinner class="relative z-10 mt-6 size-12 text-white" />
    <p class="relative z-10 mt-4 text-sm text-white/50">{loadingMessage}</p>
    {#if takingAWhile}
      <p
        class="relative z-10 mt-2 text-xs text-white/40"
        transition:fade={{ duration: 150 }}
      >
        {m.player_taking_while()}
      </p>
    {/if}
    {#if cancelVisible || takingAWhile}
      <button
        type="button"
        class="relative z-10 mt-4 rounded-lg border border-white/30 bg-white/10 px-4 py-2 text-sm text-white"
        onclick={() => onCancel()}
      >
        {m.common_cancel()}
      </button>
    {/if}
  {/if}
</div>
