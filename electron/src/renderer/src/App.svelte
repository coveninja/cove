<script lang="ts">
  import { api } from "$lib/api";
  import TopBar from "./components/TopBar.svelte";
  import { ModeWatcher } from "mode-watcher";
  import MediaCard from "./components/MediaCard.svelte";
  import type { Media } from "$lib/types/tmdb";
  import MediaPage from "./components/MediaPage.svelte";
  import * as Tooltip from "$lib/components/ui/tooltip";

  let query = $state("");
  let results: Media[] = $state([]);
  let selectedMedia: Media | null = $state(null);
  let selectedSimilar = $state<Media | null>(null);
  let loading = $state(false);

  $effect(() => {
    selectedMedia = null;
    const q = query.trim();
    const timeout = setTimeout(async () => {
      if (!q) {
        results = [];
        return;
      }
      loading = true;
      results = await api.search(q);
      loading = false;
    }, 400);
    return () => clearTimeout(timeout);
  });

  async function selectMedia(movie: Media): Promise<void> {
    selectedMedia = movie;
  }
</script>

<Tooltip.Provider>
  <TopBar bind:query bind:loading />
  {#if selectedSimilar}
    {#key selectedSimilar.id}
      <MediaCard
        media={selectedSimilar}
        onclick={() => {}}
        initialExpanded={true}
        onclose={() => (selectedSimilar = null)}
        onsimilar={(m) => (selectedSimilar = m)}
      />
    {/key}
  {/if}
  <div class="flex h-screen flex-col overflow-hidden">
    <main class="relative min-h-0 flex-1 overflow-hidden p-6 pt-18">
      {#if selectedMedia}
        <MediaPage
          media={selectedMedia}
          onsimilar={(m) => (selectedMedia = m)}
          onBack={() => (selectedMedia = null)}
        />
      {:else}
        <div
          class="mt-4 grid gap-4"
          style="grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))"
        >
          {#each results as media (media.id)}
            <MediaCard
              {media}
              onclick={() => selectMedia(media)}
              onsimilar={(m) => (selectedSimilar = m)}
            />
          {/each}
        </div>
      {/if}
    </main>
  </div>
</Tooltip.Provider>
<ModeWatcher />
