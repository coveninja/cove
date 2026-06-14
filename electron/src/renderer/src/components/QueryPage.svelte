<script lang="ts">
  import type { Media } from "$lib/types/tmdb";
  import { ScrollArea } from "$lib/components/ui/scroll-area/index.js";
  import MediaCard from "./MediaCard.svelte";
  import { SvelteMap } from "svelte/reactivity";
  import { api } from "$lib/api";
  import { Spinner } from "$lib/components/ui/spinner/index.js";
  import { Button } from "$lib/components/ui/button";
  import * as ButtonGroup from "$lib/components/ui/button-group/index.js";

  let {
    query = $bindable(""),
    loading = $bindable(true),
    onSelectMedia,
    onSuggested,
  } = $props();

  let results: Media[] = $state([]);
  let keywords: { id: number; name: string }[] = $state([]);
  // svelte-ignore non_reactive_update
  let qualityMap = new SvelteMap<number, string>();

  $effect(() => {
    const q = query.trim();
    const timeout = setTimeout(async () => {
      if (!q) {
        results = [];
        keywords = [];
        qualityMap = new SvelteMap();
        return;
      }
      loading = true;
      const [searchResults, kwResults] = await Promise.all([
        api.search(q),
        fetch(`http://localhost:6969/api/keywords?q=${encodeURIComponent(q)}`)
          .then((r) => r.json())
          .catch(() => []),
      ]);
      results = searchResults;
      keywords = kwResults ?? [];
      loading = false;

      // Batch fetch quality for all results
      if (searchResults.length > 0) {
        const ids = searchResults.map((m) => m.id).join(",");
        fetch(`http://localhost:6969/api/quality/batch?ids=${ids}`)
          .then(async (r) => {
            const reader = r.body!.getReader();
            const decoder = new TextDecoder();
            let buffer = "";

            while (true) {
              const { done, value } = await reader.read();
              if (done) break;
              buffer += decoder.decode(value, { stream: true });
              const lines = buffer.split("\n");
              buffer = lines.pop() ?? "";
              for (const line of lines) {
                if (!line.trim()) continue;
                try {
                  const { id, quality } = JSON.parse(line);
                  qualityMap.set(Number(id), quality);
                } catch {
                  /* empty */
                }
              }
            }
          })
          .catch(() => {});
      }
    }, 400);
    return () => clearTimeout(timeout);
  });
</script>

<div class="h-full gap-2.5 p-6 pt-18">
  <span class="mb-2 flex text-center text-2xl font-semibold">
    Results for
    <span class="size-1.5"></span>
    <span class="text-accent">{query}</span>
  </span>
  {#if loading}
    <div class="flex h-full w-full flex-col items-center justify-center">
      <Spinner class="size-52" />
      <span class="font-bold"> Searching...</span>
    </div>
  {:else}
    <ScrollArea class="h-full">
      {#if keywords.length > 1}
        <div class="flex flex-col gap-2 align-middle">
        <span
          class="flex shrink-0 text-center text-xs font-medium text-muted-foreground"
        >
          More to Explore:
        </span>
          <ScrollArea
            orientation="horizontal"
            class="flex-1 overflow-clip rounded-sm"
          >
            <div class="flex gap-2 pb-3">
              {#each keywords as kw (kw.id)}
                <Button
                  variant="ghost"
                  size="xs"
                  class="text-muted-foreground"
                  onclick={() => {
                  onSuggested(kw.name);
                }}
                >
                  {kw.name}
                </Button>
              {/each}
            </div>
          </ScrollArea>
        </div>
      {/if}
      <div
        class="grid gap-4 pr-4"
        style="grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))"
      >
        {#each results as media (media.id)}
          <MediaCard
            {media}
            onclick={() => {
              onSelectMedia(media);
            }}
            quality={qualityMap.get(media.id) ?? null}
            onsimilar={(m) => {
              onSelectMedia(m);
            }}
          />
        {/each}
      </div>
    </ScrollArea>
  {/if}
</div>
