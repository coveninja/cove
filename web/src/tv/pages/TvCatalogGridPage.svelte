<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import { CatalogPager } from "$lib/catalogPager.svelte";
  import TvMediaCard from "../components/TvMediaCard.svelte";
  import { focusGroup } from "../focus/actions";
  import * as m from "$lib/paraglide/messages.js";

  // Grid columns: keep this in sync with CSS grid-template-columns below.
  // Defined once so focusGroup policy and the visual layout never diverge.
  const COLS = 5;

  // Prop signature mirrors MobileCatalogGridPage (and the M3 stub) so TvApp
  // can wire catalog navigation identically.
  let {
    addonId,
    catalogType,
    catalogId,
    name,
    addonUrl,
    onSelectMedia,
    onWatch: _onWatch,
  }: {
    addonId: string;
    catalogType: string;
    catalogId: string;
    name: string;
    addonUrl?: string;
    onSelectMedia: (m: Media) => void;
    onWatch?: (m: Media, season?: number, episode?: number) => void;
  } = $props();

  const pager = new CatalogPager();

  // Re-fires whenever the catalog identity triple changes.
  $effect(() => {
    const id = addonId;
    const type = catalogType;
    const catId = catalogId;
    const url = addonUrl;
    if (!id) return;
    pager.reset({
      addonId: id,
      catalogType: type,
      catalogId: catId,
      addonUrl: url,
    });
  });
</script>

<div class="flex h-full flex-col">
  <!-- Page title -->
  <div class="shrink-0 py-4">
    <h1 class="text-2xl font-bold">{name || m.common_catalog()}</h1>
  </div>

  <!-- Scrollable grid -->
  <div
    class="min-h-0 flex-1 overflow-y-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
  >
    <div class="pb-12">
      {#if pager.loading && pager.medias.length === 0}
        <!-- Skeleton grid while initial page loads -->
        <div class="grid grid-cols-5 gap-4">
          {#each { length: 15 } as _, i (i)}
            <div
              class="aspect-2/3 w-full animate-pulse rounded-lg bg-muted"
            ></div>
          {/each}
        </div>
      {:else}
        <!--
          D-pad-navigable grid.  COLS must match the CSS grid-template-columns.
          rememberFocus: true restores the last-focused card when re-entering
          the grid from a detail overlay or another section.
        -->
        <div
          use:focusGroup={{
            id: "catalog-grid",
            policy: { type: "grid", cols: COLS },
            rememberFocus: true,
          }}
          class="grid grid-cols-5 gap-4"
        >
          {#each pager.medias as media (`${media.media_type}:${media.id}`)}
            <div
              style="content-visibility: auto; contain-intrinsic-size: auto 240px;"
            >
              <TvMediaCard
                {media}
                groupId="catalog-grid"
                onclick={() => onSelectMedia(media)}
              />
            </div>
          {/each}
        </div>

        {#if pager.hasMore}
          <div class="mt-8 flex justify-center">
            <button
              type="button"
              class="rounded-xl bg-secondary px-8 py-3 text-base font-medium disabled:opacity-50"
              onclick={() => pager.loadMore()}
              disabled={pager.loading}
            >
              {pager.loading ? m.common_loading() : m.common_load_more()}
            </button>
          </div>
        {/if}
      {/if}
    </div>
  </div>
</div>
