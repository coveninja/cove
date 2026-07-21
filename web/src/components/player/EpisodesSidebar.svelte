<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import type { Stream } from "$lib/types/addons";
  import { Button } from "$lib/components/ui/button";
  import { X } from "lucide-svelte";
  import { fly } from "svelte/transition";
  import StreamsList from "../StreamsList.svelte";

  let {
    media,
    season,
    episode,
    onPlayStream,
    onClose,
  }: {
    media: Media;
    season?: number;
    episode?: number;
    onPlayStream?: (
      stream: Stream,
      season?: number,
      episode?: number,
      episodeName?: string,
      candidates?: Stream[],
    ) => void;
    onClose: () => void;
  } = $props();
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
  class="flex flex-col bg-background/75 rounded-2xl absolute top-20 bottom-24 right-0 z-20 w-[35vw] max-w-[85vw] pt-4 gap-4"
  transition:fly={{ x: 420, duration: 250 }}
  onclick={(e) => e.stopPropagation()}
  onkeydown={(e) => e.stopPropagation()}
>
  <Button
    class="absolute top-3 right-3 z-30"
    size="icon"
    variant="outline"
    onclick={onClose}
    aria-label="Close episodes"
  >
    <X class="size-4" />
  </Button>

  <div class="pt-10 overflow-y-auto flex-1">
    <StreamsList
      {media}
      streamActive={true}
      activeSeason={season}
      activeEpisode={episode}
      autoJumpToActive={false}
      onPlayStream={(stream, s, e, name, candidates) => {
        onClose();
        onPlayStream?.(stream, s, e, name, candidates);
      }}
    />
  </div>
</div>
