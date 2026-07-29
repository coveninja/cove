<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import { Spinner } from "$lib/components/ui/spinner";
  import { Button } from "$lib/components/ui/button";
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
    onCancel,
    onRetry = undefined,
    onTryAnother = undefined,
    onClose,
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
    onClose?: () => void;
  } = $props();
</script>

<div class="absolute inset-0 z-20 flex flex-col items-center justify-center bg-black">
  {#if onClose}
    <button
      type="button"
      class="absolute right-4 top-4 z-10 flex size-9 items-center justify-center rounded-full text-white/70 hover:bg-white/10 hover:text-white"
      onclick={(event) => {
        event.stopPropagation();
        onClose?.();
      }}
      aria-label={m.player_close()}
    >
      <X class="size-5" />
    </button>
  {/if}
  {#if media?.poster_path}
    <div
      class="absolute inset-0 scale-110 bg-cover bg-center"
      style="background-image: url('{media.poster_path}'); filter: blur(5px); opacity: 0.35;"
    ></div>
  {/if}
  <div class="absolute inset-0 bg-black/65"></div>
  {#if logoUrl}
    <img
      src={logoUrl}
      alt={title}
      class="relative z-10 max-h-40 max-w-xs object-contain drop-shadow-2xl"
    />
  {:else if media?.poster_path}
    <img
      src={media.poster_path}
      alt={title}
      class="relative z-10 h-48 w-32 rounded-lg object-cover shadow-2xl"
    />
  {:else if title}
    <span class="relative z-10 px-8 text-center text-3xl font-bold text-white">{title}</span>
  {/if}
  {#if failed}
    <p class="relative z-10 mt-6 text-sm font-medium text-red-300" role="alert">
      {m.player_error()}
    </p>
    <div class="relative z-10 mt-4 flex gap-3">
      {#if onRetry}
        <Button
          size="sm"
          onclick={(event) => {
            event.stopPropagation();
            onRetry?.();
          }}
        >
          {m.common_retry()}
        </Button>
      {/if}
      {#if onTryAnother}
        <Button
          variant="outline"
          size="sm"
          class="text-white"
          onclick={(event) => {
            event.stopPropagation();
            onTryAnother?.();
          }}
        >
          {m.player_try_another()}
        </Button>
      {/if}
    </div>
  {:else}
    <Spinner class="relative z-10 mt-6 size-10" />
    <p class="relative z-10 mt-4 text-sm text-white/50">{loadingMessage}</p>
    {#if takingAWhile}
      <p class="relative z-10 mt-2 text-xs text-white/40" transition:fade={{ duration: 150 }}>
        {m.player_taking_while()}
      </p>
    {/if}
    {#if cancelVisible || takingAWhile}
      <Button
        variant="outline"
        size="sm"
        class="relative z-10 mt-4 text-white"
        onclick={(event) => {
          event.stopPropagation();
          onCancel();
        }}
      >
        {m.common_cancel()}
      </Button>
    {/if}
  {/if}
</div>
