<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import { api } from "$lib/api";
  import { ScrollArea } from "$lib/components/ui/scroll-area/index.js";
  import { Button } from "$lib/components/ui/button/index.js";
  import MediaCard from "./MediaCard.svelte";
  import { SvelteSet } from "svelte/reactivity";

  let {
    addonId,
    catalogType,
    catalogId,
    name,
    onSelectMedia,
    onWatch,
  }: {
    addonId: string;
    catalogType: string;
    catalogId: string;
    name: string;
    onSelectMedia: (m: Media) => void;
    onWatch?: (m: Media, season?: number, episode?: number) => void;
  } = $props();

  let medias = $state<Media[]>([]);
  let nextSkip = $state(0);
  let hasMore = $state(false);
  let loading = $state(false);

  // Tracks seen media_type:id pairs so addons that ignore skip don't produce
  // duplicate cards in the grid — duplicates would break the keyed {#each}.
  const seen = new SvelteSet<string>();

  // Bumped whenever the identity triple changes so an in-flight page load for
  // the previous catalog can't append its results into the new one.
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
      hasMore = res.medias.length > 0;
    } catch (e) {
      console.error("CatalogGridPage: failed to load page", e);
    } finally {
      // A stale request must not clear the flag out from under the load the
      // identity switch just started.
      if (gen === generation) loading = false;
    }
  }

  // Re-fires whenever the identity triple changes. Skip is passed explicitly so
  // nextSkip is never read synchronously inside this effect — reading it here
  // would register it as a dependency and cause an infinite re-run loop whenever
  // a page loads and advances nextSkip.
  $effect(() => {
    const id = addonId;
    const type = catalogType;
    const catId = catalogId;
    if (!id) return;
    // Suppress unused-variable lint: type/catId are read to register as deps.
    void type; void catId;
    generation++;
    medias = [];
    nextSkip = 0;
    hasMore = false;
    loading = false;
    seen.clear();
    loadPage(0);
  });
</script>

<div class="relative h-full p-6 pt-18">
  <div class="mb-6 flex items-baseline gap-3">
    <h1 class="text-2xl font-semibold">{name || "Catalog"}</h1>
  </div>

  <ScrollArea class="h-full">
    <div class="pb-8">
      {#if loading && medias.length === 0}
        <!-- Skeleton grid while initial load is in flight -->
        <div
          class="grid gap-4 pr-4"
          style="grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))"
        >
          {#each { length: 20 } as _, i (i)}
            <div class="aspect-2/3 w-full animate-pulse rounded-md bg-muted"></div>
          {/each}
        </div>
      {:else}
        <div
          class="grid gap-4 pr-4"
          style="grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))"
        >
          {#each medias as media (`${media.media_type}:${media.id}`)}
            <MediaCard
              {media}
              onclick={() => onSelectMedia(media)}
              onwatch={onWatch}
            />
          {/each}
        </div>

        {#if hasMore}
          <div class="mt-6 flex justify-center">
            <Button
              variant="outline"
              onclick={() => loadPage(nextSkip)}
              disabled={loading}
            >
              {loading ? "Loading…" : "Load more"}
            </Button>
          </div>
        {/if}
      {/if}
    </div>
  </ScrollArea>
</div>
