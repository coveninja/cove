<script lang="ts">
  import { Player, type MpvTrack } from "$lib/player/player.svelte";
  import * as Popover from "$lib/components/ui/popover";
  import { Button } from "$lib/components/ui/button";
  import { Headphones } from "lucide-svelte";
  import { langName, trackLabel } from "$lib/player/trackLabels";
  import MenuItem from "./MenuItem.svelte";

  let {
    open = $bindable(false),
    onSelect,
  }: {
    open?: boolean;
    onSelect: (track: MpvTrack) => void;
  } = $props();

  // Sorted for stable, language-grouped menus (untagged → bottom by number).
  const sortedAudio = $derived(
    [...Player.audioTracks].sort((a, b) =>
      trackLabel(a, "Audio").localeCompare(trackLabel(b, "Audio")),
    ),
  );
  const selectedAudio = $derived(Player.audioTracks.find((t) => t.selected));
</script>

<Popover.Root bind:open>
  <Popover.Trigger>
    {#snippet child({ props })}
      <Button
        {...props}
        variant="ghost"
        size="sm"
        class="gap-1.5 text-white hover:bg-white/15 hover:text-white"
      >
        <Headphones class="size-4" />
        <span class="max-w-28 truncate text-xs">
          {selectedAudio?.title || langName(selectedAudio?.lang ?? "") || "Audio"}
        </span>
      </Button>
    {/snippet}
  </Popover.Trigger>
  <Popover.Content side="top" align="end" class="w-56 p-1">
    <p class="px-2 py-1.5 text-sm font-medium text-muted-foreground">Audio</p>
    <div class="max-h-72 overflow-y-auto flex flex-col gap-1">
      {#each sortedAudio as track (track.id)}
        <MenuItem
          label={trackLabel(track, "Audio")}
          active={!!track.selected}
          onSelect={() => onSelect(track)}
        />
      {/each}
    </div>
  </Popover.Content>
</Popover.Root>
