<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import TvMediaCard from "./TvMediaCard.svelte";
  import { focusGroup, focusable } from "../focus/actions";

  // Same data contract as MobileMediaRow plus a required `id` for the focusGroup.
  // Parent derives `id` from the row key (e.g. `row-mg-123`) so groups are stable.
  let {
    id,
    header = "",
    medias = [],
    loading = false,
    onSelect = () => {},
    onWatch: _onWatch,
    onSeeAll,
  }: {
    id: string;
    header?: string | null;
    medias?: Media[];
    loading?: boolean;
    onSelect?: (media: Media) => void;
    onWatch?: (media: Media, season?: number, episode?: number) => void;
    onSeeAll?: () => void;
  } = $props();
</script>

<!-- Don't render an empty row: only show while loading or when we have items. -->
{#if loading || medias.length > 0}
  <div class="w-full">
    {#if header}
      <!-- Header is display-only: zero focusables here. "See all" lives in the strip. -->
      <div class="flex items-center ml-4">
        <h2 class="text-xl font-semibold">{header}</h2>
      </div>
    {/if}

    <!--
      D-pad-navigable horizontal strip.
      overflow-x: hidden blocks touch/mouse scroll while still creating a scroll
      container that the focus engine's scrollIntoView() can drive on D-pad moves.
      rememberFocus: true restores the last-focused card when the row is re-entered.
      "See all" is the last item inside the strip (after cards) so it stays in the
      same row group — pressing Right past the last card lands on it naturally.
    -->
    <div
      use:focusGroup={{ id, policy: { type: "row" }, rememberFocus: true }}
      class="tv-row flex gap-4 overflow-x-hidden p-4"
    >
      {#if loading}
        {#each { length: 8 } as _, i (i)}
          <!-- 9 rem (144 px) — sized down from 11 rem after 10-foot pass felt too bulky -->
          <div class="w-24 shrink-0">
            <div class="aspect-2/3 w-full animate-pulse rounded-lg bg-muted"></div>
          </div>
        {/each}
      {:else}
        {#each medias as media (media.media_type + "-" + media.id)}
          <!-- 9 rem (144 px) — sized down from 11 rem after 10-foot pass felt too bulky -->
          <div class="w-24 shrink-0">
            <TvMediaCard
              {media}
              groupId={id}
              onclick={() => onSelect(media)}
            />
          </div>
        {/each}
        {#if onSeeAll}
          <button
            type="button"
            use:focusable={{ groupId: id }}
            onclick={onSeeAll}
            class="w-20 shrink-0 self-stretch flex items-center justify-center rounded-lg bg-secondary/60 text-sm font-medium text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
          >
            See all →
          </button>
        {/if}
      {/if}
    </div>
  </div>
{/if}

