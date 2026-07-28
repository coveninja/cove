<script lang="ts">
  import TvHero from "../components/TvHero.svelte";
  import TvMediaRow from "../components/TvMediaRow.svelte";
  import TvContinueWatching from "../components/TvContinueWatching.svelte";
  import type { Media } from "$lib/types/tmdb";
  import { HomeFeedController } from "$lib/homeFeed.svelte";
  import type { Page } from "$lib/types/types";
  import { getContext, onMount } from "svelte";

  // Contexts set by TvApp: watchMedia resumes playback directly (the primary
  // action on a Continue Watching card, matching desktop/mobile), and
  // openMediaDetail is the fallback that opens the detail overlay.
  const openMediaDetail = getContext<((m: Media) => void) | undefined>(
    "openMediaDetail",
  );
  const watchMedia = getContext<
    ((m: Media, season?: number, episode?: number) => void) | undefined
  >("watchMedia");

  // onChangePage wired by TvApp for "See All" catalog navigation.
  let {
    onChangePage,
  }: {
    onChangePage?: (p: Page) => void;
  } = $props();

  const feed = new HomeFeedController();

  onMount(() => void feed.load());
</script>

<div
  class="h-full w-full overflow-y-auto scrollbar-none [&::-webkit-scrollbar]:hidden"
>
  <div class="flex flex-col gap-4">
    <TvHero />
    <!-- Continue watching — TV-native fork: D-pad strip with focus engine scrolling. -->
    <div class="px-2">
      <TvContinueWatching
        onWatch={watchMedia}
        onSelectMedia={openMediaDetail ?? (() => {})}
      />
    </div>
    {#each feed.catalogRows as row (row.key)}
      {@const ref = feed.catalogRefs.get(row.key)}
      <div class="px-2">
        <TvMediaRow
          id="row-{row.key}"
          header={row.header}
          medias={row.medias}
          loading={row.loading}
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
      </div>
    {/each}

    <!-- Taste-driven recommendation rows -->
    {#each feed.rows as row (row.key)}
      <div class="px-2">
        <TvMediaRow
          id="row-{row.key}"
          header={row.header}
          medias={row.medias}
          loading={row.loading}
        />
      </div>
    {/each}
  </div>
</div>
