<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import { api } from "$lib/api";
  import TvMediaCard from "../components/TvMediaCard.svelte";
  import { SvelteSet } from "svelte/reactivity";
  import { focusGroup } from "../focus/actions";

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
    onSelectMedia,
    onWatch: _onWatch,
  }: {
    addonId: string;
    catalogType: string;
    catalogId: string;
    name: string;
    onSelectMedia: (m: Media) => void;
    onWatch?: (m: Media, season?: number, episode?: number) => void;
  } = $props();

  // Safety backstop: stop loading once the grid reaches this size.
  const MAX_CATALOG_ITEMS = 600;

  let medias = $state<Media[]>([]);
  let nextSkip = $state(0);
  let hasMore = $state(false);
  let loading = $state(false);

  // Tracks seen media_type:id pairs so duplicate cards from addons that ignore
  // the skip offset don't break the keyed {#each}.
  const seen = new SvelteSet<string>();

  // Bumped whenever the identity triple changes so stale in-flight pages can't
  // append into a new catalog.
  let generation = 0;

  async function loadPage(skip: number): Promise<void> {
    if (loading) return;
    loading = true;
    const gen = generation;
    try {
      const res = await api.catalogPage(addonId, catalogType, catalogId, skip, 40);
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
      console.error("TvCatalogGridPage: failed to load page", e);
    } finally {
      if (gen === generation) loading = false;
    }
  }

  // Re-fires whenever the catalog identity triple changes.
  $effect(() => {
    const id = addonId;
    const type = catalogType;
    const catId = catalogId;
    if (!id) return;
    void type;
    void catId;
    generation++;
    medias = [];
    nextSkip = 0;
    hasMore = false;
    loading = false;
    seen.clear();
    loadPage(0);
  });
</script>

<div class="flex h-full flex-col">
  <!-- Page title -->
  <div class="shrink-0 py-4">
    <h1 class="text-2xl font-bold">{name || "Catalog"}</h1>
  </div>

  <!-- Scrollable grid -->
  <div
    class="min-h-0 flex-1 overflow-y-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
  >
    <div class="pb-12">
      {#if loading && medias.length === 0}
        <!-- Skeleton grid while initial page loads -->
        <div class="grid grid-cols-5 gap-4">
          {#each { length: 15 } as _, i (i)}
            <div class="aspect-2/3 w-full animate-pulse rounded-lg bg-muted"></div>
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
          {#each medias as media (`${media.media_type}:${media.id}`)}
            <div style="content-visibility: auto; contain-intrinsic-size: auto 240px;">
              <TvMediaCard
                {media}
                groupId="catalog-grid"
                onclick={() => onSelectMedia(media)}
              />
            </div>
          {/each}
        </div>

        {#if hasMore}
          <div class="mt-8 flex justify-center">
            <button
              type="button"
              class="rounded-xl bg-secondary px-8 py-3 text-base font-medium disabled:opacity-50"
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

