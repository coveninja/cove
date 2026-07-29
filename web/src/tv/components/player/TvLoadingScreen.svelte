<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import { Spinner } from "$lib/components/ui/spinner";
  import { tick } from "svelte";
  import { fade } from "svelte/transition";
  import * as m from "$lib/paraglide/messages.js";

  let {
    media = undefined,
    title,
    logoUrl,
    loadingMessage,
    takingAWhile,
    failed = false,
    cancelVisible = false,
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
    onCancel: () => void;
    onRetry?: () => void;
    onTryAnother?: () => void;
  } = $props();

  let cancelButton = $state<HTMLButtonElement | null>(null);
  let retryButton = $state<HTMLButtonElement | null>(null);

  $effect(() => {
    if (failed && retryButton) {
      tick().then(() => retryButton?.focus({ preventScroll: true }));
    } else if (cancelVisible && cancelButton) {
      tick().then(() => cancelButton?.focus({ preventScroll: true }));
    }
  });
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
  {#if failed}
    <p class="relative z-10 mt-8 text-base font-medium text-red-300" role="alert">
      {m.player_error()}
    </p>
    <div class="relative z-10 mt-5 flex gap-4">
      {#if onRetry}
        <button
          bind:this={retryButton}
          type="button"
          class="rounded-xl bg-white px-6 py-3 text-base font-medium text-black focus:ring-4 focus:ring-white"
          onclick={() => onRetry?.()}
        >
          {m.common_retry()}
        </button>
      {/if}
      {#if onTryAnother}
        <button
          type="button"
          class="rounded-xl border border-white/30 bg-white/10 px-6 py-3 text-base text-white hover:bg-white/20 focus:bg-white/20"
          onclick={() => onTryAnother?.()}
        >
          {m.player_try_another()}
        </button>
      {/if}
    </div>
    <p class="relative z-10 mt-3 text-sm text-white/40">{m.player_press_back_cancel()}</p>
  {:else}
    <Spinner class="relative z-10 mt-8 size-14 text-white" />
    <p class="relative z-10 mt-4 text-base text-white/50">{loadingMessage}</p>
    <p class="relative z-10 mt-2 text-sm text-white/40">{m.player_press_back_cancel()}</p>
    {#if takingAWhile}
      <p
        class="relative z-10 mt-2 text-sm text-white/40"
        transition:fade={{ duration: 150 }}
      >
        {m.player_taking_while()}
      </p>
    {/if}
    {#if cancelVisible || takingAWhile}
      <button
        bind:this={cancelButton}
        type="button"
        class="relative z-10 mt-5 rounded-xl border border-white/30 bg-white/10 px-6 py-3 text-base text-white hover:bg-white/20 focus:bg-white/20"
        onclick={onCancel}
      >
        {m.common_cancel()}
      </button>
    {/if}
  {/if}
</div>
