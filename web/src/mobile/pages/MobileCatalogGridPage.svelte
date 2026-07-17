<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import { api } from "$lib/api";
  import MobileMediaCard from "../components/MobileMediaCard.svelte";
  import { SvelteSet } from "svelte/reactivity";

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

  // Safety backstop: stop offering further loads once the grid reaches this size.
  const MAX_CATALOG_ITEMS = 600;

  let medias = $state<Media[]>([]);
  let nextSkip = $state(0);
  let hasMore = $state(false);
  let loading = $state(false);

  // Tracks seen media_type:id pairs so addons that ignore skip don't produce
  // duplicate cards — duplicates would break the keyed {#each}.
  const seen = new SvelteSet<string>();

  // Bumped whenever the identity triple changes so an in-flight page load for
  // the previous catalog can't append into the new one.
  let generation = 0;

  async function loadPage(skip: number): Promise<void> {
    if (loading) return;
    loading = true;
    const gen = generation;
    try {
      const res = await api.catalogPage(addonId, catalogType, catalogId, skip, 40, addonUrl);
      if (gen !== generation) return;
      const fresh: Media[] = [];
      for (const m of res.medias) {
        const key = `${m.media_type}:${m.id}`;
        if (!seen.has(key)) {
          seen.add(key);
          fresh.push(m);
        }
      }
      medias = [...medias, ...fresh];
      nextSkip = res.nextSkip;
      hasMore = res.medias.length > 0 && medias.length < MAX_CATALOG_ITEMS;
    } catch (e) {
      console.error("MobileCatalogGridPage: failed to load page", e);
    } finally {
      if (gen === generation) loading = false;
    }
  }

  // Re-fires whenever the identity triple changes.
  $effect(() => {
    const id = addonId;
    const type = catalogType;
    const catId = catalogId;
    const url = addonUrl;
    if (!id) return;
    // Suppress unused-variable lint: type/catId/url are read to register as deps.
    void type;
    void catId;
    void url;
    generation++;
    medias = [];
    nextSkip = 0;
    hasMore = false;
    loading = false;
    seen.clear();
    loadPage(0);
  });
</script>

<!-- Own safe-area top padding — no pt-18 needed -->
<div
  class="flex h-full flex-col"
  style="padding-top: calc(var(--safe-top) + 0.75rem);"
>
  <!-- Compact title header -->
  <div class="shrink-0 px-4 pb-3">
    <h1 class="text-xl font-semibold">{name || "Catalog"}</h1>
  </div>

  <!-- Scrollable grid -->
  <div
    class="min-h-0 flex-1 overflow-y-auto overscroll-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
  >
    <div class="px-3 pb-8">
      {#if loading && medias.length === 0}
        <!-- Skeleton grid while initial page is loading -->
        <div class="grid grid-cols-3 gap-2">
          {#each { length: 12 } as _, i (i)}
            <div class="aspect-2/3 w-full animate-pulse rounded-md bg-muted"></div>
          {/each}
        </div>
      {:else}
        <div class="grid grid-cols-3 gap-2">
          {#each medias as media (`${media.media_type}:${media.id}`)}
            <div style="content-visibility: auto; contain-intrinsic-size: auto 168px;">
              <MobileMediaCard
                {media}
                onclick={() => onSelectMedia(media)}
              />
            </div>
          {/each}
        </div>

        {#if hasMore}
          <div class="mt-6 flex justify-center">
            <button
              type="button"
              class="rounded-lg bg-secondary px-6 py-2.5 text-sm font-medium disabled:opacity-50"
              onclick={() => loadPage(nextSkip)}
              disabled={loading}
            >
              {loading ? "Loading…" : "Load more"}
            </button>
          </div>
        {/if}
      {/if}
    </div>
  </div>
</div>
