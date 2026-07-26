<script lang="ts">
  import { Button } from "$lib/components/ui/button";
  import * as Popover from "$lib/components/ui/popover/index.js";
  import {
    mediaUtilityItems,
    performMediaUtilityAction,
    type MediaUtilityAction,
  } from "$lib/mediaActions";
  import type { LibraryEntry } from "$lib/types/library";
  import type { Media } from "$lib/types/tmdb";
  import {
    CheckCheck,
    EllipsisVertical,
    LoaderCircle,
    RotateCcw,
    ThumbsDown,
    Trash2,
  } from "lucide-svelte";

  let {
    media,
    libraryEntry,
    dismissed,
    hasProgress,
    size = "icon",
    class: className = "",
  } = $props<{
    media: Media;
    libraryEntry: LibraryEntry | null;
    dismissed: boolean;
    hasProgress: boolean;
    size?: string | null;
    class?: string;
  }>();

  let open = $state(false);
  let working = $state<MediaUtilityAction | null>(null);
  let error = $state("");

  const items = $derived(
    mediaUtilityItems(media, { entry: libraryEntry, dismissed, hasProgress }),
  );

  async function handleAction(action: MediaUtilityAction): Promise<void> {
    if (working) return;
    working = action;
    error = "";
    try {
      await performMediaUtilityAction(action, media, {
        entry: libraryEntry,
        dismissed,
        hasProgress,
      });
      open = false;
    } catch (cause) {
      error =
        cause instanceof Error
          ? cause.message
          : "The action could not be completed";
    } finally {
      working = null;
    }
  }
</script>

<Popover.Root
  bind:open
  onOpenChange={(nextOpen) => {
    if (nextOpen) error = "";
  }}
>
  <Popover.Trigger>
    <Button
      variant="outline"
      {size}
      class={className}
      title="More actions"
      aria-label="More actions"
    >
      <EllipsisVertical />
    </Button>
  </Popover.Trigger>
  <Popover.Content class="w-80 gap-1 rounded-3xl p-1.5" align="end">
    {#each items as item (item.id)}
      <Button
        variant={item.destructive ? "destructive" : "ghost"}
        class="h-auto w-full justify-start rounded-2xl px-3 py-2.5 text-left whitespace-normal"
        disabled={working !== null}
        onclick={() => handleAction(item.id)}
      >
        {#if working === item.id}
          <LoaderCircle class="size-4 animate-spin" />
        {:else if item.id === "mark-watched"}
          <CheckCheck class="size-4" />
        {:else if item.id === "mark-unwatched"}
          <RotateCcw class="size-4" />
        {:else if item.id === "toggle-not-interested"}
          <ThumbsDown class="size-4" />
        {:else}
          <Trash2 class="size-4" />
        {/if}
        <span class="min-w-0">
          <span class="block font-medium">{item.label}</span>
          {#if item.sublabel}
            <span class="block text-xs font-normal opacity-65">
              {item.sublabel}
            </span>
          {/if}
        </span>
      </Button>
    {/each}
    {#if error}
      <p class="px-3 py-2 text-xs text-destructive" role="alert">{error}</p>
    {/if}
  </Popover.Content>
</Popover.Root>
