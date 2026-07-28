<script lang="ts">
  import { statusLabel, type LibraryStatus, STATUS_COLORS } from "$lib/api";
  import type { LibraryEntry } from "$lib/types/library";
  import type { Media } from "$lib/types/tmdb";
  import {
    BookmarkIcon,
    CheckCheck,
    List,
    RotateCcw,
    ThumbsDown,
    Trash2,
  } from "lucide-svelte";
  import { animate } from "animejs";
  import * as ContextMenu from "$lib/components/ui/context-menu/index.js";
  import {
    mediaUtilityItems,
    performMediaUtilityAction,
    setMediaLibraryStatus,
    type MediaUtilityAction,
  } from "$lib/mediaActions";
  import * as m from "$lib/paraglide/messages.js";

  let {
    libraryEntry,
    dismissed,
    hasProgress,
    media,
    lastAiredSeason = null,
    lastAiredEpisode = null,
    onpopoverchange,
  } = $props<{
    libraryEntry: LibraryEntry | null;
    dismissed: boolean;
    hasProgress: boolean;
    media: Media;
    lastAiredSeason?: number | null;
    lastAiredEpisode?: number | null;
    onpopoverchange?: (open: boolean) => void;
  }>();

  let working = $state<MediaUtilityAction | null>(null);
  const utilityItems = $derived(
    mediaUtilityItems(media, { entry: libraryEntry, dismissed, hasProgress }),
  );
  const statuses: LibraryStatus[] = [
    "watch_later",
    "watching",
    "finished",
    "dropped",
  ];

  function animateBookmarkIn(el: HTMLElement): void {
    animate(el, {
      scale: [0, 1.3, 1],
      opacity: [0, 1],
      duration: 300,
      ease: "outBack",
    });
  }

  async function handleStatus(status: LibraryStatus): Promise<void> {
    try {
      libraryEntry = await setMediaLibraryStatus(media, libraryEntry, status, {
        lastAiredSeason,
        lastAiredEpisode,
      });
      onpopoverchange?.(false);
    } catch (e) {
      console.error("library status:", e);
    }
  }

  async function handleUtility(action: MediaUtilityAction): Promise<void> {
    if (working) return;
    working = action;
    try {
      await performMediaUtilityAction(action, media, {
        entry: libraryEntry,
        dismissed,
        hasProgress,
      });
      onpopoverchange?.(false);
    } catch (error) {
      console.error("media action:", error);
    } finally {
      working = null;
    }
  }
</script>

{#each utilityItems as item (item.id)}
  <ContextMenu.Item
    variant={item.destructive ? "destructive" : "default"}
    disabled={working !== null}
    onclick={(event) => {
      event.stopPropagation();
      handleUtility(item.id);
    }}
  >
    {#if item.id === "mark-watched"}
      <CheckCheck />
    {:else if item.id === "mark-unwatched"}
      <RotateCcw />
    {:else if item.id === "toggle-not-interested"}
      <ThumbsDown />
    {:else}
      <Trash2 />
    {/if}
    <span>{item.label}</span>
  </ContextMenu.Item>
{/each}
<ContextMenu.Separator />

<ContextMenu.Sub>
  <ContextMenu.SubTrigger>
    <span class="flex items-center justify-start gap-4 align-middle">
      <List />
      {m.media_add_list()}
    </span>
  </ContextMenu.SubTrigger>
  <ContextMenu.SubContent class="w-48">
    <p class="px-2 py-1.5 text-center text-xs text-muted-foreground">
      {m.my_list_toggle_description()}
    </p>
    {#each statuses as value (value)}
      {@const isActive = libraryEntry?.status === value}
      <ContextMenu.Item
        onclick={(e) => {
          e.stopPropagation();
          handleStatus(value as LibraryStatus);
        }}
      >
        <span class="flex w-full items-center gap-3">
          <span class="size-4 shrink-0">
            {#if isActive}
              <span use:animateBookmarkIn>
                <BookmarkIcon
                  class="size-4 {STATUS_COLORS[value as LibraryStatus].text}"
                />
              </span>
            {/if}
          </span>
          <span
            class="size-2 shrink-0 rounded-full {STATUS_COLORS[
              value as LibraryStatus
            ].dot}"
          ></span>
          {statusLabel(value)}
        </span>
      </ContextMenu.Item>
    {/each}
  </ContextMenu.SubContent>
</ContextMenu.Sub>
