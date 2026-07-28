<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import { CatalogPager } from "$lib/catalogPager.svelte";
  import MobileMediaCard from "../components/MobileMediaCard.svelte";
  import * as m from "$lib/paraglide/messages.js";

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

  // Re-fires whenever the identity triple changes.
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

<!-- Own safe-area top padding — no pt-18 needed -->
<div
  class="flex h-full flex-col"
  style="padding-top: calc(var(--safe-top) + 0.75rem);"
>
  <!-- Compact title header -->
  <div class="shrink-0 px-4 pb-3">
    <h1 class="text-xl font-semibold">{name || m.common_catalog()}</h1>
  </div>

  <!-- Scrollable grid -->
  <div
    class="min-h-0 flex-1 overflow-y-auto overscroll-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
  >
    <div class="px-3 pb-8">
      {#if pager.loading && pager.medias.length === 0}
        <!-- Skeleton grid while initial page is loading -->
        <div class="grid grid-cols-3 gap-2">
          {#each { length: 12 } as _, i (i)}
            <div class="aspect-2/3 w-full animate-shimmer rounded-md"></div>
          {/each}
        </div>
      {:else}
        <div class="grid grid-cols-3 gap-2">
          {#each pager.medias as media (`${media.media_type}:${media.id}`)}
            <div
              style="content-visibility: auto; contain-intrinsic-size: auto 168px;"
            >
              <MobileMediaCard {media} onclick={() => onSelectMedia(media)} />
            </div>
          {/each}
        </div>

        {#if pager.hasMore}
          <div class="mt-6 flex justify-center">
            <button
              type="button"
              class="rounded-lg bg-secondary px-6 py-2.5 text-sm font-medium disabled:opacity-50"
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
