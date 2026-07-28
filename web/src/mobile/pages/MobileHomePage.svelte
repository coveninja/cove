<script lang="ts">
  import MobileHero from "../components/MobileHero.svelte";
  import MobileMediaRow from "../components/MobileMediaRow.svelte";
  import MobileContinueWatching from "../components/MobileContinueWatching.svelte";
  import type { Media } from "$lib/types/tmdb";
  import { HomeFeedController } from "$lib/homeFeed.svelte";
  import type { Page } from "$lib/types/types";
  import { onMount, tick } from "svelte";

  // Same contract as desktop HomePage so MobileApp.svelte can slot this in
  // with identical prop bindings.
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

  // ── Watch / select wrappers (pause hero timer while overlay is open) ───────
  let heroVisible = $state(true);

  async function handleOnWatch(
    m: Media,
    season?: number,
    episode?: number,
  ): Promise<void> {
    heroVisible = false;
    await tick();
    onWatch?.(m, season, episode);
  }

  async function handleSelectMedia(m: Media): Promise<void> {
    heroVisible = false;
    await tick();
    onSelectMedia(m);
  }

  onMount(() => void feed.load());

  // Resume timer when page becomes visible again.
  $effect(() => {
    if (visible) heroVisible = true;
  });
</script>

<div
  class="h-full w-full overflow-y-auto overscroll-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
>
  <div class="flex flex-col gap-4 pb-8">
    <!-- Full-bleed hero (no top padding — goes under status bar) -->
    <MobileHero visible={visible && heroVisible} />

    <!-- Continue watching -->
    <MobileContinueWatching
      onWatch={handleOnWatch}
      onSelectMedia={handleSelectMedia}
    />
    <!-- Catalog rows (addon catalogs, e.g. Debrid) -->
    {#each feed.catalogRows as row (row.key)}
      {@const ref = feed.catalogRefs.get(row.key)}
      <MobileMediaRow
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

    <!-- Taste-driven rows -->
    {#each feed.rows as row (row.key)}
      <MobileMediaRow
        header={row.header}
        medias={row.medias}
        loading={row.loading}
        onSelect={handleSelectMedia}
        onWatch={handleOnWatch}
      />
    {/each}
  </div>
</div>
