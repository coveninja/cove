<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import MobileMediaCard from "./MobileMediaCard.svelte";

  // Same data contract as SmallRecommendations so MobileHomePage can drive both
  // interchangeably. Parent owns fetching; this component is purely presentational.
  let {
    header = "",
    medias = [],
    loading = false,
    onSelect = () => {},
    onWatch: _onWatch,
    onSeeAll,
  } = $props<{
    header?: string | null;
    medias?: Media[];
    loading?: boolean;
    onSelect?: (media: Media) => void;
    onWatch?: (media: Media, season?: number, episode?: number) => void;
    onSeeAll?: () => void;
  }>();
</script>

<!-- Don't render an empty row: only show while loading or when we have items. -->
{#if loading || medias.length > 0}
  <div class="w-full space-y-2 px-4">
    {#if header}
      <div class="flex items-center justify-between px-1">
        <h2 class="text-base font-semibold">{header}</h2>
        {#if onSeeAll}
          <button
            onclick={onSeeAll}
            class="text-sm text-muted-foreground transition-colors hover:text-foreground"
          >
            See all →
          </button>
        {/if}
      </div>
    {/if}

    <!-- Touch-native horizontal scroll: native inertia, no chevrons. -->
    <div
      class="flex gap-3 overflow-x-auto pb-1 [scrollbar-width:none] [-webkit-overflow-scrolling:touch] [&::-webkit-scrollbar]:hidden"
    >
      {#if loading}
        {#each { length: 8 } as _, i (i)}
          <div class="w-28 shrink-0">
            <div class="aspect-2/3 w-full animate-pulse rounded-md bg-muted"></div>
          </div>
        {/each}
      {:else}
        {#each medias as media (media.media_type + "-" + media.id)}
          <div
            class="w-28 shrink-0"
            style="content-visibility: auto; contain-intrinsic-size: 112px 168px;"
          >
            <MobileMediaCard
              {media}
              onclick={() => onSelect(media)}
            />
          </div>
        {/each}
      {/if}
    </div>
  </div>
{/if}
