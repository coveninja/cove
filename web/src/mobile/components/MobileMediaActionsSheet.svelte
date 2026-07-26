<script lang="ts">
  import * as m from "$lib/paraglide/messages.js";
  import { Button } from "$lib/components/ui/button";
  import {
    mediaUtilityItems,
    performMediaUtilityAction,
    type MediaUtilityAction,
  } from "$lib/mediaActions";
  import type { LibraryEntry } from "$lib/types/library";
  import type { Media } from "$lib/types/tmdb";
  import { Portal } from "bits-ui";
  import { EllipsisVertical } from "lucide-svelte";
  import TrackSheet from "./player/TrackSheet.svelte";

  let {
    media,
    libraryEntry,
    dismissed,
    hasProgress,
    open = $bindable(false),
    showTrigger = true,
    class: className = "",
  } = $props<{
    media: Media;
    libraryEntry: LibraryEntry | null;
    dismissed: boolean;
    hasProgress: boolean;
    open?: boolean;
    showTrigger?: boolean;
    class?: string;
  }>();

  const title = $derived(media.media_type === "tv" ? media.name : media.title);
  const items = $derived(
    mediaUtilityItems(media, { entry: libraryEntry, dismissed, hasProgress }),
  );

  async function handleAction(action: MediaUtilityAction): Promise<void> {
    try {
      await performMediaUtilityAction(action, media, {
        entry: libraryEntry,
        dismissed,
        hasProgress,
      });
    } catch (error) {
      console.error("media action:", error);
    }
  }
</script>

{#if showTrigger}
  <Button
    variant="outline"
    size="icon"
    class={className}
    onclick={() => (open = true)}
    title={m.common_more_actions()}
    aria-label={m.common_more_actions()}
  >
    <EllipsisVertical />
  </Button>
{/if}

{#if open}
  <Portal>
    <TrackSheet
      title={m.common_title_actions({ title })}
      {items}
      onSelect={(id) => handleAction(id as MediaUtilityAction)}
      onClose={() => (open = false)}
    />
  </Portal>
{/if}
