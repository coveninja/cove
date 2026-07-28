<script lang="ts">
  import { statusLabel, type LibraryStatus, STATUS_COLORS } from "$lib/api";
  import type { LibraryEntry } from "$lib/types/library";
  import type { Media } from "$lib/types/tmdb";
  import * as Popover from "$lib/components/ui/popover/index.js";
  import { Button } from "$lib/components/ui/button/index.js";
  import { BookmarkIcon, BookmarkPlus } from "lucide-svelte";
  import { animate } from "animejs";
  import * as ButtonGroup from "$lib/components/ui/button-group/index.js";
  import { setMediaLibraryStatus } from "$lib/mediaActions";

  let {
    libraryEntry,
    media,
    size = "icon",
    lastAiredSeason = null,
    lastAiredEpisode = null,
    onpopoverchange,
    class: className = "",
  } = $props<{
    libraryEntry: LibraryEntry | null;
    media: Media;
    size: string | null;
    lastAiredSeason?: number | null;
    lastAiredEpisode?: number | null;
    onpopoverchange?: (open: boolean) => void;
    class?: string;
  }>();

  let popoverOpen = $state(false);

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
      // Set directly rather than relying solely on onOpenChange: mutating a
      // bind:open value doesn't reliably re-fire the component's own
      // onOpenChange callback, so the parent's popoverOpen tracking (and the
      // hover card's close check tied to it) would otherwise never hear that
      // this closed.
      popoverOpen = false;
      onpopoverchange?.(false);
    } catch (e) {
      console.error("library status:", e);
    }
  }

  const inLibrary = $derived(!!libraryEntry);
  const statuses: LibraryStatus[] = [
    "watch_later",
    "watching",
    "finished",
    "dropped",
  ];
</script>

<Popover.Root
  bind:open={popoverOpen}
  onOpenChange={(o) => onpopoverchange?.(o)}
>
  <Popover.Trigger>
    <Button variant="secondary" {size} class={className}>
      {#if inLibrary}
        <BookmarkIcon
          class="size-4 {STATUS_COLORS[libraryEntry.status as LibraryStatus]
            .text}"
        />
      {:else}
        <BookmarkPlus />
      {/if}
    </Button>
  </Popover.Trigger>
  <Popover.Content class="rounded-3xl p-0">
    <ButtonGroup.Root orientation="vertical" class="w-full">
      {#each statuses as value (value)}
        {@const isActive = libraryEntry?.status === value}
        <Button
          onclick={(e: { stopPropagation: () => void }) => {
            e.stopPropagation();
            handleStatus(value as LibraryStatus);
          }}
          variant={isActive ? "secondary" : "outline"}
          class="w-full"
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
        </Button>
      {/each}
    </ButtonGroup.Root>
  </Popover.Content>
</Popover.Root>
