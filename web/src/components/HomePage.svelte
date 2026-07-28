<script lang="ts">
  import LargeRecommendationsCard from "./LargeRecommendationsCard.svelte";
  import SmallRecommendations from "./SmallRecommendations.svelte";
  import ContinueWatching from "./ContinueWatching.svelte";
  import type { Media } from "$lib/types/tmdb";
  import { HomeFeedController } from "$lib/homeFeed.svelte";
  import type { Page } from "$lib/types/types";
  import { ScrollArea } from "$lib/components/ui/scroll-area/index.js";
  import { onMount } from "svelte";
  import { tick } from "svelte";

  // Same contract as MyListPage: parent hands down how to open a title and
  // (optionally) how to start watching it. We forward both into every row.
  let {
    onSelectMedia,
    onWatch,
    visible = true,
    onChangePage,
  }: {
    onSelectMedia: (m: Media) => void;
    onWatch?: (m: Media, season?: number, episode?: number) => void;
    visible?: boolean;
    onChangePage?: (p: Page) => void;
  } = $props();

  const feed = new HomeFeedController();

  let areVideosPaused = $state(false);

  async function handleOnWatch(
    m: Media,
    season?: number,
    episode?: number,
  ): Promise<void> {
    areVideosPaused = true;
    await tick();
    onWatch(m, season, episode);
  }

  async function handleSelectMedia(m: Media): Promise<void> {
    areVideosPaused = true;
    await tick();
    onSelectMedia(m);
  }

  onMount(() => void feed.load());
</script>

<ScrollArea class="mb-24 h-full w-full">
  <div class="flex w-full flex-col justify-start gap-2 pb-8">
    <LargeRecommendationsCard bind:isPaused={areVideosPaused} {visible} />

    <ContinueWatching
      onWatch={handleOnWatch}
      onSelectMedia={handleSelectMedia}
      navEnabled={true}
    />

    {#each feed.catalogRows as row (row.key)}
      {@const ref = feed.catalogRefs.get(row.key)}
      <SmallRecommendations
        header={row.header}
        medias={row.medias}
        loading={row.loading}
        onSelect={handleSelectMedia}
        onWatch={handleOnWatch}
        onSeeAll={ref
          ? () =>
              onChangePage?.({
                type: "catalog",
                addonId: ref.addonId,
                catalogType: ref.catalogType,
                catalogId: ref.catalogId,
                name: ref.name,
                addonUrl: ref.addonUrl,
              })
          : undefined}
      />
    {/each}

    {#each feed.rows as row (row.key)}
      <SmallRecommendations
        header={row.header}
        medias={row.medias}
        loading={row.loading}
        onSelect={handleSelectMedia}
        onWatch={handleOnWatch}
      />
    {/each}
  </div>
</ScrollArea>
